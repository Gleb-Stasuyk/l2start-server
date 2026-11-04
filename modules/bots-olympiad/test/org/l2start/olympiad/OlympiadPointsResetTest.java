package org.l2start.olympiad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Daily reset of bot nobles' olympiad points shortly before the competition start (Gleb 2026-11-04). */
class OlympiadPointsResetTest {
    static final long MIN = 60_000L;
    static final long START = 1_793_804_400_000L; // some 18:00
    static final long LEAD = 10 * MIN;

    @Test
    void dueOnlyInsideLeadBeforeStart() {
        assertFalse(OlympiadRules.pointsResetDue(true, START - (11 * MIN), START, LEAD, 0), "too early");
        assertTrue(OlympiadRules.pointsResetDue(true, START - (10 * MIN), START, LEAD, 0));
        assertTrue(OlympiadRules.pointsResetDue(true, START - MIN, START, LEAD, 0));
        assertFalse(OlympiadRules.pointsResetDue(true, START, START, LEAD, 0), "window already open");
        assertFalse(OlympiadRules.pointsResetDue(true, START + MIN, START, LEAD, 0), "during competition");
    }

    @Test
    void oncePerStartAndOffWhenDisabledOrUnknown() {
        assertFalse(OlympiadRules.pointsResetDue(true, START - MIN, START, LEAD, START), "already done for this start");
        assertTrue(OlympiadRules.pointsResetDue(true, START + (24 * 60 * MIN) - MIN, START + (24 * 60 * MIN), LEAD, START), "next day again");
        assertFalse(OlympiadRules.pointsResetDue(false, START - MIN, START, LEAD, 0), "DailyPointsReset=false");
        assertFalse(OlympiadRules.pointsResetDue(true, START - MIN, -1, LEAD, 0), "start not scheduled");
    }

    @Test
    void setsExactlyTargetLowerOrHigher() {
        assertEquals(18, OlympiadRules.resetPoints(4, 18), "burnt by draws: raised");
        assertEquals(18, OlympiadRules.resetPoints(40, 18), "winner: lowered");
        assertEquals(18, OlympiadRules.resetPoints(18, 18));
        assertEquals(0, OlympiadRules.resetPoints(5, -3));
    }
}
