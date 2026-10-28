package org.l2start.economy.market;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.network.holders.RequestTrade;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2start.bots.trade.TraderPool;
import org.l2start.economy.market.model.MarketAreas;
import org.l2start.economy.market.model.StoreSpots.Spot;
import org.l2start.economy.market.model.StoreVisit;
import org.l2start.economy.market.model.TraderPresence;

/**
 * Phase 7 polish: keep a few bots browsing Giran market SELL/BUY/CRAFT spots (walk, stop, sometimes buy from a
 * real player's sell store). Distinct from empty-BUY replace and from one-shot Shoppers visits.
 */
final class MarketBrowsers {
    static final String TOWN = "Giran";
    static final long WALK_MS = 12_000L;
    static final long STAND_MS = 8_000L;
    static final double BUY_CHANCE = 0.12;

    private final EconomyMarketModule m;
    private final Traders traders;
    private final TraderPool pool;
    private final Random rnd = new Random();
    private final List<Browser> active = new ArrayList<>();
    private int want;
    private long nextRetarget;

    private static final class Browser {
        final int botId;
        long phaseAt;
        int phase; // 0 walk, 1 stand, 2 leave-buy walk
        Spot target;
        int buyOwnerId;
        List<StoreVisit.Buy> buys = List.of();

        Browser(int botId, long now) {
            this.botId = botId;
            this.phaseAt = now;
        }
    }

    MarketBrowsers(EconomyMarketModule m, Traders traders, TraderPool pool) {
        this.m = m;
        this.traders = traders;
        this.pool = pool;
        final int[] r = parseCount(m.ctx().config().getString("GiranMarketBrowsers"));
        want = r[0] + (r[1] > r[0] ? rnd.nextInt((r[1] - r[0]) + 1) : 0);
    }

    /** Parse {@code "3-5"} → {3,5}. */
    static int[] parseCount(String raw) {
        if ((raw == null) || raw.isBlank()) {
            return new int[] { 3, 5 };
        }
        final String s = raw.trim();
        final int dash = s.indexOf('-');
        try {
            if (dash < 0) {
                final int v = Math.max(0, Integer.parseInt(s));
                return new int[] { v, v };
            }
            final int a = Math.max(0, Integer.parseInt(s.substring(0, dash).trim()));
            final int b = Math.max(0, Integer.parseInt(s.substring(dash + 1).trim()));
            return new int[] { Math.min(a, b), Math.max(a, b) };
        } catch (NumberFormatException e) {
            return new int[] { 3, 5 };
        }
    }

    /** Spots a browser may visit: Giran SELL / BUY / CRAFT. */
    static List<Spot> visitPath(List<Spot> all) {
        final List<Spot> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        for (Spot s : all) {
            if ((s == null) || (s.town() == null)) {
                continue;
            }
            if (!TOWN.equalsIgnoreCase(s.town())) {
                continue;
            }
            final String k = s.kind() == null ? "" : s.kind().toUpperCase(Locale.ROOT);
            if ("SELL".equals(k) || "BUY".equals(k) || "CRAFT".equals(k)) {
                out.add(s);
            }
        }
        return out;
    }

    int want() {
        return want;
    }

    int activeCount() {
        return active.size();
    }

    void tick(long now) throws Exception {
        if (!m.marketOn()) {
            stop();
            return;
        }
        final int[] range = parseCount(m.ctx().config().getString("GiranMarketBrowsers"));
        if ((now >= nextRetarget) || (want < range[0]) || (want > range[1])) {
            want = range[0] + (range[1] > range[0] ? rnd.nextInt((range[1] - range[0]) + 1) : 0);
            nextRetarget = now + 10 * 60_000L;
        }
        final List<Spot> path = visitPath(traders.giranBrowseSpots());
        if (path.isEmpty()) {
            return;
        }
        while (active.size() < want) {
            final int id = persona();
            if (id <= 0) {
                break;
            }
            final Spot start = path.get(rnd.nextInt(path.size()));
            final String why = pool.enter(id, start.x(), start.y(), start.z(), start.heading(), TraderPresence.level(id), p -> {
            });
            if (why != null) {
                break;
            }
            final Browser b = new Browser(id, now);
            b.target = start;
            active.add(b);
            m.ctx().log().info("market browser " + pool.name(id) + " at " + start.id());
        }
        while (active.size() > want) {
            leave(active.remove(active.size() - 1), "quota");
        }
        for (Iterator<Browser> it = active.iterator(); it.hasNext();) {
            final Browser b = it.next();
            final Player p = pool.player(b.botId);
            if ((p == null) || !p.isOnline()) {
                it.remove();
                continue;
            }
            step(b, p, path, now);
        }
    }

