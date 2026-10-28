package org.l2start.bots.brain.basic;

import java.util.List;
import java.util.Set;

import org.l2start.bots.api.NpcView;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SelfView;

/**
 * Phase 9 D: pure olympiad stadium rules. The brain gates world AI on these; stock still owns windows, points and
 * item restrictions ({@code ItemSkills} refuse potions in olympiad mode).
 */
public final class OlympiadCombat {
    private OlympiadCombat() {
    }

    /** In a stadium match: contestant flag or a stock game id (set before teleport, {@code -1} = none). */
    public static boolean inMatch(boolean olympiadMode, int olympiadGameId) {
        return olympiadMode || (olympiadGameId >= 0);
    }

    public static boolean inMatch(SelfView me) {
        return me != null && me.inOlympiadMatch();
    }

    /** Stock blocks item skills in a Grand Olympiad match; the brain must not try to drink or read a scroll. */
    public static boolean allowConsumables(boolean inMatch) {
        return !inMatch;
    }

    /** The match has begun and the bot can act (not a corpse waiting for stock to end the fight). */
    public static boolean shouldFight(boolean inMatch, boolean olympiadStart, boolean dead) {
        return inMatch && olympiadStart && !dead;
    }

    /** Teleported to the stadium, countdown still running: stand, maybe self-buff, do not stroll or sit. */
    public static boolean shouldWait(boolean inMatch, boolean olympiadStart, boolean dead) {
        return inMatch && !dead && !olympiadStart;
    }

    /**
     * The other contestant of this game. Observers share a game id but are not {@code olympiadMode}; they are skipped.
     * Same-game id wins; if the bot's id is still unknown, the nearest contestant.
     */
    public static PlayerView rival(SelfView me, List<PlayerView> players) {
        if ((me == null) || (players == null) || players.isEmpty()) {
            return null;
        }
        PlayerView best = null;
        for (PlayerView p : players) {
            if ((p == null) || (p.objectId() == me.objectId()) || !p.olympiadMode()) {
                continue;
            }
            if ((me.olympiadGameId() >= 0) && (p.olympiadGameId() != me.olympiadGameId())) {
                continue;
            }
            if ((best == null) || (p.distance() < best.distance())) {
                best = p;
            }
        }
        return best;
    }

    /** Playstyle engine talks to {@link NpcView}; a player is the same shape for HP, heading and who they look at. */
    public static NpcView asTarget(PlayerView p) {
        if (p == null) {
            return null;
        }
        return new NpcView(p.objectId(), 0, p.name(), p.level(), p.pos(), p.distance(), p.hpPct(), false, false, false, true, p.targetId(), false,
            NpcView.MELEE, p.inCombat(), 0, p.heading(), false, false, false);
    }

    public enum Role {
        TANK, MELEE, ARCHER, MAGE, SUMMONER
    }

    /** Phoenix Knight, Hell Knight, Eva's Templar, Shillien Templar. */
    static final Set<Integer> TANKS = Set.of(90, 91, 99, 106);
    /** Sagittarius, Moonlight Sentinel, Ghost Sentinel. */
    static final Set<Integer> ARCHERS = Set.of(92, 102, 109);
    /** Soultaker (necromancer). True summoner 3rds stay off the olympiad allow-list. */
    static final Set<Integer> SUMMONERS = Set.of(95);

    public static Role role(int classId, boolean bow, boolean mage) {
        if (bow || ARCHERS.contains(classId)) {
            return Role.ARCHER;
        }
        if (SUMMONERS.contains(classId)) {
            return Role.SUMMONER;
        }
        if (TANKS.contains(classId)) {
            return Role.TANK;
        }
        if (mage) {
            return Role.MAGE;
        }
        return Role.MELEE;
    }

    /**
     * Distance the bot wants to fight from. Archers/mages close from the stadium spawn into this range instead of
     * sniping from the teleport point ({@code attack()} would shoot as soon as the spawn is inside max bow range).
     */
    public static int fightRange(Role role, int attackRange, int castRange) {
        if (role == null) {
            role = Role.MELEE;
        }
        return switch (role) {
            case TANK, MELEE -> 80;
            case ARCHER -> {
                final int r = attackRange > 0 ? attackRange : 500;
                yield Math.max(220, Math.min(r - 80, 400));
            }
            case MAGE, SUMMONER -> {
                final int r = castRange > 0 ? castRange : 600;
                yield Math.max(280, Math.min(r - 80, 520));
            }
        };
    }

    /**
     * Ranged roles run in when farther than {@code fightRange} (with 40 hysteresis). Melee walks in via stock
     * {@code attack()} and does not need this.
     */
    public static boolean shouldCloseIn(Role role, double distance, int fightRange) {
        if ((role != Role.ARCHER) && (role != Role.MAGE) && (role != Role.SUMMONER)) {
            return false;
        }
        return distance > (fightRange + 40);
    }

    /** Point {@code fightRange} away from the rival, along the line from {@code me}. */
    public static Pos closeInPoint(Pos me, Pos foe, int fightRange) {
        if ((me == null) || (foe == null)) {
            return me;
        }
        final double d = me.distance2d(foe);
        if (d <= 1) {
            return me;
        }
        final double step = d - Math.max(40, fightRange);
        if (step <= 0) {
            return me;
        }
        return me.toward(foe, step);
    }
}
