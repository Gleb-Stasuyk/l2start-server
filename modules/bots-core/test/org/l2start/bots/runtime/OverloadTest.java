package org.l2start.bots.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OverloadTest {
    /** Feeds 10 s samples: 100 waves each, {@code overrunsPer} of them overran. */
    private static long feed(Overload o, long from, long forMs, long overrunsPer, long[] counters) {
        long t = from;
        for (; t < (from + forMs); t += 10_000) {
            counters[0] += 100;
            counters[1] += overrunsPer;
            o.sample(t, counters[0], counters[1]);
        }
        return t;
    }

    @Test
    void stepsUpUnderOverloadAndBackWhenCalm() {
        final Overload o = new Overload();
        final long[] c = new long[2];
        long t = feed(o, 0, 60_000, 5, c); // 5 % overruns: fine
        assertEquals(Overload.Step.NORMAL, o.step());
        t = feed(o, t, 40_000, 30, c); // 30 % for 40 s: far bots slower
        assertEquals(Overload.Step.SLOW_FAR, o.step());
        assertEquals(1.0, o.density());
        t = feed(o, t, 40_000, 30, c); // still overloaded: fewer bots
        assertEquals(Overload.Step.LESS_DENSITY, o.step());
        assertEquals(0.7, o.density(), 1e-9);
        t = feed(o, t, 100_000, 0, c); // calm, but not 2 min yet
        assertEquals(Overload.Step.LESS_DENSITY, o.step());
        t = feed(o, t, 40_000, 0, c);
        assertEquals(Overload.Step.SLOW_FAR, o.step());
        feed(o, t, 130_000, 0, c);
        assertEquals(Overload.Step.NORMAL, o.step());
    }

    @Test
    void shortSpikeDoesNothing() {
        final Overload o = new Overload();
        final long[] c = new long[2];
        long t = feed(o, 0, 20_000, 0, c);
        t = feed(o, t, 20_000, 80, c); // 20 s of spike < 30 s
        feed(o, t, 60_000, 0, c);
        assertEquals(Overload.Step.NORMAL, o.step());
    }

    @Test
    void farActiveBotsThinkHalfAsOftenOnlyWhenOverloaded() {
        final Overload o = new Overload();
        assertEquals(1000, o.delay(1000, true, 4000));
        assertTrue(o.force(Overload.Step.SLOW_FAR));
        assertEquals(2000, o.delay(1000, true, 4000));
        assertEquals(1000, o.delay(1000, true, 1000)); // near a player: as before
        assertEquals(4000, o.delay(4000, false, 9000)); // present bots: already slow
        assertFalse(o.force(Overload.Step.SLOW_FAR));
        assertTrue(o.force(null));
        assertEquals(Overload.Step.NORMAL, o.step());
    }

    @Test
    void configValues() {
        assertNull(Overload.parse("auto"));
        assertEquals(Overload.Step.LESS_DENSITY, Overload.parse(" Cut "));
        assertEquals(Overload.Step.SLOW_FAR, Overload.parse("slow"));
        assertEquals(Overload.Step.NORMAL, Overload.parse("normal"));
        assertThrows(IllegalArgumentException.class, () -> Overload.parse("x"));
    }
}
