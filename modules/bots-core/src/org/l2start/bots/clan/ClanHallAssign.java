package org.l2start.bots.clan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.config.FeatureConfig;
import org.l2jmobius.gameserver.data.sql.ClanHallTable;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.residences.AuctionableHall;
import org.l2jmobius.gameserver.entity.residences.ClanHall;
import org.l2jmobius.gameserver.entity.zone.type.ClanHallZone;
import org.l2start.bots.core.BotRecord;
import org.l2start.bots.core.BotRegistry;
import org.l2start.platform.api.ModuleContext;

/**
 * Phase 7g: assign a free auctionable clan hall to recruiter clans (nearest to GK), enable max functions,
 * keep CWH funded for ≥2 lease periods (stock pays lease).
 */
public final class ClanHallAssign {
    /** Max stock function levels / percents. */
    public static final int MAX_SUPPORT = 8;
    public static final int MAX_TELEPORT = 2;
    public static final int MAX_HP_PCT = 300;
    public static final int MAX_MP_PCT = 40;
    public static final int MAX_EXP_PCT = 50;

    private final ModuleContext ctx;
    private final ClanService clans;
    private final BotRegistry registry;

    public ClanHallAssign(ModuleContext ctx, ClanService clans, BotRegistry registry) {
        this.ctx = ctx;
        this.clans = clans;
        this.registry = registry;
    }

