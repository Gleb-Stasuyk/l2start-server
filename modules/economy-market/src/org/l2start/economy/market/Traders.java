package org.l2start.economy.market;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.l2jmobius.gameserver.config.custom.OfflineTradeConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.network.holders.RequestTrade;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.PrivateStoreMsgBuy;
import org.l2jmobius.gameserver.network.serverpackets.PrivateStoreMsgSell;
import org.l2start.bots.trade.TraderPool;
import org.l2start.economy.market.MarketRepository.StoreRow;
import org.l2start.economy.market.model.Assortment;
import org.l2start.economy.market.model.Assortment.Line;
import org.l2start.economy.market.model.MarketBook;
import org.l2start.economy.market.model.MarketItem;
import org.l2start.economy.market.model.PaperStore;
import org.l2start.economy.market.model.StoreSpots;
import org.l2start.economy.market.model.StoreSpots.Spot;
import org.l2start.economy.market.model.StoreTitle;
import org.l2start.economy.market.model.TraderPresence;
import org.l2start.platform.api.StoreDeal;

/**
 * Phase 5b/5c: bot traders in the world. Each store place keeps one trader persona (bots-core service TraderPool,
 * status TRADER) with the same name, kind and sign; it stands at its place with a stock private store (SELL,
 * PACKAGE_SELL or BUY) while a real player is near the market (and a few minutes after), at night only part of them
 * stays with the grey name of an offline store. Away, the store lives "on paper" (model PaperStore). Goods and adena
 * are real items only while the trader stands: created on open, destroyed on close (the bag is empty in the database
 * while it is away), stock offline-trade rows of bots are removed. Loaded only when bots-core is there.
 */
final class Traders {
    static final long REOPEN_AFTER_SOLD_OUT_MS = 10 * 60_000L;
    static final long LEAVE_AFTER_SOLD_OUT_MS = 60_000L;
    static final int OPEN_PER_TICK = 2;

    /** One store place: its trader and store (memory; the database has the same after every close and deal flush). */
    static final class Store {
        final Spot spot;
        StoreRow row;
        List<Line> lines = new ArrayList<>();
        volatile boolean open;
        volatile int charId;
        volatile long soldOutAt;
        long closedAt;
        int destroyed;
        boolean offlineLook;
        boolean geoLogged;
        final Map<Integer, Long> shown = new ConcurrentHashMap<>(); // pieces put in the stock list at the open

        Store(Spot spot) {
            this.spot = spot;
        }

        boolean buy() {
            return (row != null) && "BUY".equals(row.kind());
        }
    }

    private final EconomyMarketModule m;
    private final MarketRepository repo;
    private final TraderPool pool;
    private final StoreSpots spots;
    private final Assortment assortment;
    private final Random rnd = new Random();
    private final Map<String, Store> stores = new ConcurrentHashMap<>(); // spot id
    private final Map<Integer, Store> byChar = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWatched = new HashMap<>();
    private final Map<String, Boolean> loadedTowns = new HashMap<>();
    private final ConcurrentLinkedQueue<Runnable> writes = new ConcurrentLinkedQueue<>();
    private final Set<String> warned = new HashSet<>();
    private volatile boolean selfTested;
    private volatile boolean stopped;

    private Traders(EconomyMarketModule m, TraderPool pool) throws Exception {
        this.m = m;
        this.repo = m.repo();
        this.pool = pool;
        spots = StoreSpots.load(m.ctx().resources().path("data/store-spots.xml"));
        assortment = Assortment.load(m.ctx().resources().path("data/trader-profiles.xml"));
    }

    /** @return the runtime, or null without the bots-core service (module off, or not installed). */
    static Traders start(EconomyMarketModule m) throws Exception {
        final TraderPool pool = m.ctx().services().find(TraderPool.class).orElse(null);
        return (pool == null) ? null : new Traders(m, pool);
    }

    private long cfgMs(String key, long def) {
        try {
            return Math.max(0, m.ctx().config().getLong(key)) * 1000L;
        } catch (RuntimeException e) {
            return def;
        }
    }

