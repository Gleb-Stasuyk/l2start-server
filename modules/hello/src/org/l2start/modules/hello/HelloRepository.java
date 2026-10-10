package org.l2start.modules.hello;

import java.sql.SQLException;
import java.util.Optional;

import org.l2start.platform.api.ModuleDatabase;

/** mod_hello_counter access. */
final class HelloRepository {
    private final ModuleDatabase db;
    private final String table;

    HelloRepository(ModuleDatabase db) {
        this.db = db;
        this.table = db.table("counter");
    }

    Optional<Greeting.Counter> load(int charId) throws SQLException {
        return db.queryOne("SELECT day, count, total FROM " + table + " WHERE char_id = ?", rs -> new Greeting.Counter(rs.getInt(1), rs.getInt(2), rs.getInt(3)), charId);
    }

    void save(int charId, Greeting.Counter c) throws SQLException {
        db.update("INSERT INTO " + table + " (char_id, day, count, total) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE day = VALUES(day), count = VALUES(count), total = VALUES(total)", charId, c.day(), c.count(), c.total());
    }

    /** {players, total uses} */
    long[] stats() throws SQLException {
        return db.queryOne("SELECT COUNT(*), COALESCE(SUM(total), 0) FROM " + table, rs -> new long[] { rs.getLong(1), rs.getLong(2) }).orElse(new long[] { 0, 0 });
    }
}
