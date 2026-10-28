package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Memory;
import org.l2start.bots.api.PartyMember;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.chat.PvpBoard;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 7b: enact PvpDecision (initiative / protect / revenge / flee) and board counters. */
class PvpBrainTest {
    @AfterEach
    void reset() {
        BasicBrain.pvpMode = PvpDecision.Mode.NORMAL;
        BasicBrain.pvpPlayerCooldownMs = 12 * 60_000L;
        BasicBrain.pvpProtectParty = true;
        BasicBrain.pvpProtectClan = true;
        BasicBrain.pvpRevenge = true;
        BasicBrain.pvpRevengeTimeoutMs = 30 * 60_000L;
        BasicBrain.pvpRevengeDelayMs = 2 * 60_000L;
        BasicBrain.afkShare = 0.15;
    }

    private static PlayerView live(int id, String name, int level, Pos at, boolean inPvp) {
        return new PlayerView(id, name, level, 0, at, 100, 0, false, false, 0, false, false, false, inPvp);
    }

    private static BrainHarness onSpot(FakeWorld w, long seed) {
        BasicBrain.afkShare = 0;
        w.setLevel(40);
        w.teleport(new Pos(3000, 0, 0));
        w.setPeace(false);
        final BrainHarness h = BasicBrainTest.harness(w, false, seed);
        h.setAssignment(PresenceBrainTest.hunt(1));
        return h;
    }

