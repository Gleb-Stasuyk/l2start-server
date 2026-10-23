package org.l2start.bots.director;

import java.util.ArrayList;
import java.util.List;

import org.l2start.bots.api.Pos;

/** A terrain whose world has live monsters around every player (as a starting area has): ad-hoc grounds come from them. */
final class TestTerrain {
    private TestTerrain() {
    }

    static Director.Terrain mobsAround(int lo, int hi) {
        return new Director.Terrain() {
            @Override
            public Pos ground(Pos approx) {
                return approx;
            }

            @Override
            public List<Director.MobSpot> monstersNear(Pos at, int radius) {
                final List<Director.MobSpot> out = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    final double a = (Math.PI * 2 * i) / 8;
                    final int d = 1200 + ((i % 3) * 500);
                    out.add(new Director.MobSpot(new Pos(at.x() + (int) (Math.cos(a) * d), at.y() + (int) (Math.sin(a) * d), at.z()), lo + (i % ((hi - lo) + 1))));
                }
                return out;
            }
        };
    }
}
