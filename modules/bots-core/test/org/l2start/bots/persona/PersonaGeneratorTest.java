package org.l2start.bots.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PersonaGeneratorTest {
    private final PersonaGenerator gen = new PersonaGenerator(NameGeneratorTest.DICT);

    @Test
    void sameSeedSamePersona() {
        for (long seed = 1; seed < 200; seed++) {
            assertEquals(gen.generate(seed, -1, s -> false), gen.generate(seed, -1, s -> false));
        }
        assertNotEquals(gen.generate(1, -1, s -> false), gen.generate(2, -1, s -> false));
    }

    @Test
    void jsonRoundTrip() {
        for (long seed = 1; seed < 300; seed++) {
            final Persona p = gen.generate(seed * 7919, -1, s -> false);
            assertEquals(p, Persona.fromJson(p.toJson()), p.toJson());
        }
        final Persona neg = gen.generate(Long.MIN_VALUE + 5, 53, s -> false);
        assertEquals(neg, Persona.fromJson(neg.toJson()));
    }

    @Test
    void thousandPersonasLookLikeThePlan() {
        final Map<String, Integer> tz = new HashMap<>();
        final Map<Chronotype, Integer> chrono = new EnumMap<>(Chronotype.class);
        final Set<String> names = new HashSet<>();
        int female = 0;
        final int n = 5_000;
        for (long seed = 0; seed < n; seed++) {
            final Persona p = gen.generate(seed, -1, s -> names.contains(s.toLowerCase()));
            assertTrue(names.add(p.name().toLowerCase()));
            assertTrue(ClassPicker.isStartingClass(p.classId()));
            assertTrue(ClassPicker.FUTURE.get(p.classId()).contains(p.futureClassId()));
            assertTrue(p.traits().skill() >= 0 && p.traits().skill() <= 1);
            assertTrue(p.traits().reactionMs() >= 200 && p.traits().reactionMs() <= 1200);
            if (p.nameStyle().equals("RU")) {
                assertEquals(Persona.Lang.RU, p.lang());
            }
            tz.merge(p.timezone(), 1, Integer::sum);
            chrono.merge(p.chronotype(), 1, Integer::sum);
            female += p.female() ? 1 : 0;
        }
        assertEquals(Set.of("Europe/Moscow", "Europe/Kaliningrad", "Europe/Samara", "Asia/Yekaterinburg"), tz.keySet());
        assertNear(70, tz.get("Europe/Moscow"), n);
        assertNear(45, chrono.get(Chronotype.EVENING), n);
        assertTrue(female > n * 0.3 && female < n * 0.5, "female " + female);
    }

    private static void assertNear(int pct, int count, int n) {
        final double p = (100.0 * count) / n;
        assertTrue(Math.abs(p - pct) <= 3, p + "% vs " + pct + "%");
    }

    @Test
    void chosenClassIsKept() {
        for (long seed = 0; seed < 50; seed++) {
            assertEquals(25, gen.generate(seed, 25, s -> false).classId());
        }
        assertThrows(IllegalArgumentException.class, () -> gen.generate(1, 57, s -> false));
    }

    @Test
    void oldBotsKeepTheirRealNameClassAndLook() {
        final Persona p = gen.adopt(77, "Kolya1986", 53, true, 2, 5, 3);
        assertEquals("Kolya1986", p.name());
        assertEquals("RU", p.nameStyle());
        assertEquals(53, p.classId());
        assertTrue(p.female());
        assertEquals(List.of(2, 5, 3), List.of(p.face(), p.hairStyle(), p.hairColor()));
        assertEquals("GAMER", gen.adopt(1, "xXFrostbladeXx", 0, false, 0, 0, 0).nameStyle());
        assertEquals(p, Persona.fromJson(p.toJson()));
    }

    @Test
    void badSettingsFailClearly() {
        assertThrows(Exception.class, () -> PersonaGenerator.parseTimezones(List.of("Mars/Olympus:1")));
        assertThrows(IllegalArgumentException.class, () -> PersonaGenerator.parseChronotypes(List.of("LUNCH:5")));
        assertThrows(IllegalArgumentException.class, () -> NameGenerator.parseStyles(List.of("ru:0", "en:0")));
        assertThrows(IllegalArgumentException.class, () -> NameGenerator.parseStyles(List.of("ru")));
    }
}
