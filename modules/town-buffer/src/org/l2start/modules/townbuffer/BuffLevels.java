package org.l2start.modules.townbuffer;

import java.util.LinkedHashMap;
import java.util.Map;

/** Highest level of each buff learnable up to a character level (pure, tested). */
final class BuffLevels {
    record Learn(int skillId, int skillLevel, int getLevel) {
    }

    private BuffLevels() {
    }

    /** @return skill id -> level, in the order of {@code wanted}; buffs not learnable yet are left out */
    static Map<Integer, Integer> at(Iterable<Learn> tree, int[] wanted, int level) {
        final Map<Integer, Integer> best = new LinkedHashMap<>();
        for (int id : wanted) {
            int lv = 0;
            for (Learn l : tree) {
                if ((l.skillId() == id) && (l.getLevel() <= level) && (l.skillLevel() > lv)) {
                    lv = l.skillLevel();
                }
            }
            if (lv > 0) {
                best.put(id, lv);
            }
        }
        return best;
    }
}
