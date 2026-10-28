package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Live test 2026-10-22, next pass: party catch-up route, no LFP below 40, the AFK crowd at the gatekeeper. */
class StarterPassTest {
    @Test
    void partyBotRunsNearAndAppearsNearHimWhenFar() {
        assertEquals(BasicBrain.ROUTE_RUN, BasicBrain.catchUpRoute(4000, true));
        assertEquals(BasicBrain.ROUTE_TELEPORT, BasicBrain.catchUpRoute(20_000, true));
        assertEquals(BasicBrain.ROUTE_RUN, BasicBrain.catchUpRoute(20_000, false), "seen / no hidden spot: runs, tries later");
    }

    @Test
    void noLookingForPartyBelowForty() {
        assertFalse(BasicBrain.lfpAllowed(39));
        assertFalse(BasicBrain.lfpAllowed(11));
        assertTrue(BasicBrain.lfpAllowed(40));
    }

    @Test
    void gatekeeperAfkLastsTwentyMinutesToTwoHours() {
        assertEquals(20 * 60_000L, BasicBrain.gkAfkStay(0));
        assertEquals(120 * 60_000L, BasicBrain.gkAfkStay(1));
        assertTrue(BasicBrain.gkAfkStay(0.5) < org.l2start.bots.runtime.IdleWatch.AFK_TOWN_MAX_MS);
    }
}
