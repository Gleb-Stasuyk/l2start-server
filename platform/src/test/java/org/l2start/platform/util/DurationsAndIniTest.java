package org.l2start.platform.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class DurationsAndIniTest {
    @Test
    void durations() {
        assertEquals(Duration.ofMillis(500), Durations.parse("500ms"));
        assertEquals(Duration.ofSeconds(30), Durations.parse("30s"));
        assertEquals(Duration.ofMinutes(10), Durations.parse("10m"));
        assertEquals(Duration.ofHours(2), Durations.parse("2H"));
        assertEquals(Duration.ofDays(1), Durations.parse("1d"));
        assertEquals(Duration.ofMillis(250), Durations.parse("250"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("10 minutes"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("-1s"));
    }

    @Test
    void iniSectionsAndComments() {
        final IniFile ini = IniFile.parse("\uFEFF# comment\n; other\nA = 1\nB=two words # inline\nUrl = http://x/#frag\n\n[Online]\nA = 2\r\n[solo]\nC = 3\n");
        assertEquals("1", ini.resolve("solo").get("a").value());
        assertEquals("2", ini.resolve("online").get("a").value());
        assertEquals("two words", ini.resolve(null).get("b").value());
        assertEquals("http://x/#frag", ini.resolve(null).get("url").value());
        assertEquals("3", ini.resolve("solo").get("c").value());
        assertNull(ini.resolve("online").get("c"));
        assertEquals("A", ini.resolve("online").get("a").key());
    }

    @Test
    void iniRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> IniFile.parse("just text"));
        assertThrows(IllegalArgumentException.class, () -> IniFile.parse("= value"));
    }
}
