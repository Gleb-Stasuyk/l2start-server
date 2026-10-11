package org.l2start.platform.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.l2start.platform.api.StoreDeal;
import org.l2start.platform.api.StoreDealListener;

class StoreHooksTest {
    @Test
    void listenersHearDealsUntilTheirModuleRemovesThem() {
        final List<StoreDeal> heard = new ArrayList<>();
        final StoreDealListener l = heard::add;
        CoreHooks.storeDeal(null, null, 57, 1, 1, false); // no listeners, null players: inert
        StoreHooks.add("economy-market", l);
        try {
            assertEquals(1, StoreHooks.size());
            StoreHooks.deal(new StoreDeal(1, 2, 1061, 5, 120, false, true, false));
            assertEquals(1, heard.size());
            assertEquals(600, heard.get(0).total());
            assertEquals(1, heard.get(0).ownerId());
            assertEquals(false, heard.get(0).ownerReal(), "a bot's sell store");
            CoreHooks.storeDeal(null, null, 57, 1, 1, false); // null players never reach the listener
            assertEquals(1, heard.size());
            StoreHooks.remove("other", l); // not the owner
            assertEquals(1, StoreHooks.size());
        } finally {
            StoreHooks.remove("economy-market", l);
        }
        assertEquals(0, StoreHooks.size());
    }
}