    // ---- the task (scheduler thread, every few seconds)

    synchronized void tick() throws Exception {
        if (stopped) {
            return;
        }
        if (!m.marketOn()) {
            closeAll("market off");
            return;
        }
        final long now = System.currentTimeMillis();
        final List<int[]> watchers = pool.watchers();
        final int hour = LocalTime.now().getHour();
        final boolean night = TraderPresence.night(hour);
        final long day = LocalDate.now().toEpochDay();
        final double nightStay = m.ctx().config().getDouble("NightStay");
        final int radius = m.ctx().config().getInt("WatchRadius");
        final long linger = cfgMs("TraderLingerSeconds", 180_000L);
        final Set<String> wantedSpots = new HashSet<>();
        for (String town : m.quota().active().keySet()) {
            final List<Spot> active = spots.active(town, m.quota(), s -> true);
            if (active.isEmpty()) {
                continue;
            }
            loadTown(town);
            long sx = 0;
            long sy = 0;
            for (Spot s : active) {
                sx += s.x();
                sy += s.y();
            }
            if (TraderPresence.watched((int) (sx / active.size()), (int) (sy / active.size()), watchers, radius)) {
                lastWatched.put(town, now);
            }
            final boolean present = TraderPresence.present(now, lastWatched.getOrDefault(town, 0L), linger);
            int opened = 0;
            for (Spot spot : active) {
                final Store s = stores.computeIfAbsent(spot.id(), k -> new Store(spot));
                if (s.open && (s.soldOutAt > 0) && ((now - s.soldOutAt) >= LEAVE_AFTER_SOLD_OUT_MS)) {
                    close(s, "sold out");
                    continue;
                }
                final boolean stays = !night || ((s.row != null) && TraderPresence.staysAtNight(s.row.botId(), day, nightStay));
                final boolean resting = !s.open && (s.soldOutAt > 0) && ((now - s.soldOutAt) < REOPEN_AFTER_SOLD_OUT_MS);
                if (present && stays && !resting) {
                    wantedSpots.add(spot.id());
                    if (!s.open && (opened < OPEN_PER_TICK)) {
                        opened++;
                        try {
                            open(s, now, night);
                        } catch (Exception e) {
                            // one bad place must not stop the others (nor trip the module's circuit breaker every tick)
                            warnOnce("open " + spot.id() + " " + e.getClass().getSimpleName(), "trader store at " + spot.id() + " could not open: " + e);
                            s.soldOutAt = now; // retried after the rest
                        }
                    }
                }
            }
        }
        for (Store s : stores.values()) {
            if (s.open && !wantedSpots.contains(s.spot.id()) && ((s.soldOutAt == 0) || ((now - s.soldOutAt) >= LEAVE_AFTER_SOLD_OUT_MS))) {
                close(s, "nobody near");
            } else if (s.open && (pool.player(s.row.botId()) == null)) {
                close(s, "gone from the world");
            }
        }
        if (m.ctx().config().getBoolean("SelfTest") && !selfTested) {
            selfTest();
        }
    }

    private void loadTown(String town) throws Exception {
        if (loadedTowns.putIfAbsent(town, Boolean.TRUE) != null) {
            return;
        }
        final Map<String, StoreRow> rows = repo.stores(town);
        for (Spot spot : spots.spots(town)) {
            final StoreRow r = rows.get(spot.id());
            if ((r != null) && pool.known(r.botId())) {
                final Store s = stores.computeIfAbsent(spot.id(), k -> new Store(spot));
                s.row = r;
                s.lines = new ArrayList<>(repo.lines(r.botId(), m.book().items()));
            } else if (r != null) {
                repo.dropStore(r.botId()); // its persona is gone: the place gets a new trader
            }
            geoZ(stores.computeIfAbsent(spot.id(), k -> new Store(spot))); // every place: file z against the ground, in the log
        }
    }

    // ---- open

