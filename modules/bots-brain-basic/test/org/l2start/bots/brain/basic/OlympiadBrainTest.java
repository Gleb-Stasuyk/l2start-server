package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Activity;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.brain.basic.Playstyles.Cond;
import org.l2start.bots.brain.basic.Playstyles.Use;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 9 D: olympiad stadium combat — fight in place, no potions, no flee, world AI unchanged outside. */
class OlympiadBrainTest {
    static final Pos STADIUM = new Pos(30000, 0, 0);

    @AfterEach
    void defaults() {
        BasicBrain.playstyles = Playstyles.EMPTY;
        BasicBrain.usePlaystyles = true;
        BasicBrain.potions = true;
        BasicBrain.potionLo = 40;
        BasicBrain.potionHi = 60;
        BasicBrain.fleeHp = 25;
    }

    static FakeWorld stadium(long seed, boolean mage) {
        final FakeWorld w = new FakeWorld(seed, BasicBrainTest.TOWN, mage);
        w.teleport(STADIUM);
        w.setLevel(78);
        w.classId = mage ? 94 : 88; // Archmage / Duelist
        w.setOlympiad(1, true);
        w.addPlayer(rival(w.pos()));
        return w;
    }

    static PlayerView rival(Pos from) {
        final Pos at = new Pos(from.x() + 250, from.y(), from.z());
        return new PlayerView(800, "Rival", 78, 89, at, 250, 0, false, true, 0, false, false, false, true, 0, 100, 0, true, 1);
    }

    static BrainHarness harness(FakeWorld w, boolean mage, long seed) {
        final BrainHarness h = BasicBrainTest.harness(w, mage, seed);
        h.setReturnAfterDeath(0);
        return h;
    }

