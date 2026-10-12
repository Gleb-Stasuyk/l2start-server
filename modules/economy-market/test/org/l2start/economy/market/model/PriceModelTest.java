package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.l2start.economy.market.model.MarketItem.Grade;

class PriceModelTest {
    static final MarketItem SWORD = new MarketItem(126, "Artisan's Sword", "Weapon", Grade.D, 644_000, false, true, 1);
    static final MarketItem DUALS = new MarketItem(81, "Dragon Slayer", "Weapon", Grade.A, 27_000_000, false, false, 4);
    static final MarketItem ORE = new MarketItem(1869, "Iron Ore", "EtcItem", Grade.NONE, 100, true, false, 1);
    final PriceModel model = new PriceModel(PriceModel.Settings.DEFAULT);

    @Test
    void baseFollowsTheNpcPriceAndTheStage() {
        assertEquals(644_000, model.base(SWORD, 1));
        assertTrue(model.base(DUALS, 1) > model.base(DUALS, 4), "an A item before its stage is scarce, so dearer");
        assertEquals(Math.round(27_000_000 * 1.45), model.base(DUALS, 1));
        assertEquals(Math.round(644_000 * 0.8), model.base(SWORD, 5), "plentiful late, at most -20 %");
        assertEquals(1_288_000, new PriceModel(new PriceModel.Settings(2.0, 1, 1.25, 0.55, 0.85, 0.9)).base(SWORD, 1), "PriceMultiplier");
    }

    @Test
    void theIndexWeighsUpToSeventyPercentAtThirtyDeals() {
        assertEquals(0.0, PriceModel.indexWeight(0));
        assertEquals(0.35, PriceModel.indexWeight(15), 1e-9);
        assertEquals(0.7, PriceModel.indexWeight(30), 1e-9);
        assertEquals(0.7, PriceModel.indexWeight(500), 1e-9);
        final long now = 100L * 24 * 3600 * 1000;
        final IndexEntry e = new IndexEntry();
        for (int k = 0; k < 30; k++) {
            e.deal(200, now - (k * 3600_000L));
        }
        assertEquals(30, e.recentDeals(now));
        assertEquals(170, model.market(100, e, now), "0.3 x 100 + 0.7 x 200");
        assertEquals(0, e.recentDeals(now + IndexEntry.WINDOW_MS + 86_400_000L), "deals older than two weeks no longer count");
        assertEquals(100, model.market(100, e, now + IndexEntry.WINDOW_MS + 86_400_000L));
    }

    @Test
    void outliersDoNotMoveASettledIndex() {
        final IndexEntry e = new IndexEntry();
        for (int k = 0; k < 5; k++) {
            assertTrue(e.deal(1000, k));
        }
        assertEquals(1000, e.ema(), 1e-9);
        assertEquals(false, e.deal(1, 10), "a 1-adena dump is ignored");
        assertEquals(false, e.deal(50_000, 11), "a 50x price is ignored");
        assertEquals(1000, e.ema(), 1e-9);
        assertEquals(7, e.recentDeals(12), "but still counted as deals");
        e.deal(1500, 13);
        assertEquals(1100, e.ema(), 1e-9, "EMA alpha 0.2");
    }

    @Test
    void driftIsSlowSmallAndTheSameWithinAnHour() {
        final long week = PriceModel.WEEK_MS;
        double min = 9;
        double max = 0;
        for (int id = 1; id < 300; id++) {
            for (long t = 0; t < (10 * week); t += week / 7) {
                final double d = PriceModel.drift(id, t, 42);
                min = Math.min(min, d);
                max = Math.max(max, d);
                final double hourLater = PriceModel.drift(id, t + 3_600_000L, 42);
                assertTrue(Math.abs(d - hourLater) < 0.002, "no jumps between visits");
                final double dayLater = PriceModel.drift(id, t + (week / 7), 42);
                assertTrue(Math.abs(d - dayLater) <= ((2 * PriceModel.DRIFT) / 7) + 1e-9, "at most a week's step spread over 7 days");
            }
        }
        assertTrue((min >= 0.9) && (max <= 1.1), "within +-10 %: " + min + ".." + max);
        assertTrue((min < 0.95) && (max > 1.05), "but it does move");
    }

    @Test
    void sellersAskMoreThanBuyersBidAndPricesAreRound() {
        final long market = 45_320;
        final long lowSell = model.sellPrice(ORE, market, 0);
        final long highSell = model.sellPrice(ORE, market, 1);
        assertEquals(45_000, lowSell, "LW naturalizePrice");
        assertEquals(57_000, highSell, "x1.25, two significant figures");
        final long generous = model.buyPrice(ORE, market, 0, lowSell);
        final long stingy = model.buyPrice(ORE, market, 1, lowSell);
        assertEquals(39_000, generous);
        assertEquals(25_000, stingy);
        assertTrue(generous < lowSell);
    }

