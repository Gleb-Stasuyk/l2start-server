package org.l2start.modules.hello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.l2start.platform.loader.ManifestReader;
import org.l2start.platform.loader.ModuleManifest;
import org.l2start.platform.runtime.Messages;

class GreetingTest {
    private static final Path MODULE = Path.of("modules/hello");

    @Test
    void dayIsYyyymmdd() {
        assertEquals(20261010, Greeting.day(LocalDate.of(2026, 10, 10)));
        assertEquals(20270101, Greeting.day(LocalDate.of(2027, 1, 1)));
    }

    @Test
    void firstHelloStartsAtOne() {
        assertEquals(new Greeting.Counter(20261010, 1, 1), Greeting.next(null, 20261010, 5));
    }

    @Test
    void countsWithinADayAndStopsAtLimit() {
        Greeting.Counter c = null;
        for (int i = 1; i <= 3; i++) {
            c = Greeting.next(c, 20261010, 3);
            assertEquals(i, c.count());
        }
        assertNull(Greeting.next(c, 20261010, 3));
    }

    @Test
    void newDayResetsDailyCountButKeepsTotal() {
        final Greeting.Counter c = Greeting.next(new Greeting.Counter(20261009, 5, 42), 20261010, 5);
        assertEquals(new Greeting.Counter(20261010, 1, 43), c);
    }

    @Test
    void zeroLimitDisablesCommand() {
        assertNull(Greeting.next(null, 20261010, 0));
    }

    @Test
    void messagesRuEnAndFallback() throws Exception {
        final Messages m = Messages.load(MODULE.resolve("i18n"), "ru");
        assertEquals("Hello, Gleb! You said .hello 2 time(s) today (limit 5).", m.get("en", "hello.reply", "Gleb", 2, 5));
        assertEquals("Привет, Глеб! Ты сказал .hello 2 раз(а) сегодня (лимит 5).", m.get("ru", "hello.reply", "Глеб", 2, 5));
        // Unknown language -> DefaultLanguage (ru); unknown key -> key.
        assertEquals("На сегодня хватит, Глеб: лимит 5.", m.get("de", "hello.limit", "Глеб", 5));
        assertEquals("no.such.key", m.get("en", "no.such.key"));
    }

    @Test
    void bothLanguagesHaveTheSameKeys() throws Exception {
        final java.util.Properties en = new java.util.Properties();
        final java.util.Properties ru = new java.util.Properties();
        try (var a = java.nio.file.Files.newBufferedReader(MODULE.resolve("i18n/messages_en.properties")); var b = java.nio.file.Files.newBufferedReader(MODULE.resolve("i18n/messages_ru.properties"))) {
            en.load(a);
            ru.load(b);
        }
        assertEquals(en.keySet(), ru.keySet());
    }

    @Test
    void manifestIsValidAndNpcIsReserved() throws Exception {
        final ModuleManifest m = ManifestReader.read(MODULE);
        assertEquals("hello", m.id());
        assertEquals(ModuleManifest.Kind.JAR, m.kind());
        assertEquals(true, m.reserves(org.l2start.platform.loader.DataType.NPCS, HelloModule.NPC_ID));
        assertEquals(true, m.defaultEnabled("solo"));
        assertEquals(false, m.defaultEnabled("online"));
    }
}
