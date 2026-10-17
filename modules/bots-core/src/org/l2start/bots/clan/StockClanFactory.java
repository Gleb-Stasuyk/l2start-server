package org.l2start.bots.clan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.sql.CrestTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;
import org.l2jmobius.gameserver.entity.clan.Crest;
import org.l2jmobius.gameserver.entity.clan.enums.CrestType;
import org.l2jmobius.gameserver.managers.IdManager;

/**
 * Creates / repairs stock Mobius clans for bots without requiring anyone to be in the world.
 */
public final class StockClanFactory {
    public static final int LEADER_POWER = 6;
    public static final int MEMBER_POWER = 5;

    public record Member(int charId, boolean leader) {
    }

    public record Created(int clanId, int crestId) {
    }

    private StockClanFactory() {
    }

    /**
     * Insert {@code clan_data}, set {@code characters.clanid} for all members (offline OK), register in
     * {@link ClanTable}, set level and crest.
     */
    public static Created create(String name, int level, List<Member> members, byte[] crestDds) throws Exception {
        if ((name == null) || name.isBlank() || (members == null) || members.isEmpty()) {
            throw new IllegalArgumentException("stock clan: bad args");
        }
        if ((crestDds == null) || (crestDds.length == 0) || (crestDds.length > 256)) {
            throw new IllegalArgumentException("stock clan: bad crest");
        }
        if (ClanTable.getInstance().getClanByName(name) != null) {
            throw new IllegalStateException("stock clan name taken: " + name);
        }
        int leaderCharId = 0;
        for (Member m : members) {
            if (m.leader()) {
                leaderCharId = m.charId();
                break;
            }
        }
        if (leaderCharId == 0) {
            throw new IllegalArgumentException("stock clan: no leader");
        }
        final int clanId = IdManager.getInstance().getNextId();
        final Clan draft = new Clan(clanId, name);
        try (Connection con = DatabaseFactory.getConnection();
            PreparedStatement ps = con.prepareStatement(
                "SELECT char_name,level,classid,charId,title,power_grade,subpledge,apprentice,sponsor,sex,race FROM characters WHERE charId=?")) {
            ps.setInt(1, leaderCharId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("stock clan: leader char missing " + leaderCharId);
                }
                draft.setLeader(new ClanMember(draft, rs));
            }
        }
        draft.store();
        assignCharacters(clanId, members);
        final Clan live = reload(clanId);
        if (level > live.getLevel()) {
            live.changeLevel(level);
        }
        final int crestId = applyCrest(live, crestDds);
        if (crestId == 0) {
            throw new IllegalStateException("stock clan crest failed: " + name);
        }
        syncOnlinePlayers(live);
        return new Created(clanId, crestId);
    }

    /** Add character to existing stock clan (DB + memory reload). */
    public static void addMember(int clanId, int charId, boolean leader) throws Exception {
        if (ClanTable.getInstance().getClan(clanId) == null) {
            throw new IllegalStateException("no stock clan " + clanId);
        }
        assignCharacters(clanId, List.of(new Member(charId, leader)));
        if (leader) {
            try (Connection con = DatabaseFactory.getConnection();
                PreparedStatement ps = con.prepareStatement("UPDATE clan_data SET leader_id=? WHERE clan_id=?")) {
                ps.setInt(1, charId);
                ps.setInt(2, clanId);
                ps.executeUpdate();
            }
        }
        final Clan live = reload(clanId);
        syncOnlinePlayers(live);
    }

    /**
     * Ensure crest matches {@code crestDds} and is client-safe. Replaces missing, rejected, or
     * outdated blobs (e.g. after pad/encoder or BMP pack change). Returns crest id (0 = failed).
     */
    public static int ensureCrest(Clan clan, byte[] crestDds) {
        if (clan == null) {
            return 0;
        }
        if (hasMatchingCrest(clan, crestDds)) {
            return clan.getCrestId();
        }
        return applyCrest(clan, crestDds);
    }

    public static boolean hasCrest(Clan clan) {
        return hasClientSafeCrest(clan);
    }

    /** True when clan has a CrestTable blob the Interlude client will accept. */
    public static boolean hasClientSafeCrest(Clan clan) {
        if ((clan == null) || (clan.getCrestId() == 0)) {
            return false;
        }
        final Crest crest = CrestTable.getInstance().getCrest(clan.getCrestId());
        return (crest != null) && ClanCrests.isClientSafeCrest(crest.getData());
    }

    /** True when stored crest bytes equal the desired DDS (and are client-safe). */
    public static boolean hasMatchingCrest(Clan clan, byte[] crestDds) {
        if (!hasClientSafeCrest(clan) || (crestDds == null)) {
            return false;
        }
        final Crest crest = CrestTable.getInstance().getCrest(clan.getCrestId());
        return java.util.Arrays.equals(crest.getData(), crestDds);
    }

    public static Clan reload(int clanId) {
        final Clan fresh = new Clan(clanId);
        ClanTable.getInstance().registerClan(fresh);
        return fresh;
    }

    public static List<Member> membersOf(int leaderCharId, List<Integer> memberCharIds) {
        final List<Member> out = new ArrayList<>();
        out.add(new Member(leaderCharId, true));
        for (Integer id : memberCharIds) {
            if ((id != null) && (id.intValue() != leaderCharId)) {
                out.add(new Member(id, false));
            }
        }
        return out;
    }

    /** Pure: whether a module clan row still needs stock materialization. */
    public static boolean needsStockMaterialize(int moduleClanId, boolean stockClanExists) {
        return ClanService.isProvisional(moduleClanId) || !stockClanExists;
    }

    private static int applyCrest(Clan clan, byte[] crestDds) {
        if ((crestDds == null) || (crestDds.length == 0) || (crestDds.length > 256)) {
            return 0;
        }
        final Crest crest = CrestTable.getInstance().createCrest(crestDds, CrestType.PLEDGE);
        if (crest == null) {
            return 0;
        }
        clan.changeClanCrest(crest.getId());
        return crest.getId();
    }

    private static void assignCharacters(int clanId, List<Member> members) throws Exception {
        try (Connection con = DatabaseFactory.getConnection();
            PreparedStatement ps = con.prepareStatement(
                "UPDATE characters SET clanid=?, power_grade=?, subpledge=0 WHERE charId=?")) {
            for (Member m : members) {
                ps.setInt(1, clanId);
                ps.setInt(2, m.leader() ? LEADER_POWER : MEMBER_POWER);
                ps.setInt(3, m.charId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void syncOnlinePlayers(Clan clan) {
        if (clan == null) {
            return;
        }
        for (ClanMember m : clan.getMembers()) {
            final Player p = World.getPlayer(m.getObjectId());
            if (p == null) {
                continue;
            }
            if (p.getClan() != clan) {
                p.setClan(clan);
                p.setPowerGrade(m.getPowerGrade());
                p.setPledgeType(m.getPledgeType());
                p.broadcastUserInfo();
            }
        }
    }
}
