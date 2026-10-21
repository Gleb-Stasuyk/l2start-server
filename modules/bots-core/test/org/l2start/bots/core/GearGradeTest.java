package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.l2start.bots.director.GearTable;

/** Live 2026-10-21: a level 8 bot wore a D-grade Atuba Hammer +4. Gear must fit the level, no enchant below 20. */
class GearGradeTest {
    @Test
    void gradeByLevel() {
        assertEquals(0, Materializer.maxGrade(8), "no-grade below 20");
        assertEquals(0, Materializer.maxGrade(19));
        assertEquals(1, Materializer.maxGrade(20), "D at 20");
        assertEquals(1, Materializer.maxGrade(39));
        assertEquals(2, Materializer.maxGrade(40), "C at 40");
    }

    @Test
    void noEnchantBelow20() {
        assertEquals(0, Materializer.maxEnchant(8));
        assertEquals(0, Materializer.maxEnchant(19));
        assertTrue(Materializer.maxEnchant(20) >= 4);
    }

    @Test
    void planForLevel8IsNoGradeWithoutEnchant() throws Exception {
        final GearTable g = GearTable.load(java.nio.file.Path.of("modules/bots-core/data/gear.xml"));
        for (long seed = 1; seed <= 200; seed++) {
            final GearTable.Plan p = g.plan(0, 8, seed, 0);
            assertEquals(GearTable.Grade.NONE, p.grade(), "seed " + seed);
            assertEquals(0, p.weaponEnchant(), "seed " + seed);
            assertEquals(0, p.armorEnchant(), "seed " + seed);
        }
    }
}
