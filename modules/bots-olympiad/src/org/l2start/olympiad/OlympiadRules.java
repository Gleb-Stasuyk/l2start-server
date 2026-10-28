package org.l2start.olympiad;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure olympiad roster and signup rules (Living Worlds PhantomOlympiadRules pattern, rewritten).
 * No Mobius types: unit-tested. Stock min points (classed 3, non-classed 5) and participant floors
 * ({@code classedMin}/{@code nonClassedMin}) are passed in from stock config by the caller.
 */
public final class OlympiadRules {
    private OlympiadRules() {
    }

    /**
     * Interlude combat 3rd professions (MVP). Supports and summoners are excluded:
     * 96, 97, 98, 104, 105, 111, 112, 115, 116.
     */
    public static final List<Integer> COMBAT_THIRD = List.of(
        88, 89, 90, 91, 92, 93, 94, 95,
        99, 100, 101, 102, 103,
        106, 107, 108, 109, 110,
        113, 114, 117, 118);

    public static final Set<Integer> COMBAT_THIRD_SET = Set.copyOf(COMBAT_THIRD);

    public static final Set<Integer> EXCLUDED_THIRD = Set.of(96, 97, 98, 104, 105, 111, 112, 115, 116);

    /** Stock floors copied by the caller from Olympiad.ini (defaults 5 / 9). */
    public static final int DEFAULT_CLASSED_MIN = 5;
    public static final int DEFAULT_NONCLASSED_MIN = 9;
    public static final int CLASSED_MIN_POINTS = 3;
    public static final int NONCLASSED_MIN_POINTS = 5;

    public static boolean allowedClass(int classId) {
        return COMBAT_THIRD_SET.contains(classId);
    }

    /**
     * Base quota of one class in a roster of {@code rosterSize}. First {@code rosterSize % N} classes in
     * {@link #COMBAT_THIRD} get one extra so the quotas sum to {@code rosterSize}. Unknown classes get 0.
     */
    public static int baseQuota(int classId, int rosterSize) {
        return baseQuotas(rosterSize).getOrDefault(classId, 0);
    }

    public static Map<Integer, Integer> baseQuotas(int rosterSize) {
        final int n = COMBAT_THIRD.size();
        final Map<Integer, Integer> out = new LinkedHashMap<>();
        if ((rosterSize <= 0) || (n == 0)) {
            return out;
        }
        final int base = rosterSize / n;
        final int extra = rosterSize % n;
        for (int i = 0; i < n; i++) {
            out.put(COMBAT_THIRD.get(i), base + (i < extra ? 1 : 0));
        }
        return out;
    }

    /**
     * How many nobles of each class the roster should hold. Idle size is {@code rosterSize}; when a live
     * player waits in a classed queue of class C, that class is raised to at least
     * {@code max(base + rivalsPerClass, botsNeeded(live, classedMin))} so a rare class (2 Storm Screamers)
     * can still fill {@code OlympiadClassedParticipants}. The total may exceed {@code rosterSize}: a class
     * deficit beats the global cap.
     */
    public static Map<Integer, Integer> rosterTargets(int rosterSize, int rivalsPerClass, Set<Integer> liveClassedClasses) {
        return rosterTargets(rosterSize, rivalsPerClass, liveClassedClasses, Map.of(), DEFAULT_CLASSED_MIN);
    }

    public static Map<Integer, Integer> rosterTargets(int rosterSize, int rivalsPerClass, Set<Integer> liveClassedClasses,
        Map<Integer, Integer> liveByClass, int classedMin) {
        final Map<Integer, Integer> out = new LinkedHashMap<>(baseQuotas(rosterSize));
        if ((liveClassedClasses == null) || liveClassedClasses.isEmpty()) {
            return out;
        }
        for (int c : liveClassedClasses) {
            if (!allowedClass(c)) {
                continue;
            }
            final int live = liveByClass == null ? 1 : Math.max(1, liveByClass.getOrDefault(c, 1));
            final int withRivals = out.getOrDefault(c, 0) + Math.max(0, rivalsPerClass);
            out.put(c, Math.max(withRivals, botsNeeded(live, classedMin)));
        }
        return out;
    }

