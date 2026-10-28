package org.l2start.bots.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * All known bots, by character id (= Player object id) and by name. {@link #isBot(Player)} backs the platform
 * bot presence used by hooks P3-BOT-*: one hash lookup, safe from any thread.
 */
public final class BotRegistry {
    private final Map<Integer, BotRecord> byChar = new ConcurrentHashMap<>();
    private final Map<String, BotRecord> byName = new ConcurrentHashMap<>();

    public void add(BotRecord r) {
        byChar.put(r.charId(), r);
        byName.put(key(r.name()), r);
    }

    public void remove(BotRecord r) {
        byChar.remove(r.charId(), r);
        byName.remove(key(r.name()), r);
    }

    /** Update name index after a rename (identity already updated on the record). */
    public void rename(BotRecord r, String oldName, String newName) {
        if ((r == null) || (newName == null)) {
            return;
        }
        if (oldName != null) {
            byName.remove(key(oldName), r);
        }
        r.setName(newName);
        byName.put(key(newName), r);
    }

    public BotRecord byBotId(int botId) {
        for (BotRecord r : byChar.values()) {
            if (r.botId() == botId) {
                return r;
            }
        }
        return null;
    }

    public boolean isBot(Player player) {
        return (player != null) && byChar.containsKey(player.getObjectId());
    }

    public BotRecord byCharId(int charId) {
        return byChar.get(charId);
    }

    public BotRecord byName(String name) {
        return name == null ? null : byName.get(key(name));
    }

    public Collection<BotRecord> all() {
        return byChar.values();
    }

    public List<BotRecord> sorted() {
        final List<BotRecord> l = new ArrayList<>(byChar.values());
        l.sort(Comparator.comparingInt(BotRecord::botId));
        return l;
    }

    public int size() {
        return byChar.size();
    }

    public int inWorld() {
        int n = 0;
        for (BotRecord r : byChar.values()) {
            if (r.inWorld()) {
                n++;
            }
        }
        return n;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
