package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BudgetTest {
    @Test
    void farmingHourGrowsWithLevel() {
        assertEquals(22_000, Budget.farmPerHour(20));
        assertEquals(82_000, Budget.farmPerHour(40));
        assertTrue(Budget.farmPerHour(76) > Budget.farmPerHour(60));
        assertEquals(Budget.farmPerHour(80), Budget.farmPerHour(99));
    }

    @Test
    void aPlayerGetsAtMostThirtyPercentOfAnHourADay() {
        assertEquals(24_600, Budget.playerDay(40, 0.3));
        assertEquals(4_600, Budget.left(40, 0.3, 20_000));
        assertEquals(0, Budget.left(40, 0.3, 30_000));
        assertEquals(0, Budget.playerDay(40, 0));
    }

    @Test
    void huntersEarnOnPaperUpToACap() {
        final long start = Budget.hunterWallet(-1, 40, 0);
        assertEquals(164_000, start);
        assertEquals(start + Math.round(82_000 * 0.35 * 2), Budget.hunterWallet(start, 40, 2));
        assertEquals(Math.round(82_000 * 8.0), Budget.hunterWallet(start, 40, 1000));
        // a wallet above the cap (a level lost) is not cut
        assertEquals(10_000_000, Budget.hunterWallet(10_000_000, 40, 3));
    }

    @Test
    void thinWalletBuysFewer() {
        assertEquals(20, Budget.affordable(100_000, 300, 20));
        assertEquals(3, Budget.affordable(1_000, 300, 20));
        assertEquals(0, Budget.affordable(0, 300, 20));
        assertEquals(0, Budget.affordable(-5, 300, 20));
    }
}
