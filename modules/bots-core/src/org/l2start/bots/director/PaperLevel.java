package org.l2start.bots.director;

/**
 * A persona's level "on paper": it keeps growing over real time while the bot is out of the world, as if the
 * person played somewhere else. Levels per day: 4 (below 20), 2 (below 40), 1 (below 52), 0.5 (below 61),
 * 0.25 (below 76), 0.05 (76+), times the persona's own pace (0.5..1.5).
 */
public final class PaperLevel {
    public static final int MAX = 80;
    static final long DAY = 86_400_000L;

    private PaperLevel() {
    }

    static double perDay(int level) {
        if (level < 20) {
            return 4;
        }
        if (level < 40) {
            return 2;
        }
        if (level < 52) {
            return 1;
        }
        if (level < 61) {
            return 0.5;
        }
        if (level < 76) {
            return 0.25;
        }
        return 0.05;
    }

    /**
     * @param base level at {@code baseAt}
     * @param pace the persona's pace (0.5..1.5)
     * @return the level now (never below {@code base}, at most {@link #MAX})
     */
    public static int at(int base, long baseAt, double pace, long now) {
        int level = Math.max(1, Math.min(MAX, base));
        double days = Math.max(0, now - baseAt) / (double) DAY;
        while (level < MAX) {
            final double need = 1 / (perDay(level) * Math.max(0.05, pace));
            if (days < need) {
                break;
            }
            days -= need;
            level++;
        }
        return level;
    }

    /** The persona's pace from its seed: 0.5..1.5, stable. */
    public static double pace(long seed) {
        return 0.5 + (Math.floorMod(seed * 0x9E3779B97F4A7C15L, 1000) / 1000.0);
    }
}
