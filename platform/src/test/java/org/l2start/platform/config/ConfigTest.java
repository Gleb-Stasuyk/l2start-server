package org.l2start.platform.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.l2start.platform.TestModules;
import org.l2start.platform.config.PlatformSettings.FailurePolicy;
import org.l2start.platform.loader.ManifestReader;
import org.l2start.platform.loader.ModuleManifest;
import org.l2start.platform.util.IniFile;

class ConfigTest {
    @TempDir
    Path root;

    enum Mode { LAZY, BUSY }

    private ModuleManifest manifest() throws Exception {
        return ManifestReader.read(TestModules.module(root, "bots-core", "1.0.0", ",\"config\":{"
            + "\"MaxBots\":{\"type\":\"int\",\"default\":100,\"min\":0,\"max\":1000},"
            + "\"Greet\":{\"type\":\"bool\",\"default\":true},"
            + "\"Tick\":{\"type\":\"duration\",\"default\":\"250ms\",\"min\":\"10ms\"},"
            + "\"Mode\":{\"type\":\"enum\",\"default\":\"lazy\",\"values\":[\"lazy\",\"busy\"]},"
            + "\"Names\":{\"type\":\"list\",\"default\":\"a, b\"},"
            + "\"Ratio\":{\"type\":\"double\",\"default\":0.5,\"max\":1}}"));
    }

    @Test
    void defaultsOnly() throws Exception {
        final ResolvedConfig c = ResolvedConfig.resolve(manifest(), "solo", IniFile.parse(""), IniFile.parse(""), Map.of());
        assertEquals(100, c.getInt("MaxBots"));
        assertEquals(100L, c.getLong("maxbots"));
        assertTrue(c.getBoolean("Greet"));
        assertEquals(Duration.ofMillis(250), c.getDuration("Tick"));
        assertEquals(Mode.LAZY, c.getEnum("Mode", Mode.class));
        assertEquals(List.of("a", "b"), c.getList("Names"));
        assertEquals(0.5, c.getDouble("Ratio"));
        assertEquals("default", c.source("MaxBots"));
        assertThrows(IllegalArgumentException.class, () -> c.getString("Nope"));
    }

    @Test
    void fullPrecedenceChain() throws Exception {
        final IniFile defaults = IniFile.parse("MaxBots = 200\nGreet = off\n[online]\nMaxBots = 300\n");
        final IniFile overrides = IniFile.parse("MaxBots = 400\n[online]\nMode = busy\n");
        ResolvedConfig c = ResolvedConfig.resolve(manifest(), "solo", defaults, overrides, Map.of());
        assertEquals(400, c.getInt("MaxBots"));
        assertEquals("config/modules/bots-core.ini", c.source("MaxBots"));
        assertFalse(c.getBoolean("Greet"));
        assertEquals("modules/bots-core/config/defaults.ini", c.source("Greet"));
        assertEquals(Mode.LAZY, c.getEnum("Mode", Mode.class));

        c = ResolvedConfig.resolve(manifest(), "online", defaults, overrides, Map.of("L2_MOD_BOTS_CORE__MAXBOTS", "500", "L2_MOD_BOTS_CORE__RATIO", "1", "L2_MOD_OTHER__MAXBOTS", "1"));
        assertEquals(500, c.getInt("MaxBots"));
        assertEquals("env L2_MOD_BOTS_CORE__MAXBOTS", c.source("MaxBots"));
        assertEquals(Mode.BUSY, c.getEnum("Mode", Mode.class));
        assertEquals("config/modules/bots-core.ini [online]", c.source("Mode"));
    }

    @Test
    void validationErrorsNameTheSource() throws Exception {
        final ResolvedConfig.ConfigException e = assertThrows(ResolvedConfig.ConfigException.class, () -> ResolvedConfig.resolve(manifest(), "solo", IniFile.parse("MaxBots = 5000\nTick = 1ms\n"), IniFile.parse("Mode = crazy\n"), Map.of("L2_MOD_BOTS_CORE__GREET", "maybe")));
        assertTrue(e.getMessage().contains("MaxBots: 5000 > max 1000 (from modules/bots-core/config/defaults.ini)"), e.getMessage());
        assertTrue(e.getMessage().contains("Tick"));
        assertTrue(e.getMessage().contains("Mode: 'crazy' is not one of"));
        assertTrue(e.getMessage().contains("env L2_MOD_BOTS_CORE__GREET"));
    }

    @Test
    void unknownKeysWarn() throws Exception {
        final ResolvedConfig c = ResolvedConfig.resolve(manifest(), "solo", IniFile.parse("Extra = 1\n"), IniFile.parse(""), Map.of("L2_MOD_BOTS_CORE__NOPE", "1"));
        assertEquals(2, c.warnings().size(), c.warnings().toString());
        assertEquals("1", c.getString("Extra"));
    }

