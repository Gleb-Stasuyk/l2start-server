package org.l2start.platform.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BotHooksTest {
    @Test
    void oneProviderAtATimeAndInertWithoutOne() {
        assertNull(BotHooks.provider());
        assertFalse(CoreHooks.isBot(null), "no provider: never a bot");
        BotHooks.install("bots-core", p -> true);
        try {
            assertEquals("bots-core", BotHooks.provider());
            assertThrows(IllegalStateException.class, () -> BotHooks.install("other", p -> true));
            assertFalse(CoreHooks.isBot(null), "null player is never a bot");
            BotHooks.uninstall("other"); // not the owner: no effect
            assertEquals("bots-core", BotHooks.provider());
        } finally {
            BotHooks.uninstall("bots-core");
        }
        assertNull(BotHooks.provider());
    }
}