    private void open(Store s, long now, boolean night) throws Exception {
        final Spot spot = s.spot;
        if ((s.row == null) || !pool.known(s.row.botId())) {
            final int botId = pool.create(new TraderPool.Look(spot.classId(), "FEMALE".equalsIgnoreCase(spot.sex()), spot.legacy()));
            s.row = new StoreRow(botId, spot.town(), spot.id(), TraderPresence.kind(spot.kind(), spot.profile(), rnd.nextDouble()), spot.profile(), "", now, now, 0);
            s.lines = new ArrayList<>();
        }
        final int botId = s.row.botId();
        final Assortment.Profile profile = assortment.profile(s.row.profile());
        if (profile == null) {
            warnOnce("profile " + s.row.profile(), "spot " + spot.id() + ": no trader profile '" + s.row.profile() + "'");
            return;
        }
        final MarketBook book = m.book();
        final int stage = m.stage();
        final double greed = pool.greed(botId);
        final boolean buy = s.buy();
        // life on paper since the last look
        final double hours = Math.max(0, (now - s.row.touchedAt()) / 3_600_000.0);
        if (!s.lines.isEmpty() && (hours > 0.01)) {
            final long[] fresh = new long[s.lines.size()];
            for (int k = 0; k < fresh.length; k++) {
                fresh[k] = price(s.lines.get(k).itemId(), buy, greed, stage, now);
            }
            final List<Line> before = s.lines;
            s.lines = new ArrayList<>(PaperStore.age(before, fresh, hours, buy, rnd));
            if (buy) {
                paperFills(botId, before, s.lines);
            }
        }
        String title = s.row.title();
        int linesStart = s.row.linesStart();
        if (PaperStore.needsRestock(s.lines.size(), linesStart)) {
            s.lines = new ArrayList<>(assortment.stock(profile, book, stage, greed, now, rnd, this::cheapestSeller));
            if (!buy) {
                addResale(s, profile, greed, stage, now);
            }
            linesStart = s.lines.size();
            title = StoreTitle.of(profile, s.lines, book.items(), pool.lang(botId), rnd);
        }
        if (s.lines.isEmpty()) {
            warnOnce("empty " + spot.id(), "spot " + spot.id() + ": nothing to put in a " + s.row.profile() + " store at stage " + stage);
            return;
        }
        if (title.isEmpty()) {
            title = StoreTitle.of(profile, s.lines, book.items(), pool.lang(botId), rnd);
        }
        s.row = new StoreRow(botId, spot.town(), spot.id(), s.row.kind(), s.row.profile(), title, s.row.openedAt(), now, linesStart);
        repo.saveStore(s.row, s.lines);
        final int z = geoZ(s);
        s.offlineLook = night;
        s.soldOutAt = 0;
        final String why = pool.enter(botId, spot.x(), spot.y(), z, spot.heading(), TraderPresence.level(botId), p -> openStore(p, s));
        if (why != null) {
            warnOnce("enter " + spot.id(), "trader of " + spot.id() + " could not come: " + why);
        }
    }

    /** Height of the place from geodata (once per place in the log: the place z of the file against the ground). */
    private int geoZ(Store s) {
        final Spot spot = s.spot;
        final GeoEngine geo = GeoEngine.getInstance();
        final boolean has = geo.hasGeoPos(geo.getGeoX(spot.x()), geo.getGeoY(spot.y()));
        final int z = has ? geo.getHeight(spot.x(), spot.y(), spot.z()) : spot.z();
        if (!s.geoLogged) {
            s.geoLogged = true;
            final String msg = "spot " + spot.id() + " (" + spot.legacy() + ") at " + spot.x() + "," + spot.y() + ": file z " + spot.z() + ", " + (has ? ("geodata z " + z) : "no geodata");
            if (has && (Math.abs(z - spot.z()) > 32)) {
                m.ctx().log().warn(msg + " - the place is off the ground by " + (z - spot.z()));
            } else {
                m.ctx().log().info(msg);
            }
        }
        return z;
    }

