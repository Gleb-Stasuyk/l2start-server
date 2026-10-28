package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Live test 2026-10-22 (Malvin): a PvP is a FIGHT for the idle watch; a fleeing bot never reads SoE in the player's sight. */
class PvpInSightTest {
    @Test
    void scrollOfEscapeOnlyOutOfSight() {
        assertFalse(BasicBrain.fleeEscapes(300), "player next to it: keeps running on foot");
        assertFalse(BasicBrain.fleeEscapes(BasicBrain.PVP_FLEE_UNSEEN));
        assertTrue(BasicBrain.fleeEscapes(BasicBrain.PVP_FLEE_UNSEEN + 1));
        assertTrue(BasicBrain.fleeEscapes(-1), "player not in view");
    }

    @Test
    void pvpProgressFromTheLastHit() {
        assertEquals(5000, BasicBrain.pvpProgress(1000, 5000L));
        assertEquals(1000, BasicBrain.pvpProgress(1000, null));
        assertEquals(9000, BasicBrain.pvpProgress(9000, 5000L));
    }
}
