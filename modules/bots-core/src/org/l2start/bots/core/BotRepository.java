package org.l2start.bots.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.l2start.platform.api.ModuleDatabase;

/** SQL of bots-core. Writes to stock tables only for rows that belong to bots (accounts listed in mod_bots_core_account). */
public final class BotRepository {
    private final ModuleDatabase db;
    private final String accounts;
    private final String identity;

    public BotRepository(ModuleDatabase db) {
        this.db = db;
        this.accounts = db.table("account");
        this.identity = db.table("identity");
    }

    public List<BotRecord> loadAll() throws SQLException {
        return db.query("SELECT i.bot_id, i.char_id, i.account_name, COALESCE(c.char_name, i.char_name), i.class_id, i.status, i.last_login, i.last_logout, c.charId IS NULL"
            + " FROM " + identity + " i LEFT JOIN characters c ON c.charId = i.char_id ORDER BY i.bot_id", rs -> {
                BotRecord.Status st = BotRecord.Status.valueOf(rs.getString(6));
                if (rs.getBoolean(9)) {
                    st = BotRecord.Status.RETIRED; // character was deleted outside of bots-core
                }
                return new BotRecord(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getInt(5), st, rs.getLong(7), rs.getLong(8));
            });
    }

    public boolean accountExists(String login) throws SQLException {
        return db.queryOne("SELECT 1 FROM accounts WHERE login = ?", rs -> 1, login).isPresent();
    }

    /** Stock account row (banned: accessLevel -1, unknown password) + our ownership row, in one transaction. */
    public void createAccount(String login, String passwordHash) throws SQLException {
        try (Connection con = db.connection()) {
            con.setAutoCommit(false);
            try (PreparedStatement a = con.prepareStatement("INSERT INTO accounts (login, password, accessLevel, lastactive) VALUES (?, ?, -1, 0)");
                PreparedStatement b = con.prepareStatement("INSERT INTO " + accounts + " (account_name) VALUES (?)")) {
                a.setString(1, login);
                a.setString(2, passwordHash);
                a.executeUpdate();
                b.setString(1, login);
                b.executeUpdate();
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    /** Undo {@link #createAccount} when the character could not be created (only if it is ours and has no character). */
    public void dropAccount(String login) throws SQLException {
        if (db.queryOne("SELECT 1 FROM " + accounts + " WHERE account_name = ?", rs -> 1, login).isEmpty()) {
            return;
        }
        if (db.queryOne("SELECT 1 FROM characters WHERE account_name = ?", rs -> 1, login).isPresent()) {
            return;
        }
        db.update("DELETE FROM accounts WHERE login = ?", login);
        db.update("DELETE FROM " + accounts + " WHERE account_name = ?", login);
    }

    public int insertIdentity(int charId, String login, String name, int classId) throws SQLException {
        db.update("INSERT INTO " + identity + " (char_id, account_name, char_name, class_id) VALUES (?, ?, ?, ?)", charId, login, name, classId);
        return db.queryOne("SELECT bot_id FROM " + identity + " WHERE char_id = ?", rs -> rs.getInt(1), charId).orElseThrow();
    }

    public void retire(int botId) throws SQLException {
        db.update("UPDATE " + identity + " SET status = 'RETIRED' WHERE bot_id = ?", botId);
    }

    public void login(int botId, long now) throws SQLException {
        db.update("UPDATE " + identity + " SET last_login = ? WHERE bot_id = ?", now, botId);
    }

    public void logout(int botId, long now) throws SQLException {
        db.update("UPDATE " + identity + " SET last_logout = ? WHERE bot_id = ?", now, botId);
    }

    /** Bot accounts must stay banned (nobody can log in to them). Returns how many had to be banned again. */
    public int enforceBans() throws SQLException {
        return db.update("UPDATE accounts a JOIN " + accounts + " b ON b.account_name = a.login SET a.accessLevel = -1 WHERE a.accessLevel >= 0");
    }

    /** Bot characters must never have GM rights. Returns how many were reset. */
    public int enforcePlayerAccess() throws SQLException {
        return db.update("UPDATE characters c JOIN " + identity + " i ON i.char_id = c.charId SET c.accesslevel = 0 WHERE c.accesslevel <> 0");
    }
}
