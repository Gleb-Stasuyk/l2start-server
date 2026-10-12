package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TraderPresenceTest {
    @Test
    void placeKindsStay() {
        assertEquals("CRAFT", TraderPresence.kind("CRAFT", "craft", 0.0));
        assertEquals("BUY", TraderPresence.kind("BUY", "materials", 0.0));
        assertEquals("PACKAGE", TraderPresence.kind("SELL", "materials", 0.1));
        assertEquals("SELL", TraderPresence.kind("SELL", "weapons", 0.1));
    }

    @Test
    void levelsAreSecondProfession() {
        for (int id = 1; id < 500; id++) {
            final int l = TraderPresence.level(id);
            assertEquals(true, (l >= 40) && (l <= 64));
        }
    }
}
