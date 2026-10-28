package org.l2start.modules.townbuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The trade check and the buff plan (pure, tested). */
final class Deal {
    enum Verdict {
        OK, NOT_ENOUGH, WRONG
    }

    private Deal() {
    }

    /**
     * The player's side of the trade window: adena only, at least the price (overpay is kept, like on the old server); the
     * bot gives nothing.
     * @param lines item id and count of each line the player put
     */
    static Verdict check(List<long[]> lines, int botLines, long price) {
        if (botLines != 0) {
            return Verdict.WRONG;
        }
        long adena = 0;
        for (long[] l : lines) {
            if (l[0] != 57) {
                return Verdict.WRONG;
            }
            adena += l[1];
        }
        return adena >= price ? Verdict.OK : Verdict.NOT_ENOUGH;
    }

    /** Skill id and level to cast, in order, only the buffs of the payer's kind (mage or warrior). */
    static List<int[]> plan(Map<Integer, Integer> levels, int[] order, Towns.Kind kind, boolean mage) {
        final List<int[]> out = new ArrayList<>();
        for (int id : order) {
            final Integer lv = levels.get(id);
            if ((lv != null) && Towns.forClass(kind, id, mage)) {
                out.add(new int[] { id, lv });
            }
        }
        return out;
    }

    /** PP plan (tests / legacy). */
    static List<int[]> plan(Map<Integer, Integer> levels, boolean mage) {
        return plan(levels, Towns.PP_BUFFS, Towns.Kind.PP, mage);
    }
}