    private void step(Browser b, Player p, List<Spot> path, long now) {
        switch (b.phase) {
            case 0 -> { // walking to target spot
                if ((b.target == null) || ((now - b.phaseAt) > WALK_MS)
                    || (Math.hypot(p.getX() - b.target.x(), p.getY() - b.target.y()) <= 80)) {
                    p.getAI().setIntentionActive();
                    b.phase = 1;
                    b.phaseAt = now;
                    if ((rnd.nextDouble() < BUY_CHANCE) && tryStartBuy(b, p, now)) {
                        b.phase = 2;
                    }
                }
            }
            case 1 -> { // standing / looking
                if ((now - b.phaseAt) >= STAND_MS) {
                    walkNext(b, p, path, now);
                }
            }
            case 2 -> { // buying from a player store nearby
                final Player owner = World.getPlayer(b.buyOwnerId);
                if ((owner == null) || !realSell(owner) || ((now - b.phaseAt) > 25_000L)) {
                    walkNext(b, p, path, now);
                    return;
                }
                if (p.calculateDistance2D(owner) > 110) {
                    return;
                }
                p.getAI().setIntentionActive();
                if ((now - b.phaseAt) < 2_500L) {
                    return;
                }
                buy(b, p, owner);
                walkNext(b, p, path, now);
            }
            default -> walkNext(b, p, path, now);
        }
    }

    private void walkNext(Browser b, Player p, List<Spot> path, long now) {
        b.buys = List.of();
        b.buyOwnerId = 0;
        Spot next = path.get(rnd.nextInt(path.size()));
        if ((b.target != null) && (path.size() > 1)) {
            for (int i = 0; i < 4; i++) {
                final Spot cand = path.get(rnd.nextInt(path.size()));
                if (!cand.id().equals(b.target.id())) {
                    next = cand;
                    break;
                }
            }
        }
        b.target = next;
        b.phase = 0;
        b.phaseAt = now;
        p.setRunning();
        p.getAI().setIntentionMoveTo(new Location(next.x(), next.y(), next.z()));
    }

