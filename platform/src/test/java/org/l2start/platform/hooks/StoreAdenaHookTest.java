package org.l2start.platform.hooks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** [HOOK:P5-STORE-ADENA] outside a running platform: the stock line always runs (stock behaviour). */
class StoreAdenaHookTest {
    @Test
    void stockLineRunsWhenTheBuyerHasAdenaLeftOrThePlatformIsOff() {
        assertTrue(CoreHooks.storeBuyerAdenaLeft(null, new Object()), "adena left: stock line");
        assertTrue(CoreHooks.storeBuyerAdenaLeft(null, null), "platform not active: stock behaviour, even without adena");
    }
}
