package org.l2start.bots.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7b/7c: hourly PvP counters for {@code //bots pvp} (attacks, defence, revenge, flees, duels, PK kills/deaths).
 */
public final class PvpBoard {
    public enum Stat {
        ATTACK, DEFEND, REVENGE, FLEE, CALL_HELP, DUEL_OK, DUEL_NO, LIMIT, ZONE, PK_KILL, PK_DEATH, PK_HUNT
    }

    public static final PvpBoard NONE = new PvpBoard();

    private final EnumMap<Stat, ArrayDeque<Long>> stats = new EnumMap<>(Stat.class);
    /** botId → kill timestamps (last hour). */
    private final Map<Integer, ArrayDeque<Long>> pkKills = new HashMap<>();
    private final Map<Integer, ArrayDeque<Long>> pkDeaths = new HashMap<>();

    public PvpBoard() {
        for (Stat s : Stat.values()) {
            stats.put(s, new ArrayDeque<>());
        }
    }

    public synchronized void note(Stat s, long now) {
        final ArrayDeque<Long> q = stats.get(s);
        q.addLast(now);
        purge(q, now);
    }

    public synchronized void notePkKill(int botId, long now) {
        note(Stat.PK_KILL, now);
        pkKills.computeIfAbsent(botId, k -> new ArrayDeque<>()).addLast(now);
        purge(pkKills.get(botId), now);
    }

    public synchronized void notePkDeath(int botId, long now) {
        note(Stat.PK_DEATH, now);
        pkDeaths.computeIfAbsent(botId, k -> new ArrayDeque<>()).addLast(now);
        purge(pkDeaths.get(botId), now);
    }

    public synchronized Map<Stat, Integer> hourCounts(long now) {
        final Map<Stat, Integer> out = new EnumMap<>(Stat.class);
        for (Stat s : Stat.values()) {
            purge(stats.get(s), now);
            out.put(s, stats.get(s).size());
        }
        return out;
    }

    /** Per-bot PK kills/deaths in the last hour (for {@code //bots pvp}). */
    public synchronized int pkKillsHour(int botId, long now) {
        final ArrayDeque<Long> q = pkKills.get(botId);
        if (q == null) {
            return 0;
        }
        purge(q, now);
        return q.size();
    }

    public synchronized int pkDeathsHour(int botId, long now) {
        final ArrayDeque<Long> q = pkDeaths.get(botId);
        if (q == null) {
            return 0;
        }
        purge(q, now);
        return q.size();
    }

    public record PkRow(int botId, String name, int karma, int killsHour, int deathsHour) {
    }

    private static void purge(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && (now - q.peekFirst()) > 3_600_000L) {
            q.removeFirst();
        }
    }
}
