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
import org.l2jmobius.gameserver.network.serverpackets.AgitDecoInfo;
import org.l2start.bots.core.BotRecord;
import org.l2start.bots.core.BotRegistry;
import org.l2start.platform.api.ModuleContext;

/**
 * Phase 7g+: assign free auctionable CH to recruiter clans; enable max functions and keep CWH ≥ 2 hall-lease
 * periods for every bot clan that owns a hall (stock pays lease/functions).
 */
public final class ClanHallAssign {
    /** Same as stock {@code AuctionableHall} hall fee period (~7 days). */
    public static final long HALL_PERIOD_MS = 604_800_000L;

    public static final int MAX_SUPPORT = 8;
    public static final int MAX_TELEPORT = 2;
    public static final int MAX_HP_PCT = 300;
    public static final int MAX_MP_PCT = 40;
    public static final int MAX_EXP_PCT = 50;

    /** One function fee and how often stock charges it. */
    public record FeePeriod(int fee, long periodMs) {
    }

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

    /**
     * Pure: adena for 2 hall-lease periods plus function fees scaled to that window.
     * Daily fees (1d) → ×14; weekly → ×2.
     */
    public static long needForTwoPeriods(int hallLease, List<FeePeriod> functions) {
        long need = 2L * Math.max(0, hallLease);
        final long window = 2L * HALL_PERIOD_MS;
        if (functions != null) {
            for (FeePeriod f : functions) {
                if ((f == null) || (f.fee() <= 0) || (f.periodMs() <= 0)) {
                    continue;
                }
                need += (long) f.fee() * (window / f.periodMs());
            }
        }
        return need;
    }

    public static String shoutLine(String clanName, int clanLevel, String hallTown, int joinReward, int gradReward) {
        final String town = (hallTown == null) || hallTown.isBlank() ? "?" : hallTown;
        return "Клан " + clanName + " (" + clanLevel + " лвл, КХ в " + town + ", бафы) набирает в академию! "
            + (joinReward / 1000) + "к сразу, " + (gradReward / 1000) + "к за выпуск. Пиши в ПМ";
    }

    /** Pure: stock max levels we aim for. */
    public static boolean isMaxConfigured(int support, int tele, int hp, int mp, int exp) {
        return (support >= MAX_SUPPORT) && (tele >= MAX_TELEPORT) && (hp >= MAX_HP_PCT) && (mp >= MAX_MP_PCT) && (exp >= MAX_EXP_PCT);
    }

    /**
     * Assign a free hall near the spot if the clan has none; then maintain functions/CWH.
     * @return hall id or 0
     */
    public int ensureForRecruiter(int clanId, int nearX, int nearY, Player actor) {
        final Clan clan = ClanTable.getInstance().getClan(clanId);
        if (clan == null) {
            return 0;
        }
        AuctionableHall hall = ownedHall(clan);
        if (hall == null) {
            hall = pickNearestFree(ClanHallTable.getInstance().getFreeClanHalls(), nearX, nearY);
            if (hall == null) {
                ctx.log().warn("no free clan hall for bot clan " + clan.getName());
                return 0;
            }
            // CWH before setOwner so FeeTask(0) / ensurePaidFromCwh can take the lease
            topUpWarehouse(clan, hall, actor != null ? actor : onlineMember(clan));
            try {
                ClanHallTable.getInstance().setOwner(hall.getId(), clan);
                ctx.log().info("bot clan " + clan.getName() + " assigned CH#" + hall.getId() + " (" + hall.getLocation() + ")");
            } catch (Exception e) {
                ctx.log().warn("assign CH failed for " + clan.getName() + ": " + e.getMessage());
                return 0;
            }
            hall = ClanHallTable.getInstance().getAuctionableHallById(hall.getId());
        }
        return maintainHall(clan, hall, actor);
    }

    /** Maintain functions + CWH for a bot clan that already owns a hall. */
    public int ensureOwnedHall(int clanId, Player actor) {
        final Clan clan = ClanTable.getInstance().getClan(clanId);
        if (clan == null) {
            return 0;
        }
        return maintainHall(clan, ownedHall(clan), actor);
    }

    /** Top-up / enable functions for every bot clan that owns a hideout. */
    public void maintainAllOwned() {
        if (clans == null) {
            return;
        }
        for (ClanStore.Row row : clans.clans()) {
            final Clan clan = ClanTable.getInstance().getClan(row.clanId());
            if ((clan == null) || (clan.getHideoutId() <= 0)) {
                continue;
            }
            maintainHall(clan, ownedHall(clan), onlineMember(clan));
        }
    }

