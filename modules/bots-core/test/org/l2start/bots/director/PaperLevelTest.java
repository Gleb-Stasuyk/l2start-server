package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.persona.Chronotype;

class PaperLevelTest {
    static final long DAY = 86_400_000L;

    @AfterEach
    void reset() {
        PaperLevel.setGlobalPace(1);
    }

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
    void x1PaceApprovedByGleb() {
        // the average persona (~3 h a day): 40 in about three weeks, 61 in 2-3 months, 76 in half a year or more
        final double d40 = PaperLevel.days(1, 40, PaperLevel.DEFAULT_HOURS);
        final double d61 = PaperLevel.days(1, 61, PaperLevel.DEFAULT_HOURS);
        final double d76 = PaperLevel.days(1, 76, PaperLevel.DEFAULT_HOURS);
        assertTrue((d40 >= 18) && (d40 <= 25), "40 after " + d40 + " days");
        assertTrue((d61 >= 60) && (d61 <= 90), "61 after " + d61 + " days");
        assertTrue(d76 >= 180, "76 after " + d76 + " days");
        final int at3w = PaperLevel.at(1, 0, 1, 21 * DAY, 3);
        assertTrue((at3w >= 38) && (at3w <= 42), "three weeks: " + at3w);
    }

    @Test
    void moreHoursMoreLevelsAndGlobalPace() {
        final int evening = PaperLevel.at(1, 0, 1, 30 * DAY, Chronotype.EVENING.hoursPerDay());
        final int allday = PaperLevel.at(1, 0, 1, 30 * DAY, Chronotype.ALLDAY.hoursPerDay());
        final int morning = PaperLevel.at(1, 0, 1, 30 * DAY, Chronotype.MORNING.hoursPerDay());
        assertTrue((allday > evening) && (evening > morning), allday + " / " + evening + " / " + morning);
        PaperLevel.setGlobalPace(2);
        assertTrue(PaperLevel.at(1, 0, 1, 30 * DAY, 3) > PaperLevel.at(1, 0, 1, 15 * DAY, 3) - 1);
        assertTrue(PaperLevel.at(1, 0, 1, 10 * DAY, 3) > 35);
    }

    @Test
    void chronotypeHours() {
        assertEquals(1.0, Chronotype.EVENING.activity(21, DayOfWeek.TUESDAY));
        assertTrue(Chronotype.EVENING.activity(10, DayOfWeek.TUESDAY) < 0.1);
        assertEquals(1.0, Chronotype.NIGHT_OWL.activity(2, DayOfWeek.MONDAY));
        assertTrue(Chronotype.NIGHT_OWL.activity(15, DayOfWeek.MONDAY) < 0.1);
        assertEquals(1.0, Chronotype.WEEKEND.activity(14, DayOfWeek.SUNDAY));
        assertTrue(Chronotype.WEEKEND.activity(14, DayOfWeek.WEDNESDAY) < 0.1);
        assertEquals(1.0, Chronotype.MORNING.activity(7, DayOfWeek.FRIDAY));
        for (Chronotype c : Chronotype.values()) {
            for (int h = 0; h < 24; h++) {
                final double a = c.activity(h, DayOfWeek.MONDAY);
                assertTrue((a >= 0) && (a <= 1));
            }
        }
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
