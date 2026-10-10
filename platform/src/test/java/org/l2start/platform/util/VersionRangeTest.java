package org.l2start.platform.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VersionRangeTest {
    private static boolean m(String range, String version) {
        return VersionRange.parse(range).matches(Semver.parse(version));
    }

    @Test
    void wildcard() {
        assertEquals(true, m("*", "0.0.1"));
        assertEquals(true, m("", "99.0.0"));
    }

    @Test
    void exact() {
        assertEquals(true, m("1.2.3", "1.2.3"));
        assertEquals(false, m("1.2.3", "1.2.4"));
        assertEquals(true, m("=1.2.3", "1.2.3"));
    }

    @Test
    void caret() {
        assertEquals(true, m("^1.2", "1.2.0"));
        assertEquals(true, m("^1.2", "1.9.9"));
        assertEquals(false, m("^1.2", "2.0.0"));
        assertEquals(false, m("^1.2", "1.1.9"));
        assertEquals(true, m("^1", "1.0.0"));
        assertEquals(false, m("^1.2.3", "1.2.2"));
        // 0.x: minor is breaking
        assertEquals(true, m("^0.2.3", "0.2.9"));
        assertEquals(false, m("^0.2.3", "0.3.0"));
        assertEquals(true, m("^0.0.3", "0.0.3"));
        assertEquals(false, m("^0.0.3", "0.0.4"));
        assertEquals(false, m("^0", "1.0.0"));
        assertEquals(true, m("^0", "0.9.0"));
    }

    @Test
    void tilde() {
        assertEquals(true, m("~1.2.3", "1.2.9"));
        assertEquals(false, m("~1.2.3", "1.3.0"));
        assertEquals(false, m("~1.2.3", "1.2.2"));
        assertEquals(true, m("~1", "1.9.0"));
        assertEquals(false, m("~1", "2.0.0"));
    }

    @Test
    void partialMeansCompatible() {
        assertEquals(true, m("1.2", "1.2.7"));
        assertEquals(false, m("1.2", "1.3.0"));
        assertEquals(true, m("1", "1.7.0"));
    }

    @Test
    void comparators() {
        assertEquals(true, m(">=1.0 <2.0", "1.5.0"));
        assertEquals(false, m(">=1.0 <2.0", "2.0.0"));
        assertEquals(false, m(">1.0.0", "1.0.0"));
        assertEquals(true, m("<=1.0.0", "1.0.0"));
        assertEquals(true, m(">=1.0.0-rc.1", "1.0.0-rc.2"));
    }

    @Test
    void invalid() {
        for (String bad : new String[] { "^x", ">=a", "1.2.3.4", "~-1" }) {
            assertThrows(IllegalArgumentException.class, () -> VersionRange.parse(bad), bad);
        }
    }
}