    /** World thread of the login: real goods, the stock store, the sign. */
    private void openStore(Player p, Store s) {
        try {
            final boolean buy = s.buy();
            final boolean pkg = "PACKAGE".equals(s.row.kind());
            emptyBag(p);
            final int limit = buy ? p.getPrivateBuyStoreLimit() : p.getPrivateSellStoreLimit();
            final List<Line> shown = new ArrayList<>(s.lines.subList(0, Math.min(limit, s.lines.size())));
            s.shown.clear();
            int put = 0;
            if (buy) {
                final int day = (int) LocalDate.now().toEpochDay();
                long wallet = walletFor(s);
                final TradeList list = p.getBuyList();
                list.clear();
                long need = 0;
                for (Line l : shown) {
                    final long room = Math.max(0, dayLimit(l.itemId()) - repo.playerDayMax(l.itemId(), day));
                    final long count = Math.min(Math.min(l.count(), room), (l.price() > 0) ? (wallet - need) / l.price() : 0);
                    if ((count <= 0) || (l.price() > Integer.MAX_VALUE)) {
                        continue;
                    }
                    if (list.addItemByItemId(l.itemId(), (int) Math.min(Integer.MAX_VALUE, count), (int) l.price()) != null) {
                        s.shown.merge(l.itemId(), count, Long::sum);
                        need += count * l.price();
                        put++;
                    }
                }
                if (need > 0) {
                    p.addAdena(ItemProcessType.REWARD, (int) Math.min(Integer.MAX_VALUE, need), null, false);
                }
                list.setTitle(s.row.title());
            } else {
                final TradeList list = p.getSellList();
                list.clear();
                list.setPackaged(pkg);
                for (Line l : shown) {
                    final MarketItem it = m.book().items().get(l.itemId());
                    if ((it == null) || (l.price() > Integer.MAX_VALUE)) {
                        continue;
                    }
                    final int count = it.stackable() ? (int) Math.min(Integer.MAX_VALUE / Math.max(1, l.price()), l.count()) : 1;
                    if (count <= 0) {
                        continue;
                    }
                    final Item item = p.getInventory().addItem(ItemProcessType.REWARD, l.itemId(), count, p, null);
                    if ((item != null) && (list.addItem(item.getObjectId(), count, (int) l.price()) != null)) {
                        s.shown.merge(l.itemId(), (long) count, Long::sum);
                        put++;
                    }
                }
                list.setTitle(s.row.title());
            }
            if (put == 0) {
                emptyBag(p);
                s.charId = p.getObjectId();
                s.open = true;
                s.soldOutAt = System.currentTimeMillis() - LEAVE_AFTER_SOLD_OUT_MS; // leaves on the next tick
                return;
            }
            p.sitDown();
            p.setPrivateStoreType(buy ? PrivateStoreType.BUY : (pkg ? PrivateStoreType.PACKAGE_SELL : PrivateStoreType.SELL));
            if (s.offlineLook) {
                p.getAppearance().setNameColor(OfflineTradeConfig.OFFLINE_NAME_COLOR);
            }
            p.broadcastUserInfo();
            p.broadcastPacket(buy ? new PrivateStoreMsgBuy(p) : new PrivateStoreMsgSell(p));
            s.charId = p.getObjectId();
            byChar.put(p.getObjectId(), s);
            s.open = true;
            m.ctx().log().info("trader " + p.getName() + " opened " + s.row.kind() + " '" + s.row.title() + "' at " + s.spot.id() + " (" + put + " lines" + (s.offlineLook ? ", night: offline look" : "") + ")");
            if (p.isInsideZone(ZoneId.NO_STORE)) {
                m.ctx().log().warn("spot " + s.spot.id() + " is inside a no-store zone");
            }
            m.ctx().metrics().counter("stores opened").inc();
        } catch (Exception e) {
            m.ctx().log().warn("trader store at " + s.spot.id() + " failed: " + e);
            s.open = true;
            s.charId = p.getObjectId();
            s.soldOutAt = System.currentTimeMillis() - LEAVE_AFTER_SOLD_OUT_MS;
        }
    }

