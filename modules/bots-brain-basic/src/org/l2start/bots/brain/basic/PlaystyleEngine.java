package org.l2start.bots.brain.basic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2start.bots.api.NpcView;
import org.l2start.bots.api.SelfView;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.api.WorldView;
import org.l2start.bots.brain.basic.Playstyles.Cond;
import org.l2start.bots.brain.basic.Playstyles.Entry;
import org.l2start.bots.brain.basic.Playstyles.Use;

/**
 * Phase 4c: one combat decision by the class playstyle. The lines are tried in tactical order (panic, opener, stance,
 * control, debuff, area, then the rotation in document order, like LW's controller) and the first one whose skill
 * the bot knows, is ready, affordable, inside its level window, past its own pace and whose conditions hold is
 * chosen. Per bot; the state is what it cast on which target. PULL (party pulling) and LIMIT (needs a healer) are not
 * used solo. REAR = the bot stands behind the target (it looks away), CHARGES / CHARGES_BELOW = the bot's sonic or
 * force charges, NOT_SPOILED = the target is not spoiled yet (at most 3 tries on one target), MOBS_UNSPOILED =
 * unspoiled monsters around the target, TARGET_UNDEAD = an undead target. PvP lines are not used against monsters.
 */
final class PlaystyleEngine {
    /** What to cast. {@code self} = on the bot itself (panic, stance). */
    record Pick(Entry entry, SkillView skill, boolean self) {
    }

    static final long DEBUFF_MS = 30_000;
    static final long PHYSICAL_PACE = 1500;
    static final long SHARED_PACE = 250;

    private final Map<Integer, Long> paceUntil = new HashMap<>();
    private final Map<Integer, Long> castOnTarget = new HashMap<>();
    private final Set<Integer> onceOnTarget = new HashSet<>();
    private final Map<Integer, Integer> triesOnTarget = new HashMap<>();
    /** Spoil tries on one target (a failed Spoil may be tried again). */
    static final int SPOIL_TRIES = 3;
    /** Behind = at least this far (degrees) off where the target looks: within 60 degrees of its back, like stock. */
    static final double REAR_DEG = 120;
    private int target;
    private long sharedUntil;
    private boolean opened;
    /** Last chosen line (//bots info, tests). */
    private String last = "";

    String last() {
        return last;
    }

    /** A new fight target: per-target memory starts over. */
    void target(int objectId) {
        if (objectId != target) {
            target = objectId;
            castOnTarget.clear();
            onceOnTarget.clear();
            triesOnTarget.clear();
            opened = false;
        }
    }

    static int priority(Use u) {
        return switch (u) {
            case PANIC -> 0;
            case LIMIT -> 1;
            case OPENER -> 2;
            case STANCE -> 3;
            case CONTROL -> 4;
            case DEBUFF -> 5;
            case AOE -> 6;
            case ROTATION -> 7;
            case PULL -> 9;
        };
    }

    static List<Entry> ordered(Playstyles.Playstyle p) {
        final List<Entry> l = new ArrayList<>(p.entries());
        l.sort(Comparator.comparingInt((Entry e) -> priority(e.use())).thenComparingInt(Entry::order));
        return l;
    }

    /**
     * @param bad skill on this monster type refused lately (BasicBrain.badKey)
     * @return what to cast now, null = nothing (the brain hits or nukes as before)
     */
    Pick pick(Playstyles.Playstyle p, SelfView me, NpcView mob, WorldView w, Map<Long, Long> bad, long now) {
        return pick(p, me, mob, w, bad, now, false);
    }

    /**
     * @param pvp true = Grand Olympiad / 1v1: {@code PVP} lines fire, spoil/undead/mob-count lines do not, AOE is
     *        allowed on the single rival (never used to pull standing monsters)
     */
    Pick pick(Playstyles.Playstyle p, SelfView me, NpcView mob, WorldView w, Map<Long, Long> bad, long now, boolean pvp) {
        if ((p == null) || (mob == null) || (now < sharedUntil)) {
            return null;
        }
        target(mob.objectId());
        for (Entry e : ordered(p)) {
            if ((e.use() == Use.PULL) || (e.use() == Use.LIMIT) || !e.fitsLevel(me.level()) || (paceUntil.getOrDefault(e.skillId(), 0L) > now)
                || (bad.getOrDefault(BasicBrain.badKey(e.skillId(), mob.npcId()), 0L) > now)) {
                continue;
            }
            final SkillView s = known(me, e.skillId());
            if ((s == null) || !s.ready() || (s.mpCost() > me.mp())) {
                continue;
            }
            if (e.use() == Use.STANCE) {
                if ((s.buffLeft() != -1) && (me.mpPct() >= 25)) {
                    return new Pick(e, s, true);
                }
                continue;
            }
            if ((e.use() == Use.OPENER) && (opened || (mob.hpPct() < 95))) {
                continue;
            }
            if ((e.use() == Use.AOE) && !pvp && (BasicBrain.onMe(w, me) < 2)) {
                continue; // an area skill only on monsters already on the bot: never pull the ones standing around
            }
            if (holds(e, me, mob, w, now, pvp)) {
                return new Pick(e, s, e.use() == Use.PANIC);
            }
        }
        return null;
    }

