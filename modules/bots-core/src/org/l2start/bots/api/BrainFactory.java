package org.l2start.bots.api;

/** Makes brains of one kind (bots-core config key Brain picks the kind by id). */
public interface BrainFactory {
    String id();

    Brain create(BotInfo info);
}
