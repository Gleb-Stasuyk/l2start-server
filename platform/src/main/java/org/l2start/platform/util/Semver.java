package org.l2start.platform.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic version MAJOR.MINOR.PATCH[-prerelease][+build] (semver 2.0 ordering, build metadata ignored). */
public final class Semver implements Comparable<Semver> {
    private static final Pattern P = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    private final int major;
    private final int minor;
    private final int patch;
    private final String pre; // null = release

    public Semver(int major, int minor, int patch, String pre) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.pre = (pre == null || pre.isEmpty()) ? null : pre;
    }

    public static Semver parse(String text) {
        final Matcher m = P.matcher(text == null ? "" : text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a semantic version: '" + text + "' (expected MAJOR.MINOR.PATCH)");
        }
        return new Semver(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.group(4));
    }

    public static boolean isValid(String text) {
        return text != null && P.matcher(text.trim()).matches();
    }

    public int major() { return major; }
    public int minor() { return minor; }
    public int patch() { return patch; }
    public String pre() { return pre; }
    public boolean isPrerelease() { return pre != null; }

    @Override
    public int compareTo(Semver o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        if (pre == null) return o.pre == null ? 0 : 1;
        if (o.pre == null) return -1;
        final String[] a = pre.split("\\.");
        final String[] b = o.pre.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            final boolean na = a[i].matches("\\d+");
            final boolean nb = b[i].matches("\\d+");
            if (na && nb) {
                c = Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]));
            } else if (na) {
                c = -1;
            } else if (nb) {
                c = 1;
            } else {
                c = a[i].compareTo(b[i]);
            }
            if (c != 0) return c;
        }
        return Integer.compare(a.length, b.length);
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof Semver) && compareTo((Semver) o) == 0;
    }

    @Override
    public int hashCode() {
        return (major * 31 + minor) * 31 + patch + (pre == null ? 0 : pre.hashCode());
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (pre == null ? "" : "-" + pre);
    }
}
