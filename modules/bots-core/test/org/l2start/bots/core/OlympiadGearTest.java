package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.l2start.bots.director.GearTable;

/** Phase 9 E: olympiad nobles wear a mix of A and S (weapon, armour, jewellery), never a full S kit. */
class OlympiadGearTest {
    static final Set<Integer> A_JEWELS = Set.of(933, 924, 871, 862, 902, 893);
    static final Set<Integer> S_JEWELS = Set.of(920, 858, 889);

    @Test
    void mixOfAAndSNeverFullS() throws Exception {
        final GearTable g = GearTable.load(java.nio.file.Path.of("modules/bots-core/data/gear.xml"));
        int aWeapons = 0;
        int sWeapons = 0;
        for (int cls : new int[] { 88, 90, 92, 94, 95, 110, 113 }) {
            for (long seed = 1; seed <= 80; seed++) {
                final GearTable.Plan p = g.olympiadPlan(cls, seed);
                assertFalse(p.items().isEmpty(), "class " + cls + " seed " + seed);
                if (p.grade() == GearTable.Grade.A) {
                    aWeapons++;
                } else {
                    assertEquals(GearTable.Grade.S, p.grade());
                    sWeapons++;
                }
                final List<Integer> jewels = p.items().stream().filter(GearTable::olympiadJewel).toList();
                assertEquals(5, jewels.size(), "neck + 2 ears + 2 rings: " + jewels);
                boolean aJewel = false;
                boolean sJewel = false;
                for (int id : jewels) {
                    aJewel |= A_JEWELS.contains(id);
                    sJewel |= S_JEWELS.contains(id);
                }
                assertTrue(aJewel, "A jewellery missing seed " + seed + " " + jewels);
                assertTrue(sJewel, "S jewellery missing seed " + seed + " " + jewels);
                assertFalse(p.items().contains(1060) || p.items().contains(1061) || p.items().contains(1539) || p.items().contains(736),
                    "potions/SoE must not be in the gear plan");
            }
        }
        assertTrue(aWeapons > 50, "A weapons " + aWeapons);
        assertTrue(sWeapons > 50, "S weapons " + sWeapons);
    }

    @Test
    void jewelleryIdsAreUniqueSoBothEarsAndRingsFit() {
        for (long seed = 0; seed < 200; seed++) {
            final List<Integer> j = GearTable.olympiadJewels(seed);
            assertEquals(5, new HashSet<>(j).size(), "duplicate jewellery id would leave a slot empty: " + j);
        }
    }

    @Test
    void olympiadShotsAreRegularNotBlessed() {
        assertFalse(Materializer.blessedShot(1466));
        assertFalse(Materializer.blessedShot(1467));
        assertFalse(Materializer.blessedShot(2513));
        assertFalse(Materializer.blessedShot(2514));
        assertFalse(Materializer.blessedShot(6645));
        assertFalse(Materializer.blessedShot(6646));
        assertTrue(Materializer.blessedShot(3947));
        assertTrue(Materializer.blessedShot(3951));
        assertTrue(Materializer.blessedShot(3952));
        assertTrue(Materializer.blessedShot(6647));
        assertEquals(2513, Materializer.SPIRITSHOTS_PLAIN[GearTable.Grade.A.ordinal()]);
        assertEquals(2514, Materializer.SPIRITSHOTS_PLAIN[GearTable.Grade.S.ordinal()]);
        assertEquals(3951, Materializer.SPIRITSHOTS[GearTable.Grade.A.ordinal()]);
    }

    @Test
    void sameSeedSameKit() throws Exception {
        final GearTable g = GearTable.load(java.nio.file.Path.of("modules/bots-core/data/gear.xml"));
        for (long seed = 3; seed < 30; seed++) {
            assertEquals(g.olympiadPlan(92, seed).items(), g.olympiadPlan(92, seed).items());
        }
    }
}
