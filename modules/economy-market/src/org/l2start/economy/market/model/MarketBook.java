package org.l2start.economy.market.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The market in memory: catalogue, price index of real deals and quotes. Thread-safe (synchronized); the module keeps
 * it in the database (index rows) and feeds it the deals of hook P5-STORE-DEAL.
 */
public final class MarketBook {
    /** What the model says about an item now. */
    public record Quote(MarketItem item, int stage, long base, long market, double drift, int recentDeals, long lowSell, long highSell, long lowBid, long highBid) {
    }

    private final MarketItems items;
    private volatile PriceModel model;
    private volatile boolean drift;
    private final long seed;
    private final Map<Integer, IndexEntry> index = new HashMap<>();
    private final Set<Integer> dirty = new HashSet<>();
    private long realDeals;
    private long botDeals;

    public MarketBook(MarketItems items, PriceModel model, boolean drift, long seed) {
        this.items = items;
        this.model = model;
        this.drift = drift;
        this.seed = seed;
    }

    public void configure(PriceModel m, boolean d) {
        model = m;
        drift = d;
    }

    /**
     * One deal line. Only prices set by a real player (the store owner is not a bot) and items of the catalogue move the
     * index; bot prices come from the index, so letting them in would make it chase itself.
     * @return true when the index took it
     */
    public synchronized boolean deal(int itemId, long unitPrice, boolean ownerReal, long now) {
        if (!ownerReal) {
            botDeals++;
            return false;
        }
        realDeals++;
        if (!items.contains(itemId)) {
            return false;
        }
        final boolean moved = index.computeIfAbsent(itemId, k -> new IndexEntry()).deal(unitPrice, now);
        dirty.add(itemId);
        return moved;
    }

    /** Restores one stored index row (and the times of its recent deals from the trade log). */
    public synchronized void restore(int itemId, double ema, long deals, long[] recentAt) {
        final IndexEntry e = new IndexEntry(ema, deals);
        for (long at : recentAt) {
            e.remember(at);
        }
        index.put(itemId, e);
    }

    public synchronized Quote quote(int itemId, int stage, long now) {
        final MarketItem it = items.get(itemId);
        if (it == null) {
            return null;
        }
        final PriceModel m = model;
        final long base = m.base(it, stage);
        final IndexEntry e = index.get(itemId);
        final int recent = (e == null) ? 0 : e.recentDeals(now);
        long market = m.market(base, e, now);
        // drift only where real deals are too few to set the price
        final double d = drift ? (1.0 + ((PriceModel.drift(itemId, now, seed) - 1.0) * (1.0 - (PriceModel.indexWeight(recent) / PriceModel.INDEX_MAX_WEIGHT)))) : 1.0;
        market = Math.max(1, Math.round(market * d));
        final long lowSell = m.sellPrice(it, market, 0);
        final long highSell = m.sellPrice(it, market, 1);
        return new Quote(it, stage, base, market, d, recent, lowSell, highSell, m.buyPrice(it, market, 1, lowSell), m.buyPrice(it, market, 0, lowSell));
    }

    /** Index rows changed since the last call (to save). */
    public synchronized Map<Integer, double[]> takeDirty() {
        final Map<Integer, double[]> out = new HashMap<>();
        for (int id : dirty) {
            final IndexEntry e = index.get(id);
            if (e != null) {
                out.put(id, new double[] { e.ema(), e.deals() });
            }
        }
        dirty.clear();
        return out;
    }

    public synchronized int indexed() {
        return index.size();
    }

    public synchronized long realDeals() {
        return realDeals;
    }

    public synchronized long botDeals() {
        return botDeals;
    }

    public MarketItems items() {
        return items;
    }

    public PriceModel model() {
        return model;
    }
}
