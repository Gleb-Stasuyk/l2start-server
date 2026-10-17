package org.l2start.bots.clan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Phase 7f: CH auction share ~55%. */
class ClanHallBidsTest {
    @Test
    void pickTargetsAboutShare() {
        final List<Integer> open = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            open.add(i);
        }
        final List<Integer> picked = ClanHallBids.pickTargets(open, 42, 0.55);
        assertEquals(ClanHallBids.expectedCount(20, 0.55), picked.size());
        assertTrue(picked.size() >= 10 && picked.size() <= 12, "picked=" + picked.size());
        final Set<Integer> uniq = new HashSet<>(picked);
        assertEquals(picked.size(), uniq.size());
    }

    @Test
    void nextBidRaisesOrStarts() {
        assertEquals(1000, ClanHallBids.nextBid(1000, 0));
        assertEquals(1050, ClanHallBids.nextBid(1000, 1000));
        assertEquals(2100, ClanHallBids.nextBid(1000, 2000));
    }
}
