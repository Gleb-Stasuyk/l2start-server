package org.l2start.bots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Phase 4b: some bots come with buffs on, like players do. Below 26 a share of them has the buffs of the Newbie Helper
 * (exactly the stock SupportMagic rules by level and fighter / mystic); from level 40 a share has an incomplete set
 * "from a buffer or a party". The time left is real and part of it is gone already: the buffs run out. Nothing beyond
 * what a player can get in vanilla. Pure logic (tests); {@link Materializer} puts the effects on.
 */
final class ArrivalBuffs {
    /** One effect: skill id, skill level, share of its full time left. */
    record Buff(int skillId, int level, double left) {
    }

    /** Fighter buffs of a buffer (Prophet / Elder / Shillien Elder): id, max level. */
    static final int[][] FIGHTER = { { 1204, 2 }, { 1040, 3 }, { 1068, 3 }, { 1086, 2 }, { 1077, 3 }, { 1242, 3 }, { 1240, 3 }, { 1045, 6 }, { 1036, 2 }, { 1035, 4 }, { 1062, 2 } };
    /** Mystic buffs of a buffer. */
    static final int[][] MYSTIC = { { 1204, 2 }, { 1040, 3 }, { 1085, 3 }, { 1059, 3 }, { 1048, 6 }, { 1036, 2 }, { 1035, 4 } };

    private ArrivalBuffs() {
    }

    /**
     * @param newbie share of bots of level 8-25 with the Newbie Helper buffs
     * @param party share of bots of level 40+ with some buffs of a buffer
     */
    static List<Buff> pick(int level, boolean mystic, double newbie, double party, Random r) {
        final List<Buff> l = new ArrayList<>();
        if ((level >= 8) && (level <= 25)) {
            if (r.nextDouble() >= newbie) {
                return l;
            }
            final double left = 0.15 + (0.85 * r.nextDouble()); // buffed together, some time ago
            add(l, level, 8, 24, 4322, left); // Wind Walk
            add(l, level, 11, 23, 4323, left); // Shield
            if (mystic) {
                add(l, level, 12, 22, 4328, left); // Blessed Soul
                add(l, level, 13, 21, 4329, left); // Acumen
                add(l, level, 14, 20, 4330, left); // Concentration
                add(l, level, 15, 19, 4331, left); // Empower
            } else {
                add(l, level, 12, 22, 4324, left); // Blessed Body
                add(l, level, 13, 21, 4325, left); // Vampiric Rage
                add(l, level, 14, 20, 4326, left); // Regeneration
                add(l, level, 15, 19, 4327, left); // Haste
            }
            return l;
        }
        if ((level >= 40) && (r.nextDouble() < party)) {
            final List<int[]> pool = new ArrayList<>(List.of(mystic ? MYSTIC : FIGHTER));
            final int n = Math.min(pool.size(), 2 + r.nextInt(4)); // 2-5 of them: not the full set
            final double left = 0.1 + (0.8 * r.nextDouble());
            for (int i = 0; i < n; i++) {
                final int[] b = pool.remove(r.nextInt(pool.size()));
                final int lvl = level < 52 ? Math.max(1, b[1] - 1) : b[1]; // a buffer of about the same level
                l.add(new Buff(b[0], lvl, left));
            }
        }
        return l;
    }

    private static void add(List<Buff> l, int level, int from, int to, int skillId, double left) {
        if ((level >= from) && (level <= to)) {
            l.add(new Buff(skillId, 1, left));
        }
    }

    /** "0.6,0.25" -> {0.6, 0.25}; bad values: the defaults. */
    static double[] shares(String s) {
        final double[] d = { 0.6, 0.25 };
        if (s == null) {
            return d;
        }
        final String[] parts = s.split(",");
        for (int i = 0; (i < 2) && (i < parts.length); i++) {
            try {
                d[i] = Math.max(0, Math.min(1, Double.parseDouble(parts[i].trim())));
            } catch (NumberFormatException e) {
                return new double[] { 0.6, 0.25 };
            }
        }
        return d;
    }
}
