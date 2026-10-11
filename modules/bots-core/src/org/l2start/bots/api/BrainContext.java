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
}
