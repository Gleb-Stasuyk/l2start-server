package org.l2start.platform.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.l2start.platform.api.BotPledgeListener;

/** Hook P7-BOT-PLEDGE: listeners owned by modules, first "taken" wins, inert without listeners or with no bot. */
class PledgeHooksTest {
    @Test
    void firstTakenWinsAndRemoveClears() {
        final BotPledgeListener no = (r, b, t) -> false;
        final BotPledgeListener yes = (r, b, t) -> true;
        assertEquals(0, PledgeHooks.size());
        CoreHooks.botPledgeRequest(null, null, 0); // no listener: stock (nothing)
        PledgeHooks.add("a", no);
        PledgeHooks.add("b", yes);
        try {
            assertTrue(PledgeHooks.request(null, null, 0));
            CoreHooks.botPledgeRequest(null, null, -1); // null players never reach a listener
            PledgeHooks.remove("a", yes); // not its listener: stays
            assertEquals(2, PledgeHooks.size());
            PledgeHooks.remove("b", yes);
            assertFalse(PledgeHooks.request(null, null, 0));
        } finally {
            PledgeHooks.remove("a", no);
            PledgeHooks.remove("b", yes);
        }
        assertEquals(0, PledgeHooks.size());
    }
}
