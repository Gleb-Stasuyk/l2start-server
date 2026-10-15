package org.l2start.bots.core;

import java.util.HashSet;
import java.util.Set;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2start.platform.api.ModuleDatabase;

/**
 * Phase 7c: tracks real gear object ids issued to a PK bot. Stock death drop removes them from the inventory; the same
 * object id is never recreated. Rematerialize may issue a <em>new</em> object id of the same itemId (refill).
 */
public final class PkLedger {
    private final ModuleDatabase db;
    private final String table;

    public PkLedger(ModuleDatabase db) {
        this.db = db;
        this.table = db.table("pk_ledger");
    }

    /** Forget object ids that are no longer in the bag (dropped / destroyed). */
    public void sync(int botId, Player p) {
        final Set<Integer> have = objectIdsInBag(p);
        final Set<Integer> known = load(botId);
        for (Integer oid : known) {
            if (!have.contains(oid)) {
                forget(botId, oid);
            }
        }
        for (Item it : p.getInventory().getItems()) {
            if ((it.isWeapon() || it.isArmor()) && !known.contains(it.getObjectId())) {
                issued(botId, it.getObjectId(), it.getId());
            }
        }
    }

    public void issued(int botId, int objectId, int itemId) {
        try {
            db.update("INSERT INTO " + table + " (bot_id, object_id, item_id, issued_at) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE item_id=VALUES(item_id)",
                botId, objectId, itemId, System.currentTimeMillis());
        } catch (Exception e) {
            throw new IllegalStateException("pk ledger issued bot=" + botId + " oid=" + objectId, e);
        }
    }

    public void forget(int botId, int objectId) {
        try {
            db.update("DELETE FROM " + table + " WHERE bot_id=? AND object_id=?", botId, objectId);
        } catch (Exception e) {
            throw new IllegalStateException("pk ledger forget bot=" + botId + " oid=" + objectId, e);
        }
    }

    public boolean hasObject(int botId, int objectId) {
        return load(botId).contains(objectId);
    }

    public Set<Integer> load(int botId) {
        try {
            return new HashSet<>(db.query("SELECT object_id FROM " + table + " WHERE bot_id=?", rs -> rs.getInt(1), botId));
        } catch (Exception e) {
            throw new IllegalStateException("pk ledger load bot=" + botId, e);
        }
    }

    private static Set<Integer> objectIdsInBag(Player p) {
        final Set<Integer> have = new HashSet<>();
        for (Item it : p.getInventory().getItems()) {
            have.add(it.getObjectId());
        }
        return have;
    }

    /** In-memory ledger for unit tests (no DB). */
    public static final class Memory {
        private final java.util.Map<Integer, java.util.Map<Integer, Integer>> byBot = new java.util.HashMap<>();

        public synchronized void issued(int botId, int objectId, int itemId) {
            byBot.computeIfAbsent(botId, k -> new java.util.HashMap<>()).put(objectId, itemId);
        }

        public synchronized void forget(int botId, int objectId) {
            final java.util.Map<Integer, Integer> m = byBot.get(botId);
            if (m != null) {
                m.remove(objectId);
            }
        }

        public synchronized boolean hasObject(int botId, int objectId) {
            final java.util.Map<Integer, Integer> m = byBot.get(botId);
            return (m != null) && m.containsKey(objectId);
        }

        public synchronized Set<Integer> objectIds(int botId) {
            final java.util.Map<Integer, Integer> m = byBot.get(botId);
            return m == null ? Set.of() : Set.copyOf(m.keySet());
        }
    }
}
