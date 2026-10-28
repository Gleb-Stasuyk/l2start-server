package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Pos;

/** S1 + S5: every way in (here: the restore after a restart = the saved position) is scattered and keeps starting zones to their race. */
class EnterPointTest {
    static StarterZones zones;

    @BeforeAll
    static void load() throws Exception {
        zones = new StarterZones(WorldCatalog.load(Path.of("modules/bots-core/data/world.xml")));
        final StockPoints stock = StockPoints.load(Path.of("core/dist/game/data"));
        final List<Pos> nodes = new ArrayList<>(stock.creation());
        nodes.addAll(stock.respawn());
        zones.setAvoid(nodes);
        zones.setStarts(stock.starts());
    }

    @Test
    void restoreAfterARestartScattersTheSchoolPile() {
        final Random rnd = new Random(11);
        final List<Pos> inWorld = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final Pos saved = StartMigrationTest.SCHOOL.get(i % 4);
            final EnterPoint e = EnterPoint.decide(saved, "saved", false, 0, zones, inWorld, p -> p, ScatterTest.flat(true), rnd);
            assertFalse(e.fallback());
            assertFalse(e.raceMoved(), "a human on Talking Island stays there");
            assertTrue(e.pos().distance2d(saved) >= Scatter.MIN - 1, "on the node: " + e.pos());
            inWorld.add(e.pos());
        }
        for (int i = 0; i < inWorld.size(); i++) {
            for (int j = i + 1; j < inWorld.size(); j++) {
                assertTrue(inWorld.get(i).distance2d(inWorld.get(j)) >= Scatter.CLEAR, inWorld.get(i) + " / " + inWorld.get(j));
            }
        }
        for (Pos p : inWorld) {
            for (Pos n : StartMigrationTest.SCHOOL) {
                assertTrue(p.distance2d(n) >= Scatter.MIN - 1, "on a school node: " + p);
            }
        }
    }

    @Test
    void restoreInAnotherRacesStartingZoneGoesToItsOwnVillage() {
        final Random rnd = new Random(12);
        for (int race = 0; race < 5; race++) {
            for (int zone = 0; zone < 5; zone++) {
                final Pos saved = zones.village(zone).center();
                final EnterPoint e = EnterPoint.decide(saved, "saved", false, race, zones, List.of(), p -> p, ScatterTest.flat(true), rnd);
                assertEquals(race, zones.raceAt(e.pos()), "race " + race + " saved in " + StarterZones.VILLAGES.get(zone) + " entered at " + e.pos());
                assertEquals(race != zone, e.raceMoved());
                assertEquals(race != zone ? "saved+race" : "saved", e.source());
            }
        }
    }

    @Test
    void outsideStartingZonesRacesStayMixed() {
        final Pos giran = new Pos(83800, 147550, -3494);
        for (int race = 0; race < 5; race++) {
            final EnterPoint e = EnterPoint.decide(giran, "exact", false, race, zones, List.of(), p -> p, ScatterTest.flat(true), new Random(race));
            assertFalse(e.raceMoved());
            assertTrue(e.pos().distance2d(giran) < 2500);
        }
    }

    @Test
    void aStorePlaceStaysExact() {
        final Pos store = new Pos(83000, 148000, -3400);
        final EnterPoint e = EnterPoint.decide(store, "exact", true, 3, zones, List.of(store), p -> p, ScatterTest.flat(true), new Random(1));
        assertEquals(store, e.pos());
    }
}
