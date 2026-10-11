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
    void townErrandsGroupsNoSitting() {
        final FakeWorld w = BasicBrainTest.world(6, false);
        w.addPlayer(new PlayerView(901, "Other", 20, 0, new Pos(-200, -200, 0), 300, 0, false, false, 0, true));
        final BrainHarness h = BasicBrainTest.harness(w, false, 6);
        h.setAssignment(town(1));
        h.run(30 * MIN);
        assertEquals(0, h.act().count("attack"), "no fights in town");
        assertTrue(h.act().countOk("moveTo") > 5, "runs around: " + h.act().countOk("moveTo"));
        assertTrue(h.states().stream().anyMatch(s -> s.contains("/at gk") || s.contains("/at shop") || s.contains("/at wh")), "no errands: " + h.states());
        assertEquals(0, h.act().count("sit"), "sat in town");
        assertTrue(w.pos().distance2d(BasicBrainTest.TOWN) < 1500, "left the town: " + w.pos());
        // logouts here are teleports at the gatekeeper (transit / errand to the gatekeeper)
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

    @Test
    void transitStandsAMomentAtTheGatekeeperAndTeleportsOn() {
        final FakeWorld w = BasicBrainTest.world(8, false);
        final BrainHarness h = BasicBrainTest.harness(w, false, 8);
        final Pos gk = new Pos(300, 0, 0);
        h.setAssignment(new Assignment(1, Assignment.Kind.TRANSIT, "test", w.pos(), 400, 1, 10, List.of(), town(1).anchors(), gk));
        h.run(2500);
        assertEquals(0, h.act().countOk("logout"), "teleported on at once");
        h.run(10_000);
        assertTrue(h.act().countOk("logout") >= 1, "never teleported on: " + h.notes());
        assertEquals(0, h.act().count("sit"));
        assertEquals(0, h.act().count("attack"));
    }

    @Test
    void passerByRunsToTheOtherSide() {
        final FakeWorld w = BasicBrainTest.world(9, false);
        final BrainHarness h = BasicBrainTest.harness(w, false, 9);
        final Pos exit = new Pos(w.pos().x() + 2500, w.pos().y(), 0);
        h.setAssignment(new Assignment(1, Assignment.Kind.PASS, "road", w.pos(), 0, 1, 10, List.of(), List.of(), exit));
        h.run(2 * MIN);
        assertTrue(w.pos().distance2d(exit) <= 300, "did not run past: " + w.pos());
        assertEquals(0, h.act().count("sit"));
    }

    static PlayerView pk(Pos at) {
        return new PlayerView(950, "Pk", 6, 0, at, 0, 0, true, true, FakeWorld.SELF_ID, false);
    }

    @Test
    void fightBackChanceDependsOnLevelAndTraits() {
        final double even = BasicBrain.fightBackChance(0.5, 20, 20, 0.5, 0.5, 100);
        assertEquals(0.5, even, 1e-9);
        assertTrue(BasicBrain.fightBackChance(0.5, 30, 20, 0.5, 0.5, 100) > even, "stronger bot");
        assertTrue(BasicBrain.fightBackChance(0.5, 10, 20, 0.5, 0.5, 100) < even, "weaker bot");
        assertTrue(BasicBrain.fightBackChance(0.5, 20, 20, 0.9, 0.5, 100) > even, "aggressive persona");
        assertTrue(BasicBrain.fightBackChance(0.5, 20, 20, 0.5, 0.5, 20) < even, "already hurt");
        assertEquals(0, BasicBrain.fightBackChance(0, 80, 1, 1, 0, 100));
        assertEquals(1, BasicBrain.fightBackChance(1, 1, 80, 0, 1, 10));
    }

    private static BrainHarness attackedOnTheGround(FakeWorld w, long seed) {
        w.setLevel(5);
        final BrainHarness h = BasicBrainTest.harness(w, false, seed);
        h.setAssignment(hunt(1));
        h.run(4 * MIN);
        w.addPlayer(pk(new Pos(w.pos().x() + 200, w.pos().y(), 0)));
        h.run(20_000);
        return h;
    }

    @Test
    void fightsBackARealPlayer() {
        final double old = BasicBrain.fightBackBase;
        try {
            BasicBrain.fightBackBase = 1;
            final BrainHarness h = attackedOnTheGround(BasicBrainTest.world(10, false), 10);
            assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("950")), "did not fight back: " + h.notes());
            assertEquals(0, h.act().count("returnToTown"));
        } finally {
            BasicBrain.fightBackBase = old;
        }
    }

    @Test
    void runsFromARealPlayerAndEscapes() {
        final double old = BasicBrain.fightBackBase;
        try {
            BasicBrain.fightBackBase = 0;
            final BrainHarness h = attackedOnTheGround(BasicBrainTest.world(11, false), 11);
            assertTrue(h.act().count("returnToTown") >= 1, "no scroll of escape: " + h.notes());
            assertTrue(h.act().calls().stream().noneMatch(c -> c.action().equals("attack") && c.arg().equals("950")), "fought back");
        } finally {
            BasicBrain.fightBackBase = old;
        }
    }

    /** A real player without a PvP flag yet (as in КТ5) who keeps hitting the bot every 2 s down to {@code hpShare}. */
    private static void beatenBy(FakeWorld w, BrainHarness h, PlayerView p, double hpShare, long forMs) {
        final double hp = w.maxHp() * hpShare;
        for (long t = 0; t < forMs; t += 2000) {
            w.event(new org.l2start.bots.api.BotEvent.Attacked(p.objectId(), true, 50));
            w.setHp(hp); // held there (monsters around may hit too)
            h.run(2000);
        }
    }

    private static long sitsAfter(BrainHarness h, int from) {
        return h.act().calls().subList(from, h.act().calls().size()).stream().filter(c -> c.action().equals("sit")).count();
    }

    @Test
    void fightsAPlayerToTheEndWithoutSittingDown() {
        final double old = BasicBrain.fightBackBase;
        try {
            BasicBrain.fightBackBase = 1;
            final FakeWorld w = BasicBrainTest.world(12, false);
            w.setLevel(5);
            final BrainHarness h = BasicBrainTest.harness(w, false, 12);
            h.setAssignment(hunt(1));
            h.run(4 * MIN);
            final PlayerView pk = new PlayerView(951, "NoFlag", 6, 0, new Pos(w.pos().x() + 150, w.pos().y(), 0), 0, 0, false, false, 0, false);
            w.addPlayer(pk);
            final int from = h.act().calls().size();
            beatenBy(w, h, pk, 0.35, 60_000); // hurt (35 %), where a hunter would rest
            assertEquals(0, sitsAfter(h, from), "sat down in a fight with a player: " + h.notes());
            final long hits = h.act().calls().subList(from, h.act().calls().size()).stream().filter(c -> c.action().equals("attack") && c.arg().equals("951")).count();
            assertTrue(hits >= 3, "stopped hitting the player: " + hits + " " + h.notes());
            assertEquals(0, h.act().count("returnToTown"), "ran although it fights and is not badly hurt");
        } finally {
            BasicBrain.fightBackBase = old;
        }
    }

    @Test
    void badlyHurtInAPlayerFightItRunsInsteadOfSitting() {
        final double old = BasicBrain.fightBackBase;
        try {
            BasicBrain.fightBackBase = 1;
            final FakeWorld w = BasicBrainTest.world(13, false);
            w.setLevel(5);
            final BrainHarness h = BasicBrainTest.harness(w, false, 13);
            h.setAssignment(hunt(1));
            h.run(4 * MIN);
            final PlayerView pk = new PlayerView(952, "Pk", 6, 0, new Pos(w.pos().x() + 150, w.pos().y(), 0), 0, 0, true, true, FakeWorld.SELF_ID, false);
            w.addPlayer(pk);
            final int from = h.act().calls().size();
            beatenBy(w, h, pk, 0.6, 6000);
            beatenBy(w, h, pk, 0.15, 12_000);
            assertEquals(0, sitsAfter(h, from), "sat down in a fight with a player: " + h.notes());
            assertTrue(h.act().count("returnToTown") >= 1, "no scroll of escape when badly hurt: " + h.notes());
        } finally {
            BasicBrain.fightBackBase = old;
        }
    }
}
