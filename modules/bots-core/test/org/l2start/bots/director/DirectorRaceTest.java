package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Pos;

/** S5 (docs/STARTER_ZONES_PLAN.md): the director brings only bots of a starting zone's race into it (village, grounds, passers-by). */
class DirectorRaceTest {
    static WorldCatalog catalog() throws Exception {
        return WorldCatalog.load(Path.of("modules/bots-core/data/world.xml"));
    }

    static Pos groundOf(WorldCatalog c, int race) {
        for (WorldCatalog.Ground g : c.grounds()) {
            if ((StarterZones.raceOfGround(g) == race) && (g.minLevel() <= 4)) {
                return g.center();
            }
        }
        throw new IllegalStateException("no low ground for race " + race);
    }

    @Test
    void eachStartingZoneGetsOnlyItsRace() throws Exception {
        final WorldCatalog c = catalog();
        final StarterZones zones = new StarterZones(c);
        for (int race = 0; race < 5; race++) {
            for (Pos player : new Pos[] { groundOf(c, race), zones.village(race).center() }) {
                final Director d = new Director(c, DirectorTest.withWander(2.0), new Random(race));
                d.setStarterZones(zones);
                final Map<Integer, Integer> raceOf = new HashMap<>();
                final List<Director.Candidate> pool = new ArrayList<>();
                for (int i = 0; i < 300; i++) {
                    final int id = 1000 + i;
                    final int r = i % 5;
                    raceOf.put(id, r);
                    pool.add(new Director.Candidate(id, 1 + (i % 18), 0, r));
                }
                int seen = 0;
                for (int t = 0; t < 120; t++) {
                    final List<Director.Command> out = d.tick(1_000_000_000L + (t * 5_000L), 20, DirectorTest.at(player), List.of(), (lo, hi, now) -> {
                        final List<Director.Candidate> l = new ArrayList<>();
                        for (Director.Candidate x : pool) {
                            if ((x.level() >= lo) && (x.level() <= hi)) {
                                l.add(x);
                            }
                        }
                        return l;
                    }, p -> p);
                    for (Director.Command cmd : out) {
                        if (cmd instanceof Director.Command.Materialize m) {
                            final int r = m.botId() == 0 ? m.race() : raceOf.get(m.botId());
                            if (zones.raceAt(m.at()) >= 0) {
                                assertEquals(race, r, StarterZones.VILLAGES.get(race) + ": " + m.assignment().kind() + " " + m.assignment().place() + " at " + m.at());
                                seen++;
                            }
                            pool.removeIf(x -> x.botId() == m.botId());
                        }
                    }
                }
                assertTrue(seen > 0, "nobody came to " + StarterZones.VILLAGES.get(race) + " around " + player);
            }
        }
    }
}
