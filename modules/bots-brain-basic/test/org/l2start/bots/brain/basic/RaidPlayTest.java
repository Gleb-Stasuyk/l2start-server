package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.PartyMember;
import org.l2start.bots.api.Pos;

/** RB party, iteration 1: the pure rules (chat words, the tank's place, timings). */
class RaidPlayTest {
    @Test
    void goAndBuffWords() {
        assertTrue(RaidPlay.isGo("го"));
        assertTrue(RaidPlay.isGo("гооо!"));
        assertTrue(RaidPlay.isGo("go"));
        assertTrue(RaidPlay.isGo("погнали"));
        assertTrue(RaidPlay.isGo("ну го"));
        assertFalse(RaidPlay.isGo("го за мной"), "following, not fighting");
        assertFalse(RaidPlay.isGo("стоп"));
        assertFalse(RaidPlay.isGo("гогогог как дела"));
        assertFalse(RaidPlay.isGo("ого"));
        assertTrue(RaidPlay.wantsBuff("баф"));
        assertTrue(RaidPlay.wantsBuff("бафни пж"));
        assertTrue(RaidPlay.wantsBuff("го баф"));
        assertTrue(RaidPlay.wantsBuff("buff pls"));
        assertTrue(RaidPlay.wantsBuff("бф"));
        assertFalse(RaidPlay.wantsBuff("го"));
    }

    @Test
    void theTankTurnsTheBossAwayFromTheParty() {
        final Pos boss = new Pos(0, 0, 0);
        final Pos party = new Pos(-600, 0, 0);
        assertTrue(RaidPlay.bossFacesParty(boss, new Pos(-80, 0, 0), party), "tank between boss and party: the boss faces them");
        assertTrue(RaidPlay.bossFacesParty(boss, new Pos(0, 80, 0), party), "90 degrees: still turned to them");
        assertFalse(RaidPlay.bossFacesParty(boss, new Pos(80, 0, 0), party), "tank beyond the boss: its back to the party");
        final Pos spot = RaidPlay.tankSpot(boss, party, 110);
        assertEquals(110, spot.x());
        assertEquals(0, spot.y());
    }

    @Test
    void pulledAndTimings() {
        final List<PartyMember> party = List.of(new PartyMember(7, "Tank", 5, 40, new Pos(0, 0, 0), 0, 100, 100, false, 0, false));
        assertFalse(RaidPlay.bossPulled(100, false, 0, 5000, party, 1));
        assertTrue(RaidPlay.bossPulled(99, false, 0, 5000, party, 1));
        assertTrue(RaidPlay.bossPulled(100, false, 5000, 5000, party, 1));
        assertTrue(RaidPlay.bossPulled(100, false, 7, 5000, party, 1));
        final Random r = new Random(1);
        for (int i = 0; i < 100; i++) {
            final long d = RaidPlay.gkDelay(r);
            assertTrue((d >= 2000) && (d <= 10_000), "gk delay " + d);
        }
        assertEquals(3 * 60_000L, RaidPlay.INVITE_WAIT_MS);
        assertEquals(10 * 60_000L, RaidPlay.IDLE_MS);
    }

    @Test
    void kiteCandidatesCoverSeveralAnglesAndRadii() {
        final Pos center = new Pos(0, 0, 0);
        final Pos at = new Pos(300, 0, 0);
        final List<Pos> c = RaidPlay.kiteCandidates(center, at, 1);
        assertTrue(c.size() >= 8, "too few kite spots: " + c.size());
        final long distinct = c.stream().map(p -> p.x() + "," + p.y()).distinct().count();
        assertTrue(distinct >= 8, "duplicate kite spots: " + distinct);
        final Pos first = RaidPlay.circleStep(center, at, RaidPlay.kiteRadius(center, at), RaidPlay.KITE_STEP_DEG, 1);
        assertEquals(first, c.get(0), "preferred step should come first");
        assertTrue(RaidPlay.stepDir(center, at, first) == 1);
        assertTrue(RaidPlay.stepDir(center, at, RaidPlay.circleStep(center, at, 300, 40, -1)) == -1);
    }
}
