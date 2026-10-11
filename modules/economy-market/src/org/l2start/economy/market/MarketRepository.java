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

    long seed() throws SQLException {
        return db.queryOne("SELECT COALESCE(MIN(charId), 1) FROM characters", rs -> rs.getLong(1)).orElse(1L);
    }
}
