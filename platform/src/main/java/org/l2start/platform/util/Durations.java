package org.l2start.platform.util;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "500ms", "30s", "10m", "2h", "1d"; a bare number means milliseconds. */
public final class Durations {
    private static final Pattern P = Pattern.compile("^(\\d+)\\s*(ms|s|m|h|d)?$", Pattern.CASE_INSENSITIVE);

    private Durations() {
    }

    public static Duration parse(String text) {
        final Matcher m = P.matcher(text == null ? "" : text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a duration: '" + text + "' (use e.g. 500ms, 30s, 10m, 2h, 1d)");
        }
        final long n = Long.parseLong(m.group(1));
        final String unit = m.group(2) == null ? "ms" : m.group(2).toLowerCase();
        switch (unit) {
            case "s": return Duration.ofSeconds(n);
            case "m": return Duration.ofMinutes(n);
            case "h": return Duration.ofHours(n);
            case "d": return Duration.ofDays(n);
            default: return Duration.ofMillis(n);
        }
    }
}
