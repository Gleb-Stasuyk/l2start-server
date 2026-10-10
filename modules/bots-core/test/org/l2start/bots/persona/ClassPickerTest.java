package org.l2start.bots.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ClassPickerTest {
    @Test
    void sharesFollowTheApprovedDistribution() {
        final ClassPicker p = new ClassPicker(new Random(3));
        final Map<Integer, Integer> got = new HashMap<>();
        final int n = 20_000;
        for (int i = 0; i < n; i++) {
            got.merge(p.pickClass(), 1, Integer::sum);
        }
        assertEquals(100, ClassPicker.DEFAULT_WEIGHTS.values().stream().mapToInt(Integer::intValue).sum());
        ClassPicker.DEFAULT_WEIGHTS.forEach((cls, pct) -> {
            final double share = (100.0 * got.getOrDefault(cls, 0)) / n;
            assertTrue(Math.abs(share - pct) <= 1.5, "class " + cls + ": " + share + "% vs " + pct + "%");
        });
    }

    @Test
    void looksStayInsideStockCreationLimits() {
        final ClassPicker p = new ClassPicker(new Random(5));
        for (int i = 0; i < 5_000; i++) {
            final int cls = p.pickClass();
            final ClassPicker.Look l = p.look(cls);
            assertTrue((l.face() >= 0) && (l.face() <= 2));
            assertTrue((l.hairColor() >= 0) && (l.hairColor() <= 3));
            assertTrue((l.hairStyle() >= 0) && (l.hairStyle() <= (l.female() ? 6 : 4)));
        }
    }

    @Test
    void onlyStartingClasses() {
        assertThrows(IllegalArgumentException.class, () -> new ClassPicker(new Random(), Map.of(92, 10)));
        assertTrue(ClassPicker.isStartingClass(53));
        assertTrue(!ClassPicker.isStartingClass(57));
    }
}
