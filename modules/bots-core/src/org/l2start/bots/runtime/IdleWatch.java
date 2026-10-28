package org.l2start.bots.runtime;

import org.l2start.bots.api.Activity;
import org.l2start.bots.api.Pos;

/**
 * Idle detection of one bot (docs/STARTER_ZONES_PLAN.md S3). Idle = it wants to move but did not get farther than
 * {@link #MOVED} in {@link #TICKS} ticks (and at least {@link #MIN_STILL_MS}), or it has no goal at all, or an
 * intentional state (AFK, rest, fight, transit, group) went past its own maximum. A second idle within
 * {@link #AGAIN_MS} is {@link Verdict#IDLE_AGAIN} (the host teleports it to a fitting spot once nobody sees it).
 */
public final class IdleWatch {
    public static final double MOVED = 50;
    public static final int TICKS = 3;
    static final long MIN_STILL_MS = 8_000;
    static final long AGAIN_MS = 3 * 60_000L;
    /** ACTIVE bot this long without moving: a WARN in the log (diagnostics). */
    public static final long WARN_STILL_MS = 60_000;
    /** S3: the maximum of the "AFK at the gatekeeper" state (2 h plus a little slack for the logout). */
    public static final long AFK_TOWN_MAX_MS = 125 * 60_000L;

    public enum Verdict {
        OK, IDLE, IDLE_AGAIN
    }

    /** Maximum of an intentional state; 0 = never idle by time (party, post, dead). */
    static long limit(Activity.Kind k) {
        return switch (k) {
            case AFK -> 10 * 60_000L;
            case AFK_TOWN -> AFK_TOWN_MAX_MS;
            case REST -> 3 * 60_000L;
            case FIGHT -> 2 * 60_000L;
            case TRANSIT -> 15_000L;
            case GROUP -> 2 * 60_000L;
            case TOWN -> 6 * 60_000L;
            default -> 0;
        };
    }

    private Pos anchor;
    private int still;
    private long stillSince;
    private long lastMovedAt;
    private long lastIdleAt;
    private String why = "";

    public Verdict check(long now, Pos pos, Activity a) {
        if ((anchor == null) || (anchor.distance2d(pos) > MOVED)) {
            anchor = pos;
            still = 0;
            stillSince = now;
            lastMovedAt = now;
        } else {
            still++;
        }
        if (a == null) {
            return Verdict.OK;
        }
        final boolean stuck = (still >= TICKS) && ((now - stillSince) >= MIN_STILL_MS);
        final boolean idle;
        switch (a.kind()) {
            case NONE -> {
                idle = stuck;
                why = "no goal";
            }
            case MOVE -> {
                idle = a.wantsToMove() && stuck;
                why = "not moving";
            }
            case PARTY, POST, DEAD -> idle = false;
            default -> {
                final long max = limit(a.kind());
                idle = (max > 0) && ((now - a.since()) > max);
                why = a.kind().name().toLowerCase(java.util.Locale.ROOT) + " over " + (max / 1000) + " s";
            }
        }
        if (!idle) {
            return Verdict.OK;
        }
        still = 0;
        stillSince = now;
        final Verdict v = ((lastIdleAt > 0) && ((now - lastIdleAt) < AGAIN_MS)) ? Verdict.IDLE_AGAIN : Verdict.IDLE;
        lastIdleAt = now;
        return v;
    }

    /**
     * The client draws every character it knows, walls or not: this close a live player sees the bot even without a line
     * of sight (caves, floors). Farther out (up to the host's radius) only with a line of sight.
     */
    public static final double SIGHT_ALWAYS = 2000;

    /** Pure: does a live player at {@code distance} see the bot. */
    public static boolean seen(double distance, boolean lineOfSight) {
        return (distance <= SIGHT_ALWAYS) || lineOfSight;
    }

    /** What the host does about a verdict. */
    public enum Reaction {
        NONE,
        /** Another activity (the brain gets BotEvent.Idle). */
        CHANGE,
        /** Teleport to a fitting spot now (nobody live sees it). */
        TELEPORT,
        /** Seen by a live player: it runs, the teleport waits until it is unseen. */
        RUN_TELEPORT_LATER
    }

    public static Reaction react(Verdict v, boolean seenByLive) {
        return switch (v) {
            case OK -> Reaction.NONE;
            case IDLE -> Reaction.CHANGE;
            case IDLE_AGAIN -> seenByLive ? Reaction.RUN_TELEPORT_LATER : Reaction.TELEPORT;
        };
    }

    /** A waiting teleport: done once nobody live sees it; dropped when it ran off ({@code > 300} from where it waited). */
    public static Boolean pendingTeleport(Pos waitedAt, Pos now, boolean seenByLive) {
        if (waitedAt.distance2d(now) > 300) {
            return null;
        }
        return !seenByLive;
    }

    /** A bot idle again in sight of a live player waits this long for him to look away, then reads a Scroll of Escape. */
    public static final long SEEN_WAIT_MS = 30_000L;
    /** The scroll's cast (stock SoE: 20 s) plus slack; past it the escape counts as done anyway. */
    public static final long ESCAPE_MS = 40_000L;

    /** The wait in sight of a live player is over: it escapes by scroll (visible, not a sudden vanish). */
    public static boolean escapeDue(long waitingSince, long now) {
        return (waitingSince > 0) && ((now - waitingSince) >= SEEN_WAIT_MS);
    }

    /** The scroll did its work (it is far from where it read it) or its time is up: the bot logs out. */
    public static boolean escaped(Pos from, Pos now, long startedAt, long at) {
        return (from.distance2d(now) > 3000) || ((at - startedAt) >= ESCAPE_MS);
    }

    /** Why the last idle verdict. */
    public String why() {
        return why;
    }

    /** How long it has not moved farther than {@link #MOVED}. */
    public long stillFor(long now) {
        return lastMovedAt == 0 ? 0 : now - lastMovedAt;
    }

    public Pos anchor() {
        return anchor;
    }

    /** An intentional state within its limits (no WARN for standing still). */
    public static boolean standsOnPurpose(Activity a, long now) {
        if (a == null) {
            return false;
        }
        return switch (a.kind()) {
            case PARTY, POST, DEAD -> true;
            case NONE, MOVE -> false;
            default -> (now - a.since()) <= limit(a.kind());
        };
    }
}
