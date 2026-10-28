package org.l2start.olympiad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.l2start.platform.loader.ManifestReader;
import org.l2start.platform.loader.ModuleManifest;

class BotsOlympiadManifestTest {
    @Test
    void manifestIsValid() throws Exception {
        final Path dir = Path.of("modules/bots-olympiad");
        final ModuleManifest m = ManifestReader.read(dir);
        assertEquals("bots-olympiad", m.id());
        assertEquals("0.2.0", m.version().toString());
        final String json = Files.readString(dir.resolve("module.json"));
        assertTrue(json.contains("\"entrypoint\": \"org.l2start.olympiad.BotsOlympiadModule\""));
        assertTrue(json.contains("\"id\": \"bots-core\""));
        assertTrue(json.contains("\"version\": \"^0.7.0\""), "must match main bots-core, no REFUSED downgrade");
        assertTrue(json.contains("RosterSize"));
        assertTrue(json.contains("GatherNpcId"));
        assertTrue(Files.exists(dir.resolve("config/defaults.ini")));
        assertTrue(Files.readString(dir.resolve("config/defaults.ini")).contains("RosterSize = 40"));
    }
}
