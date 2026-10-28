package org.l2start.bots.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DigitNameTrimTest {
    static final NameDictionary DICT;
    static {
        try {
            DICT = NameDictionary.load(java.nio.file.Path.of("modules/bots-core/data/names"));
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void keepCountRoundsToShare() {
        assertEquals(3, DigitNameTrim.keepDigitCount(100, 20, 0.03));
        assertEquals(0, DigitNameTrim.keepDigitCount(100, 0, 0.03));
        assertEquals(2, DigitNameTrim.toRename(List.of(1, 2, 3, 4, 5), 3).size());
        assertEquals(List.of(4, 5), DigitNameTrim.toRename(List.of(5, 1, 4, 2, 3), 3));
    }

    @Test
    void twoThousandNamesAboutThreePercentDigits() {
        final NameGenerator g = new NameGenerator(new Random(42), DICT, NameGenerator.parseStyles(NameGenerator.DEFAULT_STYLES), 0.03);
        final Set<String> taken = new HashSet<>();
        int digits = 0;
        final int n = 2_000;
        for (int i = 0; i < n; i++) {
            final String name = g.next(s -> taken.contains(s.toLowerCase())).name();
            taken.add(name.toLowerCase());
            if (NameGenerator.hasDigit(name)) {
                digits++;
            }
        }
        final double p = 100.0 * digits / n;
        assertTrue(p >= 1.0 && p <= 8.0, "digit share " + p + "% (want ~3%)");
    }
}
