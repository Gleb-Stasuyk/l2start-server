package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Phase 7g: CH assign helpers (pure). */
class ClanHallAssignTest {
    @Test
    void needForTwoPeriodsSumsLeaseAndFees() {
        assertEquals(0, ClanHallAssign.needForTwoPeriods(0, List.of()));
        assertEquals(2000, ClanHallAssign.needForTwoPeriods(1000, List.of()));
        assertEquals(2600, ClanHallAssign.needForTwoPeriods(1000, List.of(200, 100)));
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
        assertEquals(300, ClanHallAssign.MAX_HP_PCT);
        assertEquals(40, ClanHallAssign.MAX_MP_PCT);
        assertEquals(50, ClanHallAssign.MAX_EXP_PCT);
    }
}
