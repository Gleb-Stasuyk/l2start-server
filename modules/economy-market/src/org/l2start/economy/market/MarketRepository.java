package org.l2start.economy.market;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.l2start.economy.market.model.IndexEntry;
import org.l2start.economy.market.model.MarketBook;
import org.l2start.platform.api.ModuleDatabase;
import org.l2start.platform.api.StoreDeal;

/** The module's tables (mod_economy_market_*) and the facts about real characters the stage needs. */
final class MarketRepository {
    private final ModuleDatabase db;
    private final String index;
    private final String log;

    MarketRepository(ModuleDatabase db) {
        this.db = db;
        index = db.table("price_index");
        log = db.table("trade_log");
    }

    void logDeal(StoreDeal d, long at) throws SQLException {
        db.update("INSERT INTO " + log + " (at, seller_id, buyer_id, item_id, count, price, buy_store, seller_bot, buyer_bot) VALUES (?,?,?,?,?,?,?,?,?)", at, d.sellerId(), d.buyerId(), d.itemId(),
            d.count(), d.price(), d.buyStore() ? 1 : 0, d.sellerBot() ? 1 : 0, d.buyerBot() ? 1 : 0);
    }

    int rotate(long before) throws SQLException {
        return db.update("DELETE FROM " + log + " WHERE at < ?", before);
    }

    void saveIndex(Map<Integer, double[]> rows) throws SQLException {
        for (Map.Entry<Integer, double[]> e : rows.entrySet()) {
            db.update("INSERT INTO " + index + " (item_id, ema, deals) VALUES (?,?,?) ON DUPLICATE KEY UPDATE ema = VALUES(ema), deals = VALUES(deals)", e.getKey(), e.getValue()[0],
                (long) e.getValue()[1]);
        }
    }

