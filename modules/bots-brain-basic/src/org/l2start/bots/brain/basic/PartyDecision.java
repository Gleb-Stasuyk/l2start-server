package org.l2start.bots.brain.basic;

import java.util.Random;

import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Memory;

/**
 * Phase 6b: does a bot join a real player's party? A sum of weights (PHASE6_PLAN 6b, Living Worlds
 * TownFakeInviteRules): level gap, whether its role is wanted, sociability, what it is doing now, what it remembers
 * of the player, refusals a short while ago. Pure: the brain answers 2-8 s later by the result.
 */
public final class PartyDecision {
    public enum Answer {
        ACCEPT("PARTY_HELLO"), DECLINE_SOLO("DECLINE_SOLO"), DECLINE_LEVEL("DECLINE_LEVEL"), DECLINE_LEAVING("DECLINE_LEAVING"), DECLINE_FULL("DECLINE_FULL"),
        DECLINE_AGAIN("DECLINE_AGAIN"), IGNORE(null);

        public final String intent;

        Answer(String intent) {
            this.intent = intent;
        }

        public boolean yes() {
            return this == ACCEPT;
        }
    }

    /** What the bot does now. */
    public enum Doing {
        HUNTING, TOWN, LEAVING, BUSY
    }

    /** Level gap from which a bot always says no (stock party exp is bad with it, and it "does not want to carry"). */
    public static final int GAP_NO = 10;
    /** A player asked again within this time after a refusal: no again. */
    public static final long ASK_AGAIN_MS = 10 * 60_000L;

    private PartyDecision() {
    }

    /**
     * @param declinedBefore refusals of this player in a row (0 = none), {@code lastDeclinedAgo} ms ago
     * @param supportWanted the bot is a healer/buffer and the inviter's party has none
     */
    public static Answer decide(BotInfo me, int myLevel, BotEvent.PartyInvite inv, Doing doing, Memory.Of mem, int declinedBefore, long lastDeclinedAgo,
        boolean supportWanted, Random rnd) {
        if (inv.botLimit() || (inv.partyMembers() >= 9)) {
            return Answer.DECLINE_FULL;
        }
        if (mem.has(Memory.Tag.KILLED_ME) || mem.has(Memory.Tag.ANNOYED) || (mem.affinity() <= -40)) {
            return rnd.nextDouble() < 0.6 ? Answer.IGNORE : Answer.DECLINE_SOLO;
        }
        if ((declinedBefore > 0) && (lastDeclinedAgo < ASK_AGAIN_MS)) {
            if (declinedBefore >= 2) {
                return rnd.nextDouble() < 0.5 ? Answer.IGNORE : Answer.DECLINE_AGAIN;
            }
            return Answer.DECLINE_AGAIN;
        }
        final int gap = Math.abs(myLevel - inv.fromLevel());
        if (gap >= GAP_NO) {
            return Answer.DECLINE_LEVEL;
        }
        if ((doing == Doing.LEAVING) || (doing == Doing.BUSY)) {
            return rnd.nextDouble() < 0.3 ? Answer.IGNORE : Answer.DECLINE_LEAVING;
        }
        final boolean friend = (mem.affinity() > 30) || mem.has(Memory.Tag.PARTY);
        double p = 0.2 + (me.sociability() * 0.45);
        if (gap <= 5) {
            p += 0.2;
        } else {
            p -= 0.25 * (1 - me.sociability()); // 6-9: by character
        }
        if (supportWanted) {
            p += 0.2;
        }
        if (doing == Doing.HUNTING) {
            p += 0.1;
        }
        if (mem.has(Memory.Tag.REZZED_ME)) {
            p += 0.2;
        }
        if (friend) {
            p = Math.max(p, 0.92);
        }
        p = Math.max(0.05, Math.min(0.95, p));
        if (rnd.nextDouble() < p) {
            return Answer.ACCEPT;
        }
        if (gap > 5) {
            return Answer.DECLINE_LEVEL;
        }
        return rnd.nextDouble() < 0.15 ? Answer.IGNORE : Answer.DECLINE_SOLO;
    }
}
