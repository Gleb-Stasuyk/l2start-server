package org.l2start.bots.brain.basic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * KT12 F3/F4: party buff sets by the target's combat role; Berserker Spirit for everyone; BD/SWS dances and songs
 * by role. In town / peace only Wind Walk (F4).
 */
public final class PartyBuffs {
    public enum Role {
        MAGE, MELEE, ARCHER, TANK, DAGGER
    }

    /** Wind Walk — the only buff in a peace zone (F4). */
    public static final int WIND_WALK = 1204;
    /** Berserker Spirit — every role (F3). */
    public static final int BERSERKER = 1062;

    /** Shared prophet/elder buffs for every role. */
    static final int[] SHARED = { WIND_WALK, 1040, 1035, 1045, 1048, 1036, BERSERKER, 1044 };
    static final int[] MAGE = { 1085, 1059, 1078, 1389 };
    static final int[] MELEE = { 1068, 1086, 1077, 1242, 1240, 1268, 1388 };
    static final int[] ARCHER = { 1068, 1086, 1077, 1242, 1240 };
    static final int[] TANK = { 1068, 1243, 1389 };
    static final int[] DAGGER = { 1068, 1086, 1077, 1242, 1268, 1240 };

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

    private static void add(List<Integer> out, int[] ids) {
        for (int id : ids) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
    }
}
