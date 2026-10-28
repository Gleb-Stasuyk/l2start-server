package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.chat.PvpBoard;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 7c: PK personas open fights; hunters hit reds; peace / &lt;20 / off respected. */
class PkBrainTest {
    @AfterEach
    void reset() {
        BasicBrain.pvpMode = PvpDecision.Mode.NORMAL;
        BasicBrain.pvpPlayerCooldownMs = 12 * 60_000L;
        BasicBrain.pvpProtectParty = true;
        BasicBrain.pvpRevenge = true;
        BasicBrain.afkShare = 0.15;
    }

    private static PlayerView live(int id, String name, int level, Pos at, boolean inPvp, int karma) {
        return new PlayerView(id, name, level, 0, at, 100, karma, false, false, 0, false, false, false, inPvp);
    }

    private static BrainHarness pkHarness(FakeWorld w, long seed, double aggression, boolean pk) {
        BasicBrain.afkShare = 0;
        w.setLevel(40);
        w.teleport(new Pos(3000, 0, 0));
        w.setPeace(false);
        final BotInfo info = new BotInfo(1, 100, "Bot", 0, false, "RU", aggression, 0.5, 0.5, 0.5, 0.5, 0.5, 200, BasicBrainTest.TOWN, seed, pk);
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, info, w, seed);
        h.setAssignment(PresenceBrainTest.hunt(1));
        return h;
    }

    @Test
    void pkOpensOnWhiteInPvpZone() {
        final FakeWorld w = BasicBrainTest.world(91, false);
        final BrainHarness h = pkHarness(w, 91, 0.4, true);
        BasicBrain.pvpPlayerCooldownMs = 0;
        w.addPlayer(live(910, "Gleb", 35, new Pos(w.pos().x() + 200, w.pos().y(), 0), true, 0));
        h.run(3000);
        assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("910")),
            "no pk attack: " + h.notes() + " " + h.act().calls());
    }

    @Test
    void pkNeverOpensInPeaceOrOnUnder20() {
        final FakeWorld w = BasicBrainTest.world(92, false);
        final BrainHarness h = pkHarness(w, 92, 0.9, true);
        BasicBrain.pvpPlayerCooldownMs = 0;
        w.setPeace(true);
        w.addPlayer(live(920, "Gleb", 35, new Pos(w.pos().x() + 150, w.pos().y(), 0), false, 0));
        h.run(2000);
        assertFalse(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("920")), h.notes().toString());

        w.clearPlayers();
        w.setPeace(false);
        w.addPlayer(live(921, "Noob", 15, new Pos(w.pos().x() + 150, w.pos().y(), 0), true, 0));
        h.run(2000);
        assertFalse(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("921")), h.notes().toString());
    }

    @Test
    void offBlocksPkInitiativeOnLive() {
        final FakeWorld w = BasicBrainTest.world(93, false);
        final BrainHarness h = pkHarness(w, 93, 0.9, true);
        BasicBrain.pvpMode = PvpDecision.Mode.OFF;
        BasicBrain.pvpPlayerCooldownMs = 0;
        w.addPlayer(live(930, "Gleb", 35, new Pos(w.pos().x() + 150, w.pos().y(), 0), true, 0));
        h.run(2000);
        assertFalse(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("930")), h.notes().toString());
    }

    @Test
    void hunterAttacksRedNearby() {
        final FakeWorld w = BasicBrainTest.world(94, false);
        // LOW + no level edge → initiative stays NONE; aggression ≥0.65 → pk-hunt on red
        final BrainHarness h = pkHarness(w, 94, 0.7, false);
        BasicBrain.pvpMode = PvpDecision.Mode.LOW;
        BasicBrain.pvpPlayerCooldownMs = 0;
        w.addPlayer(live(940, "Red", 42, new Pos(w.pos().x() + 180, w.pos().y(), 0), true, 12_000));
        h.run(3000);
        assertTrue(h.act().calls().stream().anyMatch(c -> c.action().equals("attack") && c.arg().equals("940")),
            "no pk-hunt: " + h.notes() + " " + h.act().calls());
        assertTrue(h.pvpBoard().hourCounts(h.clock().now()).getOrDefault(PvpBoard.Stat.PK_HUNT, 0) >= 1, h.notes().toString());
    }
}
