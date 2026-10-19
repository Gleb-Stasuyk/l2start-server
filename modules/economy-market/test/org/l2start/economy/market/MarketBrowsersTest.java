package org.l2start.economy.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.l2start.economy.market.model.StoreSpots.Spot;

class MarketBrowsersTest {
    @Test
    void parseCountRange() {
        assertEquals(3, MarketBrowsers.parseCount("3-5")[0]);
        assertEquals(5, MarketBrowsers.parseCount("3-5")[1]);
        assertEquals(4, MarketBrowsers.parseCount("4")[0]);
        assertEquals(4, MarketBrowsers.parseCount("4")[1]);
        assertEquals(3, MarketBrowsers.parseCount("")[0]);
        assertEquals(5, MarketBrowsers.parseCount(null)[1]);
    }

    @Test
    void visitPathKeepsGiranSellBuyCraft() {
        final List<Spot> all = List.of(
            new Spot("Giran", "g1", 1, 2, 3, 0, "SELL", "weapons", "", "", "", 0),
            new Spot("Giran", "g2", 1, 2, 3, 0, "BUY", "buy-misc", "", "", "", 0),
            new Spot("Giran", "g3", 1, 2, 3, 0, "CRAFT", "craft", "", "", "", 0),
            new Spot("Giran", "g4", 1, 2, 3, 0, "SERVICE", "svc", "", "", "", 0),
            new Spot("Dion", "d1", 1, 2, 3, 0, "SELL", "weapons", "", "", "", 0));
        final List<Spot> path = MarketBrowsers.visitPath(all);
        assertEquals(3, path.size());
        assertTrue(path.stream().allMatch(s -> "Giran".equalsIgnoreCase(s.town())));
        assertTrue(path.stream().anyMatch(s -> "SELL".equals(s.kind())));
        assertTrue(path.stream().anyMatch(s -> "BUY".equals(s.kind())));
        assertTrue(path.stream().anyMatch(s -> "CRAFT".equals(s.kind())));
    }
}
