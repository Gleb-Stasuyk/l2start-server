package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Memory;
import org.l2start.bots.api.Pos;

/** Phase 7b: duel accept / decline. */
class DuelDecisionTest {
    private static final Pos HOME = new Pos(0, 0, 0);

    private static BotInfo info(double aggression, double sociability) {
        return new BotInfo(1, 100, "Bot", 0, false, "RU", aggression, sociability, 0.5, 0.5, 0.5, 0.5, 700, HOME, 42);
    }

    @Test
    void killerUsuallyDeclined() {
        final Memory.Of killed = new Memory.Of(7, -25, 1, 0, 0, Set.of(Memory.Tag.KILLED_ME));
        int decline = 0;
        final Random r = new Random(1);
        for (int i = 0; i < 100; i++) {
            if (DuelDecision.decide(info(0.9, 0.9), killed, 40, 40, r) == DuelDecision.Answer.DECLINE) {
                decline++;
            }
        }
        assertTrue(decline >= 80, "decline " + decline);
    }

    @Test
    void aggressiveAcceptsMoreThanPassive() {
        final Memory.Of none = Memory.Of.none(7);
        int hot = 0;
        int cold = 0;
        final Random r = new Random(2);
        for (int i = 0; i < 200; i++) {
            if (DuelDecision.decide(info(0.95, 0.8), none, 40, 40, r) == DuelDecision.Answer.ACCEPT) {
                hot++;
            }
            if (DuelDecision.decide(info(0.05, 0.1), none, 40, 40, r) == DuelDecision.Answer.ACCEPT) {
                cold++;
            }
        }
        assertTrue(hot > cold + 40, "hot=" + hot + " cold=" + cold);
    }

    @Test
    void answersAreOnlyAcceptOrDecline() {
        final Random r = new Random(3);
        for (int i = 0; i < 20; i++) {
            final DuelDecision.Answer a = DuelDecision.decide(info(0.5, 0.5), Memory.Of.none(1), 30, 35, r);
            assertTrue(a == DuelDecision.Answer.ACCEPT || a == DuelDecision.Answer.DECLINE);
            assertEquals(a, a); // sanity
        }
    }
}
