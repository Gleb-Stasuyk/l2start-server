package org.l2start.platform.runtime;

import java.util.ArrayDeque;
import java.util.Deque;

/** Trips once when threshold errors happen within a sliding window. */
public final class CircuitBreaker {
    private final int threshold;
    private final long windowMillis;
    private final Deque<Long> times = new ArrayDeque<>();
    private long total;
    private boolean tripped;

    public CircuitBreaker(int threshold, long windowMillis) {
        this.threshold = Math.max(1, threshold);
        this.windowMillis = Math.max(1, windowMillis);
    }

    /** @return true exactly once: when this failure trips the breaker */
    public synchronized boolean recordFailure(long now) {
        total++;
        times.addLast(now);
        prune(now);
        if (!tripped && times.size() >= threshold) {
            tripped = true;
            return true;
        }
        return false;
    }

    public synchronized int errorsInWindow(long now) {
        prune(now);
        return times.size();
    }

    public synchronized long total() {
        return total;
    }

    public synchronized boolean isTripped() {
        return tripped;
    }

    public int threshold() {
        return threshold;
    }

    public long windowMillis() {
        return windowMillis;
    }

    private void prune(long now) {
        while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
            times.removeFirst();
        }
    }
}
