package org.l2start.bots.brain.basic;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.l2start.bots.api.PartyMember;
import org.l2start.bots.api.Pos;

/**
 * RB party, iteration 1 (docs/RB_PARTY_PLAN.md): the pure rules of a bot that answered a live player's call for a raid
 * boss. Party chat words, when the fight starts, where the tank holds the boss, how long the party waits. Unit-tested.
 */
public final class RaidPlay {
    /** The invitation is waited for this long, then the bot leaves. */
    public static final long INVITE_WAIT_MS = 3 * 60_000L;
    /** The leader does nothing this long (no move, no fight): the bots say goodbye and leave. */
    public static final long IDLE_MS = 10 * 60_000L;
    /** A bot this close to its leader when he teleports by a gatekeeper takes the same gatekeeper. */
    public static final int FOLLOW_GK = 1500;
    /** The leader stood this close to a gatekeeper before he vanished: he took it (else: a scroll or the return skill). */
    public static final int LEADER_AT_GK = 600;
    /** A jump longer than this between two ticks is a teleport. */
    public static final int JUMP = 3000;
    /** Without a monster on the party this long the fight is over (back to following). */
    public static final long QUIET_MS = 15_000L;
    /** The others wait at most this long for the tank to take the boss. */
    public static final long TANK_FIRST_MS = 4000L;

    private static final int RX = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern GO = Pattern.compile("(?:^|\\s)(?:го+|гоу|go+|погнали|поехали|начали|начинаем|атакуем|атака|пул(?:им|ю)?|pull|бьем|fight|attack)(?:$|\\s)", RX);

    private RaidPlay() {
    }

    /** "го" (go, погнали, пулим ...) in the party chat: the fight starts. "го за мной" is following, not fighting. */
    public static boolean isGo(String text) {
        if (text == null) {
            return false;
        }
        final String t = norm(text);
        if (PartyCommands.parse(text) == PartyCommands.Command.FOLLOW || PartyCommands.parse(text) == PartyCommands.Command.STOP) {
            return false;
        }
        return GO.matcher(t).find();
    }

    /** Any party line with "баф" (бафни, баф пж, го баф) or buff: the prophet buffs the whole party. */
    public static boolean wantsBuff(String text) {
        if (text == null) {
            return false;
        }
        final String t = norm(text);
        return t.contains("баф") || t.contains("buff") || Pattern.compile("(?:^|\\s)(?:бф|bf)(?:$|\\s)", RX).matcher(t).find();
    }

    /** Gatekeeper follow delay: like people clicking one after another, 2-10 s. */
    public static long gkDelay(java.util.Random rnd) {
        return 2000 + rnd.nextInt(8001);
    }

    /** Centre of the other party members (and the leader) that are alive and near. Null = nobody. */
    public static Pos center(List<PartyMember> party, int range) {
        long x = 0;
        long y = 0;
        long z = 0;
        int n = 0;
        for (PartyMember m : party) {
            if (!m.dead() && (m.distance() <= range)) {
                x += m.pos().x();
                y += m.pos().y();
                z += m.pos().z();
                n++;
            }
        }
        return n == 0 ? null : new Pos((int) (x / n), (int) (y / n), (int) (z / n));
    }

    /**
     * The tank holds the boss with its back to the party: the tank stands on the far side of the boss. True when the
     * angle party-boss-tank is under 120 degrees (the boss would face, cleave or breathe onto the party).
     */
    public static boolean bossFacesParty(Pos boss, Pos tank, Pos partyCenter) {
        if ((boss == null) || (tank == null) || (partyCenter == null)) {
            return false;
        }
        final double ax = tank.x() - boss.x();
        final double ay = tank.y() - boss.y();
        final double bx = partyCenter.x() - boss.x();
        final double by = partyCenter.y() - boss.y();
        final double la = Math.hypot(ax, ay);
        final double lb = Math.hypot(bx, by);
        if ((la < 1) || (lb < 60)) {
            return false; // on top of it, or the party stands in the boss: nothing to turn
        }
        return ((ax * bx) + (ay * by)) / (la * lb) > -0.5;
    }

    /** Where the tank pulls the boss to: {@code dist} beyond the boss, straight away from the party. */
    public static Pos tankSpot(Pos boss, Pos partyCenter, int dist) {
        final double bx = boss.x() - partyCenter.x();
        final double by = boss.y() - partyCenter.y();
        final double l = Math.max(1, Math.hypot(bx, by));
        return new Pos((int) Math.round(boss.x() + ((bx / l) * dist)), (int) Math.round(boss.y() + ((by / l) * dist)), boss.z());
    }

    /** The boss is fought: it is hurt, it hits someone, or it targets one of the party. */
    public static boolean bossPulled(double hpPct, boolean attackingNow, int bossTargetId, int leaderId, List<PartyMember> party, int selfId) {
        if ((hpPct < 100) || attackingNow || (bossTargetId == leaderId) || (bossTargetId == selfId)) {
            return true;
        }
        for (PartyMember m : party) {
            if (m.objectId() == bossTargetId) {
                return true;
            }
        }
        return false;
    }

    private static String norm(String text) {
        return " " + text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[!?.,:;)(]+", " ").replaceAll("\\s+", " ").trim() + " ";
    }
}
