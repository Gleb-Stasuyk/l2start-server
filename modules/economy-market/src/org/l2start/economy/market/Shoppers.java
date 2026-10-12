package org.l2start.economy.market;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
import org.l2start.economy.market.model.Budget;
import org.l2start.economy.market.model.MarketItem;
import org.l2start.economy.market.model.MarketAreas;
import org.l2start.economy.market.model.StoreVisit;
import org.l2start.economy.market.model.TraderPresence;

/**
 * Step 5e: bots buy in real players' private stores. Now and then (about once in 20-40 minutes per store) a bot
 * persona comes walking to a real player's sell store, stands a few seconds, buys a pack through the stock store code
 * ({@code TradeList.privateStoreBuy}: the player gets the stock "S1 purchased" message and the adena) and walks off.
 * Only lines at most {@code BuyFromPlayersMaxPrice} x market, never more than the player's day budget (5d) and the
 * bot's wallet. Half of the goods go to the resale pool of the bot sell stores, the rest leave the world. One visit at
 * a time. Runs on the market's task thread (like the traders).
 */
final class Shoppers {
    static final int PERSONAS = 3;
    static final long SCAN_MS = 20_000L;
    static final long FIRST_AFTER_MS = 5 * 60_000L; // a new store is visited at the earliest after 5 minutes
    static final long STORE_PAUSE_MS = 15 * 60_000L; // and then not again for 15 minutes
    static final double CHANCE_PER_SCAN = SCAN_MS / (25 * 60_000.0); // about once in 25 minutes
    static final long WALLET = 500_000L;

    private final EconomyMarketModule m;
    private final Traders traders;
    private final TraderPool pool;
    private final Random rnd = new Random();
    private final Map<Integer, Long> firstSeen = new HashMap<>();
    private final Map<Integer, Long> nextVisit = new HashMap<>();
    private long nextScan;
    private Visit visit;

    private static final class Visit {
        final int botId;
        final int ownerId;
        final List<StoreVisit.Buy> buys;
        final int ex, ey, ez; // where it came from (and walks back to)
        int phase;
        long at;

        Visit(int botId, int ownerId, List<StoreVisit.Buy> buys, int ex, int ey, int ez, long at) {
            this.botId = botId;
            this.ownerId = ownerId;
            this.buys = buys;
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
            this.at = at;
        }
    }

    Shoppers(EconomyMarketModule m, Traders traders, TraderPool pool) {
        this.m = m;
        this.traders = traders;
        this.pool = pool;
    }

    void tick(long now) throws Exception {
        final boolean on = m.marketOn() && m.ctx().config().getBoolean("BuyFromPlayers");
        if (visit != null) {
            if (!on) {
                end("market off");
            } else {
                step(now);
            }
            return;
        }
        if (!on || (now < nextScan)) {
            return;
        }
        nextScan = now + SCAN_MS;
        final double maxRatio = m.ctx().config().getDouble("BuyFromPlayersMaxPrice");
        final double share = m.ctx().config().getDouble("BotAdenaPerPlayerDay");
        final int day = (int) LocalDate.now().toEpochDay();
        final Set<Integer> seen = new HashSet<>();
        for (Player owner : World.getPlayers()) {
            if (!realStore(owner)) {
                continue;
            }
            final int id = owner.getObjectId();
            seen.add(id);
            firstSeen.putIfAbsent(id, now);
            if (((now - firstSeen.get(id)) < FIRST_AFTER_MS) || (now < nextVisit.getOrDefault(id, 0L)) || (rnd.nextDouble() >= CHANCE_PER_SCAN)) {
                continue;
            }
            final long limit = Math.min(Budget.left(owner.getLevel(), share, m.repo().playerPaid(id, day)), wallet(now));
            final List<StoreVisit.Buy> buys = StoreVisit.pick(offers(owner, now), owner.getSellList().isPackaged(), maxRatio, limit, rnd);
            if (buys.isEmpty()) {
                nextVisit.put(id, now + (STORE_PAUSE_MS / 3)); // nothing worth it now: look again later
                continue;
            }
            start(owner, buys, now);
            return;
        }
        firstSeen.keySet().retainAll(seen); // a store closed and opened again counts as new
    }

