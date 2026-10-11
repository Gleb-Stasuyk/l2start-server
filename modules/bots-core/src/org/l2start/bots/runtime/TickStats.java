package org.l2start.bots.runtime;

import java.util.Arrays;

/** Durations of the last ticks (ring buffer) for p50/p99/max. Thread-safe. */
public final class TickStats {
    private final long[] ring;
    private int pos;
    private int filled;

    public TickStats(int size) {
        ring = new long[size];
    }

    public synchronized void add(long nanos) {
        ring[pos] = nanos;
        pos = (pos + 1) % ring.length;
        if (filled < ring.length) {
            filled++;
        }
    }

    /** @param p 0..100 @return nanos (0 when empty) */
    public synchronized long percentile(double p) {
        if (filled == 0) {
            return 0;
        }
        final long[] copy = Arrays.copyOf(ring, filled);
        Arrays.sort(copy);
        final int i = (int) Math.min(filled - 1, Math.max(0, Math.ceil((p / 100.0) * filled) - 1));
        return copy[i];
    }

    public synchronized int count() {
        return filled;
    }
}
