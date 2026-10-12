package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.economy.market.model.SpotGenerator.Area;
import org.l2start.economy.market.model.SpotGenerator.Blocker;
import org.l2start.economy.market.model.SpotGenerator.Ground;
import org.l2start.economy.market.model.SpotGenerator.Point;

class SpotGeneratorTest {
    static final Area GIRAN = new Area("Giran", 82800, 148500, -3464, 81300, 84300, 147300, 149700, 64, 70, 160, 320, 600);

    /** Flat ground with a wall along x = 83500 (nobody walks through it) and a pit (no ground) in one corner. */
    static final Ground WALLED = new Ground() {
        @Override
        public Integer z(int x, int y, int z) {
            return ((x < 81600) && (y < 147600)) ? null : -3464;
        }

        @Override
        public boolean walk(int x1, int y1, int z1, int x2, int y2, int z2) {
            return (x1 < 83500) == (x2 < 83500);
        }
    };

    @Test
    void sixtyScatteredPlacesKeepTheirDistanceAndStayClearOfNpcs() {
        final List<Blocker> npcs = List.of(new Blocker(83300, 148000, 320), new Blocker(82500, 148900, 160));
        final List<Point> p = SpotGenerator.generate(GIRAN, List.of(), 60, npcs, WALLED, new Random(7), 20000);
        assertEquals(60, p.size());
        for (int i = 0; i < p.size(); i++) {
            final Point a = p.get(i);
            assertTrue(a.x() < 83500, "behind the wall is not reachable from the centre");
            assertFalse((a.x() < 81600) && (a.y() < 147600), "no ground there");
            assertFalse(SpotGenerator.blocked(npcs, a.x(), a.y()));
            for (int j = i + 1; j < p.size(); j++) {
                final Point b = p.get(j);
                assertTrue(Math.hypot(a.x() - b.x(), a.y() - b.y()) >= 70);
            }
        }
        // not rows: many different x and y values
        assertTrue(p.stream().map(Point::x).distinct().count() > 50);
    }

    @Test
    void sameSeedSamePlacesAndKeptPlacesNeverMove() {
        final List<Point> a = SpotGenerator.generate(GIRAN, List.of(), 20, List.of(), WALLED, new Random(42), 5000);
        final List<Point> b = SpotGenerator.generate(GIRAN, List.of(), 20, List.of(), WALLED, new Random(42), 5000);
        assertEquals(a, b);
        final List<Point> more = SpotGenerator.generate(GIRAN, a, 10, List.of(), WALLED, new Random(43), 5000);
        assertEquals(10, more.size());
        final List<Point> all = new ArrayList<>(a);
        all.addAll(more);
        for (Point m : more) {
            for (Point k : a) {
                assertTrue(Math.hypot(m.x() - k.x(), m.y() - k.y()) >= 70, "new places keep away from the kept ones");
            }
        }
    }

    @Test
    void narrowPassagesAndHeightStepsAreLeftFree() {
        final Ground corridor = new Ground() {
            @Override
            public Integer z(int x, int y, int z) {
                return (y > 149000) ? -3300 : -3464; // a terrace far above the square
            }

            @Override
            public boolean walk(int x1, int y1, int z1, int x2, int y2, int z2) {
                return Math.abs(y1 - y2) < 50; // only east-west: a corridor
            }
        };
        assertFalse(SpotGenerator.open(corridor, 82000, 148000, -3464));
        assertTrue(SpotGenerator.generate(GIRAN, List.of(), 5, List.of(), corridor, new Random(1), 3000).isEmpty());
    }
}
