package org.l2start.platform.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.l2start.platform.TestModules;

class ManifestReaderTest {
    @TempDir
    Path root;

    private ManifestException fails(String id, String json) throws Exception {
        final Path dir = root.resolve(id);
        TestModules.write(dir.resolve("module.json"), json);
        return assertThrows(ManifestException.class, () -> ManifestReader.read(dir));
    }

    private static boolean has(ManifestException e, String part) {
        for (String s : e.errors()) {
            if (s.contains(part)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void fullManifest() throws Exception {
        final Path dir = root.resolve("bots-core");
        Files.createDirectories(dir.resolve("data/npcs"));
        Files.createDirectories(dir.resolve("data/html"));
        TestModules.write(dir.resolve("module.json"), """
            {
              "id": "bots-core", "name": "Bots", "description": "d", "version": "1.2.0", "kind": "jar", "platform": "^1.0",
              "entrypoint": "org.l2start.modules.bots.BotsModule", "authors": ["a", "b"],
              "dependencies": [ {"id": "hello", "version": "^1.0"}, {"id": "market", "version": "~2.1", "optional": true} ],
              "conflicts": [ {"id": "old-bots", "version": "*"} ],
              "defaultEnabled": {"solo": true, "online": false},
              "data": {"npcs": "data/npcs", "html": "data/html"},
              "reserves": {"npcs": [[96000, 96099], [96500, 96500]]},
              "migrations": "sql",
              "config": {
                "MaxBots": {"type": "int", "default": 100, "min": 0, "max": 1000, "reloadable": true},
                "Mode": {"type": "enum", "default": "lazy", "values": ["lazy", "busy"]},
                "Tick": {"type": "duration", "default": "250ms"},
                "Names": {"type": "list", "default": ["a", "b"]}
              },
              "futureField": 1
            }
            """);
        final ModuleManifest m = ManifestReader.read(dir);
        assertEquals("bots-core", m.id());
        assertEquals("1.2.0", m.version().toString());
        assertEquals(ModuleManifest.Kind.JAR, m.kind());
        assertEquals(2, m.dependencies().size());
        assertTrue(m.dependencies().get(1).optional());
        assertEquals(1, m.conflicts().size());
        assertTrue(m.defaultEnabled("solo"));
        assertFalse(m.defaultEnabled("online"));
        assertFalse(m.defaultEnabled("other"));
        assertTrue(m.reserves(DataType.NPCS, 96050));
        assertTrue(m.reserves(DataType.NPCS, 96500));
        assertFalse(m.reserves(DataType.NPCS, 96100));
        assertEquals("a,b", m.config().get("Names").defaultValue());
        assertEquals(1, m.warnings().size());
        assertTrue(m.warnings().get(0).contains("futureField"));
    }

    @Test
    void requiredFields() throws Exception {
        final ManifestException e = fails("x1", "{}");
        for (String f : new String[] { "id is required", "name is required", "version is required", "kind is required", "platform is required" }) {
            assertTrue(has(e, f), f + " in " + e.errors());
        }
    }

    @Test
    void idRulesAndFolder() throws Exception {
        assertTrue(has(fails("Bad_Id", "{\"id\":\"Bad_Id\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"data\",\"platform\":\"*\"}"), "must match"));
        assertTrue(has(fails("folder", "{\"id\":\"other\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"data\",\"platform\":\"*\"}"), "folder name"));
    }

    @Test
    void kindRules() throws Exception {
        assertTrue(has(fails("k1", "{\"id\":\"k1\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"jar\",\"platform\":\"*\"}"), "entrypoint is required"));
        assertTrue(has(fails("k2", "{\"id\":\"k2\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"script\",\"platform\":\"*\"}"), "not supported yet"));
        assertTrue(has(fails("k3", "{\"id\":\"k3\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"data\",\"platform\":\"*\",\"entrypoint\":\"a.B\"}"), "not allowed"));
        assertTrue(has(fails("k4", "{\"id\":\"k4\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"jar\",\"platform\":\"*\",\"entrypoint\":\"NoPackage\"}"), "fully qualified"));
    }

    @Test
    void badVersionAndRanges() throws Exception {
        final ManifestException e = fails("v1", "{\"id\":\"v1\",\"name\":\"n\",\"version\":\"1.0\",\"kind\":\"data\",\"platform\":\"^x\",\"dependencies\":[{\"id\":\"a1\",\"version\":\">=zz\"}]}");
        assertTrue(has(e, "version:"));
        assertTrue(has(e, "platform:"));
        assertTrue(has(e, "dependencies.a1.version"));
    }

    @Test
    void pathsMustStayInside() throws Exception {
        final ManifestException e = fails("p1", "{\"id\":\"p1\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"data\",\"platform\":\"*\",\"data\":{\"npcs\":\"../x\",\"html\":\"/etc\",\"spawns\":\"C:/w\",\"multisell\":\"a\\\\b\"},\"migrations\":\"../sql\"}");
        assertEquals(5, e.errors().stream().filter(s -> s.contains("relative path inside")).count(), e.errors().toString());
    }

    @Test
    void dataFolderMustExistAndIdsNeedReserves() throws Exception {
        final Path dir = root.resolve("d1");
        Files.createDirectories(dir.resolve("data/multisell"));
        TestModules.write(dir.resolve("module.json"), "{\"id\":\"d1\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"data\",\"platform\":\"*\",\"data\":{\"multisell\":\"data/multisell\",\"spawns\":\"data/nope\",\"weapons\":\"data/w\"},\"reserves\":{\"spawns\":[[1,2]]}}");
        final ManifestException e = assertThrows(ManifestException.class, () -> ManifestReader.read(dir));
        assertTrue(has(e, "data.spawns: folder 'data/nope' does not exist"), e.errors().toString());
        assertTrue(has(e, "data.weapons: unknown data type"));
        assertTrue(has(e, "needs reserves.multisell"));
        assertTrue(has(e, "reserves.spawns: only npcs"));
    }

    @Test
    void badReserves() throws Exception {
        assertTrue(has(fails("r1", "{\"id\":\"r1\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"data\",\"platform\":\"*\",\"reserves\":{\"npcs\":[[5,1],[1],\"x\"]}}"), "each range"));
    }

    @Test
    void dependencyRules() throws Exception {
        final ManifestException e = fails("dep", "{\"id\":\"dep\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"data\",\"platform\":\"*\",\"dependencies\":[{\"id\":\"dep\"},{\"id\":\"a1\"},{\"id\":\"a1\"},{\"id\":\"BAD\"}],\"conflicts\":[{\"id\":\"c1\",\"optional\":true}]}");
        assertTrue(has(e, "cannot depend on itself"));
        assertTrue(has(e, "listed twice"));
        assertTrue(has(e, "bad or missing id"));
        assertTrue(has(e, "'optional' is not allowed"));
    }

    @Test
    void configSchemaRules() throws Exception {
        final ManifestException e = fails("c1", "{\"id\":\"c1\",\"name\":\"n\",\"version\":\"1.0.0\",\"kind\":\"data\",\"platform\":\"*\",\"config\":{"
            + "\"NoType\":{\"default\":1},\"NoDefault\":{\"type\":\"int\"},\"BadDefault\":{\"type\":\"int\",\"default\":\"x\"},"
            + "\"OutOfRange\":{\"type\":\"int\",\"default\":5,\"max\":3},\"EnumNoValues\":{\"type\":\"enum\",\"default\":\"a\"},"
            + "\"Dup\":{\"type\":\"bool\",\"default\":true},\"dup\":{\"type\":\"bool\",\"default\":true},\"bad key\":{\"type\":\"bool\",\"default\":true}}}");
        assertTrue(has(e, "config.NoType.type"));
        assertTrue(has(e, "config.NoDefault.default is required"));
        assertTrue(has(e, "config.BadDefault.default"));
        assertTrue(has(e, "config.OutOfRange.default"));
        assertTrue(has(e, "enum needs 'values'"));
        assertTrue(has(e, "case-insensitive"));
        assertTrue(has(e, "must be alphanumeric"));
    }

    @Test
    void brokenJsonAndMissingFile() throws Exception {
        assertTrue(fails("j1", "{\"id\": }").getMessage().contains("line 1"));
        assertThrows(ManifestException.class, () -> ManifestReader.read(root.resolve("nothing")));
    }

    @Test
    void realHelloManifestIsValid() throws Exception {
        final ModuleManifest m = ManifestReader.read(Path.of("modules/hello"));
        assertEquals("hello", m.id());
        assertTrue(m.warnings().isEmpty(), m.warnings().toString());
    }
}
