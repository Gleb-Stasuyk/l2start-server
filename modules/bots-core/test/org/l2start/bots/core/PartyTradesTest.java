package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Phase 6f: party trade accepts only shots/potions; bot side empty. */
class PartyTradesTest {
    @Test
    void acceptsShotsAndPotionsOnly() {
        assertTrue(PartyTrades.giftsOk(List.of(1835, 1061), 0));
        assertTrue(PartyTrades.giftsOk(List.of(3947), 0));
        assertTrue(PartyTrades.giftsOk(List.of(1539), 0));
        assertFalse(PartyTrades.giftsOk(List.of(57), 0), "adena alone");
        assertFalse(PartyTrades.giftsOk(List.of(1835, 57), 0), "adena mixed in");
        assertFalse(PartyTrades.giftsOk(List.of(1835), 1), "bot put something");
        assertFalse(PartyTrades.giftsOk(List.of(), 0), "empty gift");
        assertFalse(PartyTrades.giftsOk(List.of(6660), 0), "gear");
    }
}
