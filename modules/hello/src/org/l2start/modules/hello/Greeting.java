package org.l2start.modules.hello;

import java.time.LocalDate;

/** Pure logic of the .hello counter (unit-tested without a server). */
public final class Greeting {
    private Greeting() {
    }

    /** Stored counter of one character. */
    public record Counter(int day, int count, int total) {
    }

    /** yyyymmdd */
    public static int day(LocalDate date) {
        return (date.getYear() * 10000) + (date.getMonthValue() * 100) + date.getDayOfMonth();
    }

    /**
     * Next counter value after one more .hello, or null when the daily limit is reached.
     * A new day resets the daily count, the total keeps growing.
     */
    public static Counter next(Counter stored, int today, int maxPerDay) {
        final int count = (stored == null || stored.day() != today) ? 0 : stored.count();
        final int total = stored == null ? 0 : stored.total();
        if (count >= maxPerDay) {
            return null;
        }
        return new Counter(today, count + 1, total + 1);
    }
}
