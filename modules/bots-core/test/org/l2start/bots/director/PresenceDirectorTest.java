package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            for (Director.Command c : d.tick(now, 20, List.of(new Director.Live(1, ORC_CREATION)), List.of(), (lo, hi, x) -> pool, p -> p)) {
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
            for (Director.Command c : d.tick(now, 20, List.of(new Director.Live(1, ORC_CREATION)), List.of(), (lo, hi, x) -> List.of(), p -> p)) {
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
}
