package org.l2start.bots.brain.basic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * KT12 F3/F4: party buff sets by the target's combat role; Berserker Spirit for everyone; BD/SWS dances and songs
 * by role. In town / peace only Wind Walk (F4). RB party uses {@link #wantedRaid}: a shorter set so the prophet's
 * mana lasts; the tank gets the full hunt set except Berserker; mages without Regeneration / Magic Barrier / Mental Shield.
 * The second full party is buffed without Berserker.
 */
public final class PartyBuffs {
    public enum Role {
        MAGE, MELEE, ARCHER, TANK, DAGGER
    }

    /** Wind Walk — the only buff in a peace zone (F4). */
    public static final int WIND_WALK = 1204;
    public static final int SHIELD = 1040;
    public static final int MENTAL_SHIELD = 1035;
    public static final int MAGIC_BARRIER = 1036;
    public static final int REGENERATION = 1044;
    public static final int BLESSED_BODY = 1045;
    public static final int BLESSED_SOUL = 1048;
    /** Berserker Spirit — every hunt role (F3); not the RB tank. */
    public static final int BERSERKER = 1062;
    public static final int MIGHT = 1068;
    public static final int HASTE = 1086;
    public static final int FOCUS = 1077;
    public static final int ACUMEN = 1085;
    public static final int EMPOWER = 1059;
    public static final int CONCENTRATION = 1078;
    public static final int BLESS_SHIELD = 1243;
    public static final int GREATER_SHIELD = 1389;
    /** Do not recast Wind Walk while more than this many seconds remain. */
    public static final int WW_KEEP_SEC = 10 * 60;

    /** Shared prophet/elder buffs for every role. */
    static final int[] SHARED = { WIND_WALK, SHIELD, MENTAL_SHIELD, BLESSED_BODY, BLESSED_SOUL, MAGIC_BARRIER, BERSERKER, REGENERATION };
    static final int[] MAGE = { ACUMEN, EMPOWER, CONCENTRATION, GREATER_SHIELD };
    static final int[] MELEE = { MIGHT, HASTE, FOCUS, 1242, 1240, 1268, 1388 };
    static final int[] ARCHER = { MIGHT, HASTE, FOCUS, 1242, 1240 };
    static final int[] TANK = { MIGHT, BLESS_SHIELD, GREATER_SHIELD };
    static final int[] DAGGER = { MIGHT, HASTE, FOCUS, 1242, 1268, 1240 };

    /** RB: speed + a cheap shield for everyone. */
    static final int[] RAID_BASE = { WIND_WALK, SHIELD };
    /** RB mages: no Regeneration, Magic Barrier or Mental Shield. */
    static final int[] RAID_MAGE = { BLESSED_SOUL, BERSERKER, ACUMEN, EMPOWER, CONCENTRATION };
    static final int[] RAID_FIGHTER = { BLESSED_BODY, BERSERKER, MIGHT, HASTE, FOCUS };

    /** Bladedancer dances by target role (Interlude). */
    static final int[] DANCE_MELEE = { 271, 274, 275, 272 }; // Warrior, Fire, Fury, Inspiration
    static final int[] DANCE_MAGE = { 273, 276 }; // Mystic, Concentration
    static final int[] DANCE_ARCHER = { 272, 274, 275 };
    static final int[] DANCE_TANK = { 271, 272 };
    static final int[] DANCE_DAGGER = { 274, 275, 272 };

    /** Swordsinger songs by target role. */
    static final int[] SONG_MELEE = { 269, 268, 264, 304 }; // Hunter, Wind, Earth, Vitality
    static final int[] SONG_MAGE = { 265, 266, 363 }; // Life, Water, Meditation
    static final int[] SONG_ARCHER = { 269, 268, 304 };
    static final int[] SONG_TANK = { 264, 266, 304 };
    static final int[] SONG_DAGGER = { 269, 268, 304 };

    private static final Set<Integer> MAGES = Set.of(
        10, 11, 12, 13, 14, 15, 16, 17, 25, 26, 27, 28, 29, 30, 38, 39, 40, 41, 42, 43, 49, 50, 51, 52,
        94, 95, 96, 97, 98, 103, 104, 105, 110, 111, 112, 115, 116);
    private static final Set<Integer> ARCHERS = Set.of(9, 24, 37, 92, 102, 109);
    private static final Set<Integer> TANKS = Set.of(4, 5, 6, 19, 20, 32, 33, 90, 91, 99, 106);
    private static final Set<Integer> DAGGERS = Set.of(7, 8, 22, 23, 35, 36, 93, 101, 108);
    private static final Set<Integer> BLADEDANCERS = Set.of(34, 107);
    private static final Set<Integer> SWORDSINGERS = Set.of(21, 100);

    private PartyBuffs() {
    }

    public static Role roleOf(int classId) {
        if (MAGES.contains(classId)) {
            return Role.MAGE;
        }
        if (ARCHERS.contains(classId)) {
            return Role.ARCHER;
        }
        if (TANKS.contains(classId)) {
            return Role.TANK;
        }
        if (DAGGERS.contains(classId)) {
            return Role.DAGGER;
        }
        return Role.MELEE;
    }

    public static boolean isBladedancer(int classId) {
        return BLADEDANCERS.contains(classId);
    }

    public static boolean isSwordsinger(int classId) {
        return SWORDSINGERS.contains(classId);
    }

    /**
     * Buff ids to put on {@code targetClassId} from {@code casterClassId}. Caller keeps only skills the caster knows.
     * @param inPeace when true (town / peace zone) only Wind Walk (F4)
     */
    public static List<Integer> wanted(int casterClassId, int targetClassId, boolean inPeace) {
        if (inPeace) {
            return List.of(WIND_WALK);
        }
        final Role role = roleOf(targetClassId);
        final List<Integer> out = new ArrayList<>();
        add(out, SHARED);
        add(out, switch (role) {
            case MAGE -> MAGE;
            case ARCHER -> ARCHER;
            case TANK -> TANK;
            case DAGGER -> DAGGER;
            case MELEE -> MELEE;
        });
        if (isBladedancer(casterClassId)) {
            add(out, switch (role) {
                case MAGE -> DANCE_MAGE;
                case ARCHER -> DANCE_ARCHER;
                case TANK -> DANCE_TANK;
                case DAGGER -> DANCE_DAGGER;
                case MELEE -> DANCE_MELEE;
            });
        }
        if (isSwordsinger(casterClassId)) {
            add(out, switch (role) {
                case MAGE -> SONG_MAGE;
                case ARCHER -> SONG_ARCHER;
                case TANK -> SONG_TANK;
                case DAGGER -> SONG_DAGGER;
                case MELEE -> SONG_MELEE;
            });
        }
        return out;
    }

    /** Full set (hunt); same as {@link #wanted(int, int, boolean)} with inPeace false. */
    public static List<Integer> wanted(int casterClassId, int targetClassId) {
        return wanted(casterClassId, targetClassId, false);
    }

    /**
     * RB party on "баф": a short set so the prophet's mana covers the whole party.
     * Tank: the full hunt prophet set (SHARED + TANK arrays) except Berserker — Might yes;
     * Haste / Focus stay melee-only and are not on the hunt tank. Mages: no Regeneration, Magic Barrier or Mental Shield.
     * Party 1 DDs get Berserker; the second full party does not ({@link #wantedRaid(int, int, boolean)}).
     */
    public static List<Integer> wantedRaid(int casterClassId, int targetClassId) {
        return wantedRaid(casterClassId, targetClassId, true);
    }

    /**
     * @param berserker false = second full party (and the tank): no Berserker Spirit
     */
    public static List<Integer> wantedRaid(int casterClassId, int targetClassId, boolean berserker) {
        final Role role = roleOf(targetClassId);
        if (role == Role.TANK) {
            final List<Integer> out = new ArrayList<>();
            add(out, SHARED);
            add(out, TANK);
            out.removeIf(id -> id == BERSERKER);
            return out;
        }
        final List<Integer> out = new ArrayList<>();
        add(out, RAID_BASE);
        add(out, role == Role.MAGE ? RAID_MAGE : RAID_FIGHTER);
        if (!berserker) {
            out.removeIf(id -> id == BERSERKER);
        }
        return out;
    }

    /**
     * Keep only buffs the caster can use at its level (skill id present in {@code learnable}); Wind Walk and
     * Berserker Spirit stay first when learnable. Empty learnable → empty list.
     */
    public static List<Integer> filterLearnable(List<Integer> wanted, java.util.Set<Integer> learnable) {
        if ((wanted == null) || wanted.isEmpty() || (learnable == null) || learnable.isEmpty()) {
            return List.of();
        }
        final List<Integer> out = new ArrayList<>();
        if (learnable.contains(WIND_WALK) && wanted.contains(WIND_WALK)) {
            out.add(WIND_WALK);
        }
        if (learnable.contains(BERSERKER) && wanted.contains(BERSERKER)) {
            out.add(BERSERKER);
        }
        for (int id : wanted) {
            if ((id == WIND_WALK) || (id == BERSERKER)) {
                continue;
            }
            if (learnable.contains(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Ids learnable from a skill tree at {@code charLevel} (skill level ignored — presence only). */
    public static java.util.Set<Integer> learnableIds(Iterable<int[]> treeSkillIdGetLevel, int charLevel) {
        final java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        if (treeSkillIdGetLevel == null) {
            return out;
        }
        for (int[] row : treeSkillIdGetLevel) {
            if ((row != null) && (row.length >= 2) && (row[1] <= charLevel)) {
                out.add(row[0]);
            }
        }
        return out;
    }

    private static void add(List<Integer> out, int[] ids) {
        for (int id : ids) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
    }
}
