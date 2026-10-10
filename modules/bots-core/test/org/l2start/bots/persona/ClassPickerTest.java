package org.l2start.bots.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ClassPickerTest {
    @Test
    void sharesFollowTheApprovedDistribution() {
        final ClassPicker p = new ClassPicker();
        final Random rnd = new Random(3);
        final Map<Integer, Integer> got = new HashMap<>();
        final int n = 20_000;
        for (int i = 0; i < n; i++) {
            got.merge(p.pickClass(rnd), 1, Integer::sum);
        }
        assertEquals(100, ClassPicker.DEFAULT_WEIGHTS.values().stream().mapToInt(Integer::intValue).sum());
        ClassPicker.DEFAULT_WEIGHTS.forEach((cls, pct) -> {
            assertEquals(pct, p.share(cls), 0.001);
            final double share = (100.0 * got.getOrDefault(cls, 0)) / n;
            assertTrue(Math.abs(share - pct) <= 1.5, "class " + cls + ": " + share + "% vs " + pct + "%");
        });
    }

    @Test
    void looksStayInsideStockCreationLimitsAndUseEveryOption() {
        final ClassPicker p = new ClassPicker();
        final Random rnd = new Random(5);
        final boolean[] femaleHair = new boolean[7];
        final boolean[] maleHair = new boolean[5];
        for (int i = 0; i < 5_000; i++) {
            final int cls = p.pickClass(rnd);
            final ClassPicker.Look l = p.look(cls, rnd);
            assertTrue((l.face() >= 0) && (l.face() <= 2));
            assertTrue((l.hairColor() >= 0) && (l.hairColor() <= 3));
            assertTrue((l.hairStyle() >= 0) && (l.hairStyle() <= (l.female() ? 6 : 4)));
            (l.female() ? femaleHair : maleHair)[l.hairStyle()] = true;
        }
        for (boolean b : femaleHair) {
            assertTrue(b);
        }
        for (boolean b : maleHair) {
            assertTrue(b);
        }
    }

    @Test
    void onlyStartingClassesAndStockLimits() {
        assertThrows(IllegalArgumentException.class, () -> new ClassPicker(List.of("92:10"), ClassPicker.DEFAULT_FACE, ClassPicker.DEFAULT_HAIR_COLOR, ClassPicker.DEFAULT_HAIR_MALE, ClassPicker.DEFAULT_HAIR_FEMALE));
        assertThrows(IllegalArgumentException.class, () -> new ClassPicker(ClassPicker.DEFAULT_CLASSES, List.of("1", "1", "1", "1"), ClassPicker.DEFAULT_HAIR_COLOR, ClassPicker.DEFAULT_HAIR_MALE, ClassPicker.DEFAULT_HAIR_FEMALE));
        assertThrows(IllegalArgumentException.class, () -> new ClassPicker(ClassPicker.DEFAULT_CLASSES, ClassPicker.DEFAULT_FACE, ClassPicker.DEFAULT_HAIR_COLOR, List.of("1", "1", "1", "1", "1", "1"), ClassPicker.DEFAULT_HAIR_FEMALE));
        assertTrue(ClassPicker.isStartingClass(53));
        assertTrue(!ClassPicker.isStartingClass(57));
    }

    @Test
    void futureClassBelongsToTheStartingClass() {
        final Random rnd = new Random(1);
        for (int cls : ClassPicker.DEFAULT_WEIGHTS.keySet()) {
            for (int i = 0; i < 50; i++) {
                assertTrue(ClassPicker.FUTURE.get(cls).contains(ClassPicker.futureClass(cls, rnd)));
            }
        }
    }
}
