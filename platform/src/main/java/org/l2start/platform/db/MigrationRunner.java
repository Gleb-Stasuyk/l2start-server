package org.l2start.platform.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.l2start.platform.util.Semver;

/** JDBC side of migrations (MariaDB). Creates the platform tables, applies pending module migrations under a lock. */
public final class MigrationRunner {
    private static final Logger LOGGER = Logger.getLogger("Platform");
    public static final String PLATFORM_ID = "platform";
    private static final String LOCK = "l2s_platform_migrations";
    private static final String[] PLATFORM_MIGRATIONS = { "V001__platform_modules.sql" };

    private static final String CREATE_MIGRATIONS = "CREATE TABLE IF NOT EXISTS platform_module_migrations ("
        + " module_id VARCHAR(32) NOT NULL, version INT NOT NULL, description VARCHAR(200) NOT NULL, script VARCHAR(255) NOT NULL,"
        + " checksum CHAR(64) NOT NULL, module_version VARCHAR(32) NOT NULL, applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
        + " execution_ms INT NOT NULL, success TINYINT(1) NOT NULL, error TEXT NULL, PRIMARY KEY (module_id, version)"
        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci";

    public interface ConnectionSource {
        Connection get() throws SQLException;
    }

    private final ConnectionSource connections;

    public MigrationRunner(ConnectionSource connections) {
        this.connections = connections;
    }

