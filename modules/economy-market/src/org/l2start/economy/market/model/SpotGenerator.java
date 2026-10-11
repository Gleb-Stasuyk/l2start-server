package org.l2start.economy.market.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Store places of a town scattered like players put them (decision of 12.10.2026): random points in the town's market
 * area, on walkable ground near the square's height, reachable from the market centre (or from a place already taken
 * nearby), not closer than {@code spacing} to each other, clear of NPCs (more room at gatekeepers and warehouses, so
 * doors stay free) and not in narrow passages. Pure: the game gives the ground and the NPCs. Seeded, and the caller
 * keeps the result in the database, so the places stay the same.
 */
public final class SpotGenerator {
    /** The market area of a town. {@code dz}: allowed height difference to the square; {@code reach}: how far a new place may be from one it is reached from. */
    public record Area(String town, int cx, int cy, int z, int minX, int maxX, int minY, int maxY, int dz, int spacing, int npcGap, int doorGap, int reach) {
    }

    /** Something to keep away from: an NPC with the room it needs. */
    public record Blocker(int x, int y, int radius) {
    }

    /** The world as the generator needs it. */
    public interface Ground {
        /** Height of walkable ground at x, y near {@code z}, or null (no ground / no geodata answer). */
        Integer z(int x, int y, int z);

        /** A character walks straight from one point to the other. */
        boolean walk(int x1, int y1, int z1, int x2, int y2, int z2);
    }

    /** A place: x, y, z, heading. */
    public record Point(int x, int y, int z, int heading) {
    }

    static final int OPEN_DIRS = 6; // of 8 directions a few steps around must be free (no narrow passage, no wall corner)
    static final int OPEN_STEP = 110;

    private SpotGenerator() {
    }

    /**
     * @param existing places kept from before (never moved; new ones keep away from them)
     * @param want how many new places
     * @return the new places (fewer when the area has no more room)
     */
    public static List<Point> generate(Area a, List<Point> existing, int want, List<Blocker> blockers, Ground g, Random rnd, int tries) {
        final List<Point> all = new ArrayList<>(existing);
        final List<Point> out = new ArrayList<>();
        for (int t = 0; (t < tries) && (out.size() < want); t++) {
            final int x = a.minX() + rnd.nextInt(Math.max(1, (a.maxX() - a.minX()) + 1));
            final int y = a.minY() + rnd.nextInt(Math.max(1, (a.maxY() - a.minY()) + 1));
            final Integer gz = g.z(x, y, a.z());
            if ((gz == null) || (Math.abs(gz - a.z()) > a.dz())) {
                continue;
            }
            if (near(all, x, y, a.spacing()) || blocked(blockers, x, y)) {
                continue;
            }
            if (!open(g, x, y, gz) || !reached(a, all, g, x, y, gz)) {
                continue;
            }
            final Point p = new Point(x, y, gz, rnd.nextInt(65536));
            all.add(p);
            out.add(p);
        }
        return out;
    }

    static boolean near(List<Point> all, int x, int y, int spacing) {
        final long s2 = (long) spacing * spacing;
        for (Point p : all) {
            final long dx = p.x() - x;
            final long dy = p.y() - y;
            if (((dx * dx) + (dy * dy)) < s2) {
                return true;
            }
        }
        return false;
    }

    static boolean blocked(List<Blocker> blockers, int x, int y) {
        for (Blocker b : blockers) {
            final long dx = b.x() - x;
            final long dy = b.y() - y;
            if (((dx * dx) + (dy * dy)) < ((long) b.radius() * b.radius())) {
                return true;
            }
        }
        return false;
    }

    static boolean open(Ground g, int x, int y, int z) {
        int free = 0;
        for (int d = 0; d < 8; d++) {
            final double ang = (Math.PI / 4) * d;
            if (g.walk(x, y, z, x + (int) Math.round(Math.cos(ang) * OPEN_STEP), y + (int) Math.round(Math.sin(ang) * OPEN_STEP), z)) {
                free++;
            }
        }
        return free >= OPEN_DIRS;
    }

    static boolean reached(Area a, List<Point> all, Ground g, int x, int y, int z) {
        if (g.walk(a.cx(), a.cy(), a.z(), x, y, z)) {
            return true;
        }
        final long r2 = (long) a.reach() * a.reach();
        for (Point p : all) {
            final long dx = p.x() - x;
            final long dy = p.y() - y;
            if ((((dx * dx) + (dy * dy)) <= r2) && g.walk(p.x(), p.y(), p.z(), x, y, z)) {
                return true;
            }
        }
        return false;
    }
}
