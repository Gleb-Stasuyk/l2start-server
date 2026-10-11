package org.l2start.bots.director;

import java.util.Random;

/**
 * How long a dead bot lies before "To village" (phase 4d), by the persona's patience: the impatient press it at once,
 * the patient lie for a while as if waiting for a resurrection, the rest take DeathReturnMin..Max.
 */
public final class DeathWait {
    private DeathWait() {
    }

    public static long ms(double patience, long min, long max, Random rnd) {
        if (patience < 0.3) {
            return 2000 + rnd.nextInt(6000);
        }
        if (patience > 0.75) {
            return 60_000 + rnd.nextInt(120_000);
        }
        final long hi = Math.max(min, max);
        return min + (hi > min ? (long) (rnd.nextDouble() * (hi - min)) : 0);
    }
}
