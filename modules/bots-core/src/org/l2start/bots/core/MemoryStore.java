package org.l2start.bots.core;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2start.bots.api.Memory;
import org.l2start.platform.api.ModuleDatabase;

/**
 * Phase 6a: bot memories of real players in mod_bots_core_memory. Loaded per bot when its brain starts, kept in
 * memory, changed rows written in a batch once a minute and when the bot leaves.
 */
final class MemoryStore {
    /** The memory of one bot: changes are marked dirty for the next flush. */
    final class BotMemory extends Memory.MapMemory {
        final int botId;
        final Set<Integer> dirty = ConcurrentHashMap.newKeySet();

        BotMemory(int botId) {
            this.botId = botId;
        }

        @Override
        protected void put(Memory.Of o) {
            super.put(o);
            dirty.add(o.charId());
        }
    }

    private final ModuleDatabase db;
    private final String table;
    private final Map<Integer, BotMemory> bots = new ConcurrentHashMap<>();

    MemoryStore(ModuleDatabase db) {
        this.db = db;
        this.table = db == null ? null : db.table("memory");
    }

    /** The memory of a bot (loaded from the database on first use; off the shard is better, but it is one small query). */
    BotMemory of(int botId) {
        return bots.computeIfAbsent(botId, id -> {
            final BotMemory m = new BotMemory(id);
            if (db != null) {
                try {
                    for (Memory.Of o : db.query("SELECT char_id, affinity, met, party_minutes, last_seen, tags FROM " + table + " WHERE bot_id = ?",
                        rs -> new Memory.Of(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getLong(5), tags(rs.getString(6))), id)) {
                        m.load(o);
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("bot memory of " + id + ": " + e.getMessage(), e);
                }
            }
            return m;
        });
    }

    /** Write what changed (all bots); returns rows written. */
    int flush() throws SQLException {
        int n = 0;
        for (BotMemory m : bots.values()) {
            n += flush(m);
        }
        return n;
    }

    /** Write a bot's changes and forget it (it left the world). */
    int release(int botId) throws SQLException {
        final BotMemory m = bots.remove(botId);
        return m == null ? 0 : flush(m);
    }

    private int flush(BotMemory m) throws SQLException {
        if ((db == null) || m.dirty.isEmpty()) {
            return 0;
        }
        final List<Integer> ids = new ArrayList<>(m.dirty);
        m.dirty.removeAll(ids);
        for (int id : ids) {
            final Memory.Of o = m.of(id);
            db.update("INSERT INTO " + table + " (bot_id, char_id, affinity, met, party_minutes, last_seen, tags) VALUES (?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE affinity = VALUES(affinity), met = VALUES(met), party_minutes = VALUES(party_minutes), last_seen = VALUES(last_seen), tags = VALUES(tags)",
                m.botId, id, o.affinity(), o.met(), o.partyMinutes(), o.lastSeen(), tagText(o.tags()));
        }
        return ids.size();
    }

    static Set<Memory.Tag> tags(String s) {
        final EnumSet<Memory.Tag> t = EnumSet.noneOf(Memory.Tag.class);
        if (s != null) {
            for (String p : s.split(",")) {
                if (!p.isBlank()) {
                    try {
                        t.add(Memory.Tag.valueOf(p.trim()));
                    } catch (IllegalArgumentException e) {
                        // a tag of a newer version: dropped
                    }
                }
            }
        }
        return Set.copyOf(t);
    }

    static String tagText(Set<Memory.Tag> tags) {
        final StringBuilder sb = new StringBuilder();
        for (Memory.Tag t : Memory.Tag.values()) {
            if (tags.contains(t)) {
                sb.append(sb.length() == 0 ? "" : ",").append(t.name());
            }
        }
        return sb.toString();
    }

    /** For //bots info: what this bot remembers (most liked first, at most 5). */
    List<Memory.Of> summary(int botId) {
        final BotMemory m = bots.get(botId);
        if (m == null) {
            return List.of();
        }
        final List<Memory.Of> l = new ArrayList<>(m.all());
        l.sort((a, b) -> Integer.compare(Math.abs(b.affinity()) + b.partyMinutes(), Math.abs(a.affinity()) + a.partyMinutes()));
        return l.size() > 5 ? l.subList(0, 5) : l;
    }
}
