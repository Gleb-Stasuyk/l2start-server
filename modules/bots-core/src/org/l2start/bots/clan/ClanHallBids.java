package org.l2start.bots.clan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.residences.ClanHallAuction;
import org.l2jmobius.gameserver.managers.ClanHallAuctionManager;
import org.l2start.bots.core.BotRecord;
import org.l2start.bots.core.BotRegistry;
import org.l2start.platform.api.ModuleContext;

/**
 * Phase 7f: bot clans bid on a share of stock clan-hall auctions using clan warehouse adena.
 */
public final class ClanHallBids {
    private final ModuleContext ctx;
    private final ClanService clans;
    private final BotRegistry registry;

    private volatile boolean enabled = true;
    private volatile double share = 0.55;
    private volatile long budget = 200_000_000L;
    private volatile java.util.function.Supplier<java.util.Set<Integer>> skipClans = java.util.Set::of;

    public ClanHallBids(ModuleContext ctx, ClanService clans, BotRegistry registry) {
        this.ctx = ctx;
        this.clans = clans;
        this.registry = registry;
    }

    public void configure(boolean on, double bidShare, long bidBudget) {
        enabled = on;
        share = Math.max(0, Math.min(1, bidShare));
        budget = Math.max(0, bidBudget);
    }

    /** Recruiter clans already own a hall — do not bid. */
    public void setSkipClans(java.util.function.Supplier<java.util.Set<Integer>> skip) {
        skipClans = skip == null ? java.util.Set::of : skip;
    }

    /** Pure: which auction ids a clan should cover for the configured share. */
    public static List<Integer> pickTargets(List<Integer> openAuctionIds, int clanId, double share) {
        final List<Integer> sorted = new ArrayList<>(openAuctionIds);
        sorted.sort(Comparator.naturalOrder());
        if (sorted.isEmpty() || share <= 0) {
            return List.of();
        }
        final int n = Math.max(1, (int) Math.round(sorted.size() * share));
        final List<Integer> out = new ArrayList<>();
        for (int id : sorted) {
            if (Integer.remainderUnsigned(Integer.hashCode(clanId * 31 + id), 1000) < (int) Math.round(share * 1000)) {
                out.add(id);
            }
        }
        if (out.size() < n) {
            for (int id : sorted) {
                if (!out.contains(id)) {
                    out.add(id);
                    if (out.size() >= n) {
                        break;
                    }
                }
            }
        } else if (out.size() > n) {
            return List.copyOf(out.subList(0, n));
        }
        return out;
    }

    /** Pure: next bid amount (starting or +5% over highest). */
    public static int nextBid(long startingBid, long highestBid) {
        final long base = Math.max(startingBid, highestBid);
        if (highestBid <= 0) {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1, startingBid));
        }
        final long raised = base + Math.max(1L, base / 20); // +5%
        return (int) Math.min(Integer.MAX_VALUE, raised);
    }

    /** Pure: how many auctions the share covers. */
    public static int expectedCount(int open, double share) {
        if ((open <= 0) || (share <= 0)) {
            return 0;
        }
        return Math.max(1, (int) Math.round(open * share));
    }

    public void tick() {
        if (!enabled || (budget <= 0)) {
            return;
        }
        final List<ClanHallAuction> auctions = ClanHallAuctionManager.getInstance().getAuctions();
        if ((auctions == null) || auctions.isEmpty()) {
            return;
        }
        final List<Integer> open = new ArrayList<>();
        for (ClanHallAuction a : auctions) {
            if (a != null) {
                open.add(a.getId());
            }
        }
        final Set<Integer> usedAuctions = new HashSet<>();
        final Set<Integer> skip = skipClans.get();
        for (ClanStore.Row row : clans.clans()) {
            if ((skip != null) && skip.contains(row.clanId())) {
                continue;
            }
            final Clan clan = ClanTable.getInstance().getClan(row.clanId());
            if (clan == null) {
                continue;
            }
            if (clan.getHideoutId() > 0) {
                continue;
            }
            if (clan.getAuctionBiddedAt() > 0) {
                continue;
            }
            final BotRecord leader = byBot(row.leaderBotId());
            if ((leader == null) || (leader.player() == null) || !leader.inWorld()) {
                continue;
            }
            final Player bidder = leader.player();
            final List<Integer> targets = pickTargets(open, row.clanId(), share);
            for (int auctionId : targets) {
                if (usedAuctions.contains(auctionId)) {
                    continue;
                }
                final ClanHallAuction auction = ClanHallAuctionManager.getInstance().getAuction(auctionId);
                if (auction == null) {
                    continue;
                }
                final int bid = nextBid(auction.getStartingBid(), auction.getHighestBidderMaxBid());
                if (bid > budget) {
                    continue;
                }
                if (!ensureWarehouseAdena(clan, bid, bidder)) {
                    continue;
                }
                if (!clans.canSpendAdena(row.clanId(), bid, System.currentTimeMillis())) {
                    continue;
                }
                try {
                    auction.setBid(bidder, bid);
                    clans.noteAdenaSpend(row.clanId(), bid, System.currentTimeMillis());
                    usedAuctions.add(auctionId);
                    ctx.log().info("bot clan " + row.name() + " bid " + bid + " on CH auction " + auctionId);
                    break; // one auction per clan
                } catch (Exception e) {
                    ctx.log().warn("bot clan " + row.name() + " CH bid failed: " + e.getMessage());
                }
            }
        }
    }

    private boolean ensureWarehouseAdena(Clan clan, long need, Player actor) {
        final long have = clan.getWarehouse().getAdena();
        if (have >= need) {
            return true;
        }
        final long add = need - have;
        if (add > budget) {
            return false;
        }
        clan.getWarehouse().addItem(ItemProcessType.REWARD, Inventory.ADENA_ID, (int) Math.min(Integer.MAX_VALUE, add), actor, null);
        return clan.getWarehouse().getAdena() >= need;
    }

    public String hallLabel(Clan clan) {
        if (clan == null) {
            return "—";
        }
        if (clan.getHideoutId() > 0) {
            return "KH#" + clan.getHideoutId();
        }
        if (clan.getAuctionBiddedAt() > 0) {
            return "bid#" + clan.getAuctionBiddedAt();
        }
        return "—";
    }

    private BotRecord byBot(int botId) {
        for (BotRecord r : registry.all()) {
            if (r.botId() == botId) {
                return r;
            }
        }
        return null;
    }
}