    /**
     * How many bots of the live player's class must be in the classed pool (not counting the live player)
     * to reach the even {@link #poolCap}. 1 live and min 5 → 5 bots (cap 6).
     */
    public static int botsNeeded(int liveCount, int minParticipants) {
        if (liveCount <= 0) {
            return 0;
        }
        return Math.max(0, poolCap(liveCount, minParticipants) - liveCount);
    }

    /**
     * Class with the largest shortfall vs {@code target}, even when the total roster is already at or above
     * {@code rosterSize}. 0 = every class is at target.
     */
    public static int pickCreateClass(Map<Integer, Integer> target, Map<Integer, Integer> have) {
        int best = 0;
        int bestNeed = 0;
        for (int cls : COMBAT_THIRD) {
            final int need = target.getOrDefault(cls, 0) - (have == null ? 0 : have.getOrDefault(cls, 0));
            if (need > bestNeed) {
                bestNeed = need;
                best = cls;
            }
        }
        return best;
    }

    /**
     * Even pool cap to fill when live players are waiting: at least {@code minParticipants}, at least
     * one rival for each live player, then rounded up to even so 1v1 matches pair up. 0 when nobody live
     * is waiting (background signup uses chance instead).
     */
    public static int poolCap(int liveCount, int minParticipants) {
        if (liveCount <= 0) {
            return 0;
        }
        int cap = Math.max(minParticipants, liveCount + 1);
        if ((cap & 1) == 1) {
            cap++;
        }
        return cap;
    }

    public static boolean stillResting(long now, long restUntil) {
        return now < restUntil;
    }

    /** What a free (or already listed) noble should do this signup pass. */
    public enum Action {
        NONE,
        REGISTER_CLASSED,
        REGISTER_NON_CLASSED,
        UNREGISTER,
        /** Registered non-classed, but a live player of this class waits in classed: hop queues. */
        SWITCH_TO_CLASSED
    }

    /**
     * @param points olympiad points, {@code -1} if unknown (stock will grant start points on first register);
     *        known values below the stock floor skip that queue
     * @param roll 0..99, compared to {@code chancePercent} for background bot vs bot
     */
    public record SignUpInput(boolean inCompPeriod, boolean inMatch, boolean registered, boolean registeredClassed, boolean resting, int points, int liveNonClassed,
        int liveClassedThis, boolean anyLive, int classedPoolSize, int nonClassedPoolSize, int classedMin, int nonClassedMin, int chancePercent, int roll) {
    }

    public static Action shouldSignUp(SignUpInput in) {
        if (in.inMatch()) {
            return Action.NONE;
        }
        if (!in.inCompPeriod()) {
            return in.registered() ? Action.UNREGISTER : Action.NONE;
        }
        if (in.resting()) {
            return Action.NONE;
        }
        if (in.registeredClassed() && (in.liveClassedThis() <= 0)) {
            return Action.UNREGISTER;
        }
        if (in.registered() && !in.registeredClassed() && (in.liveClassedThis() > 0)) {
            final int cap = poolCap(in.liveClassedThis(), in.classedMin());
            if ((in.classedPoolSize() < cap) && pointsOk(in.points(), CLASSED_MIN_POINTS)) {
                return Action.SWITCH_TO_CLASSED;
            }
        }
        if (in.registered()) {
            return Action.NONE;
        }
        if (in.liveClassedThis() > 0) {
            final int cap = poolCap(in.liveClassedThis(), in.classedMin());
            if ((in.classedPoolSize() < cap) && pointsOk(in.points(), CLASSED_MIN_POINTS)) {
                return Action.REGISTER_CLASSED;
            }
        }
        if (in.liveNonClassed() > 0) {
            final int cap = poolCap(in.liveNonClassed(), in.nonClassedMin());
            if ((in.nonClassedPoolSize() < cap) && pointsOk(in.points(), NONCLASSED_MIN_POINTS)) {
                return Action.REGISTER_NON_CLASSED;
            }
        }
        if (!in.anyLive() && (in.roll() < in.chancePercent()) && pointsOk(in.points(), NONCLASSED_MIN_POINTS)) {
            return Action.REGISTER_NON_CLASSED;
        }
        return Action.NONE;
    }

    /** {@code points < 0} = unknown (let stock decide). 0 is treated as unknown: stock inserts start points. */
    static boolean pointsOk(int points, int min) {
        return (points < 0) || (points >= min);
    }

    public static Set<Integer> combatThird() {
        return Collections.unmodifiableSet(COMBAT_THIRD_SET);
    }
}