    private long walletFor(Store s) throws Exception {
        long cost = 0;
        for (Line l : s.lines) {
            cost += l.count() * l.price();
        }
        final long cap = Math.max(10_000, cost * 3);
        long w = repo.wallet(s.row.botId());
        if (w < 0) {
            w = cap; // a new buyer starts with three times its list
        } else {
            final double days = Math.max(0, (System.currentTimeMillis() - s.row.touchedAt()) / 86_400_000.0);
            w = Math.min(cap, w + Math.round((cap / 2.0) * days)); // its other business brings money back
        }
        repo.setWallet(s.row.botId(), w);
        return w;
    }

    private long dayLimit(int itemId) {
        final long base = Math.max(1, m.ctx().config().getInt("SellToBotsItemDay"));
        final MarketItem it = m.book().items().get(itemId);
        return ((it != null) && it.stackable() && (it.price() <= 1000)) ? base * 10 : base;
    }

    private long price(int itemId, boolean buy, double greed, int stage, long now) {
        final MarketItem it = m.book().items().get(itemId);
        if (it == null) {
            return 0;
        }
        final MarketBook.Quote q = m.book().quote(itemId, stage, now);
        if (buy) {
            final long cheapest = cheapestSeller(itemId);
            return m.book().model().buyPrice(it, q.market(), greed, (cheapest > 0) ? Math.min(cheapest, q.lowSell()) : q.lowSell());
        }
        return m.book().model().sellPrice(it, q.market(), greed);
    }

    /** The cheapest bot sell line of an item in the stores known (0 = none): a bot buyer never bids above it. */
    private long cheapestSeller(int itemId) {
        long best = 0;
        for (Store s : stores.values()) {
            if ((s.row == null) || s.buy()) {
                continue;
            }
            for (Line l : s.lines) {
                if ((l.itemId() == itemId) && (l.count() > 0) && ((best == 0) || (l.price() < best))) {
                    best = l.price();
                }
            }
        }
        return best;
    }

    /** Invisible sellers filled a buy store on paper: its money went, half of the goods come back for resale. */
    private void paperFills(int botId, List<Line> before, List<Line> after) throws Exception {
        final Map<Integer, Long> left = new HashMap<>();
        for (Line l : after) {
            left.put(l.itemId(), l.count());
        }
        long paid = 0;
        for (Line l : before) {
            final long got = l.count() - left.getOrDefault(l.itemId(), 0L);
            if (got > 0) {
                paid += got * l.price();
                repo.addResale(l.itemId(), got / 2);
            }
        }
        if (paid > 0) {
            final long w = repo.wallet(botId);
            if (w >= 0) {
                repo.setWallet(botId, w - paid);
            }
        }
    }

    /** A sell store restock takes goods bot buyers bought, when they fit its theme. */
    private void addResale(Store s, Assortment.Profile profile, double greed, int stage, long now) throws Exception {
        final Map<Integer, Long> pool = repo.resale();
        if (pool.isEmpty()) {
            return;
        }
        final Set<Integer> fits = new HashSet<>();
        for (MarketItem it : assortment.candidates(profile, m.book().items(), stage)) {
            fits.add(it.id());
        }
        for (Map.Entry<Integer, Long> e : pool.entrySet()) {
            if ((s.lines.size() >= profile.maxLines()) || !fits.contains(e.getKey()) || s.lines.stream().anyMatch(l -> l.itemId() == e.getKey())) {
                continue;
            }
            final MarketItem it = m.book().items().get(e.getKey());
            final long count = it.stackable() ? Math.min(e.getValue(), 1000) : 1;
            final long price = price(it.id(), false, greed, stage, now);
            if (price > 0) {
                s.lines.add(new Line(it.id(), it.name(), count, price));
                repo.addResale(it.id(), -count);
            }
        }
    }

    // ---- close

    /**
     * Server stop, market off: first every trader out of the world (store closed, bag emptied, logged out), then the
     * database, so all of them are out before bots-core stops even on a slow database (the platform gives a module
     * a few seconds for its stop).
     */
    /** Server stop: no tick opens a store again (the platform stops the module's tasks only after its stop). */
    synchronized void stop() {
        stopped = true;
        closeAll("server stop");
    }

