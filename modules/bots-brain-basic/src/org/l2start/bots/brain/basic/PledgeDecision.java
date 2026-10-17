package org.l2start.bots.brain.basic;

import java.util.Random;

import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Memory;

/**
 * Phase 7e: accept/decline clan or academy invites, and whether a bot-clan leader should invite a live player.
 * Pure rules (no stock calls).
 */
public final class PledgeDecision {
    public enum Answer {
        ACCEPT, DECLINE
    }

    /** Stock {@code Clan.SUBUNIT_ACADEMY}. */
    public static final int ACADEMY = -1;

    private PledgeDecision() {
    }

    /**
     * Live player invited this bot (hook P7-BOT-PLEDGE).
     * @param joinEnabled ClanJoinPlayers
     * @param canLeaveBotClan false for traders and bot-clan leaders
     * @param myLevel bot level
     * @param academy true when pledgeType is academy
     */
    public static Answer decideJoin(BotInfo me, Memory.Of mem, boolean joinEnabled, boolean canLeaveBotClan, int myLevel,
        boolean academy, Random rnd) {
        if (!joinEnabled || !canLeaveBotClan) {
            return Answer.DECLINE;
        }
        if (academy && (myLevel >= 40)) {
            return Answer.DECLINE;
        }
        if (!academy && (myLevel < 10)) {
            return Answer.DECLINE;
        }
        if (mem.has(Memory.Tag.KILLED_ME) || mem.has(Memory.Tag.ANNOYED) || (mem.affinity() <= -30)) {
            return Answer.DECLINE;
        }
        double p = 0.20 + (me.sociability() * 0.45) + (me.aggression() * 0.05);
        if (mem.has(Memory.Tag.PARTY) || (mem.affinity() > 15)) {
            p += 0.25;
        }
        if (academy) {
            p += 0.1; // juniors are often happy to learn
        }
        p = Math.max(0.05, Math.min(0.85, p));
        return rnd.nextDouble() < p ? Answer.ACCEPT : Answer.DECLINE;
    }

    /**
     * Legacy helper (proactive invite removed in 7g). Kept for tests; prefer {@link #decideAskIn}.
     * @param inviteEnabled ClanInvitePlayers
     * @param slotsFree clan has free main-pledge slots
     * @param theirLevel live player level
     */
    public static Answer decideInviteOut(BotInfo me, Memory.Of mem, boolean inviteEnabled, boolean slotsFree,
        int theirLevel, Random rnd) {
        if (!inviteEnabled || !slotsFree || (theirLevel < 20)) {
            return Answer.DECLINE;
        }
        if (mem.has(Memory.Tag.KILLED_ME) || mem.has(Memory.Tag.ANNOYED) || (mem.affinity() <= -20)) {
            return Answer.DECLINE;
        }
        double p = 0.08 + (me.sociability() * 0.35);
        if (mem.has(Memory.Tag.PARTY) || (mem.affinity() > 10)) {
            p += 0.2;
        }
        p = Math.max(0.02, Math.min(0.55, p));
        return rnd.nextDouble() < p ? Answer.ACCEPT : Answer.DECLINE;
    }

    /** Live player asked the bot-clan leader (whisper) to join. */
    public static Answer decideAskIn(BotInfo me, Memory.Of mem, boolean inviteEnabled, boolean slotsFree, int theirLevel, Random rnd) {
        if (!inviteEnabled || !slotsFree || (theirLevel < 10)) {
            return Answer.DECLINE;
        }
        if (mem.has(Memory.Tag.KILLED_ME) || mem.has(Memory.Tag.ANNOYED)) {
            return Answer.DECLINE;
        }
        double p = 0.25 + (me.sociability() * 0.4);
        if (mem.affinity() > 0) {
            p += 0.15;
        }
        p = Math.max(0.05, Math.min(0.8, p));
        return rnd.nextDouble() < p ? Answer.ACCEPT : Answer.DECLINE;
    }

    /** Bot wants to ask a live clan for academy (lvl &lt; 40). */
    public static boolean wantAcademy(BotInfo me, Memory.Of mem, int myLevel, boolean canLeave, Random rnd) {
        if (!canLeave || (myLevel >= 40) || (myLevel < 5)) {
            return false;
        }
        if (mem.has(Memory.Tag.ANNOYED) || mem.has(Memory.Tag.KILLED_ME)) {
            return false;
        }
        final double p = 0.03 + (me.sociability() * 0.08);
        return rnd.nextDouble() < p;
    }
}
