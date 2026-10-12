package org.l2start.economy.market.model;

import java.util.List;

/**
 * When bot traders stand in a town (phase 5b, like the presence director): while a real player is near the market,
 * and a little longer after the last one left (so a player who steps out and back sees the same stores). At night
 * part of the traders stay as an "offline store" (grey name, like the stock offline trade), the others are gone.
 */
public final class TraderPresence {
    private TraderPresence() {
    }

    /** A watcher (x, y, z) within {@code radius} of the market centre (2D). */
    public static boolean watched(int cx, int cy, List<int[]> watchers, int radius) {
        final long r2 = (long) radius * radius;
        for (int[] w : watchers) {
            final long dx = w[0] - cx;
            final long dy = w[1] - cy;
            if (((dx * dx) + (dy * dy)) <= r2) {
                return true;
            }
        }
        return false;
    }

    /** Stores stay while watched and {@code lingerMs} after the last watcher left. */
    public static boolean present(long now, long lastWatched, long lingerMs) {
        return (lastWatched > 0) && ((now - lastWatched) <= lingerMs);
    }

    /** Night hours (server time): 1:00-7:59. */
    public static boolean night(int hour) {
        return (hour >= 1) && (hour < 8);
    }

    /**
     * At night: does this trader keep its store open (as an offline store)? Decided once per trader and night (same
     * answer all night), {@code share} of them stay.
     */
    public static boolean staysAtNight(int botId, long epochDay, double share) {
        long z = (botId * 0x9E3779B97F4A7C15L) ^ (epochDay * 0xBF58476D1CE4E5B9L);
        z = (z ^ (z >>> 31)) * 0x94D049BB133111EBL;
        z ^= z >>> 29;
        return ((z >>> 11) / (double) (1L << 53)) < share;
    }

    /** Store kind for a new trader of a place: a sell place sells bulk goods as a package now and then. */
    public static String kind(String hint, String profile, double roll) {
        if ("BUY".equalsIgnoreCase(hint)) {
            return "BUY";
        }
        if ("CRAFT".equalsIgnoreCase(hint)) {
            return "CRAFT";
        }
        if (("materials".equals(profile) || "shots".equals(profile)) && (roll < 0.2)) {
            return "PACKAGE";
        }
        return "SELL";
    }

    /** The trader's level: 40-64 (a 2nd profession), fixed by its id. */
    public static int level(int botId) {
        return 40 + Math.floorMod(botId * 7, 25);
    }
}
