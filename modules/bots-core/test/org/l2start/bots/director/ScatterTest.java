package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Pos;

/** S1 (docs/STARTER_ZONES_PLAN.md): every login is scattered around its point, nobody lands on another one. */
class ScatterTest {
    static final Pos SCHOOL = new Pos(-90954, 248118, -3570);

    static Scatter.Ground flat(boolean geo) {
        return new Scatter.Ground() {
            @Override
            public boolean hasGeo(int x, int y) {
                return geo;
            }

            @Override
            public int height(int x, int y, int z) {
                return z;
            }

            @Override
            public boolean reachable(Pos from, Pos to) {
                return true;
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return true;
            }

            @Override
            public boolean water(int x, int y, int z) {
                return false;
            }
        };
    }

    static void assertSpread(List<Pos> placed, Pos center) {
        for (int i = 0; i < placed.size(); i++) {
            assertTrue(placed.get(i).distance2d(center) >= Scatter.MIN - 1, "on the centre: " + placed.get(i));
            for (int j = i + 1; j < placed.size(); j++) {
                assertTrue(placed.get(i).distance2d(placed.get(j)) >= Scatter.CLEAR, placed.get(i) + " / " + placed.get(j));
            }
        }
    }

    static List<Pos> enter50(Scatter.Ground g, long seed) {
        final Random rnd = new Random(seed);
        final List<Pos> placed = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final Scatter.Result r = Scatter.pick(SCHOOL, placed, g, rnd);
            assertFalse(r.fallback(), "fallback at " + i);
            placed.add(r.pos());
        }
        return placed;
    }

    @Test
    void fiftyLoginsFromOneSavedPointNeverPile() {
        for (long seed = 1; seed <= 20; seed++) {
            assertSpread(enter50(flat(true), seed), SCHOOL);
        }
    }

    @Test
    void withoutGeodataARandomPointNotTheCentre() {
        assertSpread(enter50(flat(false), 7), SCHOOL);
    }

    @Test
    void heightComesFromGeodataAndOtherFloorsAreSkipped() {
        final Scatter.Ground floors = new Scatter.Ground() {
            @Override
            public boolean hasGeo(int x, int y) {
                return true;
            }

            @Override
            public int height(int x, int y, int z) {
                return x > SCHOOL.x() ? z + 1000 : z - 7; // east of the centre: a floor above
            }

            @Override
            public boolean reachable(Pos from, Pos to) {
                return true;
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return true;
            }

            @Override
            public boolean water(int x, int y, int z) {
                return false;
            }
        };
        final Random rnd = new Random(3);
        for (int i = 0; i < 200; i++) {
            final Pos p = Scatter.pick(SCHOOL, List.of(), floors, rnd).pos();
            assertTrue(p.x() <= SCHOOL.x(), "another floor: " + p);
            assertEquals(SCHOOL.z() - 7, p.z());
        }
    }

    @Test
    void unreachablePointsAreSkipped() {
        final Scatter.Ground wall = new Scatter.Ground() {
            @Override
            public boolean hasGeo(int x, int y) {
                return true;
            }

            @Override
            public int height(int x, int y, int z) {
                return z;
            }

            @Override
            public boolean reachable(Pos from, Pos to) {
                return to.y() < from.y(); // north of the centre is behind a wall
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return true;
            }

            @Override
            public boolean water(int x, int y, int z) {
                return false;
            }
        };
        final Random rnd = new Random(5);
        for (int i = 0; i < 200; i++) {
            assertTrue(Scatter.pick(SCHOOL, List.of(), wall, rnd).pos().y() < SCHOOL.y());
        }
    }

    @Test
    void theCentreOnlyWhenNothingFits() {
        final Scatter.Ground nowhere = new Scatter.Ground() {
            @Override
            public boolean hasGeo(int x, int y) {
                return true;
            }

            @Override
            public int height(int x, int y, int z) {
                return z;
            }

            @Override
            public boolean reachable(Pos from, Pos to) {
                return false;
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return true;
            }

            @Override
            public boolean water(int x, int y, int z) {
                return false;
            }
        };
        final Scatter.Result r = Scatter.pick(SCHOOL, List.of(), nowhere, new Random(1));
        assertTrue(r.fallback());
        assertEquals(SCHOOL, r.pos());
    }
}
