package org.l2start.bots.runtime;

import java.util.Locale;

/**
 * Overload guard of the bots (no Mobius, tested offline). Fed every few seconds with the wave and overrun counters
 * of the shards. More than {@link #BAD_SHARE} of the waves overran for {@link #BAD_MS} = one step up: first
 * {@link Step#SLOW_FAR} (active bots far from live players think half as often), then {@link Step#LESS_DENSITY} (the
 * director wants 30 % fewer bots). {@link #CALM_MS} without overload = one step back. A forced step (config
 * {@code Overload}) wins over the measured one (load tests, checks).
 */
public final class Overload {
    public enum Step { NORMAL, SLOW_FAR, LESS_DENSITY }

    public static final double BAD_SHARE = 0.10;
    public static final long BAD_MS = 30_000;
    public static final long CALM_MS = 120_000;
    /** Density of the director at {@link Step#LESS_DENSITY}. */
    public static final double LESS = 0.7;
    /** Active bots farther than this from every live player tick half as often at {@link Step#SLOW_FAR}. */
    public static final double FAR = 2500;

    private Step measured = Step.NORMAL;
    private Step forced;
    private boolean primed;
    private long lastWaves;
    private long lastOverruns;
    private long badSince = -1;
    private long calmSince = -1;
    private double lastShare;

    /** @return true when the effective step changed */
    public synchronized boolean sample(long now, long waves, long overruns) {
        final Step before = step();
        final long dw = waves - lastWaves;
        final long dov = overruns - lastOverruns;
        lastWaves = waves;
        lastOverruns = overruns;
        if (!primed) {
            primed = true;
            return false;
        }
        lastShare = dw > 0 ? (double) dov / dw : 0;
        if (lastShare > BAD_SHARE) {
            calmSince = -1;
            if (badSince < 0) {
                badSince = now;
            }
            if (((now - badSince) >= BAD_MS) && (measured != Step.LESS_DENSITY)) {
                measured = Step.values()[measured.ordinal() + 1];
                badSince = now;
            }
        } else {
            badSince = -1;
            if (calmSince < 0) {
                calmSince = now;
            }
            if (((now - calmSince) >= CALM_MS) && (measured != Step.NORMAL)) {
                measured = Step.values()[measured.ordinal() - 1];
                calmSince = now;
            }
        }
        return step() != before;
    }

    /** @param s null = measured (auto) */
    public synchronized boolean force(Step s) {
        final Step before = step();
        forced = s;
        return step() != before;
    }

    public synchronized Step step() {
        return forced != null ? forced : measured;
    }

    public synchronized boolean forced() {
        return forced != null;
    }

    /** Director density factor for the step. */
    public double density() {
        return step() == Step.LESS_DENSITY ? LESS : 1;
    }

    /** Tick delay for an active bot {@code nearestLive} away from live players. */
    public long delay(long delay, boolean active, double nearestLive) {
        return (active && (step() != Step.NORMAL) && (nearestLive > FAR)) ? delay * 2 : delay;
    }

    /** Config value: auto | normal | slow | cut. */
    public static Step parse(String v) {
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "", "auto" -> null;
            case "normal", "off" -> Step.NORMAL;
            case "slow" -> Step.SLOW_FAR;
            case "cut" -> Step.LESS_DENSITY;
            default -> throw new IllegalArgumentException("Overload: auto, normal, slow or cut expected, got " + v);
        };
    }

    public synchronized String describe() {
        return String.format(Locale.ROOT, "%s%s (overrun %.0f%% of waves)", step(), forced != null ? " forced" : "", lastShare * 100);
    }
}
