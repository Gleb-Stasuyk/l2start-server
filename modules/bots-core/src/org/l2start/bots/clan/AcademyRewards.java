package org.l2start.bots.clan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.clan.OnPlayerClanJoin;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.clan.OnPlayerClanLeft;
import org.l2start.bots.core.BotRegistry;
import org.l2start.platform.api.ModuleContext;

/**
 * Phase 7f: one-time real adena rewards for live players joining / graduating a bot-clan academy.
 * Ledger table {@code mod_bots_core_academy_reward}.
 */
public final class AcademyRewards {
    public record Row(int charId, int botClanId, boolean joinPaid, boolean graduatePaid, long joinedAt, long graduatedAt) {
    }

    private final ModuleContext ctx;
    private final ClanService clans;
    private final BotRegistry registry;
    private final String table;

    private volatile long joinReward = 50_000L;
    private volatile long graduateReward = 150_000L;

    public AcademyRewards(ModuleContext ctx, ClanService clans, BotRegistry registry) {
        this.ctx = ctx;
        this.clans = clans;
        this.registry = registry;
        this.table = ctx.db().table("academy_reward");
    }

    /** Pays the join reward through a trade window with the recruiter bot; false = cannot (adena go in directly). */
    public interface JoinPayer {
        boolean offer(Player p, int botClanId, long amount, Runnable onPaid);
    }

    private volatile JoinPayer joinPayer;

    public void setJoinPayer(JoinPayer payer) {
        joinPayer = payer;
    }

    public void configure(long join, long graduate) {
        joinReward = Math.max(0, join);
        graduateReward = Math.max(0, graduate);
    }

    /** Pure: academy invite target (no clan, &lt;40, 1st–2nd class incomplete). */
    public static boolean eligible(boolean hasClan, int level, int classLevel) {
        return !hasClan && (level < 40) && (classLevel < 2);
    }

    /** Pure: graduate payout when leaving academy. */
    public static boolean graduateEligible(boolean joinPaid, boolean graduatePaid, int level) {
        return joinPaid && !graduatePaid && (level >= 40);
    }

    public void onClanJoin(OnPlayerClanJoin e) {
        final ClanMember m = e.getClanMember();
        final Clan clan = e.getClan();
        if ((m == null) || (clan == null) || !isBotClan(clan.getId())) {
            return;
        }
        if (m.getPledgeType() != Clan.SUBUNIT_ACADEMY) {
            return;
        }
        final Player p = resolvePlayer(m);
        if ((p == null) || registry.isBot(p)) {
            return;
        }
        payJoin(p, clan.getId());
    }

    public void onClanLeft(OnPlayerClanLeft e) {
        final ClanMember m = e.getClanMember();
        final Clan clan = e.getClan();
        if ((m == null) || (clan == null) || !isBotClan(clan.getId())) {
            return;
        }
        final Player p = resolvePlayer(m);
        if ((p == null) || registry.isBot(p)) {
            return;
        }
        payGraduate(p, clan.getId());
    }

    private static Player resolvePlayer(ClanMember m) {
        Player p = m.getPlayer();
        if (p == null) {
            p = World.getPlayer(m.getObjectId());
        }
        return p;
    }

    private boolean isBotClan(int clanId) {
        for (ClanStore.Row r : clans.clans()) {
            if (r.clanId() == clanId) {
                return true;
            }
        }
        return false;
    }

    void payJoin(Player p, int botClanId) {
        if (joinReward <= 0) {
            return;
        }
        final int charId = p.getObjectId();
        final Optional<Row> existing = find(charId);
        if (existing.isPresent() && existing.get().joinPaid()) {
            return;
        }
        final JoinPayer payer = joinPayer;
        final long amount = joinReward;
        if ((payer != null) && payer.offer(p, botClanId, amount, () -> ledgerJoin(p, botClanId))) {
            ctx.log().info("academy join reward " + amount + " adena offered by trade -> " + p.getName());
            return;
        }
        if (ledgerJoin(p, botClanId)) {
            p.addAdena(ItemProcessType.REWARD, (int) Math.min(Integer.MAX_VALUE, amount), null, true);
            ctx.log().info("academy join reward " + amount + " adena -> " + p.getName());
        }
    }

    /** Marks the join reward paid. @return false when the ledger could not be written (then nothing is paid) */
    boolean ledgerJoin(Player p, int botClanId) {
        final int charId = p.getObjectId();
        final Optional<Row> existing = find(charId);
        final long now = System.currentTimeMillis();
        try {
            if (existing.isEmpty()) {
                ctx.db().update("INSERT INTO " + table + " (char_id, bot_clan_id, join_paid, graduate_paid, joined_at, graduated_at) VALUES (?,?,1,0,?,0)",
                    charId, botClanId, now);
            } else {
                ctx.db().update("UPDATE " + table + " SET bot_clan_id=?, join_paid=1, joined_at=? WHERE char_id=?",
                    botClanId, now, charId);
            }
        } catch (Exception ex) {
            ctx.log().warn("academy join ledger " + p.getName() + ": " + ex.getMessage());
            return false;
        }
        return true;
    }

    void payGraduate(Player p, int botClanId) {
        if (graduateReward <= 0) {
            return;
        }
        final int charId = p.getObjectId();
        final Optional<Row> existing = find(charId);
        if (existing.isEmpty() || !graduateEligible(existing.get().joinPaid(), existing.get().graduatePaid(), p.getLevel())) {
            return;
        }
        final long now = System.currentTimeMillis();
        try {
            final int updated = ctx.db().update("UPDATE " + table + " SET graduate_paid=1, graduated_at=?, bot_clan_id=? WHERE char_id=? AND graduate_paid=0",
                now, botClanId, charId);
            if (updated <= 0) {
                return;
            }
        } catch (Exception ex) {
            ctx.log().warn("academy graduate ledger " + p.getName() + ": " + ex.getMessage());
            return;
        }
        p.addAdena(ItemProcessType.REWARD, (int) Math.min(Integer.MAX_VALUE, graduateReward), null, true);
        ctx.log().info("academy graduate reward " + graduateReward + " adena -> " + p.getName());
    }

    public Optional<Row> find(int charId) {
        try {
            return ctx.db().queryOne("SELECT char_id, bot_clan_id, join_paid, graduate_paid, joined_at, graduated_at FROM " + table + " WHERE char_id=?",
                rs -> new Row(rs.getInt(1), rs.getInt(2), rs.getInt(3) != 0, rs.getInt(4) != 0, rs.getLong(5), rs.getLong(6)), charId);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<Row> all() {
        try {
            return ctx.db().query("SELECT char_id, bot_clan_id, join_paid, graduate_paid, joined_at, graduated_at FROM " + table,
                rs -> new Row(rs.getInt(1), rs.getInt(2), rs.getInt(3) != 0, rs.getInt(4) != 0, rs.getLong(5), rs.getLong(6)));
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Row> paidSummary() {
        final List<Row> out = new ArrayList<>();
        for (Row r : all()) {
            if (r.joinPaid() || r.graduatePaid()) {
                out.add(r);
            }
        }
        return out;
    }
}