    @Test
    void initiativeWithEdgeAttacksInPvpZone() {
        final FakeWorld w = BasicBrainTest.world(71, false);
        final BrainHarness h = onSpot(w, 71);
        BasicBrain.pvpMode = PvpDecision.Mode.HIGH;
        BasicBrain.pvpPlayerCooldownMs = 0;
        w.addPlayer(live(800, "Gleb", 35, new Pos(w.pos().x() + 200, w.pos().y(), 0), true));
        h.run(3000);
        assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("800")),
            "no attack: " + h.notes() + " " + h.act().calls());
        assertTrue(h.pvpBoard().hourCounts(h.clock().now()).getOrDefault(PvpBoard.Stat.ATTACK, 0) >= 1);
    }

    @Test
    void peaceZoneNoFirstAttack() {
        final FakeWorld w = BasicBrainTest.world(72, false);
        final BrainHarness h = onSpot(w, 72);
        w.setPeace(true);
        BasicBrain.pvpMode = PvpDecision.Mode.HIGH;
        w.addPlayer(live(801, "Gleb", 30, new Pos(w.pos().x() + 150, w.pos().y(), 0), false));
        final long attacks = h.act().count("attack");
        h.run(3000);
        assertEquals(attacks, h.act().count("attack"), "attacked in peace: " + h.notes());
        assertTrue(h.notes().stream().noneMatch(n -> n.contains("PvP initiative")), h.notes().toString());
    }

    @Test
    void under20AndOffNeverInitiative() {
        final FakeWorld w = BasicBrainTest.world(73, false);
        final BrainHarness h = onSpot(w, 73);
        BasicBrain.pvpMode = PvpDecision.Mode.HIGH;
        w.addPlayer(live(802, "Noob", 15, new Pos(w.pos().x() + 150, w.pos().y(), 0), true));
        h.run(2000);
        assertFalse(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("802")), h.notes().toString());

        w.clearPlayers();
        BasicBrain.pvpMode = PvpDecision.Mode.OFF;
        w.addPlayer(live(803, "Gleb", 35, new Pos(w.pos().x() + 150, w.pos().y(), 0), true));
        h.run(2000);
        assertFalse(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("803")), h.notes().toString());
    }

    @Test
    void protectPartyMate() {
        final FakeWorld w = BasicBrainTest.world(74, false);
        final BrainHarness h = onSpot(w, 74);
        final Pos mateAt = new Pos(w.pos().x() + 100, w.pos().y(), 0);
        w.setParty(new PartyMember(900, "MateBot", 0, 40, mateAt, 50, 80, 100, false, 0, false));
        final PlayerView foe = new PlayerView(804, "Raider", 38, 0, new Pos(w.pos().x() + 180, w.pos().y(), 0), 180, 0, true, true, 900, false, false,
            false, true);
        w.addPlayer(foe);
        h.run(3000);
        assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("804")),
            "no protect: " + h.notes());
        assertTrue(h.pvpBoard().hourCounts(h.clock().now()).getOrDefault(PvpBoard.Stat.DEFEND, 0) >= 1);
    }

    @Test
    void protectClanMate() {
        final FakeWorld w = BasicBrainTest.world(78, false);
        final BrainHarness h = onSpot(w, 78);
        BasicBrain.pvpProtectParty = false;
        BasicBrain.pvpMode = PvpDecision.Mode.OFF; // no initiative on live players; protect still on
        w.setClanId(555);
        final Pos mateAt = new Pos(w.pos().x() + 120, w.pos().y(), 0);
        // live clan mate (bot=false) so Mode.OFF does not open initiative on them
        w.addPlayer(new PlayerView(910, "ClanMate", 40, 0, mateAt, 120, 0, false, false, 0, false, false, false, true, 555));
        final PlayerView foe = new PlayerView(808, "Raider", 38, 0, new Pos(w.pos().x() + 200, w.pos().y(), 0), 200, 0, true, true, 910, false, false,
            false, true, 0);
        w.addPlayer(foe);
        h.run(3000);
        assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("808")),
            "no clan protect: " + h.notes() + " " + h.act().calls());
        assertTrue(h.pvpBoard().hourCounts(h.clock().now()).getOrDefault(PvpBoard.Stat.DEFEND, 0) >= 1);
    }

    @Test
    void revengeAfterDelay() {
        final FakeWorld w = BasicBrainTest.world(75, false);
        final BrainHarness h = onSpot(w, 75);
        h.clock().set(1_000_000L);
        w.runUntil(1_000_000L);
        BasicBrain.pvpRevengeDelayMs = 60_000L;
        BasicBrain.pvpRevengeTimeoutMs = 30 * 60_000L;
        BasicBrain.pvpMode = PvpDecision.Mode.OFF; // only revenge, no initiative
        h.memory().load(new Memory.Of(805, -25, 1, 0, 1_000_000L - 90_000L, Set.of(Memory.Tag.KILLED_ME)));
        w.addPlayer(live(805, "Killer", 38, new Pos(w.pos().x() + 160, w.pos().y(), 0), true));
        h.run(3000);
        assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("805"))
            || h.notes().stream().anyMatch(n -> n.contains("revenge")), "no revenge: " + h.notes());
        assertTrue(h.pvpBoard().hourCounts(h.clock().now()).getOrDefault(PvpBoard.Stat.REVENGE, 0) >= 1
            || h.notes().stream().anyMatch(n -> n.contains("revenge")));
    }

    @Test
    void revengeTooSoonDoesNothing() {
        final FakeWorld w = BasicBrainTest.world(76, false);
        final BrainHarness h = onSpot(w, 76);
        h.clock().set(1_000_000L);
        w.runUntil(1_000_000L);
        BasicBrain.pvpRevengeDelayMs = 5 * 60_000L;
        BasicBrain.pvpMode = PvpDecision.Mode.OFF;
        h.memory().load(new Memory.Of(807, -25, 1, 0, 1_000_000L - 30_000L, Set.of(Memory.Tag.KILLED_ME)));
        w.addPlayer(live(807, "Fresh", 38, new Pos(w.pos().x() + 160, w.pos().y(), 0), true));
        h.run(2000);
        assertFalse(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("807")),
            "revenge too soon: " + h.notes());
    }

    @Test
    void revengeTimeoutExpires() {
        final FakeWorld w = BasicBrainTest.world(77, false);
        final BrainHarness h = onSpot(w, 77);
        h.clock().set(1_000_000L);
        w.runUntil(1_000_000L);
        BasicBrain.pvpRevengeTimeoutMs = 60_000L;
        BasicBrain.pvpRevengeDelayMs = 0;
        BasicBrain.pvpMode = PvpDecision.Mode.OFF;
        h.memory().load(new Memory.Of(806, -25, 1, 0, 1_000_000L - 90_000L, Set.of(Memory.Tag.KILLED_ME)));
        w.addPlayer(live(806, "OldFoe", 38, new Pos(w.pos().x() + 160, w.pos().y(), 0), true));
        h.run(2000);
        assertFalse(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("806")),
            "revenge after timeout: " + h.notes());
    }

    @Test
    void neverOpensAFightWithAPeacefulBot() {
        // live test 2026-10-21: lv 26-27 hunters on one ground "fought" each other and stood still instead of farming
        final FakeWorld w = BasicBrainTest.world(79, false);
        final BrainHarness h = onSpot(w, 79);
        BasicBrain.pvpMode = PvpDecision.Mode.HIGH;
        BasicBrain.pvpPlayerCooldownMs = 0;
        w.addPlayer(new PlayerView(801, "Grimarrow", 27, 0, new Pos(w.pos().x() + 200, w.pos().y(), 0), 200, 0, false, false, 0, true, false, false, true));
        h.run(30_000);
        assertFalse(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("801")),
            "attacked a peaceful bot: " + h.notes());
        assertFalse(h.notes().stream().anyMatch(n -> n.contains("Grimarrow") && n.contains("PvP")), h.notes().toString());
    }
}
