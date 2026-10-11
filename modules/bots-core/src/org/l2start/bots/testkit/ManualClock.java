package org.l2start.bots.testkit;

import org.l2start.bots.runtime.BotClock;

/** A clock that moves only when told (tests). */
public final class ManualClock implements BotClock {
    private long now;

    public ManualClock(long start) {
        now = start;
    }

    @Override
    public long now() {
        return now;
    }

    public void advance(long ms) {
        now += ms;
    }

    public void set(long t) {
        now = t;
    }
}
