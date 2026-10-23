package org.l2start.bots.clan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.l2start.platform.api.ModuleDatabase;

/** Persistence for {@code mod_bots_core_clan} / {@code mod_bots_core_clan_member}. */
public final class ClanStore {
    public record Row(int clanId, String templateId, String name, int level, int leaderBotId, int targetSize, String status) {
    }

    public record Member(int botId, int clanId, String role) {
    }

    private final ModuleDatabase db;
    private final String clans;
    private final String members;
    private final String playerClan;
    private final String townClan;

    public ClanStore(ModuleDatabase db) {
        this.db = db;
        this.clans = db.table("clan");
        this.members = db.table("clan_member");
        this.playerClan = db.table("player_clan");
        this.townClan = db.table("town_clan");
    }

    public List<Row> loadClans() throws Exception {
        return db.query("SELECT clan_id, template_id, name, level, leader_bot_id, target_size, status FROM " + clans + " WHERE status='ACTIVE' ORDER BY clan_id",
            rs -> new Row(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7)));
    }

    /** Recruiting town -> its bot clan (fixed mapping, V011). */
    public java.util.Map<String, Integer> loadTownClans() throws Exception {
        final java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (Object[] r : db.query("SELECT town, clan_id FROM " + townClan, rs -> new Object[] { rs.getString(1), rs.getInt(2) })) {
            m.put((String) r[0], (Integer) r[1]);
        }
        return m;
    }

    public void setTownClan(String town, int clanId) throws Exception {
        db.update("INSERT INTO " + townClan + " (town, clan_id, created_at) VALUES (?,?,?) ON DUPLICATE KEY UPDATE clan_id=VALUES(clan_id)", town, clanId,
            System.currentTimeMillis());
    }

    public List<Member> loadMembers() throws Exception {
        return db.query("SELECT bot_id, clan_id, role FROM " + members, rs -> new Member(rs.getInt(1), rs.getInt(2), rs.getString(3)));
    }

    public List<Member> membersOf(int clanId) throws Exception {
        return db.query("SELECT bot_id, clan_id, role FROM " + members + " WHERE clan_id=?", rs -> new Member(rs.getInt(1), rs.getInt(2), rs.getString(3)), clanId);
    }

    public Optional<Integer> clanIdOf(int botId) throws Exception {
        return db.queryOne("SELECT clan_id FROM " + members + " WHERE bot_id=?", rs -> rs.getInt(1), botId);
    }

    public void insertClan(Row r) throws Exception {
        db.update("INSERT INTO " + clans + " (clan_id, template_id, name, level, leader_bot_id, target_size, status, created_at) VALUES (?,?,?,?,?,?,?,?)",
            r.clanId(), r.templateId(), r.name(), r.level(), r.leaderBotId(), r.targetSize(), r.status(), System.currentTimeMillis());
    }

    public void updateClan(Row r) throws Exception {
        db.update("UPDATE " + clans + " SET template_id=?, name=?, level=?, leader_bot_id=?, target_size=?, status=? WHERE clan_id=?",
            r.templateId(), r.name(), r.level(), r.leaderBotId(), r.targetSize(), r.status(), r.clanId());
    }

    public void insertMember(int botId, int clanId, String role) throws Exception {
        db.update("INSERT INTO " + members + " (bot_id, clan_id, role) VALUES (?,?,?) ON DUPLICATE KEY UPDATE clan_id=VALUES(clan_id), role=VALUES(role)", botId, clanId, role);
    }

    public void removeMember(int botId) throws Exception {
        db.update("DELETE FROM " + members + " WHERE bot_id=?", botId);
    }

    public void dissolve(int clanId) throws Exception {
        db.update("DELETE FROM " + members + " WHERE clan_id=?", clanId);
        db.update("UPDATE " + clans + " SET status='DISSOLVED' WHERE clan_id=?", clanId);
    }

    /** Replace provisional clan_id with the stock one (members + clan row). */
    public void rematerialize(int fromClanId, ClanStore.Row stock, List<Member> mem) throws Exception {
        db.update("DELETE FROM " + members + " WHERE clan_id=?", fromClanId);
        db.update("DELETE FROM " + clans + " WHERE clan_id=?", fromClanId);
        insertClan(stock);
        for (Member m : mem) {
            insertMember(m.botId(), stock.clanId(), m.role());
        }
    }

    public int memberCount(int clanId) throws Exception {
        return db.queryOne("SELECT COUNT(*) FROM " + members + " WHERE clan_id=?", rs -> rs.getInt(1), clanId).orElse(0);
    }

    public List<Integer> freeBotIds(List<Integer> allActive) throws Exception {
        final List<Integer> taken = db.query("SELECT bot_id FROM " + members, rs -> rs.getInt(1));
        final java.util.HashSet<Integer> set = new java.util.HashSet<>(taken);
        for (Integer id : loadPlayerClanBots()) {
            set.add(id);
        }
        final List<Integer> free = new ArrayList<>();
        for (Integer id : allActive) {
            if (!set.contains(id)) {
                free.add(id);
            }
        }
        return free;
    }

    public List<Integer> loadPlayerClanBots() throws Exception {
        try {
            return db.query("SELECT bot_id FROM " + playerClan, rs -> rs.getInt(1));
        } catch (Exception e) {
            return List.of();
        }
    }

    public void markPlayerClan(int botId, boolean in) throws Exception {
        if (in) {
            db.update("INSERT INTO " + playerClan + " (bot_id, joined_at) VALUES (?,?) ON DUPLICATE KEY UPDATE joined_at=VALUES(joined_at)",
                botId, System.currentTimeMillis());
        } else {
            db.update("DELETE FROM " + playerClan + " WHERE bot_id=?", botId);
        }
    }
}
