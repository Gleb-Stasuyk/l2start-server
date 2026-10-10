package org.l2start.bots.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The last N actions/decisions of a bot, for //bots info. */
public final class ActionLog {
    public record Entry(long at, String text) {
    }

    private final int size;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public ActionLog(int size) {
        this.size = size;
    }

    public synchronized void add(long at, String text) {
        if (entries.size() >= size) {
            entries.pollFirst();
        }
        entries.addLast(new Entry(at, text));
    }

    /** Newest first. */
    public synchronized List<Entry> entries() {
        final List<Entry> l = new ArrayList<>(entries);
        java.util.Collections.reverse(l);
        return l;
    }
}
