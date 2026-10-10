package org.l2start.platform.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.l2start.platform.TestModules;
import org.l2start.platform.util.Semver;

class DependencyResolverTest {
    private static final Semver API = Semver.parse("1.0.0");

    @TempDir
    Path root;

    private final List<ModuleManifest> mods = new ArrayList<>();

    private void add(String id, String version, String extra) throws Exception {
        mods.add(ManifestReader.read(TestModules.module(root, id, version, extra)));
    }

    private static String deps(String... pairs) {
        final StringBuilder sb = new StringBuilder(",\"dependencies\":[");
        for (int i = 0; i < pairs.length; i++) {
            final String[] p = pairs[i].split(" ");
            sb.append(i > 0 ? "," : "").append("{\"id\":\"").append(p[0].replace("?", "")).append("\",\"version\":\"").append(p.length > 1 ? p[1] : "*").append("\"").append(p[0].startsWith("?") ? ",\"optional\":true" : "").append("}");
        }
        return sb.append("]").toString();
    }

    private DependencyResolver.Result resolve() {
        return DependencyResolver.resolve(mods, Set.of(), API, Map.of());
    }

    @Test
    void topologicalAndDeterministic() throws Exception {
        add("zeta", "1.0.0", "");
        add("app", "1.0.0", deps("lib ^1.0", "zeta"));
        add("lib", "1.2.0", deps("base"));
        add("base", "1.0.0", "");
        add("alpha", "1.0.0", "");
        final DependencyResolver.Result r = resolve();
        assertTrue(r.refused().isEmpty(), r.refused().toString());
        assertEquals(List.of("alpha", "base", "lib", "zeta", "app"), r.order());
        assertEquals(List.of("lib", "zeta"), r.visibleDependencies().get("app"));
    }

    @Test
    void missingDisabledAndWrongVersion() throws Exception {
        add("a1", "1.0.0", deps("nope"));
        add("b1", "1.0.0", deps("off"));
        add("c1", "1.0.0", deps("lib ^2.0"));
        add("lib", "1.5.0", "");
        final DependencyResolver.Result r = DependencyResolver.resolve(mods, Set.of("off"), API, Map.of());
        assertEquals("dependency nope is missing", r.refused().get("a1"));
        assertEquals("dependency off is disabled or invalid", r.refused().get("b1"));
        assertEquals("dependency lib 1.5.0 does not match ^2.0", r.refused().get("c1"));
        assertEquals(List.of("lib"), r.order());
    }

    @Test
    void refusalsCascade() throws Exception {
        add("a1", "1.0.0", deps("b1"));
        add("b1", "1.0.0", deps("c1"));
        add("c1", "1.0.0", deps("missing"));
        add("d1", "1.0.0", "");
        final DependencyResolver.Result r = resolve();
        assertEquals(Set.of("a1", "b1", "c1"), r.refused().keySet());
        assertTrue(r.refused().get("a1").contains("refused module b1"));
        assertEquals(List.of("d1"), r.order());
    }

    @Test
    void optionalDependencies() throws Exception {
        add("app", "1.0.0", deps("?market ^1.0", "?absent"));
        add("market", "1.3.0", "");
        DependencyResolver.Result r = resolve();
        assertEquals(List.of("market", "app"), r.order());
        assertEquals(List.of("market"), r.visibleDependencies().get("app"));

        mods.clear();
        add("app", "1.0.0", deps("?market ^2.0"));
        add("market", "1.3.0", "");
        r = resolve();
        assertTrue(r.refused().isEmpty());
        assertEquals(List.of(), r.visibleDependencies().get("app"));
        assertEquals(1, r.warnings().size());
    }

    @Test
    void conflicts() throws Exception {
        add("new-bots", "2.0.0", ",\"conflicts\":[{\"id\":\"old-bots\",\"version\":\"<2.0\"}]");
        add("old-bots", "1.0.0", "");
        add("other", "1.0.0", ",\"conflicts\":[{\"id\":\"new-bots\",\"version\":\"<1.0\"}]");
        final DependencyResolver.Result r = resolve();
        assertTrue(r.refused().get("new-bots").contains("conflicts with old-bots"));
        assertTrue(r.refused().get("old-bots").contains("conflicts with new-bots"));
        assertFalse(r.refused().containsKey("other"));
    }

    @Test
    void cycles() throws Exception {
        add("a1", "1.0.0", deps("b1"));
        add("b1", "1.0.0", deps("a1"));
        add("x1", "1.0.0", deps("y1"));
        add("y1", "1.0.0", deps("z1"));
        add("z1", "1.0.0", deps("x1"));
        add("w1", "1.0.0", deps("x1"));
        add("ok", "1.0.0", "");
        final DependencyResolver.Result r = resolve();
        assertEquals(List.of("ok"), r.order());
        assertTrue(r.refused().get("a1").startsWith("dependency cycle"));
        assertTrue(r.refused().get("z1").startsWith("dependency cycle"));
        assertTrue(r.refused().containsKey("w1"));
    }

    @Test
    void platformVersionAndDowngrade() throws Exception {
        TestModules.module(root, "future", "1.0.0", "");
        Files.writeString(root.resolve("future/module.json"), "{\"id\":\"future\",\"name\":\"f\",\"version\":\"1.0.0\",\"kind\":\"data\",\"platform\":\"^2.0\"}");
        mods.add(ManifestReader.read(root.resolve("future")));
        add("old", "1.0.0", "");
        add("same", "1.0.0", "");
        final DependencyResolver.Result r = DependencyResolver.resolve(mods, Set.of(), API, Map.of("old", Semver.parse("1.1.0"), "same", Semver.parse("1.0.0")));
        assertTrue(r.refused().get("future").contains("needs platform ^2.0"));
        assertTrue(r.refused().get("old").contains("downgrade from 1.1.0 to 1.0.0"));
        assertEquals(List.of("same"), r.order());
    }

    @Test
    void reservationOverlap() throws Exception {
        Files.createDirectories(root.resolve("a1/n"));
        Files.createDirectories(root.resolve("b1/n"));
        Files.createDirectories(root.resolve("c1/n"));
        add("a1", "1.0.0", ",\"data\":{\"npcs\":\"n\"},\"reserves\":{\"npcs\":[[100,199]]}");
        add("b1", "1.0.0", ",\"data\":{\"npcs\":\"n\"},\"reserves\":{\"npcs\":[[150,250]]}");
        add("c1", "1.0.0", ",\"data\":{\"npcs\":\"n\"},\"reserves\":{\"npcs\":[[300,399]],\"multisell\":[[100,199]]}");
        final DependencyResolver.Result r = resolve();
        assertTrue(r.refused().get("a1").contains("npcs 150..199 overlaps with module b1"), r.refused().toString());
        assertTrue(r.refused().get("b1").contains("overlaps with module a1"));
        assertEquals(List.of("c1"), r.order());
    }
}
