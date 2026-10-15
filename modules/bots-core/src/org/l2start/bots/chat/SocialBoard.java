package org.l2start.bots.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Phase 6d/6g: world-wide limits for LFP/LFM shouts, LFP replies, per-bot whisper replies, the 3-minute "we agreed to
 * party" window, and hourly social counters for {@code //bots social}.
 */
public final class SocialBoard {
    /** Default: at most this many bot LFP/LFM lines per hour in the whole world. */
    public static final int DEFAULT_SHOUTS_PER_HOUR = 4;
    /** Default whisper answers one bot may send per hour (all players). */
    public static final int DEFAULT_WHISPERS_PER_HOUR = 30;
    /** How long a party agreement lasts after a whisper about party (ms). */
    public static final long AGREED_MS = 3 * 60_000L;
    /** One bot does not shout LFP more often than this (ms). */
    public static final long BOT_SHOUT_COOLDOWN_MS = 15 * 60_000L;
    /** Phase 6g: no whisper answers for this long after «отстань» / stop spam. */
    public static final long ANNOYED_WHISPER_MS = 24L * 60 * 60 * 1000;

    /** Hourly counters for {@code //bots social}. */
    public enum Stat {
        SHOUT, LFP_REPLY, WHISPER, INVITE_OK, INVITE_NO, EMOTE, LIMIT
    }

    /** No shouts, no replies, no agreements (tests / host without a board). */
    public static final SocialBoard NONE = new SocialBoard(0);

    private int shoutsPerHour;
    private int whispersPerHour = DEFAULT_WHISPERS_PER_HOUR;
    private int shoutsThisHour;
    private long hourStart;
    private final Map<Long, Long> agreed = new HashMap<>(); // (playerId<<32)|botId -> until
    private final Map<Integer, ReplyWindow> replies = new HashMap<>();
    private final Map<Integer, WhisperWindow> whispers = new HashMap<>(); // botId -> window
    private final EnumMap<Stat, ArrayDeque<Long>> stats = new EnumMap<>(Stat.class);

    public SocialBoard(int shoutsPerHour) {
        this.shoutsPerHour = Math.max(0, shoutsPerHour);
        this.hourStart = 0;
        for (Stat s : Stat.values()) {
            stats.put(s, new ArrayDeque<>());
        }
    }

    public synchronized void setShoutsPerHour(int n) {
        shoutsPerHour = Math.max(0, n);
    }

    public synchronized void setWhispersPerHour(int n) {
        whispersPerHour = Math.max(0, n);
    }

    public synchronized int shoutsPerHour() {
        return shoutsPerHour;
    }

    public synchronized int whispersPerHour() {
        return whispersPerHour;
    }

    public synchronized int shoutsThisHour(long now) {
        rollHour(now);
        return shoutsThisHour;
    }

    /** Take one world LFP shout slot; false = limit reached (or shouts disabled). */
    public synchronized boolean tryShout(long now) {
        if (shoutsPerHour <= 0) {
            note(Stat.LIMIT, now);
            return false;
        }
        rollHour(now);
        if (shoutsThisHour >= shoutsPerHour) {
            note(Stat.LIMIT, now);
            return false;
        }
        shoutsThisHour++;
        note(Stat.SHOUT, now);
        return true;
    }

    /** Remember that this player and bot talked about party (invite will be accepted for {@link #AGREED_MS}). */
    public synchronized void noteAgreed(int playerId, int botId, long now) {
        purgeAgreed(now);
        agreed.put(key(playerId, botId), now + AGREED_MS);
    }

    public synchronized boolean isAgreed(int playerId, int botId, long now) {
        final Long until = agreed.get(key(playerId, botId));
        return until != null && until > now;
    }

    /**
     * Take one reply slot for this player's LFP shout (at most {@code maxReplies} bots). A new shout from the same
     * player within 60 s reuses the window; later = a fresh one.
     */
    public synchronized boolean tryReply(int playerId, long now, int maxReplies) {
        if (maxReplies <= 0) {
            note(Stat.LIMIT, now);
            return false;
        }
        purgeReplies(now);
        final ReplyWindow w = replies.computeIfAbsent(playerId, k -> new ReplyWindow(now));
        if (now - w.since > 60_000L) {
            w.since = now;
            w.count = 0;
        }
        if (w.count >= maxReplies) {
            note(Stat.LIMIT, now);
            return false;
        }
        w.count++;
        note(Stat.LFP_REPLY, now);
        return true;
    }

    /** Phase 6g: one bot's whisper-answer budget for the rolling hour. */
    public synchronized boolean tryWhisper(int botId, long now) {
        if (whispersPerHour <= 0) {
            note(Stat.LIMIT, now);
            return false;
        }
        purgeWhispers(now);
        final WhisperWindow w = whispers.computeIfAbsent(botId, k -> new WhisperWindow(now));
        if (now - w.since >= 3_600_000L) {
            w.since = now;
            w.count = 0;
        }
        if (w.count >= whispersPerHour) {
            note(Stat.LIMIT, now);
            return false;
        }
        w.count++;
        note(Stat.WHISPER, now);
        return true;
    }

    public synchronized void noteInviteOk(long now) {
        note(Stat.INVITE_OK, now);
    }

    public synchronized void noteInviteNo(long now) {
        note(Stat.INVITE_NO, now);
    }

    public synchronized void noteEmote(long now) {
        note(Stat.EMOTE, now);
    }

    /** Counts of each {@link Stat} in the last hour. */
    public synchronized Map<Stat, Integer> hourCounts(long now) {
        purgeStats(now);
        final Map<Stat, Integer> out = new EnumMap<>(Stat.class);
        for (Stat s : Stat.values()) {
            out.put(s, stats.get(s).size());
        }
        return out;
    }

    private void note(Stat s, long now) {
        final ArrayDeque<Long> q = stats.get(s);
        q.addLast(now);
        while (!q.isEmpty() && (now - q.peekFirst()) > 3_600_000L) {
            q.removeFirst();
        }
    }

    private void purgeStats(long now) {
        for (ArrayDeque<Long> q : stats.values()) {
            while (!q.isEmpty() && (now - q.peekFirst()) > 3_600_000L) {
                q.removeFirst();
            }
        }
    }

    private void rollHour(long now) {
        if (hourStart == 0 || now - hourStart >= 3_600_000L) {
            hourStart = now;
            shoutsThisHour = 0;
        }
    }

    private void purgeAgreed(long now) {
        final Iterator<Map.Entry<Long, Long>> it = agreed.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    private void purgeReplies(long now) {
        replies.entrySet().removeIf(e -> now - e.getValue().since > 120_000L);
    }

    private void purgeWhispers(long now) {
        whispers.entrySet().removeIf(e -> now - e.getValue().since > 3_600_000L * 2);
    }

    private static long key(int playerId, int botId) {
        return (((long) playerId) << 32) | (botId & 0xffff_ffffL);
    }

    private static final class ReplyWindow {
        long since;
        int count;

        ReplyWindow(long since) {
            this.since = since;
        }
    }

    private static final class WhisperWindow {
        long since;
        int count;

        WhisperWindow(long since) {
            this.since = since;
        }
    }
}
