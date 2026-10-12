package org.l2start.economy.market.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Step 5f (trade window): the pure rules of a whispered deal done face to face. Where to meet (a town gatekeeper,
 * warehouse keeper or Priest of Dawn near the player) and whether the player's side of the trade window is exactly
 * the deal (the bot confirms only then).
 */
public final class TradeMeet {
    public static final int ADENA = 57;
    /** Candidates farther than the nearest one plus this are another town. */
    public static final double SAME_TOWN = 3_000;

    public enum Kind {
        GATEKEEPER, WAREHOUSE, PRIEST
    }

    public record Spot(Kind kind, int npcObjectId, int x, int y, int z, double dist) {
    }

    /** One line of a trade window: item id and count. */
    public record Line(int itemId, long count) {
    }

    private TradeMeet() {
    }

    /** The nearest NPC of each kind in the player's town (the nearest candidate's town), then one kind at random. */
    public static Spot pick(List<Spot> candidates, Random rnd) {
        if ((candidates == null) || candidates.isEmpty()) {
            return null;
        }
        double nearest = Double.MAX_VALUE;
        for (Spot s : candidates) {
            nearest = Math.min(nearest, s.dist());
        }
        final Map<Kind, Spot> best = new EnumMap<>(Kind.class);
        for (Spot s : candidates) {
            if (s.dist() > (nearest + SAME_TOWN)) {
                continue;
            }
            final Spot b = best.get(s.kind());
            if ((b == null) || (s.dist() < b.dist())) {
                best.put(s.kind(), s);
            }
        }
        final List<Spot> kinds = new ArrayList<>(best.values());
        return kinds.get(rnd.nextInt(kinds.size()));
    }

    /**
     * The player's side of the window is exactly the deal: when the bot sells, only adena and exactly count x price;
     * when the bot buys, only the item and exactly the count. Nothing else, nothing less, nothing more.
     */
    public static boolean matches(boolean botSells, int itemId, long count, long price, List<Line> playerSide) {
        if ((playerSide == null) || playerSide.isEmpty() || (count <= 0) || (price <= 0)) {
            return false;
        }
        final int want = botSells ? ADENA : itemId;
        final long need = botSells ? Math.multiplyExact(count, price) : count;
        long sum = 0;
        for (Line l : playerSide) {
            if ((l.itemId() != want) || (l.count() <= 0)) {
                return false;
            }
            sum += l.count();
        }
        return sum == need;
    }

    /** The bot's side: what it puts in the window (the item when it sells, the adena when it buys). */
    public static Line botSide(boolean botSells, int itemId, long count, long price) {
        return botSells ? new Line(itemId, count) : new Line(ADENA, Math.multiplyExact(count, price));
    }
}