    private int maintainHall(Clan clan, AuctionableHall hall, Player actor) {
        if ((clan == null) || (hall == null)) {
            return 0;
        }
        final Player payer = actor != null ? actor : onlineMember(clan);
        topUpWarehouse(clan, hall, payer);
        if (!hall.getPaid() || (hall.getPaidUntil() <= System.currentTimeMillis())) {
            if (!hall.ensurePaidFromCwh()) {
                ctx.log().warn("CH#" + hall.getId() + " lease unpaid after top-up (CWH " + clan.getWarehouse().getAdena() + ", lease " + hall.getLease() + ")");
            } else {
                ctx.log().info("CH#" + hall.getId() + " lease paid until " + hall.getPaidUntil());
            }
        }
        if (hall.getPaid()) {
            enableMaxFunctions(hall, payer);
        }
        return hall.getId();
    }

    private static AuctionableHall ownedHall(Clan clan) {
        if ((clan == null) || (clan.getHideoutId() <= 0)) {
            return null;
        }
        return ClanHallTable.getInstance().getAuctionableHallById(clan.getHideoutId());
    }

    private void enableMaxFunctions(AuctionableHall hall, Player payer) {
        if (payer == null) {
            ctx.log().warn("CH#" + hall.getId() + " max functions deferred: no online clan member");
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
            return;
        }
        // stock ClanHallManager does this after updateFunctions so the client sees Recovery/Other
        if (hall instanceof AuctionableHall ah) {
            payer.sendPacket(new AgitDecoInfo(ah));
        }
    }

    private void topUpWarehouse(Clan clan, AuctionableHall hall, Player actor) {
        final long need = needForTwoPeriods(hall.getLease(), functionFeePeriods(hall));
        final long have = clan.getWarehouse().getAdena();
        if (have >= need) {
            return;
        }
        final long add = need - have;
        final int amount = (int) Math.min(Integer.MAX_VALUE, add);
        clan.getWarehouse().addItem(ItemProcessType.REWARD, Inventory.ADENA_ID, amount, actor, null);
        final long now = System.currentTimeMillis();
        // CH upkeep always funded; ledger notes up to the daily cap when possible
        if (clans.canSpendAdena(clan.getId(), add, now)) {
            clans.noteAdenaSpend(clan.getId(), add, now);
        } else if (clans.adenaSpendLimit() > 0) {
            clans.noteAdenaSpend(clan.getId(), Math.min(add, clans.adenaSpendLimit()), now);
        }
    }

    static List<FeePeriod> defaultFeePeriods() {
        return List.of(
            new FeePeriod(FeatureConfig.CH_SUPPORT8_FEE, FeatureConfig.CH_SUPPORT_FEE_RATIO),
            new FeePeriod(FeatureConfig.CH_TELE2_FEE, FeatureConfig.CH_TELE_FEE_RATIO),
            new FeePeriod(FeatureConfig.CH_HPREG13_FEE, FeatureConfig.CH_HPREG_FEE_RATIO),
            new FeePeriod(FeatureConfig.CH_MPREG5_FEE, FeatureConfig.CH_MPREG_FEE_RATIO),
            new FeePeriod(FeatureConfig.CH_EXPREG7_FEE, FeatureConfig.CH_EXPREG_FEE_RATIO));
    }

    private static List<FeePeriod> functionFeePeriods(ClanHall hall) {
        final List<FeePeriod> fees = new ArrayList<>();
        for (int t : new int[] { ClanHall.FUNC_SUPPORT, ClanHall.FUNC_TELEPORT, ClanHall.FUNC_RESTORE_HP, ClanHall.FUNC_RESTORE_MP,
            ClanHall.FUNC_RESTORE_EXP }) {
            final ClanHall.ClanHallFunction f = hall.getFunction(t);
            if (f != null) {
                final long rate = f.getRate() > 0 ? f.getRate() : periodForType(t);
                fees.add(new FeePeriod(f.getLease(), rate));
            }
        }
        return fees.isEmpty() ? new ArrayList<>(defaultFeePeriods()) : fees;
    }

    private static long periodForType(int type) {
        return switch (type) {
            case ClanHall.FUNC_TELEPORT -> FeatureConfig.CH_TELE_FEE_RATIO;
            case ClanHall.FUNC_SUPPORT -> FeatureConfig.CH_SUPPORT_FEE_RATIO;
            case ClanHall.FUNC_RESTORE_HP -> FeatureConfig.CH_HPREG_FEE_RATIO;
            case ClanHall.FUNC_RESTORE_MP -> FeatureConfig.CH_MPREG_FEE_RATIO;
            case ClanHall.FUNC_RESTORE_EXP -> FeatureConfig.CH_EXPREG_FEE_RATIO;
            default -> FeatureConfig.CH_SUPPORT_FEE_RATIO;
        };
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
