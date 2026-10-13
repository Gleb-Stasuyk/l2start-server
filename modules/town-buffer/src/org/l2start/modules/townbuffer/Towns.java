package org.l2start.modules.townbuffer;

import java.util.List;

/** The 5 buffers: town, Prophet level, spot, price of a full set (EXTRAS_PLAN §5; pure, tested). */
final class Towns {
    record Town(String name, int level, int x, int y, int z, int heading) {
        long price() {
            return Towns.price(level);
        }
    }

    /** Next to the gatekeepers (Dion: the spot of the old x100 TownBuff bot). */
    static final List<Town> ALL = List.of(new Town("Gludio", 48, -12796, 122811, -3114, 49152), new Town("Dion", 55, 15680, 142920, -2704, 32768), new Town("Giran", 64, 83461, 147904, -3404, 16384),
        new Town("Aden", 74, 146736, 25858, -2013, 0), new Town("Goddard", 78, 148024, -55235, -2728, 48000));

    /** The old x100 TownBuffSkillIds, in its order (Wind Walk first). */
    static final int[] BUFFS = { 1204, 1040, 1068, 1035, 1045, 1048, 1085, 1078, 1036, 1077, 1242, 1240, 1086, 1044, 1243, 1062, 1388, 1389 };
    /** Old TownBuffWarriorSkillIds: Might, Focus, Death Whisper, Guidance, Haste, Greater Might. */
    static final int[] WARRIOR_ONLY = { 1068, 1077, 1242, 1240, 1086, 1388 };
    /** Old TownBuffMageSkillIds: Acumen, Concentration, Greater Shield. */
    static final int[] MAGE_ONLY = { 1085, 1078, 1389 };

    private Towns() {
    }

    /** Price of a full set, linear: 20k at Prophet level 48 .. 200k at 78 (6k per level). */
    static long price(int level) {
        return 20_000L + ((long) (Math.max(48, Math.min(78, level)) - 48) * 6_000L);
    }

    /** "Баф 55 ур - 62k" (16 letters at most, the client limit). */
    static String title(int level, long price) {
        final String t = "Баф " + level + " ур - " + Math.round(price / 1000.0) + "k";
        return t.length() <= 16 ? t : t.substring(0, 16);
    }

    private static boolean in(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    /** May this buff go to a mage (true) or a warrior (false)? Old x100 canReceiveBuff. */
    static boolean forClass(int skillId, boolean mage) {
        if (in(WARRIOR_ONLY, skillId)) {
            return !mage;
        }
        if (in(MAGE_ONLY, skillId)) {
            return mage;
        }
        return true;
    }
}
