package org.l2start.economy.market.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.l2start.economy.market.lw.StorePricing;

/**
 * Step 5e: what a bot buys in a real player's store. Pure. A line is worth it when its price is at most
 * {@code market x maxRatio} (BuyFromPlayersMaxPrice, 1.1); the cheapest lines (against the market) are taken first;
 * a pack of a stackable is of the usual size for its price (LW bulk ranges); the bot never pays more than
 * {@code limit} (its wallet and the player's day budget). A package store sells all or nothing.
 */
public final class StoreVisit {
    /** A line of the player's store. {@code market}: market price of the item now (0 = not a market item). */
    public record Offer(int objectId, int itemId, long count, long price, boolean stackable, long market, long reference) {
    }

    /** What to buy: object id, item id, pieces, price each. */
    public record Buy(int objectId, int itemId, long count, long price) {
        public long total() {
            return count * price;
        }
    }

    private StoreVisit() {
    }

    public static boolean worth(Offer o, double maxRatio) {
        return (o.market() > 0) && (o.price() > 0) && (o.count() > 0) && (o.price() <= Math.floor(o.market() * maxRatio));
    }

    public static List<Buy> pick(List<Offer> offers, boolean packaged, double maxRatio, long limit, Random rnd) {
        final List<Buy> out = new ArrayList<>();
        if (limit <= 0) {
            return out;
        }
        if (packaged) {
            long total = 0;
            for (Offer o : offers) {
                if (!worth(o, maxRatio)) {
                    return out;
                }
                total += o.count() * o.price();
            }
            if (total > limit) {
                return out;
            }
            for (Offer o : offers) {
                out.add(new Buy(o.objectId(), o.itemId(), o.count(), o.price()));
            }
            return out;
        }
        final List<Offer> good = new ArrayList<>();
        for (Offer o : offers) {
            if (worth(o, maxRatio)) {
                good.add(o);
            }
        }
        if (good.isEmpty()) {
            return out;
        }
        good.sort(Comparator.comparingDouble(o -> (double) o.price() / o.market()));
        final Offer o = good.get(rnd.nextInt(Math.min(2, good.size()))); // one of the two best deals
        long count = 1;
        if (o.stackable()) {
            final int[] band = StorePricing.bulkAmountRange((int) Math.min(Integer.MAX_VALUE, Math.max(1, o.reference())));
            count = band[0] + ((band[1] > band[0]) ? rnd.nextInt((band[1] - band[0]) + 1) : 0);
            count = Math.min(count, o.count());
        }
        count = Math.min(count, limit / o.price());
        if (count > 0) {
            out.add(new Buy(o.objectId(), o.itemId(), count, o.price()));
        }
        return out;
    }
}
