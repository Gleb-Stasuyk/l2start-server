package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 4b: own buffs while hunting, stances in a fight and off before resting. */
class BuffBrainTest {
    static final long MIN = 60_000;

    @AfterEach
    void defaults() {
        BasicBrain.selfBuffs = true;
    }

    static SurvivalBrainTest.Run hunt(boolean mage, long seed, long ms) {
        final FakeWorld w = SurvivalBrainTest.ground(seed, mage, 5, 8);
        w.selfBuffs = true;
        return SurvivalBrainTest.hunt(w, mage, seed, ms);
    }

    @Test
    void keepsItsBuffOnAndRenewsItOnlyWhenItRunsOut() {
        final SurvivalBrainTest.Run r = hunt(false, 41, 45 * MIN);
        // the buff lasts 20 minutes: cast on arrival at the ground, then about every 20 minutes (more after a death)
        assertTrue(r.w().buffCasts >= 2, "buff casts " + r.w().buffCasts + " " + r.h().notes());
        assertTrue(r.w().buffCasts <= (4 + (2 * r.w().deaths)), "buff casts " + r.w().buffCasts + ", deaths " + r.w().deaths);
        assertEquals(r.w().buffCasts, r.b().buffsCast());
        assertTrue(r.w().kills >= 3, "kills " + r.w().kills);
    }

    @Test
    void mageBuffsToo() {
        final SurvivalBrainTest.Run r = hunt(true, 42, 30 * MIN);
        assertTrue(r.w().buffCasts >= 1, r.h().notes().toString());
    }

    @Test
    void stanceOnInAFightOffBeforeSitting() {
        final FakeWorld w = SurvivalBrainTest.ground(43, false, 6, 8);
        w.selfBuffs = true;
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(w, false, 43, 40 * MIN);
        assertTrue(r.h().act().calls().stream().anyMatch(c -> c.action().equals("cast") && c.arg().startsWith(FakeWorld.TOGGLE_ID + "->") && c.result().ok()), "never switched the stance on");
        assertTrue(r.w().kills >= 3, "kills " + r.w().kills);
        assertTrue(w.toggleSittingMs <= 3000, "sat with the stance on for " + w.toggleSittingMs + " ms");
    }

    @Test
    void noBuffsWhenSwitchedOff() {
        BasicBrain.selfBuffs = false;
        final SurvivalBrainTest.Run r = hunt(false, 44, 25 * MIN);
        assertEquals(0, r.w().buffCasts);
        assertTrue(!r.w().toggleOn);
    }
}
