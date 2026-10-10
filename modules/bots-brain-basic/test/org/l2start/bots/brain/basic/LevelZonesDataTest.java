package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.platform.loader.ManifestReader;
import org.l2start.platform.loader.ModuleManifest;

class LevelZonesDataTest {
    static final Path DIR = Path.of("modules/bots-brain-basic");

    @Test
    void shippedZonesCoverEveryVillageLevel1To10() throws Exception {
        final LevelZones z = LevelZones.load(DIR.resolve("data/levelzones.xml"));
        assertTrue(z.all().size() >= 15, "zones " + z.all().size());
        final Random r = new Random(1);
        for (Village v : Village.STARTING) {
            for (int lvl = 1; lvl <= 10; lvl++) {
                final int level = lvl;
                assertTrue(z.all().stream().anyMatch(x -> x.village().equals(v.id()) && x.fits(level)), v.id() + " has no zone for level " + lvl);
                final LevelZone picked = z.pick(lvl, v.id(), v.center(), r);
                assertNotNull(picked);
                assertEquals(v.id(), picked.village());
                assertTrue(picked.center().distance2d(v.center()) < 25_000, picked.id());
            }
        }
        for (LevelZone x : z.all()) {
            assertTrue((x.minLevel() >= 1) && (x.maxLevel() <= 15), x.id());
            assertFalse(x.npcIds().isEmpty(), x.id());
        }
    }

    @Test
    void manifestDependsOnBotsCore() throws Exception {
        final ModuleManifest m = ManifestReader.read(DIR);
        assertEquals("bots-brain-basic", m.id());
        final String json = Files.readString(DIR.resolve("module.json"));
        assertTrue(json.contains("\"defaultEnabled\": { \"solo\": true, \"online\": false }"));
        assertTrue(json.contains("\"id\": \"bots-core\""));
    }
}
