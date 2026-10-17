package org.l2start.modules.townbuffer;

import java.util.List;

import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;

/** Buffer spots: PP in 5 towns + SE next to PP in Giran/Goddard (post-7g). */
final class Towns {
    enum Kind {
        PP, SE
    }

    /**
     * @param id DB key (≤16 chars)
     * @param label short title prefix town name
     */
    record Spot(String id, String label, Kind kind, PlayerClass clazz, int level, long price, int x, int y, int z, int heading) {
    }

    /** PP next to gatekeepers (Dion: old x100 TownBuff spot). */
    static final List<Spot> ALL = List.of(
        new Spot("Gludio", "Gludio", Kind.PP, PlayerClass.PROPHET, 48, 20_000, -12796, 122811, -3114, 49152),
        new Spot("Dion", "Dion", Kind.PP, PlayerClass.PROPHET, 55, 62_000, 15680, 142920, -2704, 32768),
        new Spot("Giran", "Giran", Kind.PP, PlayerClass.PROPHET, 64, 116_000, 83461, 147904, -3404, 16384),
        new Spot("GiranSE", "Giran", Kind.SE, PlayerClass.SHILLIEN_ELDER, 64, 20_000, 83400, 147960, -3404, 16384),
        new Spot("Aden", "Aden", Kind.PP, PlayerClass.PROPHET, 74, 176_000, 146736, 25858, -2013, 0),
        new Spot("Goddard", "Goddard", Kind.PP, PlayerClass.PROPHET, 78, 200_000, 148024, -55235, -2728, 48000),
        new Spot("GoddardSE", "Goddard", Kind.SE, PlayerClass.SHILLIEN_SAINT, 78, 40_000, 147980, -55180, -2728, 48000));

    /** Old x100 TownBuffSkillIds (Wind Walk first). Berserker 1062 = PARTY_MEMBER. */
    static final int[] PP_BUFFS = { 1204, 1040, 1068, 1035, 1045, 1048, 1085, 1078, 1036, 1077, 1242, 1240, 1086, 1044, 1243, 1062, 1388, 1389 };
    /** @deprecated use {@link #PP_BUFFS} */
    static final int[] BUFFS = PP_BUFFS;
    static final int[] PP_WARRIOR_ONLY = { 1068, 1077, 1242, 1240, 1086, 1388 };
    static final int[] PP_MAGE_ONLY = { 1085, 1078, 1389 };
    /** @deprecated use {@link #PP_WARRIOR_ONLY} */
    static final int[] WARRIOR_ONLY = PP_WARRIOR_ONLY;
    /** @deprecated use {@link #PP_MAGE_ONLY} */
    static final int[] MAGE_ONLY = PP_MAGE_ONLY;

    /** SE set: Empower, Wild Magic, Vampiric Rage, Arcane Protection (1354 only if learned at level). */
    static final int[] SE_BUFFS = { 1059, 1303, 1268, 1354 };
    static final int[] SE_WARRIOR_ONLY = { 1268 };
    static final int[] SE_MAGE_ONLY = { 1059, 1303 };
    /** Skills that need a party member target (stock targetType). */
    static final int[] PARTY_MEMBER_BUFFS = { 1062 };

    private Towns() {
    }

    /** Legacy linear PP price (tests). Prefer {@link Spot#price()}. */
    static long price(int level) {
        return 20_000L + ((long) (Math.max(48, Math.min(78, level)) - 48) * 6_000L);
    }

    /** "Баф 55 ур - 62k" or "SE 64 ур - 20k" (≤16 chars). */
    static String title(Spot s) {
        final String prefix = s.kind() == Kind.SE ? "SE " : "Баф ";
        final String t = prefix + s.level() + " ур - " + Math.round(s.price() / 1000.0) + "k";
        return t.length() <= 16 ? t : t.substring(0, 16);
    }

    /** @deprecated use {@link #title(Spot)} */
    static String title(int level, long price) {
        final String t = "Баф " + level + " ур - " + Math.round(price / 1000.0) + "k";
        return t.length() <= 16 ? t : t.substring(0, 16);
    }

    static int[] buffs(Kind kind) {
        return kind == Kind.SE ? SE_BUFFS : PP_BUFFS;
    }

    private static boolean in(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    static boolean isPartyMemberBuff(int skillId) {
        return in(PARTY_MEMBER_BUFFS, skillId);
    }

    /** Mage/warrior filter for a buffer kind. */
    static boolean forClass(Kind kind, int skillId, boolean mage) {
        final int[] war = kind == Kind.SE ? SE_WARRIOR_ONLY : PP_WARRIOR_ONLY;
        final int[] mag = kind == Kind.SE ? SE_MAGE_ONLY : PP_MAGE_ONLY;
        if (in(war, skillId)) {
            return !mage;
        }
        if (in(mag, skillId)) {
            return mage;
        }
        return true; // shared (PP commons, SE Arcane Protection)
    }

    /** @deprecated PP-only */
    static boolean forClass(int skillId, boolean mage) {
        return forClass(Kind.PP, skillId, mage);
    }
}
