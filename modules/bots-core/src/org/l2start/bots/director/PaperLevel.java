package org.l2start.bots.director;

/**
 * A persona's level "on paper" (phase 4e, x1): it keeps growing while the bot is out of the world, as if the person
 * played somewhere else, by the hours it plays a day (its chronotype). Hours of play per level: 0.6 (below 20), 2.55
 * (below 40), 5 (below 52), 11.3 (below 61), 25 (below 76), 80 (76+). With ~3 hours a day (the average persona) that is
 * level 40 in about three weeks, 61 in two and a half months, 76 in half a year (approved by Gleb, plan 4 question 3).
 * Times the persona's own pace (0.5..1.5) and the global PaperLevelPace.
 */
public final class PaperLevel {
    public static final int MAX = 80;
    static final long DAY = 86_400_000L;
    /** Hours a day of a persona without a chronotype (the mean of the default mix). */
    public static final double DEFAULT_HOURS = 3.0;

    /** Global pace (config PaperLevelPace of bots-core), 1 = as above. */
    private static volatile double globalPace = 1;

    private PaperLevel() {
    }

    public static void setGlobalPace(double p) {
        globalPace = Math.max(0.05, Math.min(20, p));
    }

    public static double globalPace() {
        return globalPace;
    }

    /** Hours of play one level takes at this level (pace 1). */
    static double hoursPerLevel(int level) {
        if (level < 20) {
            return 0.6;
        }
        if (level < 40) {
            return 2.55;
        }
        if (level < 52) {
            return 5;
        }
        if (level < 61) {
            return 11.3;
        }
        if (level < 76) {
            return 25;
        }
        return 80;
    }

    /** As {@link #at(int, long, double, long, double)} with {@link #DEFAULT_HOURS} a day. */
    public static int at(int base, long baseAt, double pace, long now) {
        return at(base, baseAt, pace, now, DEFAULT_HOURS);
    }

    /**
     * @param base level at {@code baseAt}
     * @param pace the persona's pace (0.5..1.5)
     * @param hoursPerDay hours the persona plays a day (chronotype)
     * @return the level now (never below {@code base}, at most {@link #MAX})
     */
    public static int at(int base, long baseAt, double pace, long now, double hoursPerDay) {
        int level = Math.max(1, Math.min(MAX, base));
        double hours = (Math.max(0, now - baseAt) / (double) DAY) * Math.max(0, hoursPerDay) * Math.max(0.05, pace) * globalPace;
        while (level < MAX) {
            final double need = hoursPerLevel(level);
            if (hours < need) {
                break;
            }
            hours -= need;
            level++;
        }
        return level;
    }

    /** Days of play (at {@code hoursPerDay}, pace 1) from level {@code from} to {@code to}: for docs and tests. */
    public static double days(int from, int to, double hoursPerDay) {
        double h = 0;
        for (int l = Math.max(1, from); l < Math.min(MAX, to); l++) {
            h += hoursPerLevel(l);
        }
        return h / hoursPerDay;
    }

    /** The persona's pace from its seed: 0.5..1.5, stable. */
    public static double pace(long seed) {
        return 0.5 + (Math.floorMod(seed * 0x9E3779B97F4A7C15L, 1000) / 1000.0);
    }
}
