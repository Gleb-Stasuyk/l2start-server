package org.l2start.bots.runtime;

/**
 * Level of detail of one bot in the world (phase 3c: ACTIVE / PRESENT; OFFLINE and SIMULATED come with the
 * population manager in 3e). ACTIVE when a live player is within promote radius; back to PRESENT only after no live
 * player was within demote radius for the demote delay (hysteresis: no blinking).
 */
public final class Lod {
    public enum Level {
        ACTIVE, PRESENT
    }

    private Level level = Level.PRESENT;
    private long farSince = -1;

    public Level level() {
        return level;
    }

    /**
     * @param nearestLive distance to the nearest live (not bot) player, {@link Double#POSITIVE_INFINITY} when none
     * @return true when the level changed
     */
    public boolean update(double nearestLive, long now, int promoteRadius, int demoteRadius, long demoteDelayMs) {
        if (nearestLive <= promoteRadius) {
            farSince = -1;
            if (level != Level.ACTIVE) {
                level = Level.ACTIVE;
                return true;
            }
            return false;
        }
        if (level == Level.ACTIVE) {
            if (nearestLive <= demoteRadius) {
                farSince = -1;
                return false;
            }
            if (farSince < 0) {
                farSince = now;
            }
            if ((now - farSince) >= demoteDelayMs) {
                level = Level.PRESENT;
                farSince = -1;
                return true;
            }
        }
        return false;
    }

    /**
     * The delay to the next tick: ACTIVE at least 300 ms in combat and 1 s otherwise, PRESENT at least 3..5 s
     * (jitter so bots do not tick in step). A brain may ask for longer delays (standing in town).
     * @param jitter 0..1
     */
    public static long delay(Level level, boolean inCombat, long wanted, double jitter) {
        final long min = level == Level.ACTIVE ? (inCombat ? 300 : 1000) : 3000 + Math.round(2000 * jitter);
        return Math.max(min, wanted);
    }
}