    synchronized void closeAll(String reason) {
        final long start = System.currentTimeMillis();
        final List<Store> closed = new ArrayList<>();
        for (Store s : stores.values()) {
            if (s.open) {
                try {
                    leaveWorld(s);
                    closed.add(s);
                } catch (Exception e) {
                    m.ctx().log().warn("closing the store at " + s.spot.id() + " failed: " + e);
                }
            }
        }
        for (Store s : closed) {
            try {
                saveClosed(s, reason);
            } catch (Exception e) {
                m.ctx().log().warn("saving the store at " + s.spot.id() + " failed: " + e);
            }
        }
        if (!closed.isEmpty()) {
            m.ctx().log().info("traders: " + closed.size() + " stores closed (" + reason + ") in " + (System.currentTimeMillis() - start) + " ms");
        }
    }

    private void close(Store s, String reason) throws Exception {
        leaveWorld(s);
        saveClosed(s, reason);
    }

    /** In the world: the lines after the deals, store closed, goods and adena destroyed, logged out. */
    private void leaveWorld(Store s) {
        final int botId = s.row.botId();
        final Player p = pool.player(botId);
        if (p != null) {
            synchronized (s) {
                s.lines = remaining(s, p);
            }
            // goods first, store type untouched: the stock setPrivateStoreType(NONE) logs out a store owner without a
            // client at once (OfflineDisconnectFinished) and would save the goods into the database first
            s.destroyed = emptyBag(p);
            p.getSellList().clear();
            p.getBuyList().clear();
            byChar.remove(p.getObjectId());
        }
        s.open = false;
        s.closedAt = System.currentTimeMillis();
        pool.leave(botId);
    }

    private void saveClosed(Store s, String reason) throws Exception {
        final int botId = s.row.botId();
        s.row = new StoreRow(botId, s.row.town(), s.row.spot(), s.row.kind(), s.row.profile(), s.row.title(), s.row.openedAt(), s.closedAt, s.row.linesStart());
        repo.saveStore(s.row, s.lines);
        int stale = 0;
        if (s.charId != 0) {
            repo.clearBotOfflineTrade(String.valueOf(s.charId));
            stale = repo.clearBag(s.charId); // out of the world now: nothing of its goods may stay in the database
        }
        m.ctx().log().info("trader " + pool.name(botId) + " left " + s.spot.id() + " (" + reason + ", " + s.lines.size() + " lines on paper, " + s.destroyed + " bag items destroyed"
            + ((stale > 0) ? ", " + stale + " bag rows still in the database removed" : "") + ")");
    }

    /** The lines after the deals: each line loses what left the stock list since the open (lines not shown stay). */
    private static List<Line> remaining(Store s, Player p) {
        final TradeList list = s.buy() ? p.getBuyList() : p.getSellList();
        final Map<Integer, Long> now = new HashMap<>();
        for (TradeItem ti : list.getItems()) {
            now.merge(ti.getItem().getId(), (long) ti.getCount(), Long::sum);
        }
        final List<Line> out = new ArrayList<>();
        for (Line l : s.lines) {
            final Long shown = s.shown.get(l.itemId());
            final long gone = (shown == null) ? 0 : Math.max(0, shown - now.getOrDefault(l.itemId(), 0L));
            if ((l.count() - gone) > 0) {
                out.add(new Line(l.itemId(), l.name(), l.count() - gone, l.price()));
            }
        }
        return out;
    }

    /** Everything in the bag that is not worn: goods and adena exist only while the store stands. */
    private static int emptyBag(Player p) {
        int n = 0;
        for (Item it : new ArrayList<>(p.getInventory().getItems())) {
            if (!it.isEquipped() && (p.getInventory().destroyItem(ItemProcessType.DESTROY, it, p, null) != null)) {
                n++;
            }
        }
        return n;
    }

    // ---- deals (game thread: memory only, the database in flush)

