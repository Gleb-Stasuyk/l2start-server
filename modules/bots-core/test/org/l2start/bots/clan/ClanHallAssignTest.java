package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Phase 7g+: CH assign helpers (pure). */
class ClanHallAssignTest {
    private static final long DAY = 86_400_000L;
    private static final long WEEK = ClanHallAssign.HALL_PERIOD_MS;

    @Test
    void needForTwoPeriodsScalesDailyFees() {
        assertEquals(0, ClanHallAssign.needForTwoPeriods(0, List.of()));
        assertEquals(2000, ClanHallAssign.needForTwoPeriods(1000, List.of()));
        // 2 weeks of hall + 14× daily support fee
        final long need = ClanHallAssign.needForTwoPeriods(1000, List.of(new ClanHallAssign.FeePeriod(100, DAY)));
        assertEquals(2000 + (100L * 14), need);
        // weekly tele fee → ×2
        assertEquals(2000 + 200L, ClanHallAssign.needForTwoPeriods(1000, List.of(new ClanHallAssign.FeePeriod(100, WEEK))));
    }

    @Test
    void shoutLineHasLevelHallAndRewards() {
        final String s = ClanHallAssign.shoutLine("NightHawk", 8, "Гиран", 50_000, 150_000);
        assertTrue(s.contains("NightHawk"));
        assertTrue(s.contains("8 лвл"));
        assertTrue(s.contains("КХ в Гиран"));
        assertTrue(s.contains("бафы"));
        assertTrue(s.contains("50к"));
        assertTrue(s.contains("150к"));
    }

    @Test
    void maxFunctionLevelsMatchStockCaps() {
        assertEquals(8, ClanHallAssign.MAX_SUPPORT);
        assertEquals(2, ClanHallAssign.MAX_TELEPORT);
        assertTrue(ClanHallAssign.isMaxConfigured(8, 2, 300, 40, 50));
        assertFalse(ClanHallAssign.isMaxConfigured(7, 2, 300, 40, 50));
    }

    @Test
    void needCoversOneMillionLeaseTwoPeriods() {
        // Giran/Aden/Goddard lease ≈ 1kk → message "$s1=1000000 tomorrow"
        assertEquals(2_000_000L, ClanHallAssign.needForTwoPeriods(1_000_000, List.of()));
        final long withDaily = ClanHallAssign.needForTwoPeriods(1_000_000, List.of(new ClanHallAssign.FeePeriod(50_000, DAY)));
        assertEquals(2_000_000L + (50_000L * 14), withDaily);
    }
}
