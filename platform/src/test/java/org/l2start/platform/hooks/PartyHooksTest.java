package org.l2start.platform.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.l2start.platform.api.BotPartyListener;

/** Hook P6-BOT-PARTY: listeners owned by modules, first "taken" wins, inert without listeners or with no bot. */
class PartyHooksTest {
    @Test
    void ownedListenersFirstTakerWins() {
        final AtomicInteger calls = new AtomicInteger();
        final BotPartyListener no = (r, b) -> {
            calls.incrementAndGet();
            return false;
        };
        final BotPartyListener yes = (r, b) -> {
            calls.incrementAndGet();
            return true;
        };
        assertEquals(0, PartyHooks.size());
        CoreHooks.botPartyRequest(null, null); // no listener: nothing at all
        PartyHooks.add("a", no);
        PartyHooks.add("b", yes);
        try {
            assertTrue(PartyHooks.request(null, null));
            assertEquals(2, calls.get());
            CoreHooks.botPartyRequest(null, null); // null players never reach a listener
            assertEquals(2, calls.get());
            PartyHooks.remove("a", yes); // not its listener: stays
            assertEquals(2, PartyHooks.size());
            PartyHooks.remove("b", yes);
            assertFalse(PartyHooks.request(null, null));
        } finally {
            PartyHooks.remove("a", no);
            PartyHooks.remove("b", yes);
        }
        assertEquals(0, PartyHooks.size());
    }
}