    @Test
    void switchesPrecedence() {
        final IniFile main = IniFile.parse("alpha = on\nbots-core = off\n[online]\nalpha = off\n");
        final IniFile local = IniFile.parse("[online]\nbots-core = on\n");
        ModuleSwitches s = new ModuleSwitches("solo", main, local, Map.of());
        assertTrue(s.resolve("alpha", false).enabled());
        assertEquals("modules.ini", s.resolve("alpha", false).source());
        assertFalse(s.resolve("bots-core", true).enabled());
        assertTrue(s.resolve("other", true).enabled());
        assertTrue(s.resolve("other", true).source().startsWith("defaultEnabled"));
        assertFalse(s.isExplicit("other"));

        s = new ModuleSwitches("online", main, local, Map.of("L2_MODULE_ALPHA", "on"));
        assertTrue(s.resolve("alpha", false).enabled());
        assertEquals("env L2_MODULE_ALPHA", s.resolve("alpha", false).source());
        assertTrue(s.resolve("bots-core", false).enabled());
        assertEquals("modules.local.ini [online]", s.resolve("bots-core", false).source());
        s = new ModuleSwitches("online", main, local, Map.of());
        assertFalse(s.resolve("alpha", true).enabled());
        assertEquals("modules.ini [online]", s.resolve("alpha", true).source());
    }

    @Test
    void envNames() {
        assertEquals("BOTS_CORE", Env.idPart("bots-core"));
        assertEquals(Env.norm("GreetOnLogin"), Env.norm("GREET_ON_LOGIN"));
    }

    @Test
    void platformSettingsDefaultsPerProfile() throws Exception {
        assertEquals(FailurePolicy.ABORT, PlatformSettings.load(root, Map.of("L2_PROFILE", "online")).failurePolicy());
        assertEquals(FailurePolicy.CONTINUE, PlatformSettings.load(root, Map.of("L2_PROFILE", "solo")).failurePolicy());
        assertEquals(FailurePolicy.CONTINUE, PlatformSettings.load(root, Map.of()).failurePolicy());
        assertEquals("solo", PlatformSettings.load(root, Map.of()).profile());
    }

    @Test
    void platformSettingsFileLocalAndEnv() throws Exception {
        TestModules.write(root.resolve("platform.ini"), "FailurePolicy = continue\nBreakerErrors = 7\nModuleRoots = modules, extra\n[solo]\nFailurePolicy = continue\n[online]\nFailurePolicy = abort\nBreakerWindow = 2m\n");
        TestModules.write(root.resolve("platform.local.ini"), "[solo]\nFailurePolicy = abort\n");
        PlatformSettings s = PlatformSettings.load(root, Map.of("L2_PROFILE", "online"));
        assertEquals(FailurePolicy.ABORT, s.failurePolicy());
        assertEquals(7, s.breakerErrors());
        assertEquals(Duration.ofMinutes(2), s.breakerWindow());
        assertEquals(List.of("modules", "extra"), s.moduleRoots());
        s = PlatformSettings.load(root, Map.of("L2_PROFILE", "solo"));
        assertEquals(FailurePolicy.ABORT, s.failurePolicy());
        assertEquals("platform.local.ini", s.failurePolicySource());
        s = PlatformSettings.load(root, Map.of("L2_PROFILE", "solo", "L2_PLATFORM__FAILUREPOLICY", "continue", "L2_PLATFORM__DISABLEALL", "true", "L2_PLATFORM__BREAKER_ERRORS", "x"));
        assertEquals(FailurePolicy.CONTINUE, s.failurePolicy());
        assertTrue(s.disableAll());
        assertEquals(20, s.breakerErrors());
        assertEquals(1, s.warnings().size());
    }

    @Test
    void shippedPlatformIniHasGlebsDefaults() throws Exception {
        final Path dir = Path.of("platform/dist/game/config");
        assertEquals(FailurePolicy.ABORT, PlatformSettings.load(dir, Map.of("L2_PROFILE", "online")).failurePolicy());
        assertEquals(FailurePolicy.CONTINUE, PlatformSettings.load(dir, Map.of("L2_PROFILE", "solo")).failurePolicy());
        assertFalse(PlatformSettings.load(dir, Map.of()).disableAll());
        // No module ships with the server: modules.ini switches nothing, defaults from module.json apply.
        final ModuleSwitches s = ModuleSwitches.load(dir, "online", Map.of());
        assertFalse(s.isExplicit("anything"));
        assertTrue(s.resolve("anything", true).enabled());
        assertFalse(ModuleSwitches.load(dir, "solo", Map.of()).resolve("anything", false).enabled());
    }
}
