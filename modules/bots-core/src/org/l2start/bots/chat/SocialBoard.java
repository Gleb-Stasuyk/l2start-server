package org.l2start.bots.chat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Phase 6d: world-wide limits for LFP/LFM shouts and replies, plus the 3-minute "we agreed to party" window
 * (Living Worlds notePartyAgreed). Shared by every bot; bots-core owns one instance.
 */
public final class SocialBoard {
    /** Default: at most this many bot LFP/LFM lines per hour in the whole world. */
    public static final int DEFAULT_SHOUTS_PER_HOUR = 4;
    /** How long a party agreement lasts after a whisper about party (ms). */
    public static final long AGREED_MS = 3 * 60_000L;
    /** One bot does not shout LFP more often than this (ms). */
    public static final long BOT_SHOUT_COOLDOWN_MS = 15 * 60_000L;

    /** No shouts, no replies, no agreements (tests / host without a board). */
    public static final SocialBoard NONE = new SocialBoard(0);

    private int shoutsPerHour;
    private int shoutsThisHour;
    private long hourStart;
    private final Map<Long, Long> agreed = new HashMap<>(); // (playerId<<32)|botId -> until
    private final Map<Integer, ReplyWindow> replies = new HashMap<>();

    public SocialBoard(int shoutsPerHour) {
        this.shoutsPerHour = Math.max(0, shoutsPerHour);
        this.hourStart = 0;
    }

    public synchronized void setShoutsPerHour(int n) {
        shoutsPerHour = Math.max(0, n);
    }

    public synchronized int shoutsPerHour() {
        return shoutsPerHour;
    }

    public synchronized int shoutsThisHour(long now) {
        rollHour(now);
        return shoutsThisHour;
    }

    /** Take one world LFP shout slot; false = limit reached (or shouts disabled). */
    public synchronized boolean tryShout(long now) {
        if (shoutsPerHour <= 0) {
            return false;
        }
        rollHour(now);
        if (shoutsThisHour >= shoutsPerHour) {
            return false;
        }
        shoutsThisHour++;
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
            return false;
        }
        purgeReplies(now);
        final ReplyWindow w = replies.computeIfAbsent(playerId, k -> new ReplyWindow(now));
        if (now - w.since > 60_000L) {
            w.since = now;
            w.count = 0;
        }
        if (w.count >= maxReplies) {
            return false;
        }
        w.count++;
        return true;
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
}