    void onDeal(StoreDeal d) {
        final Store s = byChar.get(d.ownerId());
        if ((s == null) || !s.open) {
            return;
        }
        final Player p = pool.player(s.row.botId());
        final int botId = s.row.botId();
        final long total = d.count() * d.price();
        final boolean buy = d.buyStore();
        writes.add(() -> {
            try {
                final long w = repo.wallet(botId);
                repo.setWallet(botId, Math.max(0, w) + (buy ? -total : total));
                if (buy) {
                    repo.addResale(d.itemId(), d.count() / 2 + ((d.count() % 2 == 1 && rnd.nextBoolean()) ? 1 : 0));
                    if (!d.sellerBot()) {
                        repo.addPlayerDay(d.sellerId(), d.itemId(), (int) LocalDate.now().toEpochDay(), d.count());
                    }
                }
                repo.clearBotOfflineTrade(String.valueOf(d.ownerId()));
            } catch (Exception e) {
                m.ctx().log().warn("trader deal write failed: " + e);
            }
        });
        if (p != null) {
            final TradeList list = buy ? p.getBuyList() : p.getSellList();
            if (list.getItemCount() == 0) {
                // the stock packet now sets the store to NONE, which logs a client-less owner out at once: close on
                // the next tick (bag rows the stock save wrote are removed after the logout)
                s.soldOutAt = System.currentTimeMillis() - LEAVE_AFTER_SOLD_OUT_MS;
            }
        }
        m.ctx().metrics().counter(buy ? "trader buys" : "trader sales").inc();
    }

    /** Scheduler thread: deal writes, then each open store's lines (so a crash loses at most a few seconds). */
    void flush() {
        Runnable w;
        while ((w = writes.poll()) != null) {
            w.run();
        }
    }

    /** Onenable/ready: what a crash may have left. */
    void cleanupAtStart() throws Exception {
        final int bags = repo.clearTraderBags();
        final int rows = repo.clearBotOfflineTrade(null);
        if ((bags + rows) > 0) {
            m.ctx().log().info("traders: " + bags + " bag items and " + rows + " offline-trade rows of bots left from the last run removed");
        }
    }

    int openCount() {
        int n = 0;
        for (Store s : stores.values()) {
            if (s.open) {
                n++;
            }
        }
        return n;
    }

    List<String> describe() {
        final List<String> out = new ArrayList<>();
        for (Store s : stores.values()) {
            if (s.row != null) {
                out.add(s.spot.id() + " " + pool.name(s.row.botId()) + " " + s.row.kind() + " " + s.row.profile() + " '" + s.row.title() + "' " + s.lines.size() + " lines" + (s.open ? " OPEN" : ""));
            }
        }
        out.sort(null);
        return out;
    }

    private void warnOnce(String key, String msg) {
        if (warned.add(key)) {
            m.ctx().log().warn(msg);
        }
    }

    // ---- self test (smoke): a sale and a purchase through the stock TradeList, no duplicated goods

    private void selfTest() {
        Store sell = null;
        Store buyS = null;
        Store other = null;
        for (Store s : stores.values()) {
            if (!s.open || (s.soldOutAt > 0) || (pool.player(s.row.botId()) == null)) {
                continue;
            }
            if ("SELL".equals(s.row.kind()) && (sell == null)) {
                sell = s;
            } else if (s.buy() && (buyS == null)) {
                buyS = s;
            } else if (other == null) {
                other = s;
            }
        }
        if ((sell == null) || (buyS == null) || (other == null)) {
            return;
        }
        selfTested = true;
        try {
            selfTest(sell, buyS, other);
        } catch (RuntimeException e) {
            m.ctx().log().warn("SELFTEST FAILED: " + e, e);
        }
    }

