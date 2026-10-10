package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;
import org.l2start.bots.testkit.RecordingActuators;

/** Step 3e: the brain follows the presence director's orders. */
class PresenceBrainTest {
    static final long MIN = 60_000;
    static final Pos MID = new Pos(0, 4000, 0);

    static Assignment hunt(long seq) {
        return new Assignment(seq, Assignment.Kind.HUNT, "mid", MID, 900, 4, 7, List.of(new Pos(-500, 3600, 0), new Pos(500, 4400, 0)), List.of(), null);
    }

    static Assignment town(long seq) {
        return new Assignment(seq, Assignment.Kind.TOWN, "test", BasicBrainTest.TOWN, 500, 1, 10, List.of(),
            List.of(new Assignment.Anchor("gk", new Pos(300, 0, 0)), new Assignment.Anchor("shop", new Pos(-300, 200, 0)), new Assignment.Anchor("wh", new Pos(0, -350, 0)),
                new Assignment.Anchor("restart", new Pos(0, 0, 0))), null);
    }

    static PlayerView real(int id, Pos at) {
        return new PlayerView(id, "Human" + id, 6, 0, at, 0, 0, false, true, 0, false);
    }

    @Test
    void huntsOnTheOrderedGroundAndLeavesRealPlayersMonstersAlone() {
        final FakeWorld w = BasicBrainTest.world(5, false);
        w.setLevel(5);
        final Pos human = new Pos(300, 4300, 0);
        w.addPlayer(real(900, human));
        final BrainHarness h = BasicBrainTest.harness(w, false, 5);
        h.setAssignment(hunt(1));
        h.run(40 * MIN);
        assertTrue(w.kills > 5, "kills " + w.kills);
        assertTrue(h.states().stream().noneMatch(s -> s.startsWith("TOWN_IDLE")), h.states().toString());
        for (RecordingActuators.Call c : h.act().calls()) {
            if (c.action().equals("attack")) {
                final FakeWorld.Mob m = w.mobs().get(Integer.parseInt(c.arg()));
                assertTrue((m == null) || (m.spawn.distance2d(human) > 500), "attacked a mob next to a real player: " + c);
            }
        }
        assertTrue(w.pos().distance2d(MID) < 2500, "stayed on its ground: " + w.pos());
        assertEquals(0, w.stolenHits);
    }

    @Test
    void townErrandsGroupsAndSitting() {
        final FakeWorld w = BasicBrainTest.world(6, false);
        w.addPlayer(new PlayerView(901, "Other", 20, 0, new Pos(-200, -200, 0), 300, 0, false, false, 0, true));
        final BrainHarness h = BasicBrainTest.harness(w, false, 6);
        h.setAssignment(town(1));
        h.run(30 * MIN);
        assertEquals(0, h.act().count("attack"), "no fights in town");
        assertTrue(h.act().countOk("moveTo") > 5, "runs around: " + h.act().countOk("moveTo"));
        assertTrue(h.states().stream().anyMatch(s -> s.contains("/at gk") || s.contains("/at shop") || s.contains("/at wh")), "no errands: " + h.states());
        assertTrue(h.act().countOk("sit") > 0, "never sat");
        assertTrue(w.pos().distance2d(BasicBrainTest.TOWN) < 1500, "left the town: " + w.pos());
        assertEquals(0, h.act().count("logout"));
    }

    @Test
    void leavesByTheGatekeeper() {
        final FakeWorld w = BasicBrainTest.world(7, false);
        final BrainHarness h = BasicBrainTest.harness(w, false, 7);
        h.setAssignment(town(1));
        h.run(2 * MIN);
        h.setAssignment(town(1).leave(2, Assignment.Kind.LEAVE_GK, new Pos(300, 0, 0)));
        h.run(2 * MIN);
        assertTrue(h.act().countOk("logout") >= 1, "never logged out at the gatekeeper: " + h.notes());
        assertTrue(w.pos().distance2d(new Pos(300, 0, 0)) <= 160, "logged out away from the gatekeeper: " + w.pos());
    }

    @Test
    void leavingDropsTheFightAndRunsOff() {
        final FakeWorld w = BasicBrainTest.world(8, false);
        w.setLevel(5);
        final BrainHarness h = BasicBrainTest.harness(w, false, 8);
        h.setAssignment(hunt(1));
        h.run(10 * MIN);
        assertTrue(h.act().count("attack") > 0);
        final int before = h.act().calls().size();
        final Pos exit = new Pos(0, 9000, 0);
        h.setAssignment(hunt(1).leave(2, Assignment.Kind.LEAVE_RUN, exit));
        h.run(3 * MIN);
        final List<RecordingActuators.Call> after = h.act().calls().subList(before, h.act().calls().size());
        assertTrue(after.stream().anyMatch(c -> c.action().equals("stop")), "did not drop the fight");
        // self-defence aside, it does not start new fights while leaving
        assertTrue(after.stream().filter(c -> c.action().equals("attack")).count() <= 3, "kept fighting: " + after.stream().filter(c -> c.action().equals("attack")).count());
        assertTrue(w.pos().distance2d(exit) < 3000, "did not run off: " + w.pos());
    }
}
