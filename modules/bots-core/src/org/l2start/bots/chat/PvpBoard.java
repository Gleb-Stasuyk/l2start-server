package org.l2start.bots.chat;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;

/**
 * Phase 7b: hourly PvP counters for {@code //bots pvp} (attacks, defence, revenge, flees, duels, limit hits).
 */
public final class PvpBoard {
    public enum Stat {
        ATTACK, DEFEND, REVENGE, FLEE, CALL_HELP, DUEL_OK, DUEL_NO, LIMIT, ZONE
    }

    public static final PvpBoard NONE = new PvpBoard();

    private final EnumMap<Stat, ArrayDeque<Long>> stats = new EnumMap<>(Stat.class);

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

    public synchronized Map<Stat, Integer> hourCounts(long now) {
        final Map<Stat, Integer> out = new EnumMap<>(Stat.class);
        for (Stat s : Stat.values()) {
            purge(stats.get(s), now);
            out.put(s, stats.get(s).size());
        }
        return out;
    }

    private static void purge(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && (now - q.peekFirst()) > 3_600_000L) {
            q.removeFirst();
        }
    }
}
