package org.l2start.bots.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A group of bots ticked by one periodic task ("wave", every 100 ms). A wave ticks the bots whose time has come,
 * within a time budget (25 ms); who did not fit waits for the next wave (overrun). One bot's exception never stops
 * the wave. No ScheduledFuture per bot.
 */
public final class Shard {
    private final int index;
    private final TickHandler handler;
    private final long budgetNanos;
    private final LongSupplier nanoTime;
    private final List<BotSlot> slots = new CopyOnWriteArrayList<>();
    private final TickStats stats = new TickStats(2048);
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong waves = new AtomicLong();
    private final AtomicLong overruns = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong busyNanos = new AtomicLong();
    private int rotate;

    public Shard(int index, TickHandler handler, long budgetMs, LongSupplier nanoTime) {
        this.index = index;
        this.handler = handler;
        budgetNanos = budgetMs * 1_000_000L;
        this.nanoTime = nanoTime;
    }

    public void add(BotSlot slot) {
        slots.add(slot);
    }

    public void remove(BotSlot slot) {
        slots.remove(slot);
    }

    public int size() {
        return slots.size();
    }

    public List<BotSlot> slots() {
        return slots;
    }

    /** One wave. Only one thread runs waves of a shard at a time (the shard's periodic task). */
    public synchronized void wave(long now) {
        waves.incrementAndGet();
        final int n = slots.size();
        if (n == 0) {
            return;
        }
        final long start = nanoTime.getAsLong();
        final long deadline = start + budgetNanos;
        final int first = rotate++ % n; // fair: who comes first changes every wave
        boolean overrun = false;
        for (int k = 0; k < n; k++) {
            final BotSlot slot;
            try {
                slot = slots.get((first + k) % n);
            } catch (IndexOutOfBoundsException e) {
                break; // a bot left during the wave
            }
            if (slot.removed() || (slot.nextTickAt() > now)) {
                continue;
            }
            if (nanoTime.getAsLong() >= deadline) {
                overrun = true;
                break;
            }
            runOne(slot, now);
        }
        if (overrun) {
            overruns.incrementAndGet();
        }
        busyNanos.addAndGet(nanoTime.getAsLong() - start);
    }

    private void runOne(BotSlot slot, long now) {
        final long t0 = nanoTime.getAsLong();
        try {
            final long delay = handler.tick(slot, now);
            slot.setNextTickAt(now + Math.max(50, delay));
        } catch (Throwable t) {
            errors.incrementAndGet();
            final boolean tooMany = slot.failed(now, t);
            try {
                handler.failed(slot, t, tooMany);
            } catch (Throwable ignored) {
                // the handler must not break the wave either
            }
        } finally {
            final long d = nanoTime.getAsLong() - t0;
            slot.ticked(d);
            stats.add(d);
            ticks.incrementAndGet();
        }
    }

    public int index() { return index; }
    public TickStats stats() { return stats; }
    public long ticks() { return ticks.get(); }
    public long waves() { return waves.get(); }
    public long overruns() { return overruns.get(); }
    public long errors() { return errors.get(); }
    public long busyNanos() { return busyNanos.get(); }
}
