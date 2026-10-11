package org.l2start.bots.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ShardTest {
    /** A fake nano clock: every tick of a bot costs {@code cost} ns. */
    static final class FakeNanos {
        final AtomicLong t = new AtomicLong();

        long get() {
            return t.get();
        }
    }

    static final class Handler implements TickHandler {
        final FakeNanos nanos;
        final long costNanos;
        final List<Integer> ticked = new ArrayList<>();
        final List<Integer> failed = new ArrayList<>();
        final List<Integer> tooMany = new ArrayList<>();
        int throwFor = -1;
        long delay = 1000;

        Handler(FakeNanos nanos, long costNanos) {
            this.nanos = nanos;
            this.costNanos = costNanos;
        }

        @Override
        public long tick(BotSlot slot, long now) {
            nanos.t.addAndGet(costNanos);
            ticked.add(slot.botId());
            if (slot.botId() == throwFor) {
                throw new IllegalStateException("boom " + slot.botId());
            }
            return delay;
        }

        @Override
        public void failed(BotSlot slot, Throwable error, boolean many) {
            failed.add(slot.botId());
            if (many) {
                tooMany.add(slot.botId());
            }
        }
    }

    @Test
    void ticksOnlyDueBotsAndSchedulesNext() {
        final FakeNanos n = new FakeNanos();
        final Handler h = new Handler(n, 1000);
        final Shard s = new Shard(0, h, 25, n::get);
        final BotSlot a = new BotSlot(1, "a", null, 0);
        final BotSlot b = new BotSlot(2, "b", null, 500);
        s.add(a);
        s.add(b);
        s.wave(100);
        assertEquals(List.of(1), h.ticked);
        assertEquals(1100, a.nextTickAt());
        s.wave(600);
        assertEquals(List.of(1, 2), h.ticked);
        s.wave(700);
        assertEquals(2, h.ticked.size());
        assertEquals(2, s.ticks());
    }

    @Test
    void budgetOverrunMovesTheRestToTheNextWave() {
        final FakeNanos n = new FakeNanos();
        final Handler h = new Handler(n, 5_000_000); // 5 ms a tick, budget 25 ms -> 5 bots a wave
        final Shard s = new Shard(0, h, 25, n::get);
        for (int i = 0; i < 12; i++) {
            s.add(new BotSlot(i, "b" + i, null, 0));
        }
        s.wave(0);
        assertEquals(5, h.ticked.size());
        assertEquals(1, s.overruns());
        s.wave(100);
        assertEquals(10, h.ticked.size());
        s.wave(200);
        assertEquals(12, h.ticked.size()); // everyone got a tick within 3 waves
        assertEquals(12, h.ticked.stream().distinct().count());
        assertEquals(2, s.overruns());
    }

    @Test
    void exceptionIsolatedSafeIdleThenLogoutAfterTen() {
        final FakeNanos n = new FakeNanos();
        final Handler h = new Handler(n, 1000);
        h.delay = 100;
        h.throwFor = 2;
        final Shard s = new Shard(0, h, 25, n::get);
        final BotSlot good = new BotSlot(1, "good", null, 0);
        final BotSlot bad = new BotSlot(2, "bad", null, 0);
        s.add(good);
        s.add(bad);
        s.wave(0);
        assertEquals(List.of(2), h.failed);
        assertTrue(bad.safeIdle(1000));
        assertEquals(BotSlot.SAFE_IDLE_MS, bad.nextTickAt());
        // the good one keeps ticking during the bad one's safe idle
        long now = 0;
        for (int i = 0; i < 50; i++) {
            now += 200;
            s.wave(now);
        }
        assertEquals(1, h.failed.size());
        assertTrue(h.ticked.stream().filter(x -> x == 1).count() >= 40);
        // an event does not wake a safe-idle bot early
        bad.wake(now + 10);
        assertEquals(BotSlot.SAFE_IDLE_MS, bad.nextTickAt());
        // 10 errors within 10 minutes -> tooMany
        for (int i = 0; i < 9; i++) {
            now = bad.nextTickAt();
            s.wave(now);
        }
        assertEquals(10, h.failed.size());
        assertEquals(List.of(2), h.tooMany);
        assertEquals(10, s.errors());
        assertFalse(h.failed.contains(1));
    }

    @Test
    void errorsOutsideTheWindowAreForgotten() {
        final BotSlot s = new BotSlot(1, "x", null, 0);
        long now = 0;
        for (int i = 0; i < 9; i++) {
            assertFalse(s.failed(now, new RuntimeException()));
            now += 61_000;
        }
        now += 600_000;
        assertFalse(s.failed(now, new RuntimeException()));
    }

    @Test
    void wakeOnlyMovesEarlier() {
        final BotSlot s = new BotSlot(1, "x", null, 5000);
        s.wake(9000);
        assertEquals(5000, s.nextTickAt());
        s.wake(1200);
        assertEquals(1200, s.nextTickAt());
    }

    @Test
    void tickerShardsByIdPauseAndRemove() {
        final FakeNanos n = new FakeNanos();
        final Handler h = new Handler(n, 1000);
        final Ticker t = new Ticker(3, h, 25, n::get);
        assertEquals(2, Ticker.autoShards(4));
        assertEquals(3, Ticker.autoShards(12));
        for (int i = 0; i < 9; i++) {
            t.add(new BotSlot(i, "b" + i, null, 0));
        }
        assertEquals(3, t.shard(0).size());
        assertEquals(t.shard(2), t.shardOf(5));
        t.setPaused(true);
        for (int i = 0; i < 3; i++) {
            t.wave(i, 0);
        }
        assertEquals(0, h.ticked.size());
        t.setPaused(false);
        final BotSlot gone = t.shard(1).slots().get(0);
        t.remove(gone);
        assertTrue(gone.removed());
        for (int i = 0; i < 3; i++) {
            t.wave(i, 0);
        }
        assertEquals(8, h.ticked.size());
        assertEquals(8, t.size());
        assertTrue(t.percentileNanos(99) > 0);
    }
}
