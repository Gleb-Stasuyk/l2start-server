package org.l2start.bots.director;

/**
 * Settings of the presence director (module keys Director*, see module.json).
 * @param onlineCurve 24 values 0..1 by hour of the server's day (0 = night minimum, 0.5 = day, 1 = evening peak)
 * @param transitMinMs gatekeeper transit in a big town at the evening peak: one bot every min..max (0 = off); divided by
 *     the online curve (day: less often), times {@code transitStarterFactor} in the starting villages
 * @param passMinMs a passer-by on a road every min..max at a usual hour (evening: more often)
 * @param townWanderersMultiplier scales town targets, hunt caps/ground targets, and passer frequency (module default 2.0;
 *     unit-test {@link #defaults()} keeps 1.0)
 */
public record DirectorConfig(int clusterLink, int groundReach, int townMargin, int hiddenMin, int hiddenMax, int seenRadius, int unseenRadius,
    long unseenLogoutMs, long lingerMinMs, long lingerMaxMs, int[] townNight, int[] townDay, int[] townEvening, int groundMin, int groundMax,
    double indoorFactor, int huntPerCluster, int huntPerExtraPlayer, int maxPerCluster, int maxInWorld, long spawnIntervalMinMs, long spawnIntervalMaxMs,
    long leaveIntervalMs, long returnCooldownMs, long pendingTimeoutMs, double[] onlineCurve, long transitMinMs, long transitMaxMs,
    double transitStarterFactor, long passMinMs, long passMaxMs, double townWanderersMultiplier) {

    public static double[] defaultCurve() {
        return new double[] { 0.55, 0.4, 0.3, 0.2, 0.1, 0.1, 0.15, 0.25, 0.35, 0.45, 0.5, 0.5, 0.55, 0.55, 0.55, 0.55, 0.6, 0.7, 0.8, 0.9, 1, 1, 0.9, 0.75 };
    }

    /** Baseline for unit tests (multiplier 1.0). Production uses {@code TownWanderersMultiplier} from module.json (2.0). */
    public static DirectorConfig defaults() {
        return new DirectorConfig(6000, 2500, 300, 3200, 4200, 3000, 4500, 90_000, 20 * 60_000L, 90 * 60_000L, new int[] { 2, 4 }, new int[] { 5, 10 },
            new int[] { 10, 20 }, 2, 5, 0.5, 6, 3, 30, 150, 4000, 8000, 20_000, 10 * 60_000L, 30_000, defaultCurve(), 20_000, 40_000, 4.0,
            120_000, 300_000, 1.0);
    }

    public double curve(int hour) {
        return onlineCurve[Math.floorMod(hour, 24) % onlineCurve.length];
    }

    /** Clamped wanderer multiplier (≥0.1). */
    public double wanderMult() {
        return Math.max(0.1, townWanderersMultiplier);
    }
}
