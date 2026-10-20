package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;

/** Unit tests for PresencePlan pure functions. */
class PresencePlanTest {
    
    @Test
    void tradersExcludedFromCount() {
        final List<Pos> players = List.of(new Pos(1000, 1000, 0));
        final List<PresencePlan.BotSummary> bots = List.of(
            new PresencePlan.BotSummary(1, new Pos(1100, 1100, 0), Assignment.Kind.TOWN, "trader:giran:1"),
            new PresencePlan.BotSummary(2, new Pos(1200, 1200, 0), Assignment.Kind.TOWN, "trader:giran:2"),
            new PresencePlan.BotSummary(3, new Pos(1300, 1300, 0), Assignment.Kind.TOWN, "giran")
        );
        
        final PresencePlan.Plan plan = PresencePlan.calculateDeficits(
            players, bots, 1500, p -> "giran",
            0, 0, Map.of("giran", new int[] {3, 3})
        );
        
        // Only 1 non-trader visible, need 2 more
        assertEquals(1, plan.deficits().size());
        assertEquals(2, plan.deficits().get(0).needed());
    }
    
    @Test
    void sharedBotCountsForMultiplePlayers() {
        final List<Pos> players = List.of(
            new Pos(1000, 1000, 0),
            new Pos(1500, 1000, 0)
        );
        final List<PresencePlan.BotSummary> bots = List.of(
            new PresencePlan.BotSummary(1, new Pos(1250, 1000, 0), Assignment.Kind.HUNT, "ground1")
        );
        
        final PresencePlan.Plan plan = PresencePlan.calculateDeficits(
            players, bots, 500, p -> null,
            2, 2, Map.of()
        );
        
        // Both players see the shared bot, so both have visible=1, need 1 more each
        assertEquals(2, plan.deficits().size());
        assertEquals(1, plan.deficits().get(0).needed());
        assertEquals(1, plan.deficits().get(1).needed());
    }
    
    @Test
    void townVsFieldClassification() {
        final List<Pos> players = List.of(
            new Pos(1000, 1000, 0), // in town
            new Pos(5000, 5000, 0)  // in field
        );
        final List<PresencePlan.BotSummary> bots = new ArrayList<>();
        
        final PresencePlan.Plan plan = PresencePlan.calculateDeficits(
            players, bots, 1500,
            p -> p.x() < 2000 ? "giran" : null,
            1, 1, Map.of("giran", new int[] {2, 2})
        );
        
        assertEquals(2, plan.deficits().size());
        assertTrue(plan.deficits().get(0).inTown());
        assertEquals("giran", plan.deficits().get(0).townId());
        assertFalse(plan.deficits().get(1).inTown());
        assertEquals(null, plan.deficits().get(1).townId());
    }
    
    @Test
    void perTownRanges() {
        final List<Pos> players = List.of(
            new Pos(1000, 1000, 0), // giran
            new Pos(2000, 2000, 0), // gludio
            new Pos(3000, 3000, 0)  // aden
        );
        final List<PresencePlan.BotSummary> bots = new ArrayList<>();
        
        final Map<String, int[]> townRanges = Map.of(
            "giran", new int[] {10, 15},
            "gludio", new int[] {6, 10},
            "aden", new int[] {10, 15}
        );
        
        final PresencePlan.Plan plan = PresencePlan.calculateDeficits(
            players, bots, 1500,
            p -> {
                if (p.x() < 1500) return "giran";
                if (p.x() < 2500) return "gludio";
                return "aden";
            },
            0, 0, townRanges
        );
        
        assertEquals(3, plan.deficits().size());
        // Ranges applied (needed = target - visible, visible=0)
        assertTrue(plan.deficits().get(0).needed() >= 10 && plan.deficits().get(0).needed() <= 15);
        assertTrue(plan.deficits().get(1).needed() >= 6 && plan.deficits().get(1).needed() <= 10);
        assertTrue(plan.deficits().get(2).needed() >= 10 && plan.deficits().get(2).needed() <= 15);
    }
    
    @Test
    void spawnOutsideVisibility() {
        final List<Pos> players = List.of(new Pos(1000, 1000, 0));
        final Pos inside = new Pos(1100, 1100, 0); // ~141 units away
        final Pos outside = new Pos(3000, 1000, 0); // 2000 units away
        
        assertFalse(PresencePlan.isOutsideVisibility(inside, players, 1500));
        assertTrue(PresencePlan.isOutsideVisibility(outside, players, 1500));
    }
    
    @Test
    void disabledWhenRangesZero() {
        final List<Pos> players = List.of(new Pos(1000, 1000, 0));
        final List<PresencePlan.BotSummary> bots = new ArrayList<>();
        
        final PresencePlan.Plan plan = PresencePlan.calculateDeficits(
            players, bots, 1500, p -> "giran",
            0, 0, Map.of("giran", new int[] {0, 0})
        );
        
        assertEquals(0, plan.deficits().size());
    }
    
    @Test
    void townClassification() {
        assertEquals("starter", PresencePlan.classifyTown("talking_island"));
        assertEquals("starter", PresencePlan.classifyTown("elven_village"));
        assertEquals("starter", PresencePlan.classifyTown("dwarven_village"));
        assertEquals("large", PresencePlan.classifyTown("giran"));
        assertEquals("large", PresencePlan.classifyTown("aden"));
        assertEquals("large", PresencePlan.classifyTown("goddard"));
        assertEquals("medium", PresencePlan.classifyTown("gludio"));
        assertEquals("medium", PresencePlan.classifyTown("dion"));
        assertEquals("small", PresencePlan.classifyTown("heine"));
        assertEquals("small", PresencePlan.classifyTown("rune"));
    }
}
