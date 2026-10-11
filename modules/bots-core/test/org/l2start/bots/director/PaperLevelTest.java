package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaperLevelTest {
    static final long DAY = 86_400_000L;

    @Test
    void growsFastLowSlowHighNeverDownNeverAboveMax() {
        assertEquals(10, PaperLevel.at(10, 0, 1, 0));
        final int week = PaperLevel.at(1, 0, 1, 7 * DAY);
        assertTrue((week > 10) && (week < 40), "a week from level 1: " + week);
        final int high = PaperLevel.at(76, 0, 1, 30 * DAY);
        assertTrue((high >= 76) && (high <= 80), "high levels crawl: " + high);
        assertEquals(80, PaperLevel.at(80, 0, 1.5, 1000 * DAY));
        int prev = 0;
        for (long t = 0; t < (60 * DAY); t += DAY / 2) {
            final int l = PaperLevel.at(5, 0, 1, t);
            assertTrue(l >= prev);
            prev = l;
        }
        assertEquals(20, PaperLevel.at(20, 5 * DAY, 1, 0), "a clock behind the base does not lower it");
    }

    @Test
    void paceIsStableAndBounded() {
        for (long s = -1000; s < 1000; s++) {
            final double p = PaperLevel.pace(s);
            assertTrue((p >= 0.5) && (p <= 1.5));
            assertEquals(p, PaperLevel.pace(s));
        }
    }
}
