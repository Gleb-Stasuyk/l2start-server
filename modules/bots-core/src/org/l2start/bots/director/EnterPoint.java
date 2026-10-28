package org.l2start.bots.director;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import org.l2start.bots.api.Pos;

/**
 * Where a bot really enters the world (docs/STARTER_ZONES_PLAN.md S1, S5), for every way in: a saved position (creation,
 * restore after a restart), a GM's "near", a director's exact point. A point in the starting zone of another race is
 * replaced by a spread point of its own village; then the point is scattered ({@link Scatter}), clear of other players
 * and bots and of the stock create/respawn nodes. A store place stays as it is.
 * @param source saved / near / exact, "+race" when moved to its own village
 */
public record EnterPoint(Pos from, Pos pos, String source, boolean fallback, int tries, boolean raceMoved) {
    static final int NODES_RADIUS = 3000;

    public static EnterPoint decide(Pos wanted, String source, boolean store, int race, StarterZones zones, Collection<Pos> occupied, Director.Terrain terrain,
        Scatter.Ground ground, Random rnd) {
        if (store) {
            return new EnterPoint(wanted, wanted, source, false, 0, false);
        }
        Pos from = wanted;
        boolean moved = false;
        if ((zones != null) && zones.foreign(race, wanted)) {
            final Pos v = zones.villagePoint(race, occupied, Scatter.CLEAR, terrain, rnd);
            if (v != null) {
                from = v;
                moved = true;
            }
        }
        final List<Pos> clear = new ArrayList<>(occupied);
        if (zones != null) {
            clear.addAll(zones.nodesNear(from, NODES_RADIUS));
        }
        Scatter.Result s = Scatter.pick(from, clear, ground, rnd);
        final int zone = zones == null ? -1 : zones.raceAt(from);
        for (int k = 0; (k < 10) && (zone >= 0) && !s.fallback() && (zones.raceAt(s.pos()) != zone); k++) {
            s = Scatter.pick(from, clear, ground, rnd); // stays in its starting zone
        }
        return new EnterPoint(from, s.pos(), moved ? source + "+race" : source, s.fallback(), s.tries(), moved);
    }
}
