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
}
