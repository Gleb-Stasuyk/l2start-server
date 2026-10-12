package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.economy.market.model.StoreVisit.Buy;
import org.l2start.economy.market.model.StoreVisit.Offer;

class StoreVisitTest {
    static final Offer SSD_FAIR = new Offer(1, 1463, 5000, 22, true, 20, 20);
    static final Offer SSD_DEAR = new Offer(2, 1463, 5000, 30, true, 20, 20);
    static final Offer SWORD = new Offer(3, 120, 1, 50_000, false, 48_000, 40_000);

    @Test
    void buysAtMostTenPercentAboveTheMarket() {
        assertTrue(StoreVisit.worth(SSD_FAIR, 1.1));
        assertTrue(!StoreVisit.worth(SSD_DEAR, 1.1));
        assertTrue(StoreVisit.pick(List.of(SSD_DEAR), false, 1.1, 1_000_000, new Random(1)).isEmpty());
        assertTrue(!StoreVisit.worth(new Offer(4, 9999, 10, 5, true, 0, 5), 1.1), "not a market item");
    }

    @Test
    void aPackOfTheUsualSizeWithinTheLimit() {
        final List<Buy> b = StoreVisit.pick(List.of(SSD_FAIR), false, 1.1, 1_000_000, new Random(2));
        assertEquals(1, b.size());
        assertTrue((b.get(0).count() >= 1) && (b.get(0).count() <= 5000));
        final List<Buy> thin = StoreVisit.pick(List.of(SSD_FAIR), false, 1.1, 2_200, new Random(2));
        assertEquals(100, thin.get(0).count());
        assertTrue(StoreVisit.pick(List.of(SSD_FAIR), false, 1.1, 0, new Random(2)).isEmpty());
        assertEquals(1, StoreVisit.pick(List.of(SWORD), false, 1.1, 60_000, new Random(2)).get(0).count());
        assertTrue(StoreVisit.pick(List.of(SWORD), false, 1.1, 40_000, new Random(2)).isEmpty());
    }

    @Test
    void aPackageIsAllOrNothing() {
        assertTrue(StoreVisit.pick(List.of(SSD_FAIR, SSD_DEAR), true, 1.1, 10_000_000, new Random(3)).isEmpty());
        final List<Buy> all = StoreVisit.pick(List.of(SSD_FAIR, SWORD), true, 1.1, 10_000_000, new Random(3));
        assertEquals(2, all.size());
        assertEquals(5000, all.get(0).count());
        assertTrue(StoreVisit.pick(List.of(SSD_FAIR, SWORD), true, 1.1, 100_000, new Random(3)).isEmpty());
    }
}
