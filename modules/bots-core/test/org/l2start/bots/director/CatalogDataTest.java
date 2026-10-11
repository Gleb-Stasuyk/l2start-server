package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Pos;

/** The shipped data/world.xml and data/gear.xml (made by tools/gen-world.py from the stock data). */
class CatalogDataTest {
    static final Path DATA = Path.of("modules/bots-core/data");

    @Test
    void worldHasAllTownsWithGatekeepersAndGrounds() throws Exception {
        final WorldCatalog w = WorldCatalog.load(DATA.resolve("world.xml"));
        assertTrue(w.towns().size() >= 15, "towns: " + w.towns().size());
        assertTrue(w.grounds().size() >= 300, "grounds: " + w.grounds().size());
        for (WorldCatalog.Town t : w.towns()) {
            assertFalse(t.anchors("gk").isEmpty(), t.id() + " has no gatekeeper");
            assertTrue(t.minLevel() <= t.maxLevel(), t.id());
            assertEquals(t, w.townAt(t.anchors("gk").get(0), 300), t.id() + ": its gatekeeper is not in it");
        }
        for (WorldCatalog.Ground g : w.grounds()) {
            assertTrue((g.minLevel() >= 1) && (g.minLevel() <= g.maxLevel()) && (g.maxLevel() <= 90), g.id());
            assertFalse(g.spots().isEmpty(), g.id());
        }
        final WorldCatalog.Town giran = w.town("giran_castle_town");
        assertNotNull(giran);
        assertTrue((giran.minLevel() >= 25) && (giran.maxLevel() <= 55), "giran " + giran.minLevel() + "-" + giran.maxLevel());
        // every level band has somewhere to hunt
        for (int lvl = 1; lvl <= 78; lvl++) {
            final int l = lvl;
            assertTrue(w.grounds().stream().anyMatch(g -> g.fits(l)), "no ground for level " + lvl);
        }
        // grid lookup = brute force
        final WorldCatalog.Ground any = w.grounds().get(w.grounds().size() / 2);
        final List<WorldCatalog.Ground> near = w.groundsNear(any.center(), 2500);
        assertTrue(near.contains(any));
        assertTrue(w.groundsNear(new Pos(-500_000, -500_000, 0), 2500).isEmpty());
    }

    @Test
    void gearFitsGradeAndClass() throws Exception {
        final GearTable g = GearTable.load(DATA.resolve("gear.xml"));
        assertTrue(g.sets() > 20, "sets " + g.sets());
        assertTrue(g.weapons() > 50, "weapons " + g.weapons());
        assertTrue(g.plan(2, 5, 1).items().isEmpty(), "nothing below level 10");
        final int[] professions = { 2, 3, 5, 6, 8, 9, 12, 13, 14, 16, 17, 20, 21, 23, 24, 27, 28, 30, 33, 34, 36, 37, 40, 41, 43, 46, 48, 51, 52, 55, 57 };
        for (int p : professions) {
            for (int lvl = 10; lvl <= 80; lvl += 7) {
                for (long seed = 0; seed < 4; seed++) {
                    final GearTable.Plan plan = g.plan(p, lvl, seed);
                    assertFalse(plan.items().isEmpty(), "class " + p + " lv " + lvl);
                    assertTrue(plan.items().size() >= 2, "class " + p + " lv " + lvl + ": " + plan.items());
                    assertEquals(GearTable.mage(p), plan.armor() == GearTable.Armor.MAGIC, "class " + p);
                }
            }
        }
        assertEquals(GearTable.Grade.D, GearTable.Grade.of(20));
        assertEquals(GearTable.Grade.S, GearTable.Grade.of(76));
    }
}