    /** A real (not bot) player with an open sell store. */
    private boolean realStore(Player p) {
        return (p != null) && p.isOnline() && ((p.getPrivateStoreType() == PrivateStoreType.SELL) || (p.getPrivateStoreType() == PrivateStoreType.PACKAGE_SELL))
            && (p.getSellList().getItemCount() > 0) && !traders.isBotStore(p.getObjectId()) && !org.l2start.platform.hooks.CoreHooks.isBot(p);
    }

    private List<StoreVisit.Offer> offers(Player owner, long now) {
        final List<StoreVisit.Offer> out = new ArrayList<>();
        for (TradeItem ti : owner.getSellList().getItems()) {
            final int itemId = ti.getItem().getId();
            final MarketItem it = m.book().items().get(itemId);
            final long market = (it == null) ? 0 : m.book().quote(itemId, m.stage(), now).market();
            out.add(new StoreVisit.Offer(ti.getObjectId(), itemId, ti.getCount(), ti.getPrice(), ti.getItem().isStackable(), market, ti.getItem().getReferencePrice()));
        }
        return out;
    }

    private long wallet(long now) {
        return WALLET; // a shopper buys for the traders' resale and its friends: the day budget of the player is the real limit
    }

    private void start(Player owner, List<StoreVisit.Buy> buys, long now) throws Exception {
        final int botId = persona();
        if (botId <= 0) {
            return;
        }
        // come from 350-500 away, from where it can walk straight to the store
        final GeoEngine geo = GeoEngine.getInstance();
        int x = owner.getX();
        int y = owner.getY();
        int z = owner.getZ();
        for (int t = 0; t < 16; t++) {
            final double a = rnd.nextDouble() * 2 * Math.PI;
            final int d = 350 + rnd.nextInt(150);
            final int cx = owner.getX() + (int) (Math.cos(a) * d);
            final int cy = owner.getY() + (int) (Math.sin(a) * d);
            final int cz = geo.getHeight(cx, cy, owner.getZ());
            if ((Math.abs(cz - owner.getZ()) < 64) && geo.canMoveToTarget(cx, cy, cz, owner.getX(), owner.getY(), owner.getZ(), owner.getInstanceId())) {
                x = cx;
                y = cy;
                z = cz;
                break;
            }
        }
        final int fx = x;
        final int fy = y;
        final int fz = z;
        final String why = pool.enter(botId, x, y, z, org.l2start.economy.market.model.SpotGenerator.headingTo(x, y, owner.getX(), owner.getY()), TraderPresence.level(botId), p -> {
            final double d = Math.max(1, Math.hypot(fx - owner.getX(), fy - owner.getY()));
            final int tx = owner.getX() + (int) (((fx - owner.getX()) * 55) / d);
            final int ty = owner.getY() + (int) (((fy - owner.getY()) * 55) / d);
            p.setRunning();
            p.getAI().setIntentionMoveTo(new Location(tx, ty, owner.getZ()));
        });
        if (why != null) {
            m.ctx().log().warn("shopper " + pool.name(botId) + " could not come to " + owner.getName() + "'s store: " + why);
            nextVisit.put(owner.getObjectId(), System.currentTimeMillis() + STORE_PAUSE_MS);
            return;
        }
        visit = new Visit(botId, owner.getObjectId(), buys, x, y, z, now);
        nextVisit.put(owner.getObjectId(), now + STORE_PAUSE_MS);
        m.ctx().log().info("shopper " + pool.name(botId) + " walks to " + owner.getName() + "'s store to buy " + describe(buys));
    }

    private void step(long now) throws Exception {
        final Visit v = visit;
        final Player p = pool.player(v.botId);
        final Player owner = World.getPlayer(v.ownerId);
        if ((p == null) || !p.isOnline()) {
            end("gone");
            return;
        }
        switch (v.phase) {
            case 0 -> { // walking there
                if ((owner == null) || !realStore(owner)) {
                    leaveSoon(v, p, now, "the store closed");
                } else if ((p.calculateDistance2D(owner) <= 110) || ((now - v.at) > 30_000L)) {
                    p.getAI().setIntentionActive();
                    p.setHeading(org.l2start.economy.market.model.SpotGenerator.headingTo(p.getX(), p.getY(), owner.getX(), owner.getY()));
                    p.broadcastUserInfo();
                    v.phase = 1;
                    v.at = now;
                }
            }
            case 1 -> { // looking at the goods
                if ((now - v.at) >= (3_000L + rnd.nextInt(4_000))) {
                    if ((owner != null) && realStore(owner)) {
                        buy(v, p, owner);
                    }
                    v.phase = 2;
                    v.at = now;
                }
            }
            case 2 -> { // stands a bit, walks off
                if ((now - v.at) >= 5_000L) {
                    leaveSoon(v, p, now, null);
                }
            }
            default -> { // walking off
                if ((now - v.at) >= 12_000L) {
                    end(null);
                }
            }
        }
    }

