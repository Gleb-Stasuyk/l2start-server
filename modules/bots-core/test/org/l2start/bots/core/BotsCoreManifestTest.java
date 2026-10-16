package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.l2start.platform.loader.ManifestReader;
import org.l2start.platform.loader.ModuleManifest;

class BotsCoreManifestTest {
    @Test
    void manifestIsValidOnInSoloOffInOnline() throws Exception {
        final Path dir = Path.of("modules/bots-core");
        final ModuleManifest m = ManifestReader.read(dir);
        assertEquals("bots-core", m.id());
        final String json = Files.readString(dir.resolve("module.json"));
        assertTrue(json.contains("\"defaultEnabled\": { \"solo\": true, \"online\": false }"));
        assertTrue(json.contains("\"platform\": \"^1.10\""));
        assertTrue(json.contains("PkBotShare"));
        assertTrue(json.contains("BotClans"));
        assertTrue(json.contains("ClanMembersMin"));
        assertTrue(json.contains("ClanMixedPartyChance"));
    }

    @Test
    void migrationsCreateOnlyModuleTables() throws Exception {
        final StringBuilder all = new StringBuilder();
        try (var files = Files.list(Path.of("modules/bots-core/sql"))) {
            for (Path f : files.sorted().toList()) {
                all.append(Files.readString(f)).append('\n');
            }
        }
        assertTrue(all.toString().contains("mod_bots_core_persona") && all.toString().contains("mod_bots_core_state"));
        assertTrue(all.toString().contains("mod_bots_core_pk_ledger"));
        assertTrue(all.toString().contains("mod_bots_core_clan"));
        assertTrue(all.toString().contains("mod_bots_core_clan_member"));
        for (String line : all.toString().split("\n")) {
            if (line.toUpperCase().contains("CREATE TABLE")) {
                assertTrue(line.contains("mod_bots_core_"), line);
            }
            assertTrue(!line.trim().toUpperCase().matches("(ALTER|DROP|UPDATE|DELETE|INSERT|REPLACE|TRUNCATE|RENAME)\\b.*"), "migration must not touch stock tables: " + line);
        }
    }

    @Test
    void pkBotShareClampedToPlan() {
        assertEquals(0.02, BotsCoreModule.pkShareOf(null), 1e-9);
        assertEquals(0.02, BotsCoreModule.pkShareOf("0.02"), 1e-9);
        assertEquals(0.03, BotsCoreModule.pkShareOf("0.05"), 1e-9);
        assertEquals(0.0, BotsCoreModule.pkShareOf("0"), 1e-9);
        assertEquals(0.02, BotsCoreModule.pkShareOf("nope"), 1e-9);
    }
}
