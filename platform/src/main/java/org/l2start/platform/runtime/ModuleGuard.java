package org.l2start.platform.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps every call into module code at runtime (listeners, commands, tasks): catches everything, counts errors,
 * measures time, warns about slow calls, trips the circuit breaker. A module that is not active is not called.
 */
public final class ModuleGuard {
    private static final Logger LOGGER = Logger.getLogger("Platform");
    private static final int LOGS_PER_MINUTE = 5;

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private final String moduleId;
    private final CircuitBreaker breaker;
    private final long slowNanos;
    private final BooleanSupplier active;
    private final Consumer<String> onTrip;
    private final LongSupplier clock;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();
    private final AtomicLong slow = new AtomicLong();
    private final Deque<String> recentErrors = new ArrayDeque<>();
    private long logWindowStart;
    private int logsInWindow;

    public ModuleGuard(String moduleId, CircuitBreaker breaker, long slowNanos, BooleanSupplier active, Consumer<String> onTrip, LongSupplier clock) {
        this.moduleId = moduleId;
        this.breaker = breaker;
        this.slowNanos = slowNanos;
        this.active = active;
        this.onTrip = onTrip;
        this.clock = clock;
    }

    public void run(String point, ThrowingRunnable r) {
        call(point, () -> {
            r.run();
            return null;
        }, null);
    }

    public <T> T call(String point, Callable<T> c, T fallback) {
        if (!active.getAsBoolean()) {
            return fallback;
        }
        final long start = System.nanoTime();
        try {
            return c.call();
        } catch (Throwable t) {
            failure(point, t);
            return fallback;
        } finally {
            final long spent = System.nanoTime() - start;
            calls.incrementAndGet();
            nanos.addAndGet(spent);
            if (spent > slowNanos) {
                slow.incrementAndGet();
                if (allowLog()) {
                    LOGGER.warning("Platform: module " + moduleId + ": slow call " + point + " took " + (spent / 1_000_000) + " ms");
                }
            }
        }
    }

    /** Records an error that happened in module code. */
    public void failure(String point, Throwable t) {
        final long now = clock.getAsLong();
        synchronized (recentErrors) {
            recentErrors.addFirst(String.format("%tT %s: %s", now, point, t));
            while (recentErrors.size() > 5) {
                recentErrors.removeLast();
            }
        }
        if (allowLog()) {
            LOGGER.log(Level.WARNING, "Platform: module " + moduleId + ": error in " + point + " (" + (breaker.errorsInWindow(now) + 1) + "/" + breaker.threshold() + " in window)", t);
        }
        if (breaker.recordFailure(now)) {
            onTrip.accept("circuit breaker: " + breaker.threshold() + " errors within " + (breaker.windowMillis() / 1000) + " s, last in " + point + ": " + t);
        }
    }

    private synchronized boolean allowLog() {
        final long now = clock.getAsLong();
        if (now - logWindowStart > 60_000) {
            logWindowStart = now;
            logsInWindow = 0;
        }
        return ++logsInWindow <= LOGS_PER_MINUTE;
    }

    public long calls() {
        return calls.get();
    }

    public long totalMillis() {
        return nanos.get() / 1_000_000;
    }

    public long slowCalls() {
        return slow.get();
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    public List<String> recentErrors() {
        synchronized (recentErrors) {
            return new ArrayList<>(recentErrors);
        }
    }
}
