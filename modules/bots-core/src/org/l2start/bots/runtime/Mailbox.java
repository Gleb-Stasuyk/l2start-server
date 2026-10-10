package org.l2start.bots.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.l2start.bots.api.BotEvent;

/**
 * Events for one bot: many producers (stock game threads), one consumer (the bot's shard). Bounded: when full the
 * oldest event is dropped. Events with the same {@link BotEvent#key()} collapse into the newest one (Attacked by the
 * same attacker: damage is summed).
 */
public final class Mailbox {
    public static final int DEFAULT_LIMIT = 64;

    private final int limit;
    private final ArrayDeque<BotEvent> queue = new ArrayDeque<>();
    private long dropped;
    private long collapsed;

    public Mailbox() {
        this(DEFAULT_LIMIT);
    }

    public Mailbox(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit " + limit);
        }
        this.limit = limit;
    }

    public synchronized void offer(BotEvent event) {
        final String key = event.key();
        if (key != null) {
            for (Iterator<BotEvent> it = queue.iterator(); it.hasNext();) {
                final BotEvent old = it.next();
                if (key.equals(old.key())) {
                    it.remove();
                    collapsed++;
                    if ((old instanceof BotEvent.Attacked a) && (event instanceof BotEvent.Attacked b)) {
                        event = new BotEvent.Attacked(b.attackerId(), b.byPlayer(), a.damage() + b.damage());
                    }
                    break;
                }
            }
        }
        while (queue.size() >= limit) {
            queue.pollFirst();
            dropped++;
        }
        queue.addLast(event);
    }

    /** Everything queued, oldest first; the mailbox is empty afterwards. */
    public synchronized List<BotEvent> drain() {
        if (queue.isEmpty()) {
            return List.of();
        }
        final List<BotEvent> l = new ArrayList<>(queue);
        queue.clear();
        return l;
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized long collapsed() {
        return collapsed;
    }
}
