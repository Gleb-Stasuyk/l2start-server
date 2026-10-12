package org.l2start.rates.floating;

/**
 * The numbers of docs/RATES_PLAN.md §0 (module.json keys). Pure.
 * <ul>
 * <li>F = clamp(LevelCurve(level) x DiffCurve(mob level - level), FClamp); level = the killer's, in a party the highest
 * level of the party.</li>
 * <li>Item chance: ordinary monster DropChance x F (drop) / SpoilChance x F (spoil), raid RaidChance, epic x1 (stock).</li>
 * <li>Adena exactly x AdenaRate (raid RaidAdena, epic stock), never floating.</li>
 * <li>Stackable amount x A, A = 1 + (AmountMax - 1) x u^Skew (u uniform 0..1), independent of F.</li>
 * </ul>
 */
public record Rules(Curve levelCurve, Curve diffCurve, double fMin, double fMax, double dropChance, double spoilChance, double raidChance, double adenaRate, double raidAdena,
    double amountMax, double skew, boolean raidAmount, int maxExtraRolls) {
    public static final String LEVEL_CURVE = "1:1.5,20:1.5,40:1.2,52:1.0,61:0.85,76:0.7,80:0.7";
    public static final String DIFF_CURVE = "-8:0.4,-5:0.7,-3:0.9,-2:1.0,2:1.0,3:1.1,5:1.25,7:1.3";
    public static final Rules DEFAULT = new Rules(Curve.parse(LEVEL_CURVE), Curve.parse(DIFF_CURVE), 0.35, 1.5, 3, 3, 2, 5, 5, 3, 2, false, 4);

    public double f(int level, int mobLevel) {
        final double f = levelCurve.at(level) * diffCurve.at(mobLevel - level);
        return Math.max(fMin, Math.min(fMax, f));
    }

    /** Item chance multiplier of an ordinary monster. */
    public double chance(boolean spoil, double f) {
        return (spoil ? spoilChance : dropChance) * f;
    }

    /** Amount multiplier for a uniform {@code u} in [0, 1). */
    public double amount(double u) {
        return 1 + ((Math.max(1, amountMax) - 1) * Math.pow(u, Math.max(0.1, skew)));
    }

    /** Mean of {@link #amount}: 1 + (max - 1) / (skew + 1). */
    public double meanAmount() {
        return 1 + ((Math.max(1, amountMax) - 1) / (Math.max(0.1, skew) + 1));
    }
}
