package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Phase 7c: ledger never re-issues the same object id after a simulated stock drop. */
class PkLedgerTest {
    @Test
    void memoryTracksIssueForgetAndNoReissueOfSameOid() {
        final PkLedger.Memory led = new PkLedger.Memory();
        final int bot = 7;
        led.issued(bot, 1001, 78); // sword
        led.issued(bot, 1002, 352); // armour
        assertTrue(led.hasObject(bot, 1001));
        assertEquals(2, led.objectIds(bot).size());

        // stock karma drop removed the sword
        led.forget(bot, 1001);
        assertFalse(led.hasObject(bot, 1001));
        assertTrue(led.hasObject(bot, 1002));

        // rematerialize may issue a *new* oid of the same itemId — not 1001 again as the same instance
        led.issued(bot, 2001, 78);
        assertFalse(led.hasObject(bot, 1001), "dropped oid must stay forgotten");
        assertTrue(led.hasObject(bot, 2001));
        assertTrue(led.hasObject(bot, 1002));
    }
}
