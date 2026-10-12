package org.l2start.economy.market.model;

import org.l2start.economy.market.lw.StorePricing;

/**
 * The price model of phase 5a (pure, no game state). base = NPC reference x grade/stage factor x PriceMultiplier
 * (x DropItemPriceMultiplier for items NPCs do not sell, docs/RATES_PLAN.md);
 * market = base blended with the index of real deals (weight 0 at none up to 70 % at 30 deals in two weeks) and,
 * in solo, a slow drift of at most 10 % around it; sellers ask market x SellMarkup by greed, buyers bid market x BuyBid
 * by stinginess, below the cheapest bot seller and within the NPC arbitrage guard. Prices are rounded like a player
 * would type them (LW naturalizePrice).
 */
public final class PriceModel {
    public static final double INDEX_MAX_WEIGHT = 0.7;
    public static final int INDEX_FULL_DEALS = 30;
    public static final double DRIFT = 0.10;
    static final long WEEK_MS = 7L * 24 * 3600 * 1000;

    /** Settings (module.json keys PriceMultiplier, SellMarkup, BuyBid, NpcArbitrageGuard). */
    public record Settings(double multiplier, double sellLo, double sellHi, double bidLo, double bidHi, double npcGuard, double dropMultiplier) {
        public static final Settings DEFAULT = new Settings(1.0, 1.00, 1.25, 0.55, 0.85, 0.9);

        /** Without the rates factor of items NPCs do not sell (x1, before rates-floating). */
        public Settings(double multiplier, double sellLo, double sellHi, double bidLo, double bidHi, double npcGuard) {
            this(multiplier, sellLo, sellHi, bidLo, bidHi, npcGuard, 1.0);
        }
    }

    private final Settings s;

    public PriceModel(Settings s) {
        this.s = s;
    }

    /**
     * Grade and stage: an item of a later stage than the server is scarce (+15 % per stage ahead, up to +60 %), one of an
     * earlier stage is plentiful (-5 % per stage behind, down to -20 %).
     */
    public static double stageFactor(int itemStage, int serverStage) {
        final int d = itemStage - serverStage;
        return (d >= 0) ? Math.min(1.6, 1.0 + (0.15 * d)) : Math.max(0.8, 1.0 + (0.05 * d));
    }

    public long base(MarketItem item, int serverStage) {
        final double drop = (!item.npc() && (s.dropMultiplier() > 0)) ? s.dropMultiplier() : 1.0; // rates: adena x5, items x~3.5
        final double b = Math.max(1, item.price()) * stageFactor(item.stage(), serverStage) * ((s.multiplier() > 0) ? s.multiplier() : 1.0) * drop;
        return Math.max(1, Math.round(b));
    }

    public static double indexWeight(int recentDeals) {
        return INDEX_MAX_WEIGHT * Math.min(1.0, Math.max(0, recentDeals) / (double) INDEX_FULL_DEALS);
    }

    /** Base blended with the index; {@code index} null or empty = base. */
    public long market(long base, IndexEntry index, long now) {
        if ((index == null) || (index.ema() <= 0)) {
            return base;
        }
        final double w = indexWeight(index.recentDeals(now));
        return Math.max(1, Math.round((base * (1 - w)) + (index.ema() * w)));
    }

    /**
     * Solo drift factor in [0.9, 1.1]: a value per item and week (hash), linear in between, so prices move slowly and the
     * same on every visit within an hour.
     */
    public static double drift(int itemId, long now, long seed) {
        final long week = Math.floorDiv(now, WEEK_MS);
        final double t = (now - (week * WEEK_MS)) / (double) WEEK_MS;
        final double a = noise(itemId, week, seed);
        final double b = noise(itemId, week + 1, seed);
        return 1.0 + (DRIFT * (a + ((b - a) * t)));
    }

    /** -1..1, deterministic. */
    static double noise(int itemId, long week, long seed) {
        long z = (seed * 0x9E3779B97F4A7C15L) ^ (itemId * 0xBF58476D1CE4E5B9L) ^ (week * 0x94D049BB133111EBL);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return ((z >>> 11) / (double) (1L << 53) * 2.0) - 1.0;
    }

    /** Asking price of a bot seller: market x SellMarkup by greed 0..1, never below the NPC price of an NPC-sold item. */
    public long sellPrice(MarketItem item, long market, double greed) {
        long p = natural(Math.round(market * lerp(s.sellLo(), s.sellHi(), greed)));
        if (item.npc() && (item.price() > 0)) {
            p = Math.max(p, item.price());
        }
        return Math.max(1, p);
    }

    /**
     * Bid of a bot buyer: market x BuyBid by stinginess 0..1 (0 = generous), at most NpcArbitrageGuard x the NPC price of an
     * NPC-sold item and always below the cheapest bot seller of the item ({@code cheapestSeller} <= 0 = none).
     * @return the bid, 0 = no bid (the item cannot be bought without loss)
     */
    public long buyPrice(MarketItem item, long market, double stingy, long cheapestSeller) {
        long p = natural(Math.round(market * lerp(s.bidHi(), s.bidLo(), stingy)));
        if (item.npc() && (item.price() > 0)) {
            p = Math.min(p, (long) Math.floor(item.price() * s.npcGuard()));
        }
        if (cheapestSeller > 0) {
            p = Math.min(p, cheapestSeller - 1);
        }
        // rounding must not lift it again
        final long n = natural(p);
        if (n <= p) {
            p = n;
        }
        return Math.max(0, p);
    }

    static long natural(long price) {
        return (price > Integer.MAX_VALUE) ? price : StorePricing.naturalizePrice((int) Math.max(1, price));
    }

    static double lerp(double lo, double hi, double t) {
        final double c = Math.max(0, Math.min(1, t));
        return lo + ((hi - lo) * c);
    }

    public Settings settings() {
        return s;
    }
}
