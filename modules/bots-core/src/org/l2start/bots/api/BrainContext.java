package org.l2start.bots.api;

import java.util.Map;
import java.util.Random;

/** Everything a brain gets for one tick. Only the bot's own shard thread calls a brain, so no locking is needed. */
public interface BrainContext {
    BotInfo info();

    WorldView world();

    Actuators act();

    /** Random numbers of this bot (seeded from the persona). */
    Random rnd();

    /** Current time (ms), injected: tests use a manual clock. */
    long now();

    /** Persistent scratch space of this bot's brain (lives while the bot is in the world). */
    Map<String, Object> blackboard();

    /** A delay of one reaction: persona reaction time with jitter, 200..1200 ms. */
    long reactionDelay();

    /** Note a decision in the bot's action log (//bots info shows the last 10). */
    void note(String text);

    /** The presence director's current order for this bot (null = no director: manual mode). */
    default Assignment assignment() {
        return null;
    }

    /** Phase 6a: phrases of this bot (persona language and style); {@link Chat#SILENT} without phrase banks. */
    default Chat chat() {
        return Chat.SILENT;
    }

    /** Phase 6a: what this bot remembers of real players (bots-core keeps it in the database). */
    default Memory memory() {
        return NO_MEMORY;
    }

    /**
     * Phase 6d: world-wide LFP shout/reply limits and the "we agreed to party" window. Shared by every bot;
     * {@link org.l2start.bots.chat.SocialBoard#NONE} when the host has no board (tests without one).
     */
    default org.l2start.bots.chat.SocialBoard social() {
        return org.l2start.bots.chat.SocialBoard.NONE;
    }

    /** Forgets everything right away (no memory at all). */
    Memory NO_MEMORY = new Memory() {
        @Override
        public Of of(int charId) {
            return Of.none(charId);
        }

        @Override
        public void note(int charId, int affinityDelta, Tag tag, boolean met) {
        }

        @Override
        public void partyMinutes(int charId, int minutes) {
        }

        @Override
        public void forget(int charId, Tag tag) {
        }
    };
}
