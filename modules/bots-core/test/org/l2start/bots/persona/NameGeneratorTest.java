package org.l2start.bots.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class NameGeneratorTest {
    @Test
    void tenThousandValidUniqueNamesInTheApprovedStyleMix() {
        final NameGenerator g = new NameGenerator(new Random(42));
        final Set<String> taken = new HashSet<>();
        final Map<NameGenerator.Style, Integer> styles = new EnumMap<>(NameGenerator.Style.class);
        for (int i = 0; i < 10_000; i++) {
            final NameGenerator.Named n = g.next(s -> taken.contains(s.toLowerCase()));
            assertTrue(NameGenerator.isValid(n.name()), n.name());
            assertTrue(n.name().matches("[A-Za-z0-9]{3,16}"), n.name());
            assertTrue(taken.add(n.name().toLowerCase()), "duplicate " + n.name());
            styles.merge(n.style(), 1, Integer::sum);
        }
        // 35/30/20/15 +- 3 percentage points (names rejected as taken shift the mix a little)
        assertNear(35, styles.get(NameGenerator.Style.RU));
        assertNear(30, styles.get(NameGenerator.Style.EN));
        assertNear(20, styles.get(NameGenerator.Style.FANTASY));
        assertNear(15, styles.get(NameGenerator.Style.GAMER));
    }

    private static void assertNear(int percent, Integer count) {
        final double p = count / 100.0;
        assertTrue(Math.abs(p - percent) <= 3, "share " + p + "% vs " + percent + "%");
    }

    @Test
    void sameSeedSameNames() {
        final NameGenerator a = new NameGenerator(new Random(7));
        final NameGenerator b = new NameGenerator(new Random(7));
        for (int i = 0; i < 100; i++) {
            assertEquals(a.next(s -> false), b.next(s -> false));
        }
    }

    @Test
    void stopWordsAndBadShapesAreRejected() {
        assertFalse(NameGenerator.isValid("GmVasya"));
        assertFalse(NameGenerator.isValid("SuperAdmin"));
        assertFalse(NameGenerator.isValid("TankBot"));
        assertFalse(NameGenerator.isValid("Blyaha"));
        assertFalse(NameGenerator.isValid("ab"));
        assertFalse(NameGenerator.isValid("Aaaargh"));
        assertFalse(NameGenerator.isValid("Vasya_1987"));
        assertFalse(NameGenerator.isValid("Васян"));
        assertFalse(NameGenerator.isValid("ThisNameIsWayTooLong"));
        assertTrue(NameGenerator.isValid("xXShadowbladeXx"));
        assertTrue(NameGenerator.isValid("DarkLord777"));
        assertTrue(NameGenerator.isValid("Dim4ik"));
    }

    @Test
    void givesUpWhenEverythingIsTaken() {
        assertThrows(IllegalStateException.class, () -> new NameGenerator(new Random(1)).next(s -> true));
    }
}
