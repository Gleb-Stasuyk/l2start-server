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
}