    /** Index rows plus the times of the real deals of the last two weeks (store owner not a bot). */
    int loadIndex(MarketBook book, long now) throws SQLException {
        final Map<Integer, List<Long>> recent = new HashMap<>();
        db.query("SELECT item_id, at FROM " + log + " WHERE at >= ? AND ((buy_store = 0 AND seller_bot = 0) OR (buy_store = 1 AND buyer_bot = 0)) ORDER BY at", rs -> {
            recent.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>()).add(rs.getLong(2));
            return null;
        }, now - IndexEntry.WINDOW_MS);
        final List<Object> rows = db.query("SELECT item_id, ema, deals FROM " + index, rs -> {
            final int id = rs.getInt(1);
            book.restore(id, rs.getDouble(2), rs.getLong(3), recent.getOrDefault(id, List.of()).stream().mapToLong(Long::longValue).toArray());
            return id;
        });
        return rows.size();
    }

    long dealsSince(long since) throws SQLException {
        return db.queryOne("SELECT COUNT(*) FROM " + log + " WHERE at >= ?", rs -> rs.getLong(1), since).orElse(0L);
    }

    /** Real characters (not on bot accounts of bots-core, when its table exists): max level and total hours played. */
    double[] realPlayers() throws SQLException {
        final String exclude = botAccounts() ? " WHERE account_name NOT IN (SELECT account_name FROM mod_bots_core_account)" : "";
        final Optional<double[]> r = db.queryOne("SELECT COALESCE(MAX(level), 1), COALESCE(SUM(onlinetime), 0) FROM characters" + exclude, rs -> new double[] { rs.getInt(1), rs.getLong(2) / 3600.0 });
        return r.orElse(new double[] { 1, 0 });
    }

    /** Online: days since the first real character and the average level of the top 10. */
    double[] serverAge(long now) throws SQLException {
        final String exclude = botAccounts() ? " WHERE account_name NOT IN (SELECT account_name FROM mod_bots_core_account)" : "";
        final double top = db.queryOne("SELECT COALESCE(AVG(level), 1) FROM (SELECT level FROM characters" + exclude + " ORDER BY level DESC LIMIT 10) t", rs -> rs.getDouble(1)).orElse(1.0);
        final long first = db.queryOne("SELECT COALESCE(MIN(createDate), CURRENT_DATE) FROM characters" + exclude, rs -> rs.getDate(1).getTime()).orElse(now);
        return new double[] { Math.max(0, (now - first) / 86_400_000.0), top };
    }

    private boolean botAccounts() throws SQLException {
        return db.queryOne("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'mod_bots_core_account'", rs -> rs.getInt(1)).orElse(0) > 0;
    }

    // ---- traders (5b/5c)

    /** A bot store on paper. */
    record StoreRow(int botId, String town, String spot, String kind, String profile, String title, long openedAt, long touchedAt, int linesStart) {
    }

    Map<String, StoreRow> stores(String town) throws SQLException {
        final Map<String, StoreRow> out = new HashMap<>();
        db.query("SELECT bot_id, town, spot, kind, profile, title, opened_at, touched_at, lines_start FROM " + db.table("store") + " WHERE town = ?", rs -> {
            final StoreRow r = new StoreRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getInt(9));
            out.put(r.spot(), r);
            return null;
        }, town);
        return out;
    }

    void dropStore(int botId) throws SQLException {
        db.update("DELETE FROM " + db.table("store_item") + " WHERE bot_id = ?", botId);
        db.update("DELETE FROM " + db.table("store") + " WHERE bot_id = ?", botId);
    }

    List<org.l2start.economy.market.model.Assortment.Line> lines(int botId, org.l2start.economy.market.model.MarketItems items) throws SQLException {
        return db.query("SELECT item_id, count, price FROM " + db.table("store_item") + " WHERE bot_id = ? ORDER BY item_id", rs -> {
            final org.l2start.economy.market.model.MarketItem it = items.get(rs.getInt(1));
            return new org.l2start.economy.market.model.Assortment.Line(rs.getInt(1), (it == null) ? ("#" + rs.getInt(1)) : it.name(), rs.getLong(2), rs.getLong(3));
        }, botId);
    }

    /** Saves the store and replaces its lines. */
    void saveStore(StoreRow r, List<org.l2start.economy.market.model.Assortment.Line> lines) throws SQLException {
        db.update("INSERT INTO " + db.table("store") + " (bot_id, town, spot, kind, profile, title, opened_at, touched_at, lines_start) VALUES (?,?,?,?,?,?,?,?,?) "
            + "ON DUPLICATE KEY UPDATE town = VALUES(town), spot = VALUES(spot), kind = VALUES(kind), profile = VALUES(profile), title = VALUES(title), opened_at = VALUES(opened_at), touched_at = VALUES(touched_at), lines_start = VALUES(lines_start)",
            r.botId(), r.town(), r.spot(), r.kind(), r.profile(), r.title(), r.openedAt(), r.touchedAt(), r.linesStart());
        db.update("DELETE FROM " + db.table("store_item") + " WHERE bot_id = ?", r.botId());
        for (org.l2start.economy.market.model.Assortment.Line l : lines) {
            db.update("INSERT INTO " + db.table("store_item") + " (bot_id, item_id, count, price) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE count = count + VALUES(count), price = VALUES(price)", r.botId(), l.itemId(), l.count(), l.price());
        }
    }

    long wallet(int botId) throws SQLException {
        return db.queryOne("SELECT adena FROM " + db.table("wallet") + " WHERE bot_id = ?", rs -> rs.getLong(1), botId).orElse(-1L);
    }

    void setWallet(int botId, long adena) throws SQLException {
        db.update("INSERT INTO " + db.table("wallet") + " (bot_id, adena) VALUES (?,?) ON DUPLICATE KEY UPDATE adena = VALUES(adena)", botId, Math.max(0, adena));
    }

    Map<Integer, Long> resale() throws SQLException {
        final Map<Integer, Long> out = new HashMap<>();
        db.query("SELECT item_id, count FROM " + db.table("resale") + " WHERE count > 0", rs -> out.put(rs.getInt(1), rs.getLong(2)));
        return out;
    }

    void addResale(int itemId, long delta) throws SQLException {
        db.update("INSERT INTO " + db.table("resale") + " (item_id, count) VALUES (?, GREATEST(0, ?)) ON DUPLICATE KEY UPDATE count = GREATEST(0, count + ?)", itemId, delta, delta);
    }

    void addPlayerDay(int charId, int itemId, int day, long count) throws SQLException {
        db.update("INSERT INTO " + db.table("player_day") + " (char_id, item_id, day, count) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE count = count + VALUES(count)", charId, itemId, day, count);
    }

    /** The most pieces of the item one real character sold to bots on that day. */
    long playerDayMax(int itemId, int day) throws SQLException {
        return db.queryOne("SELECT COALESCE(MAX(count), 0) FROM " + db.table("player_day") + " WHERE item_id = ? AND day = ?", rs -> rs.getLong(1), itemId, day).orElse(0L);
    }

    int rotatePlayerDays(int beforeDay) throws SQLException {
        return db.update("DELETE FROM " + db.table("player_day") + " WHERE day < ?", beforeDay);
    }

    /**
     * Stock offline-trade rows of bot characters (the stock code writes them for a store owner without a client after
     * every deal): a bot store must never come back as a stock offline trader. @return rows removed
     */
    int clearBotOfflineTrade(String charIds) throws SQLException {
        if (!botAccounts()) {
            return 0;
        }
        final String who = (charIds != null) ? charIds : "SELECT char_id FROM mod_bots_core_identity";
        int n = db.update("DELETE FROM character_offline_trade_items WHERE charId IN (" + who + ")");
        n += db.update("DELETE FROM character_offline_trade WHERE charId IN (" + who + ")");
        return n;
    }

    /** Bag items (loc INVENTORY) of trader characters left by a crash: goods exist only while a trader stands. */
    int clearTraderBags() throws SQLException {
        if (!botAccounts()) {
            return 0;
        }
        return db.update("DELETE FROM items WHERE loc = 'INVENTORY' AND owner_id IN (SELECT char_id FROM mod_bots_core_identity WHERE status = 'TRADER')");
    }

    long seed() throws SQLException {
        return db.queryOne("SELECT COALESCE(MIN(charId), 1) FROM characters", rs -> rs.getLong(1)).orElse(1L);
    }
}
