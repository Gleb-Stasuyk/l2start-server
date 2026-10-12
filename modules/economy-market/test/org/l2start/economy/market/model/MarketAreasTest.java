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
        // the box and the centre come from Gleb's polygon of the market
        assertEquals(82194, a.area("giran").minX());
        assertEquals(82725, a.area("giran").maxX());
        assertEquals(147879, a.area("giran").minY());
        assertEquals(149330, a.area("giran").maxY());
        assertTrue(a.polygon("Giran").inside(a.area("giran").cx(), a.area("giran").cy()));
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

    @Test
    void sixtyTradersInsideTheMarketPolygonAndCraftersAroundTheGuildsman() throws Exception {
        final MarketAreas a = MarketAreas.load(Path.of("modules/economy-market/data/market-areas.xml"));
        final SpotGenerator.Polygon poly = a.polygon("Giran");
        final MarketAreas.Crafters c = a.crafters("Giran");
        assertEquals(31805, c.npcId());
        assertEquals(82840, c.x());
        assertEquals(147796, c.y());
        final SpotGenerator.Ground flat = new SpotGenerator.Ground() {
            @Override
            public Integer z(int x, int y, int z) {
                return -3464;
            }

            @Override
            public boolean walk(int x1, int y1, int z1, int x2, int y2, int z2) {
                return true;
            }
        };
        final SpotGenerator.Area g = a.area("giran");
        final MarketAreas.Service udo = a.services("Giran").get(0);
        final List<SpotGenerator.Blocker> npcs = List.of(new SpotGenerator.Blocker(udo.spot().x(), udo.spot().y(), g.spacing()), new SpotGenerator.Blocker(c.x(), c.y(), g.npcGap()));
        final List<SpotGenerator.Point> pts = SpotGenerator.generate(g, List.of(), 55, npcs, flat, new Random(3), 60_000, poly);
        assertEquals(55, pts.size());
        for (SpotGenerator.Point p : pts) {
            assertTrue(poly.inside(p.x(), p.y()), "inside the polygon: " + p);
            assertTrue(Math.hypot(p.x() - udo.spot().x(), p.y() - udo.spot().y()) >= g.spacing(), "clear of 4UDO: " + p);
        }
        // not in rows: x values spread, not a handful of columns
        assertTrue(pts.stream().map(SpotGenerator.Point::x).distinct().count() > 40);
        final SpotGenerator.Area ring = new SpotGenerator.Area("Giran", c.x(), c.y(), c.z(), c.x() - c.rMax(), c.x() + c.rMax(), c.y() - c.rMax(), c.y() + c.rMax(), g.dz(), c.spacing(), g.npcGap(), g.doorGap(), g.reach());
        final List<SpotGenerator.Point> crafters = SpotGenerator.generate(ring, pts, 5, List.of(), flat, new Random(4), 20_000, a.crafterShape("Giran"));
        assertEquals(5, crafters.size());
        for (SpotGenerator.Point p : crafters) {
            final double d = Math.hypot(p.x() - c.x(), p.y() - c.y());
            assertTrue((d >= c.rMin()) && (d <= c.rMax()), "near the guildsman: " + p);
            assertTrue(!poly.inside(p.x(), p.y()), "not among the traders: " + p);
            final Spot craft = new Spot("Giran", "giran-24", p.x(), p.y(), p.z(), 0, "CRAFT", "craft", "", "DWARF", "MALE", 57);
            assertTrue(a.fits(craft));
        }
        // in front of the NPC (heading 16500 = +y) the way stays free
        assertTrue(!c.inside(c.x(), c.y() + 250));
        assertTrue(c.inside(c.x(), c.y() - 250));
        // an old layout place (the former box) does not fit: the places are generated again
        assertTrue(!a.fits(new Spot("Giran", "giran-01", 83500, 148500, -3464, 0, "SELL", "weapons", "", "", "MALE", 2)));
        assertTrue(a.fits(new Spot("Giran", "giran-01", 82450, 148600, -3464, 0, "SELL", "weapons", "", "", "MALE", 2)));
        assertEquals(16384, SpotGenerator.headingTo(0, 0, 0, 100));
    }

    @Test
    void giranHasTwoStaticResourceCraftersInTheCraftersRing() throws Exception {
        final MarketAreas a = MarketAreas.load(Path.of("modules/economy-market/data/market-areas.xml"));
        org.junit.jupiter.api.Assertions.assertEquals(2, a.resourceCrafters("Giran"));
        org.junit.jupiter.api.Assertions.assertEquals(0, a.resourceCrafters("Dion"));
    }
}
