package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Memory;

/** Phase 7a: fight / flee / call-help / none. */
class PvpDecisionTest {
    private static PvpDecision.Situation base(boolean initiative) {
        return new PvpDecision.Situation(true, true, initiative, false, false, false, true, true, true, true, PvpDecision.Mode.NORMAL, 40, 40, false,
            false, 1, 1, 90, 80, 0.5, false, false);
    }

    @Test
    void peaceZoneNever() {
        final PvpDecision.Situation s = new PvpDecision.Situation(false, true, true, false, false, false, true, true, true, true, PvpDecision.Mode.HIGH, 50, 30,
            false, false, 3, 0, 100, 100, 1.0, false, false);
        assertEquals(PvpDecision.Intent.NONE, PvpDecision.decide(s).intent());
        assertEquals("peace", PvpDecision.decide(s).why());
        final PvpDecision.Situation t = new PvpDecision.Situation(true, false, true, true, false, false, true, true, true, true, PvpDecision.Mode.HIGH, 50, 30,
            false, false, 1, 0, 100, 100, 1.0, false, false);
        assertEquals(PvpDecision.Intent.NONE, PvpDecision.decide(t).intent());
    }

    @Test
    void offBlocksInitiativeButNotRevengeOrProtect() {
        final PvpDecision.Situation init = new PvpDecision.Situation(true, true, true, false, false, false, true, true, true, true, PvpDecision.Mode.OFF, 45, 40,
            false, false, 2, 0, 90, 80, 0.9, false, false);
        assertEquals(PvpDecision.Intent.NONE, PvpDecision.decide(init).intent());
        assertEquals("off", PvpDecision.decide(init).why());

        final PvpDecision.Situation revenge = new PvpDecision.Situation(true, true, true, false, true, false, true, true, true, true, PvpDecision.Mode.OFF, 40, 40,
            false, false, 1, 1, 80, 80, 0.5, false, false);
        assertEquals(PvpDecision.Intent.FIGHT, PvpDecision.decide(revenge).intent());
        assertEquals("revenge", PvpDecision.decide(revenge).why());

        final PvpDecision.Situation protect = new PvpDecision.Situation(true, true, true, false, false, true, true, true, true, true, PvpDecision.Mode.OFF, 40, 40,
            false, false, 1, 1, 80, 80, 0.5, false, false);
        assertEquals(PvpDecision.Intent.FIGHT, PvpDecision.decide(protect).intent());
        assertEquals("protect", PvpDecision.decide(protect).why());
    }

    @Test
    void cooldownAndUnder20() {
        final PvpDecision.Situation cd = new PvpDecision.Situation(true, true, true, false, false, false, true, true, true, true, PvpDecision.Mode.HIGH, 50, 40,
            false, false, 2, 0, 100, 100, 1.0, true, false);
        assertEquals(PvpDecision.Intent.NONE, PvpDecision.decide(cd).intent());
        assertEquals("cooldown", PvpDecision.decide(cd).why());

        final PvpDecision.Situation low = new PvpDecision.Situation(true, true, true, false, false, false, true, true, true, true, PvpDecision.Mode.HIGH, 40, 15,
            false, false, 2, 0, 100, 100, 1.0, false, true);
        assertEquals(PvpDecision.Intent.NONE, PvpDecision.decide(low).intent());
        assertEquals("under20", PvpDecision.decide(low).why());
    }