    /** The cast went through (or was refused / on cooldown): remember it for pacing and per-target conditions. */
    void cast(Pick pick, boolean ok, boolean mage, long now) {
        final Entry e = pick.entry();
        if (!ok) {
            paceUntil.put(e.skillId(), now + 1000);
            return;
        }
        final long pace = e.paceMs() >= 0 ? e.paceMs() : (mage ? 0 : PHYSICAL_PACE);
        paceUntil.put(e.skillId(), now + pace);
        sharedUntil = now + SHARED_PACE;
        castOnTarget.put(e.skillId(), now);
        onceOnTarget.add(e.skillId());
        triesOnTarget.merge(e.skillId(), 1, Integer::sum);
        opened = true;
        last = e.use() + " " + e.skillId() + " " + e.name() + " (" + why(e) + ")";
    }

    /** The conditions of a line with their numbers, for //bots info ("SELF_HP_BELOW 45, UNDER_ATTACK"). */
    static String why(Entry e) {
        final StringBuilder sb = new StringBuilder();
        for (Cond c : e.when()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(c);
            switch (c) {
                case MP_ABOVE -> sb.append(' ').append((int) e.mpAbove());
                case TARGET_HP_BELOW -> sb.append(' ').append((int) e.hpBelow());
                case TARGET_HP_ABOVE -> sb.append(' ').append((int) e.hpAbove());
                case SELF_HP_BELOW -> sb.append(' ').append((int) e.selfHpBelow());
                case MOBS_NEAR, MOBS_UNSPOILED -> sb.append(' ').append(Math.max(2, e.mobsAtLeast()));
                case CHARGES -> sb.append(' ').append(Math.max(1, e.chargesAtLeast()));
                case CHARGES_BELOW -> sb.append(' ').append(e.chargesBelow());
                default -> {
                }
            }
        }
        return sb.toString();
    }

    private static SkillView known(SelfView me, int id) {
        for (SkillView s : me.skills()) {
            if (s.id() == id) {
                return s;
            }
        }
        return null;
    }

    boolean holds(Entry e, SelfView me, NpcView mob, WorldView w, long now) {
        return holds(e, me, mob, w, now, false);
    }

    boolean holds(Entry e, SelfView me, NpcView mob, WorldView w, long now, boolean pvp) {
        for (Cond c : e.when()) {
            final boolean ok = switch (c) {
                case ALWAYS, SELF_ABNORMAL_FREE, HEALER_READY -> true;
                case MP_ABOVE -> me.mpPct() > e.mpAbove();
                case TARGET_HP_BELOW -> mob.hpPct() < e.hpBelow();
                case TARGET_HP_ABOVE -> mob.hpPct() > e.hpAbove();
                case SELF_HP_BELOW -> me.hpPct() < e.selfHpBelow();
                case MOBS_NEAR -> !pvp && mobsNear(w, mob) >= Math.max(2, e.mobsAtLeast());
                case DEBUFF_MISSING -> (now - castOnTarget.getOrDefault(e.skillId(), -DEBUFF_MS)) >= DEBUFF_MS;
                case ONCE_PER_TARGET -> !onceOnTarget.contains(e.skillId());
                case NOT_SPOILED -> !pvp && !mob.spoiled() && (triesOnTarget.getOrDefault(e.skillId(), 0) < SPOIL_TRIES);
                case MOBS_UNSPOILED -> !pvp && unspoiledNear(w, mob) >= Math.max(2, e.mobsAtLeast());
                case REAR -> mob.behind(me.pos(), REAR_DEG);
                case CHARGES -> me.charges() >= Math.max(1, e.chargesAtLeast());
                case CHARGES_BELOW -> me.charges() < e.chargesBelow();
                case TARGET_UNDEAD -> !pvp && mob.undead();
                case DURABLE_TARGET -> (mob.hpPct() > 50) && (mob.level() >= (me.level() - 2));
                case UNDER_ATTACK, FOCUS_ON_ME -> mob.targetId() == me.objectId();
                case PVP -> pvp;
                case UNKNOWN -> false;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Living unspoiled monsters within 200 of the target, the target included. */
    static int unspoiledNear(WorldView w, NpcView mob) {
        int n = 0;
        for (NpcView v : w.npcs()) {
            if (!v.dead() && v.monster() && !v.spoiled() && (v.pos().distance2d(mob.pos()) <= 200)) {
                n++;
            }
        }
        return n;
    }

    /** Living monsters within 200 of the target, the target included. */
    static int mobsNear(WorldView w, NpcView mob) {
        int n = 0;
        for (NpcView v : w.npcs()) {
            if (!v.dead() && v.monster() && (v.pos().distance2d(mob.pos()) <= 200)) {
                n++;
            }
        }
        return n;
    }
}
