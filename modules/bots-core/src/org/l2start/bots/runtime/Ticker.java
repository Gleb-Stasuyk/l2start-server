package org.l2start.bots.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** N shards; a bot belongs to shard {@code botId % N}. The host runs {@link #wave(int, long)} of every shard periodically. */
public final class Ticker {
    private final Shard[] shards;
    private volatile boolean paused;

    public Ticker(int shardCount, TickHandler handler, long budgetMs, LongSupplier nanoTime) {
        shards = new Shard[Math.max(1, shardCount)];
        for (int i = 0; i < shards.length; i++) {
            shards[i] = new Shard(i, handler, budgetMs, nanoTime);
        }
    }

    /** The plan's default: max(2, cores / 4). */
    public static int autoShards(int cores) {
        return Math.max(2, cores / 4);
    }

    public Shard shardOf(int botId) {
        return shards[Math.floorMod(botId, shards.length)];
    }

    public void add(BotSlot slot) {
        shardOf(slot.botId()).add(slot);
    }

    public void remove(BotSlot slot) {
        slot.markRemoved();
        shardOf(slot.botId()).remove(slot);
    }

    public void wave(int shard, long now) {
        if (!paused) {
            shards[shard].wave(now);
        }
    }

    public int shardCount() {
        return shards.length;
    }

    public Shard shard(int i) {
        return shards[i];
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean p) {
        paused = p;
    }

    public List<BotSlot> slots() {
        final List<BotSlot> l = new ArrayList<>();
        for (Shard s : shards) {
            l.addAll(s.slots());
        }
        return l;
    }

    public int size() {
        int n = 0;
        for (Shard s : shards) {
            n += s.size();
        }
        return n;
    }

    public long ticks() {
        long n = 0;
        for (Shard s : shards) {
            n += s.ticks();
        }
        return n;
    }

    public long waves() {
        long n = 0;
        for (Shard s : shards) {
            n += s.waves();
        }
        return n;
    }

    public long overruns() {
        long n = 0;
        for (Shard s : shards) {
            n += s.overruns();
        }
        return n;
    }

    public long errors() {
        long n = 0;
        for (Shard s : shards) {
            n += s.errors();
        }
        return n;
    }

    public long busyNanos() {
        long n = 0;
        for (Shard s : shards) {
            n += s.busyNanos();
        }
        return n;
    }

    /** p-th percentile of tick time over all shards (approximation: the max of the shards' percentiles). */
    public long percentileNanos(double p) {
        long v = 0;
        for (Shard s : shards) {
            v = Math.max(v, s.stats().percentile(p));
        }
        return v;
    }
}