    private void leaveSoon(Visit v, Player p, long now, String why) {
        p.getAI().setIntentionMoveTo(new Location(v.ex, v.ey, v.ez));
        v.phase = 3;
        v.at = now;
        if (why != null) {
            m.ctx().log().info("shopper " + p.getName() + " leaves: " + why);
        }
    }

    /** The stock store code, like RequestPrivateStoreBuy: the bot pays exactly the price (hook P5-STORE-ADENA keeps all-adena safe). */
    private void buy(Visit v, Player p, Player owner) throws Exception {
        final TradeList list = owner.getSellList();
        final Set<RequestTrade> req = new HashSet<>();
        long total = 0;
        for (StoreVisit.Buy b : v.buys) {
            final TradeItem ti = list.getItems().stream().filter(t -> t.getObjectId() == b.objectId()).findFirst().orElse(null);
            if ((ti == null) || (ti.getPrice() != b.price()) || (ti.getCount() < b.count())) {
                m.ctx().log().info("shopper " + p.getName() + ": " + owner.getName() + "'s store changed, buys nothing");
                return;
            }
            req.add(new RequestTrade(b.objectId(), b.itemId(), (int) b.count(), (int) b.price()));
            total += b.total();
        }
        if ((total <= 0) || (total > Integer.MAX_VALUE)) {
            return;
        }
        p.addAdena(ItemProcessType.REWARD, (int) total, null, false);
        final int res = list.privateStoreBuy(p, req);
        if (res != 0) {
            m.ctx().log().warn("shopper " + p.getName() + " could not buy in " + owner.getName() + "'s store (result " + res + ")");
            return;
        }
        if (list.getItemCount() == 0) {
            owner.setPrivateStoreType(PrivateStoreType.NONE); // like the stock packet: an empty store closes
            owner.broadcastUserInfo();
        }
        final long paid = total;
        final int day = (int) LocalDate.now().toEpochDay();
        traders.spent(paid);
        m.repo().addPlayerDay(owner.getObjectId(), 0, day, paid);
        m.repo().addFlow(day, "bought", paid);
        for (StoreVisit.Buy b : v.buys) {
            m.repo().addResale(b.itemId(), b.count() / 2);
        }
        m.ctx().metrics().counter("bought from players").inc();
        m.ctx().log().info("shopper " + p.getName() + " bought " + describe(v.buys) + " from " + owner.getName() + " for " + paid + " adena");
    }

    private void end(String why) {
        final Visit v = visit;
        visit = null;
        if (v == null) {
            return;
        }
        final Player p = pool.player(v.botId);
        if (p != null) {
            Traders.emptyBag(p);
        }
        pool.leave(v.botId);
        if (why != null) {
            m.ctx().log().info("shopper " + pool.name(v.botId) + " left (" + why + ")");
        }
    }

    void stop() {
        end("server stop");
    }

    /** One of the shopper personas that is not in the world (new ones made as needed). */
    private int persona() throws Exception {
        final List<Integer> ids = new ArrayList<>(m.repo().shoppers());
        ids.removeIf(id -> !pool.known(id));
        if (ids.size() < PERSONAS) {
            final int id = pool.create(new TraderPool.Look(MarketAreas.randomProfession(rnd), rnd.nextBoolean(), ""));
            m.repo().addShopper(id);
            ids.add(id);
        }
        final List<Integer> free = new ArrayList<>();
        for (int id : ids) {
            if (pool.player(id) == null) {
                free.add(id);
            }
        }
        return free.isEmpty() ? -1 : free.get(rnd.nextInt(free.size()));
    }

    private String describe(List<StoreVisit.Buy> buys) {
        final StringBuilder sb = new StringBuilder();
        for (StoreVisit.Buy b : buys) {
            final MarketItem it = m.book().items().get(b.itemId());
            sb.append((sb.length() > 0) ? ", " : "").append(b.count()).append(" x ").append((it == null) ? String.valueOf(b.itemId()) : it.name()).append(" @").append(b.price());
        }
        return sb.toString();
    }
}
