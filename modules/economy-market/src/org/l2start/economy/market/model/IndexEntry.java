package org.l2start.economy.market.model;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The price index of one item from real deals: an EMA of the unit price plus the times of the deals of the last
 * {@link #WINDOW_MS} (their number sets the weight of the index in the market price). Outliers (more than
 * {@link #OUTLIER} times off the current index once it has a few deals) are counted but do not move the EMA.
 * Not thread-safe: the owner synchronizes.
 */
public final class IndexEntry {
    public static final long WINDOW_MS = 14L * 24 * 3600 * 1000;
    static final double ALPHA = 0.2;
    static final double OUTLIER = 3.0;
    static final int SETTLED = 3;

    private double ema;
    private long deals;
    private final Deque<Long> recent = new ArrayDeque<>();

    public IndexEntry() {
    }

    /** Restores a stored index (recent deal times are restored separately with {@link #remember}). */
    public IndexEntry(double ema, long deals) {
        this.ema = ema;
        this.deals = deals;
    }

    /** @return true when the deal moved the index (false = outlier or bad price) */
    public boolean deal(long unitPrice, long now) {
        if (unitPrice <= 0) {
            return false;
        }
        remember(now);
        deals++;
        if ((ema <= 0) || (deals <= 1)) {
            ema = unitPrice;
            return true;
        }
        if ((deals > SETTLED) && ((unitPrice > (ema * OUTLIER)) || (unitPrice < (ema / OUTLIER)))) {
            return false;
        }
        ema += ALPHA * (unitPrice - ema);
        return true;
    }

    public void remember(long at) {
        recent.addLast(at);
    }

    /** Deals within the window before {@code now} (older ones are dropped). */
    public int recentDeals(long now) {
        while (!recent.isEmpty() && (recent.peekFirst() < (now - WINDOW_MS))) {
            recent.pollFirst();
        }
        return recent.size();
    }

    public double ema() {
        return ema;
    }

    public long deals() {
        return deals;
    }
}
