package org.l2start.bots.runtime;

/** What a shard does with a bot whose tick is due (bots-core: build the world view, run the brain). */
public interface TickHandler {
    /** @return the delay (ms) to the next tick of this bot */
    long tick(BotSlot slot, long now) throws Exception;

    /**
     * The tick threw. The slot is already in SAFE_IDLE for 60 s.
     * @param tooMany 10 errors within 10 minutes: the bot should leave the world
     */
    void failed(BotSlot slot, Throwable error, boolean tooMany);
}
