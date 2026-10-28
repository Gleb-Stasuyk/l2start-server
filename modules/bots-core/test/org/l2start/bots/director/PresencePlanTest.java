package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * All races' character creation points must generate field deficits even when not in a predefined ground.
     * Uses actual creation coordinates from game data (core/dist/game/data/stats/players/templates/1stClass/*.xml).
     */
    @Test
    void allRaceCreationPointsCovered() {
        // Actual creation coordinates from game data
        final Map<String, Pos> creationPoints = Map.of(
            "human", new Pos(-71338, 258271, -3104),      // HumanFighter
            "elf", new Pos(46045, 41251, -3440),          // ElvenFighter
            "dark_elf", new Pos(28295, 11063, -4224),     // DarkFighter
            "orc", new Pos(-56693, -113610, -690),        // OrcFighter/Shaman
            "dwarf", new Pos(108512, -174026, -408)       // DwarvenFighter
        );
        
        final Map<String, int[]> townRanges = Map.of(
            "talking_island", new int[] {3, 5},
            "elven_village", new int[] {3, 5},
            "dark_elven_village", new int[] {3, 5},
            "orc_village", new int[] {3, 5},
            "dwarven_village", new int[] {3, 5}
        );
        
        for (Map.Entry<String, Pos> entry : creationPoints.entrySet()) {
            final String race = entry.getKey();
            final Pos spawn = entry.getValue();
            final List<Pos> players = List.of(spawn);
            final List<PresencePlan.BotSummary> bots = List.of(); // No bots yet
            
            // Town lookup: check if inside any village (with correct radii from world.xml)
            final PresencePlan.Plan plan = PresencePlan.calculateDeficits(players, bots, 1500, 
                p -> {
                    // Village centers and radii from world.xml
                    final Map<String, double[]> villages = Map.of(
                        "talking_island", new double[] {-84331, 242962, 8500},
                        "elven_village", new double[] {45365, 49721, 3191},
                        "dark_elven_village", new double[] {11423, 16857, 4685},
                        "orc_village", new double[] {-44721, -113607, 4482},  // REVERTED radius
                        "dwarven_village", new double[] {115348, -180507, 4020}
                    );
                    
                    for (Map.Entry<String, double[]> v : villages.entrySet()) {
                        final double dist = Math.sqrt(Math.pow(p.x() - v.getValue()[0], 2) + Math.pow(p.y() - v.getValue()[1], 2));
                        if (dist <= v.getValue()[2]) {
                            return v.getKey();
                        }
                    }
                    return null; // Not in town = field
                },
                1, 3, townRanges);
            
            assertFalse(plan.deficits().isEmpty(), race + " creation point must generate deficit");
            final PresencePlan.Deficit deficit = plan.deficits().get(0);
            assertTrue(deficit.needed() > 0, race + " player needs bots");
            
            // Most creation points are in field (not town), but test accepts both
            if (deficit.inTown()) {
                // In town (e.g. if a village covers the creation point)
                assertTrue(deficit.townId() != null, race + " in town should have townId");
            } else {
                // In field (expected for most creation points)
                assertNull(deficit.townId(), race + " in field should have no townId");
            }
        }
    }
}
