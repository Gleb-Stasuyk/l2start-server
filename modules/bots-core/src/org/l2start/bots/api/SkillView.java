package org.l2start.bots.api;

/**
 * A skill the bot knows.
 * @param kind NUKE = magic damage on one enemy, STRIKE = physical damage on one enemy, BUFF = a lasting good effect the
 *        bot can put on itself (2 minutes or longer), TOGGLE = a stance switched on and off,
 *        SUMMON = calls a servitor (it may need crystals: {@link #itemId}), CORPSE = used on a dead monster (Sweeper), OTHER = everything else
 * @param ready not on cooldown (reuse) right now
 * @param buffLeft BUFF: seconds left of an effect of the same kind (abnormal type) on the bot, 0 = none; TOGGLE: -1 = on,
 *        0 = off
 * @param itemId SUMMON: item the skill consumes (0 = none) and {@code itemCount} of it per cast
 */
public record SkillView(int id, int level, Kind kind, int mpCost, int castRange, boolean ready, int buffLeft, int itemId, int itemCount) {
    public enum Kind {
        NUKE, STRIKE, BUFF, TOGGLE, SUMMON, CORPSE, OTHER
    }

    /**
     * Servitor summons of the three summoner lines a bot may call, weakest tier first (phase 4c). Within a tier the
     * bots pick by their id; dwarven golems and cannons, cubics and the necromancer's corpse summons are not here.
     */
    public static final int[][] SERVITOR_TIERS = {
        { 1111, 1225, 1226, 1227, 1128, 1228 }, // Kat / Mew the Cat, Boxer / Mirage the Unicorn, Shadow / Silhouette
        { 1276, 1277, 1278 }, // Kai the Cat, Merrow the Unicorn, Soulless
        { 1331, 1332, 1333 }, // Feline Queen, Unicorn Seraphim, Nightshade
        { 1406, 1407, 1408 }, // Feline King, Magnus the Unicorn, Spectral Lord
    };

    /** One of {@link #SERVITOR_TIERS}. */
    public static boolean servitorSummon(int skillId) {
        for (int[] tier : SERVITOR_TIERS) {
            for (int id : tier) {
                if (id == skillId) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Before phase 4c (no item consumed). */
    public SkillView(int id, int level, Kind kind, int mpCost, int castRange, boolean ready, int buffLeft) {
        this(id, level, kind, mpCost, castRange, ready, buffLeft, 0, 0);
    }

    /** Before phase 4b (no effect on the bot). */
    public SkillView(int id, int level, Kind kind, int mpCost, int castRange, boolean ready) {
        this(id, level, kind, mpCost, castRange, ready, 0, 0, 0);
    }
}
