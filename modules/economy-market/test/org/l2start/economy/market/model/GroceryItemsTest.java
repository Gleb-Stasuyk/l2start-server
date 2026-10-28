package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class GroceryItemsTest {
    @Test
    void onlyGrocerTitlesContribute() {
        final GroceryItems g = GroceryItems.of(
            Map.of(30081, "Grocer", 30001, "Weapon Merchant", 30082, "Grocer"),
            Map.of(30081, Set.of(1835, 1060, 736), 30001, Set.of(2, 3), 30082, Set.of(17)));
        assertTrue(g.contains(1835)); // NG SS
        assertTrue(g.contains(1060)); // potion
        assertTrue(g.contains(736)); // SoE
        assertTrue(g.contains(17));
        assertFalse(g.contains(2)); // weapon merchant only
        assertFalse(g.contains(1463)); // D-grade SS not in this fixture (4UDO keeps it)
    }

    @Test
    void assortmentSkipsGrocery() throws Exception {
        final Assortment a = Assortment.load(java.nio.file.Path.of("modules/economy-market/data/trader-profiles.xml"));
        a.setGrocery(GroceryItems.of(Map.of(1, "Grocer"), Map.of(1, Set.of(1835, 1060))));
        final MarketItems items = MarketItems.load(java.nio.file.Path.of("modules/economy-market/data/market-items.xml"));
        final Assortment.Profile p = a.profiles().values().stream().filter(x -> !x.buy()).findFirst().orElseThrow();
        final var cand = a.candidates(p, items, 5);
        assertTrue(cand.stream().noneMatch(i -> i.id() == 1835 || i.id() == 1060));
    }

    @Test
    void shippedListHasSoEAndNgSs() throws Exception {
        final GroceryItems g = GroceryItems.loadList(java.nio.file.Path.of("modules/economy-market/data/grocery-items.txt"));
        assertTrue(g.size() >= 200);
        assertTrue(g.contains(1830)); // SoE
        assertTrue(g.contains(1835)); // NG soulshot
        assertFalse(g.contains(1463)); // D-grade SS (4UDO), not Grocer
    }
}
