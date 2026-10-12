package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class PaperStoreTest {
    static final List<Assortment.Line> STORE = List.of(new Assortment.Line(1463, "Soulshot: D-grade", 5000, 12), new Assortment.Line(126, "Artisan's Sword", 1, 700_000));

    @Test
    void aShortAbsenceChangesLittleAndTheSameVisitChangesNothing() {
        final List<Assortment.Line> same = PaperStore.age(STORE, null, 0, false, new Random(1));
        assertEquals(STORE, same, "no time, no change");
        final List<Assortment.Line> hour = PaperStore.age(STORE, null, 1, false, new Random(1));
        assertTrue(hour.get(0).count() > 4800, "an hour: a few shots sold (about 2 %): " + hour.get(0).count());
    }

    @Test
    void daysAwaySellGoodsAndPullPricesToTheMarket() {
        final Random rnd = new Random(2);
        int swordsLeft = 0;
        for (int k = 0; k < 200; k++) {
            final List<Assortment.Line> week = PaperStore.age(STORE, new long[] { 10, 650_000 }, 24 * 7, false, rnd);
            final Assortment.Line shots = week.get(0);
            assertTrue(shots.count() < 2500, "a week: most shots sold");
            assertTrue((shots.price() >= 10) && (shots.price() < 12), "price crept to the market: " + shots.price());
            swordsLeft += (week.size() == 2) ? 1 : 0;
        }
        assertTrue((swordsLeft > 30) && (swordsLeft < 120), "a single sword sells within a week about two times in three: " + swordsLeft);
    }

    @Test
    void restockWhenMostLinesAreGone() {
        assertTrue(!PaperStore.needsRestock(3, 4));
        assertTrue(!PaperStore.needsRestock(2, 4));
        assertTrue(PaperStore.needsRestock(1, 4));
        assertTrue(PaperStore.needsRestock(0, 1));
    }
}