    @Test
    void aBuyerAlwaysPaysLessThanTheCheapestBotSeller() {
        final PriceModel greedyBuyer = new PriceModel(new PriceModel.Settings(1, 1.0, 1.0, 1.2, 1.3, 0.9));
        for (long market = 1; market < 2_000_000; market = (market * 3) + 7) {
            final long cheapest = greedyBuyer.sellPrice(ORE, market, 0);
            final long bid = greedyBuyer.buyPrice(ORE, market, 0, cheapest);
            assertTrue(bid < cheapest, market + ": bid " + bid + " vs seller " + cheapest);
        }
    }

    @Test
    void npcArbitrageIsGuarded() {
        final MarketItem potion = new MarketItem(1061, "Healing Potion", "EtcItem", Grade.NONE, 330, true, true, 1);
        assertEquals(330, model.sellPrice(potion, 200, 0), "never below the NPC price");
        assertTrue(model.buyPrice(potion, 1000, 0, 0) <= 297, "never above 90 % of the NPC price");
    }

    @Test
    void theStageComesFromLevelAndPlayTime() {
        assertEquals(1, Stage.solo(15, 3));
        assertEquals(2, Stage.solo(25, 10));
        assertEquals(3, Stage.solo(45, 40));
        assertEquals(4, Stage.solo(55, 100));
        assertEquals(5, Stage.solo(70, 0));
        assertEquals(2, Stage.solo(15, 130), "a long game runs one stage ahead");
        assertEquals(3, Stage.online(50, 45));
        assertEquals(1, Stage.online(3, 76), "a new server starts at 1 whatever the levels");
        assertEquals(4, Stage.resolve("4", 1));
        assertEquals(2, Stage.resolve("auto", 2));
        assertEquals(2, Stage.resolve("9", 2));
        assertTrue(Stage.available(SWORD, 1));
        assertTrue(!Stage.available(DUALS, 3));
    }

    @Test
    void onlyGiranHasStoresForNow() {
        final TownQuota q = TownQuota.parse("Giran:20,Dion:0,Aden:0,Oren:0,Gludio:0,Heine:0,Rune:0,Goddard:0,Schuttgart:0,Floran:0,Hunters:0,Gludin:0");
        assertEquals(20, q.stores("giran"));
        assertEquals(0, q.stores("Dion"));
        assertEquals(0, q.stores("Talking Island"));
        assertEquals(java.util.Map.of("giran", 20), q.active());
        assertEquals(8, TownQuota.parse("Giran:20,Dion:8,bad,Aden:x").stores("Dion"), "a town is turned on by its number");
    }

    @Test
    void theBookIndexesOnlyPricesOfRealPlayers() {
        final MarketBook book = new MarketBook(new MarketItems(java.util.List.of(SWORD, ORE)), model, false, 1);
        final long now = 1_000_000_000L;
        assertEquals(false, book.deal(1869, 50, false, now), "a bot's store price is not a market signal");
        assertEquals(false, book.deal(57, 50, true, now), "not a market item");
        assertTrue(book.deal(1869, 150, true, now));
        assertEquals(1, book.indexed());
        assertEquals(1, book.takeDirty().size());
        assertEquals(0, book.takeDirty().size());
        final MarketBook.Quote q = book.quote(1869, 1, now);
        assertEquals(100, q.base());
        assertEquals(101, q.market(), "one deal: weight 0.7/30 -> 100 x 0.977 + 150 x 0.023");
        assertTrue(q.highBid() < q.lowSell());
    }

    @Test
    void theShippedCatalogueLoads() throws Exception {
        final MarketItems items = MarketItems.load(Path.of("modules/economy-market/data/market-items.xml"));
        assertTrue(items.size() > 2400, "LW allow-list plus shots and potions: " + items.size());
        assertEquals(Grade.D, items.get(1463).grade(), "Soulshot: D-grade");
        assertTrue(items.get(1060).npc(), "Lesser Healing Potion is sold by NPCs");
        assertEquals(4, items.get(81).stage(), "Dragon Slayer is A");
        for (MarketItem i : items.all()) {
            assertTrue(org.l2start.economy.market.lw.StoreEligibility.isAllowed(i.id()) || ("EtcItem".equals(i.kind()) && (i.price() <= 5000)), "only the LW list plus consumables: " + i.name());
        }
    }
}
