package org.l2start.bots.runtime;

/** Time source of the bot runtime: real time in the game, a manual clock in tests. */
@FunctionalInterface
public interface BotClock {
    BotClock SYSTEM = System::currentTimeMillis;

    long now();
}
