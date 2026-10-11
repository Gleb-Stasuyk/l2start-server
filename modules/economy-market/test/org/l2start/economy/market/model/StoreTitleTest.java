package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class StoreTitleTest {
    @Test
    void signsSayWhatIsInside() throws Exception {
        final MarketBook b = AssortmentTest.book();
        final Assortment a = AssortmentTest.profiles();
        final Random rnd = new Random(1);
        final List<Assortment.Line> shots = List.of(new Assortment.Line(1463, "Soulshot: D-grade", 5000, 12), new Assortment.Line(3948, "Blessed Spiritshot: D-Grade", 2000, 47));
        assertEquals("SSD 12a / BSSD 47a", StoreTitle.of(a.profile("shots"), shots, b.items(), "EN", rnd));
        assertEquals("SSD 12а / BSSD 47а", StoreTitle.of(a.profile("shots"), shots, b.items(), "RU", rnd));
        final List<Assortment.Line> ore = List.of(new Assortment.Line(1869, "Iron Ore", 50, 120));
        assertEquals("BUY Iron Ore 120a", StoreTitle.of(a.profile("buy-materials"), ore, b.items(), "EN", rnd));
        assertEquals("Куплю Iron Ore 120а", StoreTitle.of(a.profile("buy-materials"), ore, b.items(), "RU", rnd));
        final List<Assortment.Line> two = List.of(new Assortment.Line(126, "Artisan's Sword", 1, 700_000), new Assortment.Line(77, "Tsurugi", 1, 4_700_000));
        assertTrue(StoreTitle.of(a.profile("weapons"), two, b.items(), "EN", rnd).startsWith("C weapons"), "highest grade inside");
        assertEquals("Tsurugi 4.7kk", StoreTitle.of(a.profile("weapons"), List.of(two.get(1)), b.items(), "EN", rnd));
    }

    @Test
    void everyGeneratedStoreHasAShortSign() throws Exception {
        final MarketBook b = AssortmentTest.book();
        final Assortment a = AssortmentTest.profiles();
        final Random rnd = new Random(2);
        for (int k = 0; k < 30; k++) {
            for (Assortment.Profile p : a.profiles().values()) {
                final List<Assortment.Line> lines = a.stock(p, b, 1 + (k % 5), rnd.nextDouble(), 0, rnd, id -> 0);
                if (lines.isEmpty()) {
                    continue;
                }
                for (String lang : List.of("RU", "EN", "MIX")) {
                    final String t = StoreTitle.of(p, lines, b.items(), lang, rnd);
                    assertTrue(!t.isEmpty() && (t.length() <= StoreTitle.MAX), p.name() + ": '" + t + "'");
                }
            }
        }
    }

    @Test
    void presenceFollowsRealPlayersAndNights() {
        final List<int[]> w = List.of(new int[] { 82800, 148500, -3450 });
        assertTrue(TraderPresence.watched(82500, 148480, w, 4500));
        assertTrue(!TraderPresence.watched(82500, 148480, List.of(new int[] { 90000, 148500, 0 }), 4500));
        assertTrue(TraderPresence.present(10_000, 9_000, 180_000));
        assertTrue(!TraderPresence.present(500_000, 9_000, 180_000));
        assertTrue(!TraderPresence.present(500_000, 0, 180_000), "never watched");
        assertTrue(TraderPresence.night(3) && !TraderPresence.night(12) && !TraderPresence.night(0));
        int stay = 0;
        for (int id = 1; id <= 1000; id++) {
            if (TraderPresence.staysAtNight(id, 20_000, 0.6)) {
                stay++;
            }
            assertEquals(TraderPresence.staysAtNight(id, 20_000, 0.6), TraderPresence.staysAtNight(id, 20_000, 0.6), "the same all night");
        }
        assertTrue((stay > 520) && (stay < 680), "about 60 % stay: " + stay);
        assertEquals("BUY", TraderPresence.kind("BUY", "buy-crystals", 0.1));
        assertEquals("PACKAGE", TraderPresence.kind("SELL", "materials", 0.1));
        assertEquals("SELL", TraderPresence.kind("SELL", "weapons", 0.1));
        for (int id = 0; id < 100; id++) {
            assertTrue((TraderPresence.level(id) >= 40) && (TraderPresence.level(id) <= 64));
        }
    }
}
