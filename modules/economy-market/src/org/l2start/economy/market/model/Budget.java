package org.l2start.economy.market.model;

/**
 * Step 5d: where the adena of bots comes from and how much of it may reach real players. Pure.
 * <ul>
 * <li>An hour of farming at a level (x1 Interlude, rough): 2 000 + 50 x level^2 adena (lv 20 ~22k, lv 40 ~82k, lv 60
 * ~182k, lv 76 ~291k).</li>
 * <li>A hunter bot earns on paper a share of that per hour it hunts unseen (the rest went to gear) and pays its shots and
 * potions from it. No other adena comes to bots.</li>
 * <li>Bots pay a real player per day at most {@code BotAdenaPerPlayerDay} (0.3) of an hour of farming at the player's
 * level: buy stores and purchases in the player's store together.</li>
 * </ul>
 */
public final class Budget {
    /** Share of an hour of farming a hunter keeps for shots and potions. */
    public static final double HUNTER_KEEPS = 0.35;
    /** A hunter's wallet holds at most this many hours of farming (it spends the rest on gear). */
    public static final double HUNTER_CAP_HOURS = 8;
    /** A new hunter starts with this many hours of farming. */
    public static final double HUNTER_START_HOURS = 2;
    /** Hours of paper hunting counted between two arrivals at most. */
    public static final double HUNTER_MAX_GAP_HOURS = 24;

    private Budget() {
    }

    public static long farmPerHour(int level) {
        final long l = Math.max(1, Math.min(80, level));
        return 2_000 + (50 * l * l);
    }

    /** Adena bots may pay one real player of {@code level} a day. */
    public static long playerDay(int level, double share) {
        return Math.round(farmPerHour(level) * Math.max(0, share));
    }

    /** What is left of a player's day budget after {@code paid} (never below 0). */
    public static long left(int level, double share, long paid) {
        return Math.max(0, playerDay(level, share) - Math.max(0, paid));
    }

    /** A hunter's wallet after {@code hours} of paper hunting (wallet < 0: a new hunter). */
    public static long hunterWallet(long wallet, int level, double hours) {
        final long cap = Math.round(farmPerHour(level) * HUNTER_CAP_HOURS);
        if (wallet < 0) {
            return Math.round(farmPerHour(level) * HUNTER_START_HOURS);
        }
        final double h = Math.max(0, Math.min(HUNTER_MAX_GAP_HOURS, hours));
        return Math.max(wallet, Math.min(cap, wallet + Math.round(farmPerHour(level) * HUNTER_KEEPS * h)));
    }

    /** Pieces of {@code want} a wallet pays for at {@code unit} each. */
    public static int affordable(long wallet, long unit, int want) {
        if ((want <= 0) || (wallet <= 0)) {
            return 0;
        }
        if (unit <= 0) {
            return want;
        }
        return (int) Math.min(want, wallet / unit);
    }
}
