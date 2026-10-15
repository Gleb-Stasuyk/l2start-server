package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Memory;
import org.l2start.bots.api.Pos;
import org.l2start.bots.chat.PvpBoard;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 7b: duel invite → answerDuel + board counters. */
class DuelBrainTest {
    private static BrainHarness hunting(FakeWorld w, BotInfo info, long seed) {
        BasicBrain.afkShare = 0;
        w.setLevel(40);
        w.teleport(new Pos(3000, 0, 0));
        w.setPeace(false);
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, info, w, seed);
        h.setAssignment(PresenceBrainTest.hunt(1));
        return h;
    }

    @Test
    void acceptsOrDeclinesAndCounts() {
        final FakeWorld w = BasicBrainTest.world(81, false);
        final BotInfo aggressive = new BotInfo(1, 100, "Bot", 0, false, "RU", 0.95, 0.9, 0.5, 0.5, 0.5, 0.5, 200, BasicBrainTest.TOWN, 81);
        final BrainHarness h = hunting(w, aggressive, 81);
        w.duelInvite(900, "Gleb", 40, false);
        h.run(15_000);
        assertTrue(w.chat.stream().anyMatch(c -> c.startsWith("answerDuel:"))
            || h.act().calls().stream().anyMatch(c -> c.action().equals("answerDuel")),
            "no answer: " + w.chat + " " + h.notes() + " " + h.act().calls());
        final var counts = h.pvpBoard().hourCounts(h.clock().now());
        assertTrue(counts.getOrDefault(PvpBoard.Stat.DUEL_OK, 0) + counts.getOrDefault(PvpBoard.Stat.DUEL_NO, 0) >= 1, counts.toString());
    }

    @Test
    void killerUsuallyDeclines() {
        final FakeWorld w = BasicBrainTest.world(82, false);
        final BotInfo me = new BotInfo(1, 100, "Bot", 0, false, "RU", 0.9, 0.9, 0.5, 0.5, 0.5, 0.5, 200, BasicBrainTest.TOWN, 82);
        final BrainHarness h = hunting(w, me, 82);
        h.clock().set(1_000_000L);
        w.runUntil(1_000_000L);
        h.memory().load(new Memory.Of(901, -25, 1, 0, h.clock().now(), Set.of(Memory.Tag.KILLED_ME)));
        assertTrue(h.memory().of(901).has(Memory.Tag.KILLED_ME));
        w.duelInvite(901, "Killer", 40, false);
        h.run(15_000);
        assertTrue(w.chat.contains("answerDuel:false") || h.notes().stream().anyMatch(n -> n.contains("declined duel"))
            || h.act().calls().stream().anyMatch(c -> c.action().equals("answerDuel") && c.arg().equals("false")),
            "expected decline: " + w.chat + " " + h.notes());
    }
}
