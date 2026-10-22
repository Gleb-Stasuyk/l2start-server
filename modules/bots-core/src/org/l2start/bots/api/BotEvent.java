package org.l2start.bots.api;

/**
 * Something that happened to a bot between two ticks. Events come from stock game threads into the bot mailbox and
 * are handed to the brain at the start of its next tick. {@link #key()} equal = the newer one replaces the older one.
 */
public sealed interface BotEvent {
    /** Same key: only the newest is kept (null = never collapsed). */
    default String key() {
        return null;
    }

    /** Something hit the bot (damage of collapsed events is summed by the mailbox). */
    record Attacked(int attackerId, boolean byPlayer, double damage) implements BotEvent {
        @Override
        public String key() {
            return "attacked:" + attackerId;
        }
    }

    /** The bot killed a monster. */
    record Killed(int npcObjectId, int npcId) implements BotEvent {
    }

    record Died(int killerId) implements BotEvent {
        @Override
        public String key() {
            return "died";
        }
    }

    /** Back on its feet (revived in town after death, or a teleport ended). */
    record Teleported(Pos to) implements BotEvent {
        @Override
        public String key() {
            return "teleported";
        }
    }

    /**
     * The idle watch of bots-core (docs/STARTER_ZONES_PLAN.md S3): no goal, no movement or an intentional state past its
     * maximum. The brain takes up another activity; {@code again} = the second time in a row (a teleport waits until no
     * live player sees the bot, meanwhile it should run).
     */
    record Idle(String why, boolean again) implements BotEvent {
        @Override
        public String key() {
            return "idle";
        }
    }

    record LevelUp(int oldLevel, int newLevel) implements BotEvent {
        @Override
        public String key() {
            return "level";
        }
    }

    record Whisper(String from, String text) implements BotEvent {
    }

    /**
     * Phase 6a: a real player invited the bot to a party (hook P6-BOT-PARTY). The stock invitation waits 15 s for
     * {@link Actuators#answerParty}; no answer = it expires like with a player who looked away.
     * @param partyMembers members of the inviter's party now (0 = he has none)
     * @param botLimit the inviter has as many bots in his party as allowed already (bots-core PartyBotsMax)
     */
    record PartyInvite(int fromId, String from, int fromLevel, int fromClassId, int partyMembers, boolean botLimit) implements BotEvent {
        @Override
        public String key() {
            return "partyInvite";
        }
    }

    /**
     * Phase 6a: a real player wrote in a chat the bot can read (whispers stay {@link Whisper}).
     * @param channel PARTY (its own party), ALL (white chat within hearing), SHOUT, TRADE
     */
    record Chat(String channel, int fromId, String from, String text) implements BotEvent {
    }

    /**
     * Phase 6d: a real player invited a bot that is already in a bot-only party (hook P6-BOT-PARTY busy path).
     * The invited bot whispers; the party leader invites the player into that party.
     * @param whisper say {@code PAIR_REDIRECT} to the player
     * @param invite call {@link Actuators#inviteParty} (leader only)
     */
    record PartyRedirect(int fromId, String from, int fromLevel, int fromClassId, boolean whisper, boolean invite) implements BotEvent {
        @Override
        public String key() {
            return "partyRedirect:" + fromId;
        }
    }

    /**
     * Phase 7b: a real player challenged the bot to a duel (hook P7-BOT-DUEL). Answer with
     * {@link Actuators#answerDuel} within the stock request window.
     */
    record DuelInvite(int fromId, String from, int fromLevel, boolean partyDuel) implements BotEvent {
        @Override
        public String key() {
            return "duelInvite";
        }
    }

    /**
     * Phase 7e: a real player invited the bot into a clan / academy (hook P7-BOT-PLEDGE). Answer with
     * {@link Actuators#answerPledge} within the stock request window. {@code pledgeType} is stock
     * ({@code Clan.SUBUNIT_ACADEMY=-1} for academy, 0 for main). {@code canLeaveBotClan} false for traders and
     * bot-clan leaders; {@code joinEnabled} is ClanJoinPlayers.
     */
    record PledgeInvite(int fromId, String from, int fromLevel, int pledgeType, boolean canLeaveBotClan, boolean joinEnabled)
        implements BotEvent {
        @Override
        public String key() {
            return "pledgeInvite";
        }
    }

    /**
     * Phase 7e: clan chat rally / gather from a live leader or bot leader. Non-traders move toward the point.
     */
    record ClanRally(int fromId, String from, int x, int y, int z, String kind) implements BotEvent {
        @Override
        public String key() {
            return "clanRally:" + fromId;
        }
    }

    /**
     * RB party (iteration 1): a live player asked in trade / shout for this role for a raid boss and bots-core picked
     * this bot. It whispers him, runs to him and waits for his invitation (3 min).
     * @param role TANK, BISHOP, PROPHET or DD (org.l2start.bots.chat.RbParse.Role)
     * @param summoned brought into the world for this call: it logs out when it is over
     * @param at where the player stood when he asked
     */
    record RbCall(int fromId, String from, String role, boolean summoned, Pos at) implements BotEvent {
        @Override
        public String key() {
            return "rbCall";
        }
    }
}
