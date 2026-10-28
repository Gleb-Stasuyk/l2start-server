package org.l2start.bots.brain.basic;

import java.util.List;

import org.l2start.bots.api.NpcView;
import org.l2start.bots.api.PlayerView;
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
}
