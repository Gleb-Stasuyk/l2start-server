package org.l2start.bots.director;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import org.l2start.bots.api.Pos;

/**
 * Start-up relocation of bot personas that are out of the world (docs/STARTER_ZONES_PLAN.md S2, S5):
 * <ul>
 * <li>saved position or home within {@link #NEAR} of a stock creation point: to a spread point of its own starting
 * village (a new character's home is never the create node any more);</li>
 * <li>saved position within {@link #NEAR} of another stock respawn point: scattered around it;</li>
 * <li>saved position or home in the starting zone of another race: to its own starting village.</li>
 * </ul>
 * Idempotent: a second run moves nobody.
 */
public final class StartMigration {
    public static final int NEAR = 300;

    /** One persona: where it logged out and its home. */
    public record Row(int botId, int charId, int race, Pos loc, Pos home) {
    }

    /** @param why what was wrong (create node, respawn node, foreign zone) */
    public record Move(Row row, Pos loc, Pos home, String why) {
    }

    /** Where to put somebody: a spread village point of a race, a scatter around a point. */
    public interface Placer {
        /** null = no village point (unknown race, no ground) */
        Pos village(int race, Collection<Pos> occupied);

        Scatter.Result around(Pos center, Collection<Pos> occupied);
    }

    private StartMigration() {
    }

    public static List<Move> plan(List<Row> rows, Collection<Pos> creation, Collection<Pos> respawn, StarterZones zones, Placer placer) {
        final List<Move> out = new ArrayList<>();
        final List<Pos> taken = new ArrayList<>(); // where the moved ones are now (they must not pile up again)
        for (Row r : rows) {
            final boolean locCreate = near(r.loc(), creation);
            final boolean homeCreate = near(r.home(), creation);
            final boolean locForeign = zones.foreign(r.race(), r.loc());
            final boolean homeForeign = zones.foreign(r.race(), r.home());
            final boolean locRespawn = !locCreate && near(r.loc(), respawn);
            if (!locCreate && !homeCreate && !locForeign && !homeForeign && !locRespawn) {
                continue;
            }
            Pos loc = r.loc();
            Pos home = r.home();
            final List<String> why = new ArrayList<>();
            if (locCreate || locForeign) {
                final Pos v = placer.village(r.race(), taken);
                if (v != null) {
                    loc = v;
                    why.add(locCreate ? "at a create node" : "in a foreign starting zone");
                }
            } else if (locRespawn) {
                Scatter.Result s = placer.around(r.loc(), taken);
                for (int k = 0; (k < 10) && !s.fallback() && (near(s.pos(), respawn) || near(s.pos(), creation)); k++) {
                    s = placer.around(r.loc(), taken); // off every node, or the next start moves it again
                }
                if (!s.fallback()) {
                    loc = s.pos();
                    why.add("at a respawn node");
                }
            }
            if (homeCreate || homeForeign) {
                final Pos v = (loc != r.loc()) && !zones.foreign(r.race(), loc) && (zones.raceAt(loc) == r.race()) ? loc : placer.village(r.race(), taken);
                if (v != null) {
                    home = v;
                    why.add(homeCreate ? "home at a create node" : "home in a foreign starting zone");
                }
            }
            if ((loc != r.loc()) || (home != r.home())) {
                taken.add(loc);
                out.add(new Move(r, loc, home, String.join(", ", why)));
            }
        }
        return out;
    }

    static boolean near(Pos p, Collection<Pos> points) {
        if ((p == null) || ((p.x() == 0) && (p.y() == 0))) {
            return false;
        }
        for (Pos s : points) {
            if (s.distance2d(p) <= NEAR) {
                return true;
            }
        }
        return false;
    }

    /** A village placer on a terrain (the game: geodata; tests: flat). */
    public static Placer placer(StarterZones zones, Director.Terrain terrain, Scatter.Ground ground, Random rnd) {
        return new Placer() {
            @Override
            public Pos village(int race, Collection<Pos> occupied) {
                return zones.villagePoint(race, occupied, Scatter.CLEAR, terrain, rnd);
            }

            @Override
            public Scatter.Result around(Pos center, Collection<Pos> occupied) {
                return Scatter.pick(center, occupied, ground, rnd);
            }
        };
    }
}
