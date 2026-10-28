package org.l2start.modules.worldinfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LevelRangeTest {
    @Test
    void percentilesDropOutliers() {
        final int[] lv = { 1, 40, 41, 42, 43, 44, 45, 46, 47, 80 };
        assertEquals("40-47", LevelRange.of(lv, 5, 10, 90));
        assertEquals("1-80", LevelRange.of(lv, 5, 0, 100));
    }

    @Test
    void tooFewOrSame() {
        assertNull(LevelRange.of(new int[] { 20, 21 }, 5, 10, 90));
        assertEquals("33", LevelRange.of(new int[] { 33, 33, 33, 33, 33 }, 5, 10, 90));
    }

    @Test
    void label() {
        assertEquals("Cruma Tower (40-47)", LevelRange.label("Cruma Tower", "40-47"));
        assertEquals("Giran", LevelRange.label("Giran", null));
        assertEquals("Giran", LevelRange.label("Giran", ""));
    }
}