    private boolean tryStartBuy(Browser b, Player p, long now) {
        Player best = null;
        double bestD = 900;
        for (Player o : World.getPlayers()) {
            if (!realSell(o)) {
                continue;
            }
            final double d = p.calculateDistance2D(o);
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        if (best == null) {
            return false;
        }
        final List<StoreVisit.Offer> offers = new ArrayList<>();
        for (TradeItem ti : best.getSellList().getItems()) {
            offers.add(new StoreVisit.Offer(ti.getObjectId(), ti.getItem().getId(), ti.getCount(), ti.getPrice(), ti.getItem().isStackable(),
                0, ti.getItem().getReferencePrice()));
        }
        final List<StoreVisit.Buy> buys = StoreVisit.pick(offers, best.getSellList().isPackaged(), 1.1, 200_000L, rnd);
        if (buys.isEmpty()) {
            return false;
        }
        b.buyOwnerId = best.getObjectId();
        b.buys = buys;
        b.phaseAt = now;
        final double d = Math.max(1, Math.hypot(p.getX() - best.getX(), p.getY() - best.getY()));
        final int tx = best.getX() + (int) (((p.getX() - best.getX()) * 55) / d);
        final int ty = best.getY() + (int) (((p.getY() - best.getY()) * 55) / d);
        final int tz = GeoEngine.getInstance().getHeight(tx, ty, best.getZ());
        p.setRunning();
        p.getAI().setIntentionMoveTo(new Location(tx, ty, tz));
        return true;
    }

    private void buy(Browser b, Player p, Player owner) {
        try {
            final TradeList list = owner.getSellList();
            final java.util.Set<RequestTrade> req = new java.util.HashSet<>();
            long total = 0;
            for (StoreVisit.Buy buy : b.buys) {
                final TradeItem ti = list.getItems().stream().filter(t -> t.getObjectId() == buy.objectId()).findFirst().orElse(null);
                if ((ti == null) || (ti.getPrice() != buy.price()) || (ti.getCount() < buy.count())) {
                    return;
                }
                req.add(new RequestTrade(buy.objectId(), buy.itemId(), (int) buy.count(), (int) buy.price()));
                total += buy.total();
            }
            if ((total <= 0) || (total > Integer.MAX_VALUE)) {
                return;
            }
            p.addAdena(ItemProcessType.REWARD, (int) total, null, false);
            if (list.privateStoreBuy(p, req) == 0) {
                Traders.emptyBag(p);
                m.ctx().log().info("market browser " + p.getName() + " bought at " + owner.getName() + " for " + total);
            }
        } catch (Exception e) {
            m.ctx().log().warn("market browser buy: " + e.getMessage());
        }
    }

    private boolean realSell(Player p) {
        return (p != null) && p.isOnline() && ((p.getPrivateStoreType() == PrivateStoreType.SELL) || (p.getPrivateStoreType() == PrivateStoreType.PACKAGE_SELL))
            && (p.getSellList().getItemCount() > 0) && !traders.isBotStore(p.getObjectId()) && !org.l2start.platform.hooks.CoreHooks.isBot(p);
    }

    private int persona() throws Exception {
        final List<Integer> ids = new ArrayList<>(m.repo().shoppers());
        ids.removeIf(id -> !pool.known(id));
        while (ids.size() < (want + Shoppers.PERSONAS + 2)) {
            final int id = pool.create(new TraderPool.Look(MarketAreas.randomProfession(rnd), rnd.nextBoolean(), ""));
            m.repo().addShopper(id);
            ids.add(id);
        }
        final List<Integer> free = new ArrayList<>();
        for (int id : ids) {
            if (pool.player(id) == null) {
                boolean used = false;
                for (Browser b : active) {
                    if (b.botId == id) {
                        used = true;
                        break;
                    }
                }
                if (!used) {
                    free.add(id);
                }
            }
        }
        return free.isEmpty() ? -1 : free.get(rnd.nextInt(free.size()));
    }

    private void leave(Browser b, String why) {
        final Player p = pool.player(b.botId);
        int charId = 0;
        if (p != null) {
            charId = p.getObjectId();
            Traders.emptyBag(p);
        }
        pool.leave(b.botId);
        if (charId > 0) {
            try {
                m.repo().clearBotOfflineTrade(String.valueOf(charId));
                final int gone = m.repo().clearBag(charId); // logout may re-save tutorial junk
                if ((gone > 0) && (why != null)) {
                    m.ctx().log().info("market browser " + pool.name(b.botId) + " cleared " + gone + " bag rows");
                }
            } catch (Exception e) {
                m.ctx().log().warn("market browser clearBag: " + e.getMessage());
            }
        }
        if (why != null) {
            m.ctx().log().info("market browser " + pool.name(b.botId) + " left (" + why + ")");
        }
    }

    void stop() {
        for (Browser b : new ArrayList<>(active)) {
            leave(b, "stop");
        }
        active.clear();
        try {
            for (int id : m.repo().shoppers()) {
                final Player p = pool.player(id);
                if (p != null) {
                    final int charId = p.getObjectId();
                    Traders.emptyBag(p);
                    pool.leave(id);
                    m.repo().clearBotOfflineTrade(String.valueOf(charId));
                    m.repo().clearBag(charId);
                }
            }
            m.repo().clearShopperBags();
        } catch (Exception e) {
            m.ctx().log().warn("market browser stop sweep: " + e.getMessage());
        }
    }
}
