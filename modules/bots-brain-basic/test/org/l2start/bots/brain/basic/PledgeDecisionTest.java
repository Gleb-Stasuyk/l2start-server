package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Memory;
import org.l2start.bots.api.Pos;
import org.l2start.bots.clan.ClanService;

/** Phase 7e/7g: pledge accept/decline (invite only when asked). */
class PledgeDecisionTest {
    private static final Pos HOME = new Pos(0, 0, 0);

    private static BotInfo info(double sociability, double aggression) {
        return new BotInfo(1, 100, "Bot", 0, false, "RU", aggression, sociability, 0.5, 0.5, 0.5, 0.5, 400, HOME, 42);
    }

    @Test
    void joinDeclinesWhenDisabledOrCannotLeave() {
        final Random rnd = new Random(1);
        final Memory.Of mem = Memory.Of.none(2);
        assertEquals(PledgeDecision.Answer.DECLINE, PledgeDecision.decideJoin(info(0.8, 0.5), mem, false, true, 30, false, rnd));
        assertEquals(PledgeDecision.Answer.DECLINE, PledgeDecision.decideJoin(info(0.8, 0.5), mem, true, false, 30, false, rnd));
        assertEquals(PledgeDecision.Answer.DECLINE, PledgeDecision.decideJoin(info(0.8, 0.5), mem, true, true, 45, true, rnd));
    }

    @Test
    void joinDeclinesAnnoyed() {
        final Memory.Of mem = new Memory.Of(2, -40, 0, 0, 1, Set.of(Memory.Tag.ANNOYED));
        assertEquals(PledgeDecision.Answer.DECLINE, PledgeDecision.decideJoin(info(0.9, 0.5), mem, true, true, 30, false, new Random(1)));
    }

    @Test
    void inviteOutNeedsSlotsAndLevel() {
        final Random rnd = new Random(2);
        final Memory.Of mem = Memory.Of.none(3);
        assertEquals(PledgeDecision.Answer.DECLINE, PledgeDecision.decideInviteOut(info(0.9, 0.2), mem, false, true, 40, rnd));
        assertEquals(PledgeDecision.Answer.DECLINE, PledgeDecision.decideInviteOut(info(0.9, 0.2), mem, true, false, 40, rnd));
        assertEquals(PledgeDecision.Answer.DECLINE, PledgeDecision.decideInviteOut(info(0.9, 0.2), mem, true, true, 15, rnd));
    }

    @Test
    void askInDeclinesWhenDisabled() {
        assertEquals(PledgeDecision.Answer.DECLINE,
            PledgeDecision.decideAskIn(info(0.9, 0.2), Memory.Of.none(3), false, true, 40, new Random(1)));
    }

    @Test
    void wantMembersForShare() {
        assertEquals(70, ClanService.wantMembersForPool(100, 0.70));
        assertEquals(0, ClanService.wantMembersForPool(0, 0.70));
    }

    @Test
    void academyOnlyBelow40() {
        assertFalse(PledgeDecision.wantAcademy(info(0.9, 0.2), Memory.Of.none(1), 40, true, new Random(1)));
        assertFalse(PledgeDecision.wantAcademy(info(0.9, 0.2), Memory.Of.none(1), 20, false, new Random(1)));
    }
}
