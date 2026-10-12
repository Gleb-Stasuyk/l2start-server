package org.l2start.economy.market.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A bot store while nobody looks (phase 5b, "life on paper"): invisible buyers take part of the goods (cheap stackables
 * go faster than single pricey pieces), prices creep towards the current market and a store that sold most of its
 * goods is restocked. Pure; the caller persists the lines.
 */
public final class PaperStore {
    /** Share of a line's goods sold per day at the market price (stackables) and the chance a single piece goes per day. */
    static final double STACK_DAY = 0.35;
    static final double PIECE_DAY = 0.15;
    /** Below this share of the starting lines the trader refills the store. */
    static final double RESTOCK_BELOW = 0.5;
    /** A day brings a price this much closer to the current quote. */
    static final double PRICE_PULL = 0.3;

    private PaperStore() {
    }

    /**
     * @param lines the store now
     * @param fresh the price the model gives each line now (same order), for the price creep
     * @param hours time since the last look
     * @param buy a buy store: invisible sellers fill it (the count is what it still wants)
     * @return the store after that time; lines sold out are gone
     */
    public static List<Assortment.Line> age(List<Assortment.Line> lines, long[] fresh, double hours, boolean buy, Random rnd) {
        final double days = Math.max(0, Math.min(14, hours / 24.0));
        final List<Assortment.Line> out = new ArrayList<>();
        for (int k = 0; k < lines.size(); k++) {
            final Assortment.Line l = lines.get(k);
            long count = l.count();
            if (count > 1) {
                final double share = 1 - Math.pow(1 - (STACK_DAY * (0.5 + rnd.nextDouble())), days);
                count -= Math.round(count * Math.min(0.95, share));
            } else if (rnd.nextDouble() < (1 - Math.pow(1 - PIECE_DAY, days))) {
                count = 0;
            }
            if (count <= 0) {
                continue;
            }
            long price = l.price();
            if ((fresh != null) && (k < fresh.length) && (fresh[k] > 0)) {
                final double pull = 1 - Math.pow(1 - PRICE_PULL, days);
                price = Math.max(1, Math.round(price + ((fresh[k] - price) * pull)));
            }
            out.add(new Assortment.Line(l.itemId(), l.name(), count, price));
        }
        return out;
    }

    /** The trader refills (a new assortment) when fewer than half of its starting lines are left. */
    public static boolean needsRestock(int linesLeft, int linesAtStart) {
        return (linesAtStart <= 0) || (linesLeft < Math.max(1, Math.ceil(linesAtStart * RESTOCK_BELOW)));
    }
}
