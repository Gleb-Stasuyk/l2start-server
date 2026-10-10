package org.l2start.platform.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class SemverTest {
    @Test
    void parsesAndPrints() {
        final Semver v = Semver.parse("1.4.2-rc.1+build.5");
        assertEquals(1, v.major());
        assertEquals(4, v.minor());
        assertEquals(2, v.patch());
        assertEquals("rc.1", v.pre());
        assertEquals("1.4.2-rc.1", v.toString());
    }

    @Test
    void rejectsInvalid() {
        for (String bad : new String[] { "1", "1.2", "1.2.3.4", "01.2.3", "a.b.c", "1.2.-3", "", " " }) {
            assertThrows(IllegalArgumentException.class, () -> Semver.parse(bad), bad);
            assertFalse(Semver.isValid(bad));
        }
    }

    @Test
    void ordersLikeSemver2() {
        final List<String> expected = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0", "10.0.0");
        final List<Semver> list = new ArrayList<>();
        for (String s : expected) {
            list.add(Semver.parse(s));
        }
        Collections.shuffle(list, new java.util.Random(7));
        Collections.sort(list);
        final List<String> got = new ArrayList<>();
        for (Semver s : list) {
            got.add(s.toString());
        }
        assertEquals(expected, got);
    }

    @Test
    void equalityIgnoresBuildMetadata() {
        assertEquals(Semver.parse("1.2.3+a"), Semver.parse("1.2.3+b"));
        assertTrue(Semver.parse("1.2.3").compareTo(Semver.parse("1.2.3-rc")) > 0);
    }
}
