package org.l2start.bots.director;

import java.util.Collection;
import java.util.Random;

import org.l2start.bots.api.Pos;

/**
 * Where a bot really appears around a wanted point (any login, a return to town, a GM spawn): a ring of
 * {@link #MIN}..{@link #MAX} around it, the height from geodata, never closer than {@link #CLEAR} to another player or
 * bot; up to {@link #TRIES} tries with a growing ring. Without geodata a random point of the ring (never the centre).
 * The centre itself only when nothing fits ({@link Result#fallback()}: the caller logs a WARN).
 */
public final class Scatter {
    public static final int MIN = 150;
    public static final int MAX = 600;
    public static final int CLEAR = 150;
    public static final int TRIES = 30;
    /** Another floor (a building, a bridge): not the same place. Tightened from 300 to avoid roofs/under bridges. */
    static final int MAX_DZ = 100;

    /** Geodata for the scatter (stock GeoEngine in the game, a fake in tests). */
    public interface Ground {
        boolean hasGeo(int x, int y);

        int height(int x, int y, int z);

        /** One can walk there from the centre (straight or by stock pathfinding). */
        boolean reachable(Pos from, Pos to);

        /** The cell is passable (not a wall, not blocked). */
        boolean passable(int x, int y, int z);

        /** The point is in water. */
        boolean water(int x, int y, int z);
    }

    /** @param fallback nothing fitted: the centre itself */
    public record Result(Pos pos, boolean fallback, int tries) {
    }

    private Scatter() {
    }

    public static Result pick(Pos center, Collection<Pos> occupied, Ground ground, Random rnd) {
        for (int i = 0; i < TRIES; i++) {
            final double outer = MAX * (1 + (i / 10.0)); // 600, then up to ~2300 on the last tries
            final double ang = rnd.nextDouble() * Math.PI * 2;
            // uniform over the ring area
            final double r = Math.sqrt((MIN * (double) MIN) + (rnd.nextDouble() * ((outer * outer) - (MIN * (double) MIN))));
            final int x = center.x() + (int) Math.round(Math.cos(ang) * r);
            final int y = center.y() + (int) Math.round(Math.sin(ang) * r);
            final Pos p;
            if (ground.hasGeo(x, y)) {
                final int z = ground.height(x, y, center.z());
                if (Math.abs(z - center.z()) > MAX_DZ) {
                    continue;
                }
                p = new Pos(x, y, z);
                if (!ground.passable(x, y, z) || ground.water(x, y, z) || !clear(p, occupied) || !ground.reachable(center, p)) {
                    continue;
                }
            } else {
                p = new Pos(x, y, center.z());
                if (!clear(p, occupied)) {
                    continue;
                }
            }
            return new Result(p, false, i + 1);
        }
        return new Result(center, true, TRIES);
    }

    static boolean clear(Pos p, Collection<Pos> occupied) {
        for (Pos o : occupied) {
            if (o.distance2d(p) < CLEAR) {
                return false;
            }
        }
        return true;
    }

    /** Distance to the nearest of {@code others} (infinity when none). */
    public static double nearest(Pos p, Collection<Pos> others) {
        double best = Double.POSITIVE_INFINITY;
        for (Pos o : others) {
            best = Math.min(best, o.distance2d(p));
        }
        return best;
    }
}
