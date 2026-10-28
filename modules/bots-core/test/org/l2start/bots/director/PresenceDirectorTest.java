package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;

/** Live test 2026-10-20: field top-ups ran away (8, then 15 with max 3) and noPersona grew (8 -> 13). */
class PresenceDirectorTest {
    static final Pos ORC_CREATION = new Pos(-56693, -113610, -690);

    static Director director() throws Exception {
        final WorldCatalog catalog = WorldCatalog.load(java.nio.file.Path.of("modules/bots-core/data/world.xml"));
        final Director d = new Director(catalog, DirectorConfig.defaults(), new Random(7));
        final StarterZones zones = new StarterZones(catalog);
        zones.setStarts(Map.of(ORC_CREATION, 3)); // the live server feeds the stock create points
        d.setStarterZones(zones);
        d.setPresenceField(1, 3);
        d.setPresenceTown("starter", 3, 5);
        return d;
    }

    @Test
    void deficitCountsPendingBots() throws Exception {
        final Director d = director();
        final List<Director.Candidate> pool = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            pool.add(new Director.Candidate(i, 1 + (i % 5), 0, 3));
        }
        long now = 1_000_000_000L;
        final long until = now + Math.min(40_000L, DirectorConfig.defaults().pendingTimeoutMs() - 1000);
        int field = 0;
        // the bots never arrive within the window (still materializing): they stay pending and count
        for (; now < until; now += 2000) {
            for (Director.Command c : d.tick(now, 20, List.of(new Director.Live(1, ORC_CREATION)), List.of(), (lo, hi, x) -> pool, TestTerrain.mobsAround(1, 5))) {
                if ((c instanceof Director.Command.Materialize m) && (m.assignment().kind() == Assignment.Kind.HUNT) && m.assignment().place().startsWith("adhoc_")) {
                    field++;
                }
            }
        }
        assertTrue(field >= 1, "no field top-up at all");
        assertTrue(field <= 3, "field top-ups ran away past the max of 3: " + field);
    }

    @Test
    void noPersonaCreatesPersonas() throws Exception {
        final Director d = director();
        long now = 1_000_000_000L;
        int created = 0;
        for (int t = 0; t < 10; t++, now += 2000) {
            for (Director.Command c : d.tick(now, 20, List.of(new Director.Live(1, ORC_CREATION)), List.of(), (lo, hi, x) -> List.of(), TestTerrain.mobsAround(1, 5))) {
                if ((c instanceof Director.Command.Materialize m) && (m.botId() == 0) && (m.assignment().kind() == Assignment.Kind.HUNT)) {
                    created++;
                    assertEquals(3, m.race(), "an orc for the orc starting zone");
                }
            }
        }
        assertTrue(created >= 1, "empty pool: presence must create new personas, " + d.statsLine());
    }

    @Test
    void assignedOutOfSightAndPendingCount() {
        final Pos player = new Pos(0, 0, 0);
        // one bot sent to the player's ground still walking in from far away, one pending: target 3 -> 1 more
        final List<PresencePlan.BotSummary> bots = List.of(new PresencePlan.BotSummary(5, new Pos(9000, 0, 0), Assignment.Kind.HUNT, "g1"));
        final PresencePlan.Plan plan = PresencePlan.calculateDeficits(List.of(player), List.of(1), bots, 1500, p -> null, p -> "g1",
            Map.of("g1", 1), 3, 3, Map.of());
        assertEquals(1, plan.deficits().size());
        assertEquals(1, plan.deficits().get(0).needed());
        final PresencePlan.Plan full = PresencePlan.calculateDeficits(List.of(player), List.of(1), bots, 1500, p -> null, p -> "g1",
            Map.of("g1", 2), 3, 3, Map.of());
        assertTrue(full.deficits().isEmpty(), "1 assigned + 2 pending = 3: no more");
    }

    @Test
    void stableTargetDoesNotReroll() {
        final int t = PresencePlan.stableTarget(42, "g1", 1, 3);
        for (int i = 0; i < 20; i++) {
            assertEquals(t, PresencePlan.stableTarget(42, "g1", 1, 3));
        }
        assertTrue((t >= 1) && (t <= 3));
    }

    @Test
    void inCombatNearPlayersCounted() {
        final List<Director.Live> live = List.of(new Director.Live(1, new Pos(0, 0, 0)));
        final List<Director.BotNow> bots = List.of(
            new Director.BotNow(1, new Pos(300, 0, 0), null, false, 1, 0, true),
            new Director.BotNow(2, new Pos(300, 0, 0), null, false, 1, 0, false),
            new Director.BotNow(3, new Pos(90000, 0, 0), null, false, 1, 0, true));
        assertEquals(1, Director.countInCombatNear(bots, live, 1500));
    }

    /** Live test 2026-10-21: field top-ups were ordered, then gone. They walk in unseen and must not be logged out. */
    @Test
    void presenceBotWalkingInIsNotLoggedOutUnseen() throws Exception {
        final Director d = director();
        final List<Director.Candidate> pool = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            pool.add(new Director.Candidate(i, 1 + (i % 5), 0, 3));
        }
        final Director.Live me = new Director.Live(1, ORC_CREATION);
        long now = 1_000_000_000L;
        Director.Command.Materialize top = null;
        for (int t = 0; (t < 20) && (top == null); t++, now += 2000) {
            for (Director.Command c : d.tick(now, 20, List.of(me), List.of(), (lo, hi, x) -> pool, TestTerrain.mobsAround(1, 5))) {
                if ((c instanceof Director.Command.Materialize m) && m.assignment().place().startsWith("adhoc_")) {
                    top = m;
                }
            }
        }
        assertTrue(top != null, "no field top-up");
        final int id = top.botId() == 0 ? 999 : top.botId();
        // in the world 3800 away (out of sight), walking in for 2 minutes: no logout
        final Pos far = new Pos(ORC_CREATION.x() + 3800, ORC_CREATION.y(), ORC_CREATION.z());
        for (int t = 0; t < 60; t++, now += 2000) {
            final Director.BotNow b = new Director.BotNow(id, far, top.assignment(), false, 1);
            for (Director.Command c : d.tick(now, 20, List.of(me), List.of(b), (lo, hi, x) -> pool, TestTerrain.mobsAround(1, 5))) {
                assertFalse((c instanceof Director.Command.Logout l) && (l.botId() == id), "presence bot logged out while walking in: " + c);
            }
        }
    }

    @Test
    void noCreateWhenPersonaCapReached() throws Exception {
        final Director d = director();
        d.setCanCreate(false);
        long now = 1_000_000_000L;
        for (int t = 0; t < 10; t++, now += 2000) {
            for (Director.Command c : d.tick(now, 20, List.of(new Director.Live(1, ORC_CREATION)), List.of(), (lo, hi, x) -> List.of(), TestTerrain.mobsAround(1, 5))) {
                assertFalse((c instanceof Director.Command.Materialize m) && (m.botId() == 0), "ordered a new persona over the cap: " + c);
            }
        }
    }

    @Test
    void nearLevelPersonaUsedWhenBandIsEmpty() throws Exception {
        final Director d = director();
        // only personas 3-5 levels outside the exact band: one of them comes instead of a create
        final List<Director.Candidate> pool = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            pool.add(new Director.Candidate(i, 6, 0, 3));
        }
        long now = 1_000_000_000L;
        int existing = 0;
        for (int t = 0; t < 10; t++, now += 2000) {
            for (Director.Command c : d.tick(now, 20, List.of(new Director.Live(1, ORC_CREATION)), List.of(),
                (lo, hi, x) -> pool.stream().filter(k -> (k.level() >= lo) && (k.level() <= hi)).toList(), TestTerrain.mobsAround(1, 5))) {
                if ((c instanceof Director.Command.Materialize m) && m.assignment().place().startsWith("adhoc_") && (m.botId() != 0)) {
                    existing++;
                }
            }
        }
        assertTrue(existing >= 1, "a near-level persona should be used, " + d.statsLine());
    }
}
