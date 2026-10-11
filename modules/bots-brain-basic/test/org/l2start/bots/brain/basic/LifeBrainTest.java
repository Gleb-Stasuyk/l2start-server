package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 4d: the end of a hunt, errands in town, AFK. */
class LifeBrainTest {
    static final long MIN = 60_000;

    @AfterEach
    void reset() {
        BasicBrain.afkShare = 0.15;
    }

    private static BrainHarness hunting(FakeWorld w, long seed) {
        w.setLevel(5);
        final BrainHarness h = BasicBrainTest.harness(w, false, seed);
        h.setAssignment(PresenceBrainTest.hunt(1));
        h.run(5 * MIN);
        return h;
    }

    @Test
    void readsAScrollOfEscapeAfterTheFightAndLandsInTown() {
        BasicBrain.afkShare = 0;
        final FakeWorld w = BasicBrainTest.world(21, false);
        w.giveItem(FakeWorld.SOE, 2);
        final BrainHarness h = hunting(w, 21);
        h.setAssignment(PresenceBrainTest.hunt(1).leave(2, Assignment.Kind.LEAVE_SOE, null));
        h.run(90_000);
        assertEquals(1, w.soeUsed, "scrolls read: " + w.soeUsed + " " + h.notes());
        assertEquals(0, h.act().count("returnToTown"), "used the plain return instead of the scroll");
        assertTrue(w.pos().distance2d(BasicBrainTest.TOWN) < 100, "not in town: " + w.pos());
        assertTrue(h.notes().stream().anyMatch(s -> s.contains("reads a scroll of escape")), h.notes().toString());
    }

    @Test
    void withoutAScrollItGoesBackAnyway() {
        BasicBrain.afkShare = 0;
        final FakeWorld w = BasicBrainTest.world(22, false);
        final BrainHarness h = hunting(w, 22);
        h.setAssignment(PresenceBrainTest.hunt(1).leave(2, Assignment.Kind.LEAVE_SOE, null));
        h.run(90_000);
        assertTrue(h.act().count("returnToTown") >= 1, h.notes().toString());
        assertTrue(w.pos().distance2d(BasicBrainTest.TOWN) < 100, "not in town: " + w.pos());
    }

    @Test
    void logsOutOnTheSpotSitting() {
        BasicBrain.afkShare = 0;
        final FakeWorld w = BasicBrainTest.world(23, false);
        final BrainHarness h = hunting(w, 23);
        h.setAssignment(PresenceBrainTest.hunt(1).leave(2, Assignment.Kind.LEAVE_SPOT, w.pos()));
        h.run(2 * MIN);
        assertTrue(w.logouts >= 1, "no logout: " + h.notes());
        assertTrue(h.act().countOk("sit") >= 1, "did not sit down first");
        assertTrue(w.sitting(), "logged out standing");
    }

    static Assignment errands(long seq) {
        return new Assignment(seq, Assignment.Kind.ERRANDS, "test", BasicBrainTest.TOWN, 500, 1, 10, List.of(),
            List.of(new Assignment.Anchor("gk", new Pos(300, 0, 0)), new Assignment.Anchor("shop", new Pos(-300, 200, 0)), new Assignment.Anchor("wh", new Pos(0, -350, 0)),
                new Assignment.Anchor("trainer", new Pos(250, 300, 0)), new Assignment.Anchor("restart", new Pos(0, 0, 0))),
            new Pos(300, 0, 0));
    }

    @Test
    void errandsInTownThenTheGatekeeper() {
        BasicBrain.afkShare = 0;
        final FakeWorld w = BasicBrainTest.world(24, false);
        final BrainHarness h = BasicBrainTest.harness(w, false, 24);
        h.setAssignment(errands(1));
        h.run(4 * MIN);
        final long visits = h.states().stream().filter(s -> s.endsWith("/to shop") || s.endsWith("/to wh") || s.endsWith("/to trainer")).distinct().count();
        assertTrue(visits >= 2, "NPCs visited: " + h.states());
        assertTrue(w.logouts >= 1, "never teleported at the gatekeeper: " + h.notes());
        assertTrue(w.pos().distance2d(new Pos(300, 0, 0)) < 200, "logged out away from the gatekeeper: " + w.pos());
        assertEquals(0, h.act().count("sit"), "sat in town");
        assertEquals(0, h.act().count("attack"));
    }

    @Test
    void goesAfkOnTheGroundStandingStill() {
        BasicBrain.afkShare = 1;
        final FakeWorld w = BasicBrainTest.world(25, false);
        final BrainHarness h = hunting(w, 25);
        h.run(45 * MIN);
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("afk for")), h.notes().toString());
    }
}