    @Test
    void fightFleeCallHelp() {
        assertEquals(PvpDecision.Intent.FIGHT, PvpDecision.decide(withAttacked(base(false))).intent());

        final PvpDecision.Situation fleeHp = new PvpDecision.Situation(true, true, false, true, false, false, true, true, true, true, PvpDecision.Mode.NORMAL, 40, 40,
            false, false, 1, 0, 20, 80, 0.5, false, false);
        assertEquals(PvpDecision.Intent.FLEE, PvpDecision.decide(fleeHp).intent());

        final PvpDecision.Situation out = new PvpDecision.Situation(true, true, false, true, false, false, true, true, true, true, PvpDecision.Mode.NORMAL, 40, 40,
            false, false, 3, 0, 70, 80, 0.4, false, false);
        assertEquals(PvpDecision.Intent.CALL_HELP, PvpDecision.decide(out).intent());

        final PvpDecision.Situation edge = new PvpDecision.Situation(true, true, true, false, false, false, true, true, true, true, PvpDecision.Mode.NORMAL, 48, 40,
            false, false, 1, 1, 90, 80, 0.7, false, false);
        assertEquals(PvpDecision.Intent.FIGHT, PvpDecision.decide(edge).intent());
        assertEquals("initiative", PvpDecision.decide(edge).why());
    }

    @Test
    void grudgeTimeout() {
        final long now = 100L * 60_000; // 100 minutes on the clock
        final Memory.Of fresh = new Memory.Of(7, -20, 1, 0, now - 60_000, Set.of(Memory.Tag.KILLED_ME));
        assertTrue(PvpDecision.grudgeActive(fresh, now, 30 * 60_000L));
        final Memory.Of old = new Memory.Of(7, -20, 1, 0, now - (40L * 60_000), Set.of(Memory.Tag.ATTACKED_ME));
        assertFalse(PvpDecision.grudgeActive(old, now, 30 * 60_000L));
        assertTrue(PvpDecision.grudgeActive(old, now, 0));
        assertFalse(PvpDecision.grudgeActive(Memory.Of.none(7), now, 30 * 60_000L));
    }

    @Test
    void revengeNotInstantThenReadyThenTimeout() {
        final long now = 100L * 60_000;
        final long delay = 2 * 60_000L;
        final long timeout = 30 * 60_000L;
        final Memory.Of just = new Memory.Of(7, -20, 1, 0, now - 30_000, Set.of(Memory.Tag.KILLED_ME));
        assertTrue(PvpDecision.grudgeActive(just, now, timeout));
        assertFalse(PvpDecision.revengeReady(just, now, delay, timeout));
        final Memory.Of ready = new Memory.Of(7, -20, 1, 0, now - (3 * 60_000L), Set.of(Memory.Tag.ATTACKED_ME));
        assertTrue(PvpDecision.revengeReady(ready, now, delay, timeout));
        final Memory.Of expired = new Memory.Of(7, -20, 1, 0, now - (40L * 60_000), Set.of(Memory.Tag.KILLED_ME));
        assertFalse(PvpDecision.revengeReady(expired, now, delay, timeout));
        // no lastSeen: not instant when a delay is set
        final Memory.Of undated = new Memory.Of(7, -20, 1, 0, 0, Set.of(Memory.Tag.KILLED_ME));
        assertFalse(PvpDecision.revengeReady(undated, now, delay, timeout));
        assertTrue(PvpDecision.revengeReady(undated, now, 0, timeout));
    }

    @Test
    void noEdgeBlocksInitiative() {
        final PvpDecision.Situation weak = new PvpDecision.Situation(true, true, true, false, false, false, true, true, true, true,
            PvpDecision.Mode.NORMAL, 30, 40, false, false, 1, 3, 90, 80, 0.5, false, false);
        assertEquals(PvpDecision.Intent.NONE, PvpDecision.decide(weak).intent());
        assertEquals("no edge", PvpDecision.decide(weak).why());
    }

    /** Tiny helper: same situation but attacked. */
    private static PvpDecision.Situation withAttacked(PvpDecision.Situation s) {
        return new PvpDecision.Situation(s.inPvpZone(), s.targetInPvpZone(), false, true, s.revengeActive(), s.allyUnderAttack(), s.targetLive(),
            s.protectPartyOn(), s.protectClanOn(), s.revengeOn(), s.mode(), s.myLevel(), s.theirLevel(), s.meMage(), s.themMage(), s.enemiesNear(),
            s.alliesNear(), s.hpPct(), s.mpPct(), s.aggression(), s.cooldownBlocks(), s.targetUnder20());
    }
}
