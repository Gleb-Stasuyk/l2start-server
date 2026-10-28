package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Pos;

/** S2 / S5 (docs/STARTER_ZONES_PLAN.md) on the real data/world.xml and the stock datapack (tests run from the repository root). */
class StartMigrationTest {
    static final List<Pos> SCHOOL = List.of(new Pos(-90875, 248162, -3570), new Pos(-90954, 248118, -3570), new Pos(-90918, 248070, -3570), new Pos(-90890, 248027, -3570));
    static StarterZones zones;
    static StockPoints stock;

    @BeforeAll
    static void load() throws Exception {
        zones = new StarterZones(WorldCatalog.load(Path.of("modules/bots-core/data/world.xml")));
        stock = StockPoints.load(Path.of("core/dist/game/data"));
        final List<Pos> nodes = new ArrayList<>(stock.creation());
        nodes.addAll(stock.respawn());
        zones.setAvoid(nodes);
        zones.setStarts(stock.starts());
    }

    @Test
    void everyStartingCreatePointIsInItsRacesZone() {
        assertTrue(stock.starts().size() >= 9, "create points of the 9 starting classes: " + stock.starts());
        stock.starts().forEach((p, race) -> assertEquals(race, zones.raceAt(p), "create point " + p));
    }

    static StartMigration.Placer placer(long seed) {
        return StartMigration.placer(zones, p -> p, ScatterTest.flat(true), new Random(seed));
    }

    @Test
    void stockPointsComeFromTheDatapack() {
        assertTrue(stock.creation().containsAll(SCHOOL), "Einhovant's School create nodes");
        assertTrue(stock.respawn().containsAll(SCHOOL), "and its respawn nodes");
        assertFalse(stock.respawn().contains(new Pos(-79411, 240677, -3450)), "chaotic respawn points are not taken");
        assertTrue(stock.creation().size() >= 9, "creation points of the starting classes: " + stock.creation().size());
    }

    @Test
    void schoolIsPartOfTalkingIslandAndEachVillageHasItsRace() {
        for (Pos p : SCHOOL) {
            assertEquals(0, zones.raceAt(p), "school " + p);
        }
        for (int race = 0; race < 5; race++) {
            assertEquals(race, zones.raceAt(zones.village(race).center()), StarterZones.VILLAGES.get(race));
        }
        assertEquals(-1, zones.raceAt(new Pos(83800, 147550, -3494)), "Giran is no starting zone");
    }

    @Test
    void migrationClearsThePiles() {
        final List<StartMigration.Row> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            final Pos p = SCHOOL.get(i % 4);
            rows.add(new StartMigration.Row(i + 1, 1000 + i, 0, p, p));
        }
        final List<StartMigration.Move> moves = StartMigration.plan(rows, stock.creation(), stock.respawn(), zones, placer(1));
        assertEquals(60, moves.size());
        final List<Pos> locs = new ArrayList<>();
        for (StartMigration.Move m : moves) {
            assertFalse(StartMigration.near(m.loc(), stock.creation()), "still on a create node: " + m.loc());
            assertFalse(StartMigration.near(m.home(), stock.creation()), "home still on a create node: " + m.home());
            assertEquals(0, zones.raceAt(m.loc()), "out of Talking Island: " + m.loc());
            locs.add(m.loc());
        }
        for (int i = 0; i < locs.size(); i++) {
            for (int j = i + 1; j < locs.size(); j++) {
                assertTrue(locs.get(i).distance2d(locs.get(j)) >= Scatter.CLEAR, locs.get(i) + " / " + locs.get(j));
            }
        }
        // a second start moves nobody
        final List<StartMigration.Row> after = new ArrayList<>();
        for (StartMigration.Move m : moves) {
            after.add(new StartMigration.Row(m.row().botId(), m.row().charId(), 0, m.loc(), m.home()));
        }
        assertTrue(StartMigration.plan(after, stock.creation(), stock.respawn(), zones, placer(2)).isEmpty());
    }

    @Test
    void everyRaceGetsItsOwnVillageNotItsCreateNode() {
        for (int race = 0; race < 5; race++) {
            for (int i = 0; i < 30; i++) {
                final Pos p = zones.villagePoint(race, List.of(), 0, x -> x, new Random((race * 100) + i));
                assertNotNull(p);
                assertEquals(race, zones.raceAt(p), StarterZones.VILLAGES.get(race) + " " + p);
                assertFalse(StartMigration.near(p, stock.creation()), "on a create node: " + p);
            }
        }
    }

    @Test
    void respawnNodesOutsideStartingZonesAreScatteredInPlace() {
        final Pos giran = stock.respawn().stream().filter(p -> p.distance2d(new Pos(83800, 147550, -3494)) < 3000).findFirst().orElseThrow();
        final List<StartMigration.Row> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(new StartMigration.Row(i + 1, 1000 + i, i % 5, giran, new Pos(0, 0, 0)));
        }
        final List<StartMigration.Move> moves = StartMigration.plan(rows, stock.creation(), stock.respawn(), zones, placer(3));
        assertEquals(10, moves.size());
        for (StartMigration.Move m : moves) {
            assertTrue(m.loc().distance2d(giran) >= Scatter.MIN - 1, "on the node: " + m.loc());
            assertTrue(m.loc().distance2d(giran) < 3000, "sent away from Giran: " + m.loc());
        }
    }

    @Test
    void botsOfAnotherRaceLeaveAStartingZone() {
        final Pos elvenCentre = zones.village(1).center();
        final List<StartMigration.Row> rows = List.of(new StartMigration.Row(1, 1001, 3, elvenCentre, elvenCentre)); // an orc in the Elven Village
        final List<StartMigration.Move> moves = StartMigration.plan(rows, stock.creation(), stock.respawn(), zones, placer(4));
        assertEquals(1, moves.size());
        assertEquals(3, zones.raceAt(moves.get(0).loc()), "orc moved to the Orc Village: " + moves.get(0).loc());
        assertEquals(3, zones.raceAt(moves.get(0).home()));
    }
}
