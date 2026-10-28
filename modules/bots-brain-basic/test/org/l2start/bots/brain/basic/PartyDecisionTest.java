package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Memory;

/** Phase 6b: does a bot join a real player's party (level, role, character, what it does, memory, refusals). */
class PartyDecisionTest {
    static final int GLEB = 5000;

    static BotInfo info(boolean mage, double sociability, double patience) {
        return new BotInfo(1, 100, "Bot", mage ? 10 : 0, false, "RU", 0.5, sociability, 0.5, patience, 0.5, 0.8, 700, BasicBrainTest.TOWN, 42);
    }

    private static BotEvent.PartyInvite inv(int level, int members, boolean limit) {
        return new BotEvent.PartyInvite(GLEB, "Gleb", level, 0, members, limit);
    }

    @Test
    void decisionRules() {
        final Random r = new Random(1);
        final BotInfo bot = info(false, 0.5, 0.5);
        final Memory.Of none = Memory.Of.none(GLEB);
        assertEquals(PartyDecision.Answer.DECLINE_FULL, PartyDecision.decide(bot, 20, inv(20, 3, true), PartyDecision.Doing.HUNTING, none, 0, 0, false, r));
        assertEquals(PartyDecision.Answer.DECLINE_FULL, PartyDecision.decide(bot, 20, inv(20, 9, false), PartyDecision.Doing.HUNTING, none, 0, 0, false, r));
        assertEquals(PartyDecision.Answer.DECLINE_LEVEL, PartyDecision.decide(bot, 20, inv(30, 0, false), PartyDecision.Doing.HUNTING, none, 0, 0, false, r));
        assertEquals(PartyDecision.Answer.DECLINE_LEVEL, PartyDecision.decide(bot, 30, inv(20, 0, false), PartyDecision.Doing.HUNTING, none, 0, 0, false, r));
        assertEquals(PartyDecision.Answer.DECLINE_AGAIN, PartyDecision.decide(bot, 20, inv(20, 0, false), PartyDecision.Doing.HUNTING, none, 1, 60_000, false, r));
        for (int i = 0; i < 50; i++) {
            final PartyDecision.Answer a = PartyDecision.decide(bot, 20, inv(20, 0, false), PartyDecision.Doing.HUNTING, none, 2, 60_000, false, r);
            assertTrue((a == PartyDecision.Answer.DECLINE_AGAIN) || (a == PartyDecision.Answer.IGNORE), a.toString());
            final PartyDecision.Answer b = PartyDecision.decide(bot, 20, inv(20, 0, false), PartyDecision.Doing.LEAVING, none, 0, 0, false, r);
            assertFalse(b.yes(), "a leaving bot never joins");
            final PartyDecision.Answer k = PartyDecision.decide(bot, 20, inv(20, 0, false), PartyDecision.Doing.HUNTING,
                new Memory.Of(GLEB, 0, 1, 0, 0, java.util.Set.of(Memory.Tag.KILLED_ME)), 0, 0, false, r);
            assertFalse(k.yes(), "never with its killer");
        }
    }

    @Test
    void partyAgreedAlwaysAccepts() {
        final Random r = new Random(3);
        final Memory.Of none = Memory.Of.none(GLEB);
        for (int i = 0; i < 100; i++) {
            // agreed window beats leaving/busy/low sociability (still after hard no: full / gap>=10 / recent declines)
            assertEquals(PartyDecision.Answer.ACCEPT,
                PartyDecision.decide(info(false, 0.1, 0.5), 20, inv(20, 0, false), PartyDecision.Doing.LEAVING, none, 0, 0, false, true, r));
            assertEquals(PartyDecision.Answer.ACCEPT,
                PartyDecision.decide(info(true, 0.05, 0.5), 20, inv(27, 0, false), PartyDecision.Doing.BUSY, none, 0, 0, false, true, r));
        }
    }

    @Test
    void sociableHealerSaysYesMoreOftenThanALoneFighter() {
        final Random r = new Random(7);
        final Memory.Of none = Memory.Of.none(GLEB);
        int healer = 0;
        int loner = 0;
        int friend = 0;
        for (int i = 0; i < 2000; i++) {
            healer += PartyDecision.decide(info(true, 0.9, 0.5), 20, inv(21, 0, false), PartyDecision.Doing.HUNTING, none, 0, 0, true, r).yes() ? 1 : 0;
            loner += PartyDecision.decide(info(false, 0.1, 0.5), 20, inv(27, 0, false), PartyDecision.Doing.TOWN, none, 0, 0, false, r).yes() ? 1 : 0;
            friend += PartyDecision.decide(info(false, 0.1, 0.5), 20, inv(21, 0, false), PartyDecision.Doing.TOWN, new Memory.Of(GLEB, 40, 3, 30, 0, java.util.Set.of(Memory.Tag.PARTY)),
                0, 0, false, r).yes() ? 1 : 0;
        }
        assertTrue(healer > 1800, "sociable healer: " + healer);
        assertTrue(loner < 400, "unsociable fighter, 7 levels apart: " + loner);
        assertTrue(friend > 1750, "someone it played with: " + friend);
    }

    @Test
    void partyMinutesMakeUnsociableBotAcceptMoreOften() {
        final Random r = new Random(11);
        final BotInfo loner = info(false, 0.15, 0.5);
        final Memory.Of stranger = Memory.Of.none(GLEB);
        final Memory.Of longParty = new Memory.Of(GLEB, 5, 2, 120, 0, java.util.Set.of()); // 120 min → +0.2
        int cold = 0;
        int warm = 0;
        for (int i = 0; i < 2000; i++) {
            cold += PartyDecision.decide(loner, 40, inv(40, 0, false), PartyDecision.Doing.TOWN, stranger, 0, 0, false, r).yes() ? 1 : 0;
            warm += PartyDecision.decide(loner, 40, inv(40, 0, false), PartyDecision.Doing.TOWN, longParty, 0, 0, false, r).yes() ? 1 : 0;
        }
        assertTrue(warm > cold + 200, "party minutes should help: cold=" + cold + " warm=" + warm);
    }

}
