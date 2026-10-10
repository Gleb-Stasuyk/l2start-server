package org.l2start.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class RuntimeTest {
    @Test
    void ownershipUndoesEverythingInReverseOrder() {
        final Ownership o = new Ownership();
        final List<String> undone = new ArrayList<>();
        o.add("listener", "L1", () -> undone.add("L1"));
        o.add("task", "T1", () -> undone.add("T1"));
        final Ownership.Registration r = o.add("task", "T2", () -> undone.add("T2"));
        o.add("admin", "A1", () -> {
            throw new IllegalStateException("boom");
        });
        o.add("voiced", "V1", () -> undone.add("V1"));
        o.forget(r);
        assertEquals(Map.of("admin", 1, "listener", 1, "task", 1, "voiced", 1), o.counts());
        final List<String> errors = new ArrayList<>();
        assertEquals(3, o.undoAll(errors::add));
        assertEquals(List.of("V1", "T1", "L1"), undone);
        assertEquals(1, errors.size());
        assertEquals(0, o.size());
        assertEquals(0, o.undoAll(errors::add));
    }

    @Test
    void breakerTripsOnceInsideWindow() {
        final CircuitBreaker b = new CircuitBreaker(3, 1000);
        assertFalse(b.recordFailure(0));
        assertFalse(b.recordFailure(500));
        assertFalse(b.recordFailure(1200)); // the one at 0 left the window
        assertEquals(2, b.errorsInWindow(1200));
        assertTrue(b.recordFailure(1300));
        assertFalse(b.recordFailure(1301));
        assertTrue(b.isTripped());
        assertEquals(5, b.total());
    }

    @Test
    void guardCatchesCountsAndTrips() {
        final AtomicLong clock = new AtomicLong(1000);
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicReference<String> tripped = new AtomicReference<>();
        final ModuleGuard g = new ModuleGuard("fx", new CircuitBreaker(3, 60_000), Long.MAX_VALUE, active::get, tripped::set, clock::get);
        assertEquals("ok", g.call("p", () -> "ok", "fallback"));
        for (int i = 0; i < 2; i++) {
            g.run("event X", () -> {
                throw new IllegalStateException("bad " + 1);
            });
        }
        assertNull(tripped.get());
        assertEquals("fallback", g.call("cmd", () -> {
            throw new Error("fatal-ish");
        }, "fallback"));
        assertTrue(tripped.get().contains("3 errors within 60 s"), tripped.get());
        assertEquals(3, g.breaker().total());
        assertEquals(3, g.recentErrors().size());
        assertEquals(4, g.calls());
        active.set(false);
        final AtomicBoolean ran = new AtomicBoolean();
        g.run("after", () -> ran.set(true));
        assertFalse(ran.get(), "inactive module must not be called");
    }

    @Test
    void guardCountsSlowCalls() {
        final ModuleGuard g = new ModuleGuard("fx", new CircuitBreaker(3, 1000), 1, () -> true, s -> {
        }, System::currentTimeMillis);
        g.run("slow", () -> Thread.sleep(5));
        assertEquals(1, g.slowCalls());
    }

    @Test
    void messagesFallbackChain() {
        final Properties en = new Properties();
        en.setProperty("a", "A {0}");
        en.setProperty("only.en", "E");
        en.setProperty("q", "it''s {0}");
        final Properties ru = new Properties();
        ru.setProperty("a", "Я {0}");
        final Messages m = Messages.of(Map.of("en", en, "ru", ru), "ru");
        assertEquals("A 1", m.get("en", "a", 1));
        assertEquals("Я 1", m.get("ru", "a", 1));
        assertEquals("Я 1", m.get(null, "a", 1));
        assertEquals("Я 1", m.get("fr", "a", 1));
        assertEquals("E", m.get("ru", "only.en"));
        assertEquals("missing", m.get("en", "missing"));
        assertEquals("it's x", m.get("en", "q", "x"));
        assertEquals("it's {0}", m.get("en", "q"));
    }

    interface Svc {
        int v();
    }

    @Test
    void servicesVisibility() {
        final ServiceDirectory d = new ServiceDirectory();
        d.provide("core", Svc.class, (Svc) () -> 7);
        assertEquals(7, d.find("core", List.of(), Svc.class).orElseThrow().v());
        assertEquals(7, d.find("app", List.of("core"), Svc.class).orElseThrow().v());
        assertTrue(d.find("stranger", List.of(), Svc.class).isEmpty());
        assertThrows(IllegalStateException.class, () -> d.provide("other", Svc.class, (Svc) () -> 1));
        assertThrows(IllegalArgumentException.class, () -> d.provide("x", Runnable.class, "not runnable"));
        d.remove("other", Svc.class);
        assertTrue(d.find("app", List.of("core"), Svc.class).isPresent());
        d.remove("core", Svc.class);
        assertTrue(d.find("app", List.of("core"), Svc.class).isEmpty());
    }
}
