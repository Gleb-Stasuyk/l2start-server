package org.l2start.platform.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Version range: "*", "1.2.3" (exact), "^1.2", "~1.2.3", comparators ">=1.0 <2.0" (space = AND).
 * Partial versions are padded with zeros ("^1" = ">=1.0.0 <2.0.0").
 */
public final class VersionRange {
    private final String text;
    private final List<Predicate<Semver>> parts;

    private VersionRange(String text, List<Predicate<Semver>> parts) {
        this.text = text;
        this.parts = parts;
    }

    public static VersionRange parse(String input) {
        final String text = input == null ? "" : input.trim();
        final List<Predicate<Semver>> parts = new ArrayList<>();
        if (text.isEmpty() || text.equals("*") || text.equalsIgnoreCase("x")) {
            return new VersionRange(text.isEmpty() ? "*" : text, parts);
        }
        for (String token : text.split("\\s+")) {
            parts.addAll(token(token));
        }
        return new VersionRange(text, parts);
    }

    private static List<Predicate<Semver>> token(String t) {
        final List<Predicate<Semver>> out = new ArrayList<>();
        if (t.startsWith("^")) {
            final int[] v = partial(t.substring(1));
            final Semver lo = new Semver(v[0], v[1], v[2], null);
            final Semver hi;
            if (v[0] > 0 || v[3] == 1) {
                hi = new Semver(v[0] + 1, 0, 0, null);
            } else if (v[1] > 0 || v[3] == 2) {
                hi = new Semver(0, v[1] + 1, 0, null);
            } else {
                hi = new Semver(0, 0, v[2] + 1, null);
            }
            out.add(s -> s.compareTo(lo) >= 0);
            out.add(s -> s.compareTo(hi) < 0);
        } else if (t.startsWith("~")) {
            final int[] v = partial(t.substring(1));
            final Semver lo = new Semver(v[0], v[1], v[2], null);
            final Semver hi = v[3] == 1 ? new Semver(v[0] + 1, 0, 0, null) : new Semver(v[0], v[1] + 1, 0, null);
            out.add(s -> s.compareTo(lo) >= 0);
            out.add(s -> s.compareTo(hi) < 0);
        } else if (t.startsWith(">=")) {
            final Semver x = full(t.substring(2));
            out.add(s -> s.compareTo(x) >= 0);
        } else if (t.startsWith("<=")) {
            final Semver x = full(t.substring(2));
            out.add(s -> s.compareTo(x) <= 0);
        } else if (t.startsWith(">")) {
            final Semver x = full(t.substring(1));
            out.add(s -> s.compareTo(x) > 0);
        } else if (t.startsWith("<")) {
            final Semver x = full(t.substring(1));
            out.add(s -> s.compareTo(x) < 0);
        } else if (t.startsWith("=")) {
            final Semver x = full(t.substring(1));
            out.add(s -> s.compareTo(x) == 0);
        } else {
            final int[] v = partial(t);
            if (v[3] == 3) {
                final Semver x = Semver.parse(t);
                out.add(s -> s.compareTo(x) == 0);
            } else {
                // "1.2" = "~1.2", "1" = "^1"
                return token((v[3] == 1 ? "^" : "~") + t);
            }
        }
        return out;
    }

    /** Parses "1", "1.2", "1.2.3", "1.2.3-rc.1" -> {major, minor, patch, numberOfParts}. */
    private static int[] partial(String t) {
        final String core = t.contains("-") ? t.substring(0, t.indexOf('-')) : t;
        final String[] p = core.split("\\.");
        if (p.length == 0 || p.length > 3) {
            throw new IllegalArgumentException("bad version range part: '" + t + "'");
        }
        final int[] v = new int[4];
        try {
            for (int i = 0; i < p.length; i++) {
                v[i] = Integer.parseInt(p[i]);
                if (v[i] < 0) {
                    throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad version range part: '" + t + "'");
        }
        v[3] = p.length;
        return v;
    }

    private static Semver full(String t) {
        final int[] v = partial(t);
        if (v[3] == 3) {
            return Semver.parse(t);
        }
        return new Semver(v[0], v[1], v[2], null);
    }

    public boolean matches(Semver v) {
        for (Predicate<Semver> p : parts) {
            if (!p.test(v)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return text;
    }
}
