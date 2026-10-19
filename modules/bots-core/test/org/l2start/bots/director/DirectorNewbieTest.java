package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;

/** S6 improvements: a new live character of any race gets 1-3 beginners of its race near the gremlins (scattered), once. */
class DirectorNewbieTest {
    static WorldCatalog catalog;
    static StarterZones zones;
    static StockPoints stock;
    static List<Pos> gremlins;

    @BeforeAll
    static void load() throws Exception {
        catalog = WorldCatalog.load(Path.of("modules/bots-core/data/world.xml"));
        zones = new StarterZones(catalog);
        stock = StockPoints.load(Path.of("core/dist/game/data"));
        gremlins = StockPoints.gremlins(Path.of("core/dist/game/data"));
        zones.setStarts(stock.starts());
    }

    @Test
    void everyStartingCreatePointHasGremlinsNearby() {
        assertTrue(gremlins.size() >= 6, "gremlin spawns: " + gremlins);
        int checked = 0;
        for (Pos c : stock.creation()) {
            if (zones.raceAt(c) >= 0) {
                assertNotNull(StockPoints.nearest(gremlins, c, 2500), "no gremlins near the create point " + c + " (race " + zones.raceAt(c) + ")");
                checked++;
            }
        }
        assertTrue(checked >= 9, "create points in starting zones: " + checked);
    }

    @Test
    void newPlayerOfAnyRaceGetsCompanionsOfItsRaceAtItsGremlinsOnce() {
        for (int race = 0; race < 5; race++) {
            Pos create = null;
            for (Pos c : stock.creation()) {
                if (zones.raceAt(c) == race) {
                    create = c;
                    break;
                }
            }
            assertNotNull(create, "a create point of race " + race);
            final Pos grem = StockPoints.nearest(gremlins, create, 2500);
            final Director d = new Director(catalog, DirectorConfig.defaults(), new Random(race));
            d.setStarterZones(zones);
            final Map<Integer, Integer> raceOf = new HashMap<>();
            final List<Director.Candidate> pool = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                raceOf.put(2000 + i, i % 5);
                pool.add(new Director.Candidate(2000 + i, 1 + (i % 3), 0, i % 5));
            }
            final Pos home = zones.villagePoint(race, List.of(), 0, p -> p, new Random(1));
            final long t0 = 1_000_000_000L;
            assertTrue(d.requestNewbie(77 + race, race, 2, grem, zones.village(race).id(), home, t0));
            assertFalse(d.requestNewbie(77 + race, race, 2, grem, zones.village(race).id(), home, t0), "once per character");
            final List<Director.Command.Materialize> came = new ArrayList<>();
            final List<Director.BotNow> inWorld = new ArrayList<>();
            for (int t = 0; t < 20; t++) {
                for (Director.Command cmd : d.tick(t0 + (t * 2000L), 14, DirectorTest.at(create), new ArrayList<>(inWorld), (lo, hi, now) -> {
                    final List<Director.Candidate> l = new ArrayList<>();
                    for (Director.Candidate x : pool) {
                        if ((x.level() >= lo) && (x.level() <= hi)) {
                            l.add(x);
                        }
                    }
                    return l;
                }, p -> p)) {
                    if ((cmd instanceof Director.Command.Materialize m) && (m.assignment().kind() == Assignment.Kind.NEWBIE)) {
                        came.add(m);
                        pool.removeIf(x -> x.botId() == m.botId());
                        inWorld.add(new Director.BotNow(m.botId(), m.at(), m.assignment(), false, m.botId()));
                    }
                }
            }
            assertEquals(2, came.size(), StarterZones.VILLAGES.get(race));
            for (Director.Command.Materialize m : came) {
                assertEquals(race, m.botId() == 0 ? m.race() : raceOf.get(m.botId()), "race of the companion");
                assertTrue((m.level() >= 1) && (m.level() <= 3), "level " + m.level());
                assertTrue(m.at().distance2d(create) >= DirectorConfig.defaults().hiddenMin(), "appeared in sight: " + m.at());
                assertEquals(grem, m.assignment().center(), "the gremlins nearest to the player");
                assertEquals(home, m.assignment().exit());
            }
            // back home after its gremlins: town life or it runs off out of sight
            final Director.Command.Materialize m = came.get(0);
            final List<Director.BotNow> back = List.of(new Director.BotNow(m.botId(), home, m.assignment(), false, m.botId()));
            boolean handled = false;
            for (int t = 0; (t < 60) && !handled; t++) {
                for (Director.Command cmd : d.tick(t0 + 60_000 + (t * 2000L), 14, DirectorTest.at(create), back, (lo, hi, now) -> List.of(), p -> p)) {
                    if ((cmd instanceof Director.Command.Assign a) && (a.botId() == m.botId())) {
                        assertTrue((a.assignment().kind() == Assignment.Kind.TOWN) || (a.assignment().kind() == Assignment.Kind.LEAVE_RUN), a.assignment().toString());
                        handled = true;
                    }
                }
            }
            assertTrue(handled, "nothing done with the companion back home");
        }
    }

    @Test
    void killsBeforeGoingHomeAreTenToFifteen() {
        for (long seq = 1; seq < 500; seq++) {
            final int k = Assignment.newbieKills(seq, (int) (seq * 7));
            assertTrue((k >= 10) && (k <= 15), "kills " + k);
        }
    }
}