    private void selfTest(Store sell, Store buyS, Store other) {
        final Player seller = pool.player(sell.row.botId());
        final Player visitor = pool.player(other.row.botId());
        final Player buyer = pool.player(buyS.row.botId());
        // 1. the visitor buys one piece of the first line of the sell store
        final TradeItem ti = seller.getSellList().getItems().iterator().next();
        final int itemId = ti.getItem().getId();
        final int before = ti.getCount();
        final long visitorHad = count(visitor, itemId);
        visitor.addAdena(ItemProcessType.REWARD, ti.getPrice() + 1000, null, false); // not all of it: the stock code needs the buyer's adena item after paying
        final int res = seller.getSellList().privateStoreBuy(visitor, new HashSet<>(List.of(new RequestTrade(ti.getObjectId(), itemId, 1, ti.getPrice()))));
        final int after = seller.getSellList().getItems().stream().filter(t -> t.getItem().getId() == itemId).mapToInt(TradeItem::getCount).sum();
        final long sellerLeft = count(seller, itemId);
        final boolean saleOk = (res == 0) && (count(visitor, itemId) == (visitorHad + 1)) && (after == (before - 1)) && (sellerLeft == after);
        m.ctx().log().info("SELFTEST sell deal " + (saleOk ? "ok" : "FAILED") + ": item " + itemId + " store " + before + " -> " + after + ", seller bag " + sellerLeft + ", buyer bag " + visitorHad + " -> " + count(visitor, itemId) + " (result " + res + ")");
        // 2. the visitor sells one piece to the buy store
        final TradeItem bi = buyer.getBuyList().getItems().iterator().next();
        final int bid = bi.getItem().getId();
        final int want = bi.getCount();
        final long buyerHad = count(buyer, bid);
        final int adenaBefore = buyer.getAdena();
        final Item piece = visitor.getInventory().addItem(ItemProcessType.REWARD, bid, 1, visitor, null);
        final boolean sold = buyer.getBuyList().privateStoreSell(visitor, new RequestTrade[] { new RequestTrade(piece.getObjectId(), bid, 1, bi.getPrice()) });
        final int wantAfter = buyer.getBuyList().getItems().stream().filter(t -> t.getItem().getId() == bid).mapToInt(TradeItem::getCount).sum();
        final boolean buyOk = sold && (count(buyer, bid) == (buyerHad + 1)) && (wantAfter == (want - 1)) && (buyer.getAdena() == (adenaBefore - bi.getPrice())) && (count(visitor, bid) == 0 || visitor.getInventory().getItemByObjectId(piece.getObjectId()) == null);
        m.ctx().log().info("SELFTEST buy deal " + (buyOk ? "ok" : "FAILED") + ": item " + bid + " wants " + want + " -> " + wantAfter + ", buyer bag " + buyerHad + " -> " + count(buyer, bid) + ", adena " + adenaBefore + " -> " + buyer.getAdena());
        // 3. a buyer pays with ALL his adena (stock bug fixed by hook P5-STORE-ADENA: the adena went, the item did not)
        final TradeItem t3 = seller.getSellList().getItems().stream().filter(t -> t.getCount() > 0).findFirst().orElse(null);
        if (t3 == null) {
            m.ctx().log().info("SELFTEST all-adena deal skipped: the sell store is empty");
            return;
        }
        final Item adena = visitor.getInventory().getAdenaInstance();
        if (adena != null) {
            visitor.getInventory().destroyItem(ItemProcessType.DESTROY, adena, visitor, null);
        }
        visitor.addAdena(ItemProcessType.REWARD, t3.getPrice(), null, false);
        final int id3 = t3.getItem().getId();
        final long had3 = count(visitor, id3);
        final int res3 = seller.getSellList().privateStoreBuy(visitor, new HashSet<>(List.of(new RequestTrade(t3.getObjectId(), id3, 1, t3.getPrice()))));
        final boolean allOk = (res3 == 0) && (count(visitor, id3) == (had3 + 1)) && (visitor.getAdena() == 0);
        m.ctx().log().info("SELFTEST all-adena deal " + (allOk ? "ok" : "FAILED") + ": item " + id3 + ", buyer bag " + had3 + " -> " + count(visitor, id3) + ", buyer adena " + visitor.getAdena() + " (result " + res3 + ")");
    }

    private static long count(Player p, int itemId) {
        long n = 0;
        for (Item it : p.getInventory().getItems()) {
            if ((it.getId() == itemId) && !it.isEquipped()) {
                n += it.getCount();
            }
        }
        return n;
    }
}