    /** Creates platform_module_migrations and applies the platform's own migrations (platform_modules table). */
    public void ensurePlatformTables(String platformVersion) throws Exception {
        try (Connection con = connections.get(); Statement st = con.createStatement()) {
            st.execute(CREATE_MIGRATIONS);
        }
        final List<Migration> own = new ArrayList<>();
        for (String name : PLATFORM_MIGRATIONS) {
            try (InputStream in = MigrationRunner.class.getResourceAsStream("/org/l2start/platform/sql/" + name)) {
                if (in == null) {
                    throw new IOException("missing platform resource " + name);
                }
                final java.util.regex.Matcher m = MigrationScanner.NAME.matcher(name);
                if (!m.matches()) {
                    throw new IOException("bad platform migration name " + name);
                }
                own.add(MigrationScanner.of(Integer.parseInt(m.group(1)), m.group(2).replace('_', ' '), name, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        final MigrationPlanner.Plan plan = MigrationPlanner.plan(own, applied(PLATFORM_ID));
        if (!plan.ok()) {
            throw new IllegalStateException(String.join("; ", plan.errors()));
        }
        apply(PLATFORM_ID, platformVersion, plan.pending());
    }

    public boolean platformTablesExist() {
        try (Connection con = connections.get(); Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'platform_modules'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public Map<Integer, AppliedMigration> applied(String moduleId) throws SQLException {
        final Map<Integer, AppliedMigration> out = new LinkedHashMap<>();
        try (Connection con = connections.get(); PreparedStatement ps = con.prepareStatement("SELECT version, script, checksum, success, error FROM platform_module_migrations WHERE module_id = ? ORDER BY version")) {
            ps.setString(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1), new AppliedMigration(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5)));
                }
            }
        }
        return out;
    }

    /** Applies the plan of one module. Returns number of applied migrations; throws with the failing script on error. */
    public int migrate(String moduleId, String moduleVersion, List<Migration> available) throws Exception {
        final MigrationPlanner.Plan plan = MigrationPlanner.plan(available, applied(moduleId));
        for (String w : plan.warnings()) {
            LOGGER.warning("Platform: " + moduleId + ": " + w);
        }
        if (!plan.ok()) {
            throw new IllegalStateException(String.join("; ", plan.errors()));
        }
        apply(moduleId, moduleVersion, plan.pending());
        return plan.pending().size();
    }

    private void apply(String moduleId, String moduleVersion, List<Migration> pending) throws Exception {
        if (pending.isEmpty()) {
            return;
        }
        try (Connection con = connections.get()) {
            lock(con);
            try {
                for (Migration m : pending) {
                    final long start = System.currentTimeMillis();
                    try (Statement st = con.createStatement()) {
                        for (String stmt : SqlSplitter.split(m.sql())) {
                            st.execute(stmt);
                        }
                    } catch (SQLException | RuntimeException e) {
                        record(con, moduleId, moduleVersion, m, System.currentTimeMillis() - start, false, e.getMessage());
                        throw new IllegalStateException("migration " + m.script() + " failed: " + e.getMessage(), e);
                    }
                    record(con, moduleId, moduleVersion, m, System.currentTimeMillis() - start, true, null);
                    LOGGER.info("Platform: " + moduleId + ": applied migration " + m.script() + " (" + (System.currentTimeMillis() - start) + " ms)");
                }
            } finally {
                unlock(con);
            }
        }
    }

    private static void record(Connection con, String moduleId, String moduleVersion, Migration m, long ms, boolean success, String error) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("REPLACE INTO platform_module_migrations (module_id, version, description, script, checksum, module_version, execution_ms, success, error) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, moduleId);
            ps.setInt(2, m.version());
            ps.setString(3, m.description().length() > 200 ? m.description().substring(0, 200) : m.description());
            ps.setString(4, m.script());
            ps.setString(5, m.checksum());
            ps.setString(6, moduleVersion);
            ps.setInt(7, (int) ms);
            ps.setBoolean(8, success);
            ps.setString(9, error);
            ps.executeUpdate();
        }
    }

    private static void lock(Connection con) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT GET_LOCK('" + LOCK + "', 60)")) {
            if (!rs.next() || rs.getInt(1) != 1) {
                throw new SQLException("could not get migration lock " + LOCK + " within 60 s");
            }
        }
    }

    private static void unlock(Connection con) {
        try (Statement st = con.createStatement()) {
            st.execute("SELECT RELEASE_LOCK('" + LOCK + "')");
        } catch (SQLException e) {
            // connection is returned to the pool anyway
        }
    }

    /** Last version of each module that ran on this database. Empty when the table does not exist yet. */
    public Map<String, Semver> lastVersions() {
        final Map<String, Semver> out = new HashMap<>();
        if (!platformTablesExist()) {
            return out;
        }
        try (Connection con = connections.get(); Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT module_id, last_version FROM platform_modules")) {
            while (rs.next()) {
                if (Semver.isValid(rs.getString(2))) {
                    out.put(rs.getString(1), Semver.parse(rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            LOGGER.warning("Platform: cannot read platform_modules: " + e.getMessage());
        }
        return out;
    }

    /** Remembers the state; last_version only grows (downgrade protection). */
    public void saveState(String moduleId, String version, String state, String error, boolean enabled) {
        if (!platformTablesExist()) {
            return;
        }
        try (Connection con = connections.get(); PreparedStatement ps = con.prepareStatement("INSERT INTO platform_modules (module_id, last_version, last_state, last_error, last_enabled_at) VALUES (?,?,?,?, IF(?, NOW(), NULL))"
            + " ON DUPLICATE KEY UPDATE last_version = IF(? > '', ?, last_version), last_state = VALUES(last_state), last_error = VALUES(last_error), last_enabled_at = IF(?, NOW(), last_enabled_at)")) {
            ps.setString(1, moduleId);
            ps.setString(2, version);
            ps.setString(3, state);
            ps.setString(4, error);
            ps.setBoolean(5, enabled);
            final boolean newer = isNewer(con, moduleId, version);
            ps.setString(6, newer ? "y" : "");
            ps.setString(7, version);
            ps.setBoolean(8, enabled);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.warning("Platform: cannot save state of " + moduleId + ": " + e.getMessage());
        }
    }

    private static boolean isNewer(Connection con, String moduleId, String version) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT last_version FROM platform_modules WHERE module_id = ?")) {
            ps.setString(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !Semver.isValid(rs.getString(1)) || !Semver.isValid(version)) {
                    return true;
                }
                return Semver.parse(version).compareTo(Semver.parse(rs.getString(1))) > 0;
            }
        }
    }
}
