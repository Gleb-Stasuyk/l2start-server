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
        assertTrue(json.contains("\"platform\": \"^1.2\""));
    }

    @Test
    void migrationCreatesOnlyModuleTables() throws Exception {
        final String sql = Files.readString(Path.of("modules/bots-core/sql/V001__bots_core.sql"));
        for (String line : sql.split("\n")) {
            if (line.toUpperCase().contains("CREATE TABLE")) {
                assertTrue(line.contains("mod_bots_core_"), line);
            }
            assertTrue(!line.toUpperCase().matches(".*\\b(ALTER|DROP|UPDATE|DELETE|INSERT)\\b.*"), "migration must not touch stock tables: " + line);
        }
    }
}
