package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.economy.market.model.StoreSpots.Spot;

class MarketAreasTest {
    @Test
    void giranAreaAnd4udo() throws Exception {
        final MarketAreas a = MarketAreas.load(Path.of("modules/economy-market/data/market-areas.xml"));
        assertEquals(82800, a.area("giran").cx());
        final MarketAreas.Service s = a.services("Giran").get(0);
        assertEquals("4UDO", s.spot().legacy());
        assertEquals(82703, s.spot().x());
        assertEquals(147932, s.spot().y());
        assertEquals(-3464, s.spot().z());
        assertEquals("DWARF", s.spot().race());
        assertArrayEquals(new int[] { 1463, 1464, 1465, 3948, 3949, 3950 }, s.items());
    }

    @Test
    void sixtyPlacesFormerTradersFirstThenThemesAndCrafters() throws Exception {
        final StoreSpots old = StoreSpots.load(Path.of("modules/economy-market/data/store-spots.xml"));
        final List<Spot> legacy = old.spots("giran").subList(0, 20);
        final List<SpotGenerator.Point> pts = new ArrayList<>();
        for (int k = 0; k < 60; k++) {
            pts.add(new SpotGenerator.Point(82000 + (k * 10), 148000, -3464, 0));
        }
        final List<Spot> plan = MarketAreas.plan("Giran", pts, 0, legacy, new Random(5));
        assertEquals(60, plan.size());
        assertEquals("giran-01", plan.get(0).id());
        assertEquals("Xevoq", plan.get(0).legacy());
        assertEquals(legacy.get(19).legacy(), plan.get(19).legacy());
        final long crafters = plan.stream().filter(p -> "CRAFT".equals(p.kind())).count();
        assertEquals(5, crafters);
        assertTrue(plan.stream().filter(p -> "CRAFT".equals(p.kind())).allMatch(p -> (p.classId() == MarketAreas.WARSMITH) && "DWARF".equals(p.race())));
        assertTrue(plan.stream().filter(p -> "BUY".equals(p.kind())).count() >= 10);
        assertTrue(plan.stream().noneMatch(p -> "4UDO".equals(p.legacy())), "4UDO is the service trader, not a market place");
    }
}
