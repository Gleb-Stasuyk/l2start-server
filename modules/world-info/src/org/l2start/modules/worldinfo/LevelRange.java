package org.l2start.modules.worldinfo;

import java.util.Arrays;

/** Level range of the monsters around a point: low..high percentile of their levels (pure, tested). */
final class LevelRange {
    private LevelRange() {
    }

    /** @return "a-b" (or "a" when equal), null when fewer than {@code minCount} levels */
    static String of(int[] levels, int minCount, int lowPct, int highPct) {
        if ((levels == null) || (levels.length < Math.max(1, minCount))) {
            return null;
        }
        final int[] s = levels.clone();
        Arrays.sort(s);
        final int lo = s[index(s.length, lowPct)];
        final int hi = s[index(s.length, highPct)];
        return lo == hi ? String.valueOf(lo) : lo + "-" + hi;
    }

    private static int index(int n, int pct) {
        return (int) Math.min(n - 1, Math.max(0, Math.round(((n - 1) * pct) / 100.0)));
    }

    /** Name with the range in brackets; unchanged without a range. */
    static String label(String name, String range) {
        return ((range == null) || range.isEmpty()) ? name : name + " (" + range + ")";
    }
}