    @Test
    void fightsTheRivalWithAttackWhenTheMatchHasStarted() {
        final FakeWorld w = stadium(201, false);
        final BrainHarness h = harness(w, false, 201);
        h.run(3000);
        assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("800")),
            "no attack: " + h.notes() + " " + h.act().calls());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("olympiad")), h.notes().toString());
        assertEquals(Activity.Kind.FIGHT, h.brain().activity().kind());
        assertTrue(h.brain().inCombat());
        assertEquals(0, h.act().count("sit"));
        assertEquals(0, h.act().count("returnToTown"));
        assertEquals(0, h.act().count("logout"));
    }

    @Test
    void waitsWithoutAttackingOrSittingBeforeTheMatchStarts() {
        final FakeWorld w = stadium(202, false);
        w.setOlympiad(1, false);
        final BrainHarness h = harness(w, false, 202);
        h.run(3000);
        assertEquals(0, h.act().count("attack"), "attacked during wait: " + h.notes());
        assertEquals(0, h.act().count("sit"));
        assertEquals(0, h.act().count("returnToTown"));
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("olympiad wait")), h.notes().toString());
    }

    @Test
    void doesNotDrinkPotionsOrFleeTheStadium() {
        final FakeWorld w = stadium(203, false);
        w.giveItem(1061, 20);
        w.giveItem(FakeWorld.SOE, 1);
        w.setHp(w.maxHp() * 0.2);
        final BrainHarness h = harness(w, false, 203);
        h.run(4000);
        assertEquals(0, w.potionsUsed, "drank a potion in olympiad");
        assertEquals(0, w.soeUsed, "read SoE in olympiad");
        assertEquals(0, h.act().count("returnToTown"));
        assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("800")),
            "gave up the fight: " + h.notes());
        assertFalse(w.sitting());
    }

    @Test
    void mageNukesTheRival() {
        final FakeWorld w = stadium(204, true);
        final BrainHarness h = harness(w, true, 204);
        h.run(3000);
        assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("cast") && c.arg().equals(FakeWorld.NUKE_ID + "->800"))
            || h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("800")),
            "mage neither nuked nor hit: " + h.notes() + " " + h.act().calls());
        assertEquals(0, w.potionsUsed);
        assertEquals(0, h.act().count("sit"));
    }

    @Test
    void playstylePvpRotationIsUsed() {
        final FakeWorld w = stadium(205, false);
        w.addSkill(new FakeWorld.Extra(3, SkillView.Kind.STRIKE, 5, 1500, 0, 0, 0));
        BasicBrain.playstyles = new Playstyles(List.of(new Playstyles.Playstyle("duelist", Set.of(88), null,
            List.of(new Playstyles.Entry(3, "Power Strike", Use.ROTATION, EnumSet.of(Cond.ALWAYS), 0, 0, 0, 0, 0, 0, 999, -1, 0)))));
        final BrainHarness h = harness(w, false, 205);
        h.run(4000);
        final BasicBrain b = (BasicBrain) h.brain();
        assertTrue(b.playstyleCasts() >= 1, "no playstyle cast: " + h.notes() + " last=" + b.playstyleLast());
        assertTrue(b.playstyleLast().contains("Power Strike") || h.notes().stream().anyMatch(n -> n.contains("Power Strike")),
            b.playstyleLast() + " " + h.notes());
    }

    @Test
    void afterTheMatchItStrollsWhereItIsAndDoesNotHunt() {
        final FakeWorld w = stadium(206, false);
        w.addMob(2, 78, new Pos(STADIUM.x() + 80, STADIUM.y(), 0));
        final BrainHarness h = harness(w, false, 206);
        h.run(1500);
        w.setOlympiad(-1, false);
        h.run(4000);
        assertTrue(h.states().stream().anyMatch(s -> s.startsWith("TOWN_IDLE")), "states " + h.states());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("far from the starting villages")), h.notes().toString());
        assertEquals(0, w.kills, "hunted after olympiad: kills " + w.kills + " " + h.notes());
        assertEquals(0, h.act().count("returnToTown"));
    }

    @Test
    void outsideAMatchFarStrollIsUnchanged() {
        final FakeWorld w = new FakeWorld(207, BasicBrainTest.TOWN, false);
        w.teleport(STADIUM);
        w.setLevel(78);
        w.setPeace(false);
        final BrainHarness h = harness(w, false, 207);
        h.run(3000);
        assertTrue(h.states().stream().anyMatch(s -> s.contains("far from the starting villages") || s.startsWith("TOWN_IDLE")),
            "states " + h.states() + " " + h.notes());
        assertTrue(h.notes().stream().noneMatch(n -> n.contains("olympiad")), h.notes().toString());
        assertEquals(0, h.act().count("attack"), "hunted or PvP'd far from villages: " + h.notes());
    }

    @Test
    void archerRunsFromSpawnIntoBowRange() {
        final FakeWorld w = stadium(209, false);
        w.setArcher(true);
        w.classId = 92;
        w.clearPlayers();
        final Pos from = w.pos();
        w.addPlayer(new PlayerView(800, "Rival", 78, 92, new Pos(from.x() + 700, from.y(), from.z()), 700, 0, false, true, 0, false, false, false, true, 0, 100, 0, true, 1));
        final BrainHarness h = harness(w, false, 209);
        h.run(2000);
        assertTrue(h.act().count("moveTo") >= 1, "archer stayed at spawn: " + h.notes() + " " + h.act().calls());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("olympiad close")), h.notes().toString());
    }

    @Test
    void hellKnightSummonsDarkPantherBeforeTheMatch() {
        final FakeWorld w = stadium(210, false);
        w.classId = 91;
        w.setOlympiad(1, false);
        w.addSkill(new FakeWorld.Extra(BasicBrain.DARK_PANTHER, SkillView.Kind.SUMMON, 20, 0, 1459, 2, 0));
        w.giveItem(1459, 40);
        final BrainHarness h = harness(w, false, 210);
        h.run(4000);
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("summons")), h.notes().toString());
        assertEquals(0, h.act().count("attack"), "attacked during wait: " + h.notes());
    }

    @Test
    void selfBuffsDuringTheWait() {
        final FakeWorld w = stadium(211, false);
        w.selfBuffs = true;
        w.setOlympiad(1, false);
        final BrainHarness h = harness(w, false, 211);
        h.run(3000);
        assertTrue(w.buffCasts >= 1 || h.act().calls().stream().anyMatch(c -> c.action().equals("cast") && c.arg().startsWith(FakeWorld.BUFF_ID + "->")),
            "no pre-fight buff: " + h.notes() + " " + h.act().calls());
        assertEquals(0, h.act().count("attack"));
    }

    @Test
    void observerIsNotFought() {
        final FakeWorld w = stadium(208, false);
        w.clearPlayers();
        w.addPlayer(new PlayerView(801, "Obs", 80, 0, new Pos(STADIUM.x() + 80, STADIUM.y(), 0), 80, 0, false, false, 0, false, false, false, true, 0, 100, 0, false, 1));
        final BrainHarness h = harness(w, false, 208);
        h.run(2000);
        assertEquals(0, h.act().count("attack"), "hit an observer: " + h.notes());
        assertTrue(h.states().stream().anyMatch(s -> s.contains("no rival"))
            || h.notes().stream().anyMatch(n -> n.contains("no rival")),
            "states " + h.states() + " notes " + h.notes());
    }
}
