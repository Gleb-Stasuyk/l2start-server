package org.l2start.bots.director;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;

/** Pure functions for presence guarantee: counting bots per player, classifying deficits. */
public final class PresencePlan {
    
    /** Deficit for one player: how many bots to spawn (field or town). */
    public record Deficit(int playerId, boolean inTown, String townId, int needed, Pos playerPos) {}
    
    /** Plan: list of deficits per player. */
    public record Plan(List<Deficit> deficits) {}
    
    /** Bot summary for counting: position, assignment kind, place. */
    public record BotSummary(int botId, Pos pos, Assignment.Kind kind, String place) {}
    
    /**
     * Calculate deficits: for each player, count visible non-trader bots and determine if more are needed.
     * @param players list of (playerId, pos)
     * @param bots list of bot summaries
     * @param visibilityRadius unseenRadius from config
     * @param townLookup function: Pos -> town ID or null
     * @param fieldMin field presence min
     * @param fieldMax field presence max
     * @param townRanges map: town ID -> [min, max]
     * @return plan with deficits
     */
    public static Plan calculateDeficits(
        List<Pos> players,
        List<BotSummary> bots,
        int visibilityRadius,
        java.util.function.Function<Pos, String> townLookup,
        int fieldMin,
        int fieldMax,
        Map<String, int[]> townRanges
    ) {
        final List<Deficit> deficits = new ArrayList<>();
        
        // Identify trader bots (place starts with "trader:")
        final Set<Integer> traders = new HashSet<>();
        for (BotSummary b : bots) {
            if (b.place != null && b.place.startsWith("trader:")) {
                traders.add(b.botId);
            }
        }
        
        for (int i = 0; i < players.size(); i++) {
            final Pos playerPos = players.get(i);
            final String townId = townLookup.apply(playerPos);
            final boolean inTown = townId != null;
            
            // Count visible non-trader bots
            int visible = 0;
            for (BotSummary b : bots) {
                if (traders.contains(b.botId)) {
                    continue; // traders excluded
                }
                if (playerPos.distance2d(b.pos) <= visibilityRadius) {
                    visible++;
                }
            }
            
            // Determine target and deficit
            final int target;
            if (inTown) {
                final int[] range = townRanges.getOrDefault(townId, new int[] {0, 0});
                if (range[1] <= 0) {
                    continue; // town presence disabled
                }
                target = range[0] + (int)(Math.random() * ((range[1] - range[0]) + 1));
            } else {
                if (fieldMax <= 0) {
                    continue; // field presence disabled
                }
                target = fieldMin + (int)(Math.random() * ((fieldMax - fieldMin) + 1));
            }
            
            final int needed = Math.max(0, target - visible);
            if (needed > 0) {
                deficits.add(new Deficit(i, inTown, townId, needed, playerPos));
            }
        }
        
        return new Plan(deficits);
    }
    
    /**
     * Classify a town by ID into category (starter/small/medium/large).
     * Simplified heuristic based on common town names.
     */
    public static String classifyTown(String townId) {
        if (townId == null) {
            return "small";
        }
        final String id = townId.toLowerCase();
        // Starter villages
        if (id.contains("village") || id.equals("talking_island") || id.equals("elven_village") || 
            id.equals("dark_elven_village") || id.equals("orc_village") || id.equals("dwarven_village") || 
            id.equals("kamael_village")) {
            return "starter";
        }
        // Large towns
        if (id.equals("giran") || id.equals("aden") || id.equals("goddard")) {
            return "large";
        }
        // Medium towns
        if (id.equals("gludio") || id.equals("dion")) {
            return "medium";
        }
        // Small towns (default)
        return "small";
    }
    
    /**
     * Check if a spawn position is outside visibility of all players.
     * @param spawnPos proposed spawn position
     * @param players list of player positions
     * @param visibilityRadius unseenRadius
     * @return true if spawn is far enough from all players
     */
    public static boolean isOutsideVisibility(Pos spawnPos, List<Pos> players, int visibilityRadius) {
        for (Pos p : players) {
            if (spawnPos.distance2d(p) <= visibilityRadius) {
                return false;
            }
        }
        return true;
    }
}
