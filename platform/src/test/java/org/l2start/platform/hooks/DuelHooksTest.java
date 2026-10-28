package org.l2start.platform.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.l2start.platform.api.BotDuelListener;

/** Hook P7-BOT-DUEL: listeners owned by modules, first "taken" wins, inert without listeners or with no bot. */
class DuelHooksTest {
    @Test
    void ownedListenersFirstTakerWins() {
        final AtomicInteger calls = new AtomicInteger();
        final BotDuelListener no = (r, b, party) -> {
            calls.incrementAndGet();
            return false;
        };
        final BotDuelListener yes = (r, b, party) -> {
            calls.incrementAndGet();
            return true;
        };
        assertEquals(0, DuelHooks.size());
        CoreHooks.botDuelRequest(null, null, false); // no listener: stock (nothing)
        DuelHooks.add("a", no);
        DuelHooks.add("b", yes);
        try {
            assertTrue(DuelHooks.request(null, null, false));
            assertEquals(2, calls.get());
            CoreHooks.botDuelRequest(null, null, true); // null players never reach a listener
            assertEquals(2, calls.get());
            DuelHooks.remove("a", yes); // not its listener: stays
            assertEquals(2, DuelHooks.size());
            DuelHooks.remove("b", yes);
            assertFalse(DuelHooks.request(null, null, false));
        } finally {
            DuelHooks.remove("a", no);
            DuelHooks.remove("b", yes);
        }
        assertEquals(0, DuelHooks.size());
    }
}
