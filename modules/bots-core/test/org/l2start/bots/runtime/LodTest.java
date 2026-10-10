package org.l2start.bots.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LodTest {
    private static final double NONE = Double.POSITIVE_INFINITY;

    @Test
    void promoteNearDemoteOnlyAfterDelayAndFar() {
        final Lod lod = new Lod();
        assertEquals(Lod.Level.PRESENT, lod.level());
        assertFalse(lod.update(3500, 0, 3000, 4500, 45_000));
        assertTrue(lod.update(2900, 1000, 3000, 4500, 45_000));
        assertEquals(Lod.Level.ACTIVE, lod.level());
        // between promote and demote radius: stays ACTIVE forever
        for (long t = 2000; t < 200_000; t += 2000) {
            assertFalse(lod.update(4000, t, 3000, 4500, 45_000));
        }
        // far away: still ACTIVE before 45 s
        assertFalse(lod.update(NONE, 200_000, 3000, 4500, 45_000));
        assertFalse(lod.update(5000, 244_000, 3000, 4500, 45_000));
        assertEquals(Lod.Level.ACTIVE, lod.level());
        assertTrue(lod.update(NONE, 245_000, 3000, 4500, 45_000));
        assertEquals(Lod.Level.PRESENT, lod.level());
    }

    @Test
    void comingBackResetsTheDemoteTimer() {
        final Lod lod = new Lod();
        lod.update(100, 0, 3000, 4500, 45_000);
        lod.update(NONE, 1000, 3000, 4500, 45_000);
        lod.update(4000, 40_000, 3000, 4500, 45_000); // back within demote radius
        lod.update(NONE, 50_000, 3000, 4500, 45_000);
        assertEquals(Lod.Level.ACTIVE, lod.level());
        lod.update(NONE, 94_000, 3000, 4500, 45_000);
        assertEquals(Lod.Level.ACTIVE, lod.level());
        lod.update(NONE, 95_000, 3000, 4500, 45_000);
        assertEquals(Lod.Level.PRESENT, lod.level());
    }

    @Test
    void tickDelays() {
        assertEquals(300, Lod.delay(Lod.Level.ACTIVE, true, 100, 0.5));
        assertEquals(1000, Lod.delay(Lod.Level.ACTIVE, false, 100, 0.5));
        assertEquals(60_000, Lod.delay(Lod.Level.ACTIVE, false, 60_000, 0.5));
        assertEquals(3000, Lod.delay(Lod.Level.PRESENT, true, 100, 0));
        assertEquals(5000, Lod.delay(Lod.Level.PRESENT, false, 100, 1));
    }
}