    /** Pure: pick nearest free hall to (x,y); empty → null. */
    public static AuctionableHall pickNearestFree(Map<Integer, AuctionableHall> free, int x, int y) {
        if ((free == null) || free.isEmpty()) {
            return null;
        }
        AuctionableHall best = null;
        double bestD = Double.MAX_VALUE;
        for (AuctionableHall h : free.values()) {
            if (h == null) {
                continue;
            }
            final ClanHallZone z = h.getZone();
            final double d = z == null ? Double.MAX_VALUE : z.getDistanceToZone(x, y);
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    /** Pure: adena needed in CWH for 2 periods of hall lease + all function fees. */
    public static long needForTwoPeriods(int hallLease, List<Integer> functionFees) {
        long funcs = 0;
        for (int f : functionFees) {
            funcs += Math.max(0, f);
        }
        return 2L * (Math.max(0, hallLease) + funcs);
    }

    public static String shoutLine(String clanName, int clanLevel, String hallTown, int joinReward, int gradReward) {
        final String town = (hallTown == null) || hallTown.isBlank() ? "?" : hallTown;
        return "Клан " + clanName + " (" + clanLevel + " лвл, КХ в " + town + ", бафы) набирает в академию! "
            + (joinReward / 1000) + "к сразу, " + (gradReward / 1000) + "к за выпуск. Пиши в ПМ";
    }

    /**
     * Ensure clan owns a free hall near the spot (or nearest free anywhere), max functions, CWH topped up.
     * @return hall id or 0
     */
    public int ensureForRecruiter(int clanId, int nearX, int nearY, Player actor) {
        final Clan clan = ClanTable.getInstance().getClan(clanId);
        if (clan == null) {
            return 0;
        }
        AuctionableHall hall = null;
        if (clan.getHideoutId() > 0) {
            hall = ClanHallTable.getInstance().getAuctionableHallById(clan.getHideoutId());
        }
        if (hall == null) {
            hall = pickNearestFree(ClanHallTable.getInstance().getFreeClanHalls(), nearX, nearY);
            if (hall == null) {
                ctx.log().warn("no free clan hall for bot clan " + clan.getName());
                return 0;
            }
            try {
                ClanHallTable.getInstance().setOwner(hall.getId(), clan);
                ctx.log().info("bot clan " + clan.getName() + " assigned CH#" + hall.getId() + " (" + hall.getLocation() + ")");
            } catch (Exception e) {
                ctx.log().warn("assign CH failed for " + clan.getName() + ": " + e.getMessage());
                return 0;
            }
            hall = ClanHallTable.getInstance().getAuctionableHallById(hall.getId());
        }
        if (hall == null) {
            return 0;
        }
        enableMaxFunctions(hall, actor != null ? actor : onlineMember(clan));
        topUpWarehouse(clan, hall, actor != null ? actor : onlineMember(clan));
        return hall.getId();
    }

    public void topUpAllRecruiters(Iterable<int[]> clanNearXy) {
        for (int[] row : clanNearXy) {
            if ((row == null) || (row.length < 3)) {
                continue;
            }
            ensureForRecruiter(row[0], row[1], row[2], null);
        }
    }

    private void enableMaxFunctions(AuctionableHall hall, Player payer) {
        if (payer == null) {
            return;
        }
        apply(hall, payer, ClanHall.FUNC_SUPPORT, MAX_SUPPORT, FeatureConfig.CH_SUPPORT8_FEE, FeatureConfig.CH_SUPPORT_FEE_RATIO);
        apply(hall, payer, ClanHall.FUNC_TELEPORT, MAX_TELEPORT, FeatureConfig.CH_TELE2_FEE, FeatureConfig.CH_TELE_FEE_RATIO);
        apply(hall, payer, ClanHall.FUNC_RESTORE_HP, MAX_HP_PCT, FeatureConfig.CH_HPREG13_FEE, FeatureConfig.CH_HPREG_FEE_RATIO);
        apply(hall, payer, ClanHall.FUNC_RESTORE_MP, MAX_MP_PCT, FeatureConfig.CH_MPREG5_FEE, FeatureConfig.CH_MPREG_FEE_RATIO);
        apply(hall, payer, ClanHall.FUNC_RESTORE_EXP, MAX_EXP_PCT, FeatureConfig.CH_EXPREG7_FEE, FeatureConfig.CH_EXPREG_FEE_RATIO);
    }

    private void apply(ClanHall hall, Player payer, int type, int level, int fee, long ratio) {
        final ClanHall.ClanHallFunction cur = hall.getFunction(type);
        if ((cur != null) && (cur.getLevel() >= level)) {
            return;
        }
        fundPlayer(payer, fee + 10_000);
        final boolean addNew = cur == null;
        if (!hall.updateFunctions(payer, type, level, fee, ratio, addNew)) {
            ctx.log().warn("CH#" + hall.getId() + " function " + type + " lvl " + level + " failed");
        }
    }

    private void topUpWarehouse(Clan clan, AuctionableHall hall, Player actor) {
        final List<Integer> fees = functionFees(hall);
        final long need = needForTwoPeriods(hall.getLease(), fees);
        final long have = clan.getWarehouse().getAdena();
        if (have >= need) {
            return;
        }
        final long add = need - have;
        if ((actor == null) || !clans.canSpendAdena(clan.getId(), add, System.currentTimeMillis())) {
            // still fund: recruiter CH must stay paid (ledger note when possible)
            clan.getWarehouse().addItem(ItemProcessType.REWARD, Inventory.ADENA_ID, (int) Math.min(Integer.MAX_VALUE, add), actor, null);
            return;
        }
        clan.getWarehouse().addItem(ItemProcessType.REWARD, Inventory.ADENA_ID, (int) Math.min(Integer.MAX_VALUE, add), actor, null);
        clans.noteAdenaSpend(clan.getId(), add, System.currentTimeMillis());
    }

    private static List<Integer> functionFees(ClanHall hall) {
        final List<Integer> fees = new ArrayList<>();
        for (int t : new int[] { ClanHall.FUNC_SUPPORT, ClanHall.FUNC_TELEPORT, ClanHall.FUNC_RESTORE_HP, ClanHall.FUNC_RESTORE_MP,
            ClanHall.FUNC_RESTORE_EXP }) {
            final ClanHall.ClanHallFunction f = hall.getFunction(t);
            if (f != null) {
                fees.add(f.getLease());
            }
        }
        if (fees.isEmpty()) {
            fees.add(FeatureConfig.CH_SUPPORT8_FEE);
            fees.add(FeatureConfig.CH_TELE2_FEE);
            fees.add(FeatureConfig.CH_HPREG13_FEE);
            fees.add(FeatureConfig.CH_MPREG5_FEE);
            fees.add(FeatureConfig.CH_EXPREG7_FEE);
        }
        return fees;
    }

    private static void fundPlayer(Player p, long need) {
        final long have = p.getAdena();
        if (have >= need) {
            return;
        }
        p.addAdena(ItemProcessType.REWARD, (int) Math.min(Integer.MAX_VALUE, need - have), null, false);
    }

    private Player onlineMember(Clan clan) {
        for (var m : clan.getMembers()) {
            final Player p = m == null ? null : m.getPlayer();
            if (p != null) {
                return p;
            }
        }
        for (BotRecord r : registry.all()) {
            if ((r.player() != null) && (r.player().getClanId() == clan.getId())) {
                return r.player();
            }
        }
        return null;
    }

    /** Town label for shout: hall location string or "?". */
    public static String hallTownLabel(Clan clan) {
        if ((clan == null) || (clan.getHideoutId() <= 0)) {
            return "";
        }
        final AuctionableHall h = ClanHallTable.getInstance().getAuctionableHallById(clan.getHideoutId());
        return h == null ? "" : h.getLocation();
    }
}
