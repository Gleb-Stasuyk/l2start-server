package org.l2start.platform.api;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Connections come from the stock pool. Module tables are named mod_&lt;id&gt;_&lt;name&gt; (see {@link #table(String)}). */
public interface ModuleDatabase {
    Connection connection() throws SQLException;

    /** table("counter") for module "hello" = "mod_hello_counter". */
    String table(String name);

    int update(String sql, Object... params) throws SQLException;

    <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws SQLException;

    <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) throws SQLException;

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}
