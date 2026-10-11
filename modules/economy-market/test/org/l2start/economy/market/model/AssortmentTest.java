package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class AssortmentTest {
    static MarketBook book() throws Exception {
        return new MarketBook(MarketItems.load(Path.of("modules/economy-market/data/market-items.xml")), new PriceModel(PriceModel.Settings.DEFAULT), false, 7);
    }

    static Assortment profiles() throws Exception {
        return Assortment.load(Path.of("modules/economy-market/data/trader-profiles.xml"));
    }

    @Test
    void everyProfileOfAPlaceExistsAndHasGoodsAtStageOne() throws Exception {
        final Assortment a = profiles();
        final MarketBook b = book();
        for (StoreSpots.Spot s : StoreSpots.load(Path.of("modules/economy-market/data/store-spots.xml")).spots("Giran")) {
            final Assortment.Profile p = a.profile(s.profile());
            assertTrue(p != null, s.id() + ": unknown profile " + s.profile());
            assertEquals("BUY".equals(s.kind()), p.buy(), s.id() + " kind");
        }
        for (Assortment.Profile p : a.profiles().values()) {
            assertFalse(a.candidates(p, b.items(), 5).isEmpty(), p.name() + " has no goods at all");
        }
        assertFalse(a.candidates(a.profile("shots"), b.items(), 1).isEmpty());
        assertFalse(a.candidates(a.profile("weapons"), b.items(), 1).isEmpty());
    }

    @Test
    void earlyStoresCarryNoHighGrades() throws Exception {
        final Assortment a = profiles();
        final MarketBook b = book();
        final Random rnd = new Random(1);
        for (int k = 0; k < 200; k++) {
            for (String name : List.of("weapons", "armor", "shots", "jewels")) {
                for (Assortment.Line l : a.stock(a.profile(name), b, 1, rnd.nextDouble(), 0, rnd, id -> 0)) {
                    final MarketItem i = b.items().get(l.itemId());
                    assertTrue(i.stage() <= 1, "stage 1 store sells " + i.name());
                    assertTrue((i.grade() == MarketItem.Grade.NONE) || (i.grade() == MarketItem.Grade.D), i.name());
                }
            }
        }
    }

    @Test
    void sPiecesAreRareEvenAtStageFive() throws Exception {
        final Assortment a = profiles();
        final MarketBook b = book();
        final Random rnd = new Random(2);
        int s = 0;
        int all = 0;
        for (int k = 0; k < 500; k++) {
            for (Assortment.Line l : a.stock(a.profile("weapons"), b, 5, 0.5, 0, rnd, id -> 0)) {
                all++;
                if (b.items().get(l.itemId()).grade() == MarketItem.Grade.S) {
                    s++;
                }
            }
        }
        assertTrue(all > 500);
        assertTrue(s < (all * 0.1), "S share " + s + "/" + all);
    }

    @Test
    void buyStoresPayLessThanTheCheapestSellerAndAmountsAreBelievable() throws Exception {
        final Assortment a = profiles();
        final MarketBook b = book();
        final Random rnd = new Random(3);
        final Map<Integer, Long> sellers = new HashMap<>();
        for (int k = 0; k < 100; k++) {
            for (Assortment.Line l : a.stock(a.profile("materials"), b, 3, rnd.nextDouble(), 0, rnd, id -> 0)) {
                sellers.merge(l.itemId(), l.price(), Math::min);
                assertTrue((l.count() >= 1) && (l.count() <= 15000), l.toString());
            }
        }
        int checked = 0;
        for (int k = 0; k < 200; k++) {
            for (Assortment.Line l : a.stock(a.profile("buy-materials"), b, 3, rnd.nextDouble(), 0, rnd, id -> sellers.getOrDefault(id, 0L))) {
                final Long cheapest = sellers.get(l.itemId());
                if (cheapest != null) {
                    checked++;
                    assertTrue(l.price() < cheapest, l.name() + " bid " + l.price() + " vs seller " + cheapest);
                }
                final MarketItem i = b.items().get(l.itemId());
                if (i.npc()) {
                    assertTrue(l.price() <= (i.price() * 0.9), "NPC arbitrage: " + l.name());
                }
            }
        }
        assertTrue(checked > 10, "overlap of sellers and buyers: " + checked);
    }
}
