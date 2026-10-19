package org.l2start.bots.api;

/**
 * What a brain is doing now, for the idle watch of bots-core (docs/STARTER_ZONES_PLAN.md S3).
 * @param wantsToMove it wants to get somewhere (no movement = idle)
 * @param since when this activity (or its last progress) began; intentional standing is idle after its own maximum
 */
public record Activity(Kind kind, boolean wantsToMove, long since) {
    public enum Kind {
        /** No goal at all. */
        NONE,
        /** Walking to a place, searching a hunting ground, leaving. */
        MOVE,
        FIGHT,
        REST,
        AFK,
        /** Appeared at a gatekeeper "after a teleport", teleports on in a few seconds. */
        TRANSIT,
        /** Standing with others in a town. */
        GROUP,
        /** Strolling / standing about in a town. */
        TOWN,
        /** In a real player's party: does what the player does. */
        PARTY,
        /** A store, a buffer at its post, an academy recruiter at its gatekeeper. */
        POST,
        DEAD
    }
}
