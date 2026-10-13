package org.l2start.modules.townbuffer;

import java.util.List;

/** The 5 buffers: NPC id, town, Prophet level, price per buff (EXTRAS_PLAN §5; pure, tested). */
final class Towns {
    record Town(int npcId, String name, int level) {
        long price() {
            return Towns.price(level);
        }
    }

    static final List<Town> ALL = List.of(new Town(95200, "Gludio", 48), new Town(95201, "Dion", 55), new Town(95202, "Giran", 64), new Town(95203, "Aden", 74), new Town(95204, "Goddard", 78));

    /** Prophet buffs (Hierophant at 76+: the 3 Prophecies at 78). */
    static final int[] BUFFS = { 1040, 1068, 1035, 1036, 1043, 1044, 1045, 1048, 1062, 1077, 1078, 1085, 1086, 1087, 1240, 1242, 1243, 1059, 1268, 1303, 1304, 1388, 1389, 1032, 1033, 1182, 1189, 1191, 1257, 1352, 1353, 1392, 1393, 1355, 1356, 1357 };

    private Towns() {
    }

    /** Linear: 20k at Prophet level 48 .. 200k at 78 (6k per level). */
    static long price(int level) {
        return 20_000L + ((long) (Math.max(48, Math.min(78, level)) - 48) * 6_000L);
    }

    /** "Баф 62k". */
    static String title(long price) {
        return "Баф " + Math.round(price / 1000.0) + "k";
    }

    static Town byNpc(int npcId) {
        for (Town t : ALL) {
            if (t.npcId() == npcId) {
                return t;
            }
        }
        return null;
    }
}
