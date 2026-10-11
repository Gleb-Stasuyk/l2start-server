package org.l2start.economy.market.model;

/**
 * The server stage 1-5: what bot stores carry at all. Roughly 1 = no grade / D, shots and potions; 2 = D/C, C recipes;
 * 3 = C/B; 4 = B/A, A rare; 5 = A, S single pieces. An item shows up from its own stage (data/market-items.xml).
 */
public final class Stage {
    /** Max level of the stage: a player at or below it is in that stage (20, 40, 52, 61, beyond). */
    private static final int[] LEVEL_TOP = { 19, 39, 51, 60 };
    /** Solo: after this many hours of play per stage the market runs one stage ahead of the level (a long game ages the server). */
    static final int HOURS_PER_STAGE = 120;
    /** Online: days a stage takes at least. */
    static final int DAYS_PER_STAGE = 21;

    private Stage() {
    }

    public static int ofLevel(int level) {
        for (int k = 0; k < LEVEL_TOP.length; k++) {
            if (level <= LEVEL_TOP[k]) {
                return k + 1;
            }
        }
        return 5;
    }

    /**
     * Solo: by the highest level of the player's characters and the hours played (like the bots' growth on paper).
     * The level decides; a long game moves the market one stage ahead at most.
     */
    public static int solo(int maxLevel, double hoursPlayed) {
        final int byLevel = ofLevel(maxLevel);
        final int ahead = (hoursPlayed >= (HOURS_PER_STAGE * byLevel)) ? 1 : 0;
        return clamp(byLevel + ahead);
    }

    /** Online: the days since the server start cap the stage, the average level of the top 10 players drives it. */
    public static int online(double daysSinceStart, double top10Level) {
        final int byDays = 1 + (int) (Math.max(0, daysSinceStart) / DAYS_PER_STAGE);
        return clamp(Math.min(byDays, ofLevel((int) Math.round(top10Level))));
    }

    /** MarketStage setting: "auto" (or anything not 1-5) = the computed stage, else the fixed one. */
    public static int resolve(String setting, int auto) {
        if (setting != null) {
            try {
                final int s = Integer.parseInt(setting.trim());
                if ((s >= 1) && (s <= 5)) {
                    return s;
                }
            } catch (NumberFormatException e) {
                // auto
            }
        }
        return clamp(auto);
    }

    public static boolean available(MarketItem item, int stage) {
        return item.stage() <= stage;
    }

    static int clamp(int s) {
        return Math.max(1, Math.min(5, s));
    }
}
