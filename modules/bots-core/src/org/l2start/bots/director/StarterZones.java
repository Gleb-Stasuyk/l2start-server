package org.l2start.bots.director;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;

/**
 * The five starting zones (docs/STARTER_ZONES_PLAN.md): a starting village (its polygon in data/world.xml) and the
 * hunting grounds of that village up to {@link #MAX_LEVEL}. Talking Island - humans, Elven Village - elves, Dark Elven
 * Village - dark elves, Orc Village - orcs, Dwarven Village - dwarves. Only bots of that race live there.
 */
public final class StarterZones {
    /** Grounds of a starting village above this level (e.g. wasteland_5, 25-27, marked talking_island) are not part of it. */
    public static final int MAX_LEVEL = 20;
    /** Starting villages by race (0 human .. 4 dwarf). */
    public static final List<String> VILLAGES = List.of("talking_island", "elven_village", "dark_elven_village", "orc_village", "dwarven_village");
    static final int TOWN_MARGIN = 300;

    private final WorldCatalog catalog;
    /** Stock create/respawn nodes: a village point keeps more than {@link StartMigration#NEAR} from them. */
    private volatile List<Pos> avoid = List.of();

    public StarterZones(WorldCatalog catalog) {
        this.catalog = catalog;
    }

    public void setAvoid(Collection<Pos> nodes) {
        avoid = List.copyOf(nodes);
    }

    public List<Pos> avoid() {
        return avoid;
    }

    public WorldCatalog catalog() {
        return catalog;
    }

    /** Race of a starting class (0 human .. 4 dwarf), -1 = not a starting class. */
    public static int raceOfStartClass(int startClass) {
        return switch (startClass) {
            case 0, 10 -> 0;
            case 18, 25 -> 1;
            case 31, 38 -> 2;
            case 44, 49 -> 3;
            case 53 -> 4;
            default -> -1;
        };
    }

    /** Race of the starting zone at {@code p}, -1 = not in a starting zone. */
    public int raceAt(Pos p) {
        final WorldCatalog.Town t = catalog.townAt(p, TOWN_MARGIN);
        if (t != null) {
            return VILLAGES.indexOf(t.id());
        }
        for (WorldCatalog.Ground g : catalog.groundsNear(p, 0)) {
            if ((g.maxLevel() <= MAX_LEVEL) && (g.center().distance2d(p) <= g.radius())) {
                final int r = VILLAGES.indexOf(g.town());
                if (r >= 0) {
                    return r;
                }
            }
        }
        return -1;
    }

    /** {@code p} is in the starting zone of another race than {@code race}. */
    public boolean foreign(int race, Pos p) {
        if (race < 0) {
            return false;
        }
        final int z = raceAt(p);
        return (z >= 0) && (z != race);
    }

    public WorldCatalog.Town village(int race) {
        return ((race < 0) || (race >= VILLAGES.size())) ? null : catalog.town(VILLAGES.get(race));
    }

    /** Is this ground part of a starting zone (and of which race), -1 = no. */
    public static int raceOfGround(WorldCatalog.Ground g) {
        return g.maxLevel() <= MAX_LEVEL ? VILLAGES.indexOf(g.town()) : -1;
    }

    /**
     * A spread point in the starting village of {@code race}: near its gatekeeper, shops, warehouse or trainers (never at
     * the stock create/respawn nodes), at least {@code clear} from {@code occupied} when possible, on the ground.
     * @return null when the village or the ground is unknown
     */
    public Pos villagePoint(int race, Collection<Pos> occupied, int clear, Director.Terrain terrain, Random rnd) {
        final WorldCatalog.Town t = village(race);
        if (t == null) {
            return null;
        }
        final List<Pos> spots = new ArrayList<>();
        for (Assignment.Anchor a : t.anchors()) {
            switch (a.kind()) {
                case "gk", "shop", "wh", "trainer" -> spots.add(a.pos());
                default -> {
                }
            }
        }
        if (spots.isEmpty()) {
            spots.add(t.center());
        }
        Pos loose = null;
        for (int round = 0; round < 3; round++) {
            Collections.shuffle(spots, rnd);
            for (Pos s : spots) {
                final double ang = rnd.nextDouble() * Math.PI * 2;
                final int r = 120 + rnd.nextInt(281);
                final Pos approx = new Pos(s.x() + (int) (Math.cos(ang) * r), s.y() + (int) (Math.sin(ang) * r), s.z());
                final Pos p = terrain.ground(approx);
                if ((p == null) || (Scatter.nearest(p, avoid) <= StartMigration.NEAR)) {
                    continue;
                }
                if (Scatter.nearest(p, occupied) >= clear) {
                    return p;
                }
                if (loose == null) {
                    loose = p;
                }
            }
        }
        return loose;
    }
}
