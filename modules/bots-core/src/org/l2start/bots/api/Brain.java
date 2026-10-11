package org.l2start.bots.api;

/** The decision maker of one bot. A brain object belongs to one bot. */
public interface Brain {
    /**
     * One decision step.
     * @return milliseconds until the next tick the brain would like (the runtime may tick later, by LOD, or earlier, on an event)
     */
    long tick(BrainContext ctx);

    /** Short state for //bots info and the stats (e.g. LEVEL/fight). */
    String state();

    /** The bot leaves the world or the brain is replaced. */
    default void stop(BrainContext ctx) {
    }

    /** In combat right now (the runtime ticks faster). */
    default boolean inCombat() {
        return false;
    }
}
