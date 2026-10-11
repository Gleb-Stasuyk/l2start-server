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
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.mechanics.stats.Stat;
import org.l2jmobius.gameserver.mechanics.stats.functions.FuncAdd;
import org.l2jmobius.gameserver.network.holders.RequestTrade;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.PrivateStoreMsgBuy;
import org.l2jmobius.gameserver.network.serverpackets.PrivateStoreMsgSell;
import org.l2jmobius.gameserver.network.serverpackets.RecipeShopMsg;
import org.l2start.bots.trade.TraderPool;
import org.l2start.economy.market.MarketRepository.StoreRow;
import org.l2start.economy.market.model.Assortment;
import org.l2start.economy.market.model.Assortment.Line;
import org.l2start.economy.market.model.MarketAreas;
import org.l2start.economy.market.model.MarketBook;
import org.l2start.economy.market.model.MarketItem;
import org.l2start.economy.market.model.PaperStore;
import org.l2start.economy.market.model.SpotGenerator;
import org.l2start.economy.market.model.StoreSpots;
import org.l2start.economy.market.model.StoreSpots.Spot;
import org.l2start.economy.market.model.StoreTitle;
import org.l2start.economy.market.model.TraderPresence;
import org.l2start.platform.api.StoreDeal;

/**
 * Phase 5b/5c/5e: static bot traders (decision of 12.10.2026). Each store place of a town (generated once, scattered,
 * kept in the database) has one trader persona (bots-core service TraderPool, status TRADER) with the same name, kind
 * and sign. They come in at the server start, independent of players and of the presence director, sit with a stock
 * store (SELL, PACKAGE_SELL, BUY, MANUFACTURE for the dwarf crafters) and stay; a fixed service trader (4UDO) sells
 * shots at the NPC price. The store lives on in place: every {@code RestockMinutes} the invisible buyers of the paper
 * life take their part and a store that sold most of its goods is refilled. At night part of the traders get the grey
 * name of an offline store. Goods and adena are real items only while the trader stands: created on open, destroyed
 * before any logout save, stock offline-trade rows of bots removed. Loaded only when bots-core is there.
 */
final class Traders {
    static final long REOPEN_AFTER_LOGOUT_MS = 30_000L;
    static final int OPEN_PER_TICK = 4;
    static final int SERVICE_STOCK = 200_000;
    static final int SERVICE_EXTRA_SLOTS = 2;

    /** One store place: its trader and store (memory; the database has the same after every change). */
    static final class Store {
        final Spot spot;
        final int[] serviceItems; // a service trader: what it sells at the NPC price (null = a market place)
        StoreRow row;
        List<Line> lines = new ArrayList<>();
        volatile boolean open;
        volatile int charId;
        volatile long soldOutAt;
        long reopenAt;
        boolean forceRestock;
        long closedAt;
        int destroyed;
        boolean offlineLook;
        boolean geoLogged;
        final Map<Integer, Long> shown = new ConcurrentHashMap<>(); // pieces put in the stock list at the open

        Store(Spot spot, int[] serviceItems) {
            this.spot = spot;
            this.serviceItems = serviceItems;
        }

        String kind() {
            return (row != null) ? row.kind() : spot.kind();
        }

        boolean buy() {
            return "BUY".equals(kind());
        }

        boolean craft() {
            return "CRAFT".equals(kind());
        }

        boolean service() {
            return serviceItems != null;
        }
    }

    private final EconomyMarketModule m;
    private final MarketRepository repo;
    private final TraderPool pool;
    private final StoreSpots legacy;
    private final MarketAreas areas;
    private final Assortment assortment;
    private final Crafts crafts;
    private final Random rnd = new Random();
    private final Map<String, Store> stores = new ConcurrentHashMap<>(); // spot id
    private final Map<Integer, Store> byChar = new ConcurrentHashMap<>();
    private final Map<String, List<Store>> towns = new HashMap<>();
    private final Map<String, Integer> townWant = new HashMap<>(); // the quota the stores of a town were made for
    private final ConcurrentLinkedQueue<Runnable> writes = new ConcurrentLinkedQueue<>();
    private final Set<String> warned = new HashSet<>();
    private volatile boolean selfTested;
    private volatile boolean stopped;
    private Boolean lastNight;

    private Traders(EconomyMarketModule m, TraderPool pool) throws Exception {
        this.m = m;
        this.repo = m.repo();
        this.pool = pool;
        legacy = StoreSpots.load(m.ctx().resources().path("data/store-spots.xml"));
        areas = MarketAreas.load(m.ctx().resources().path("data/market-areas.xml"));
        assortment = Assortment.load(m.ctx().resources().path("data/trader-profiles.xml"));
        crafts = new Crafts(m);
    }

    /** @return the runtime, or null without the bots-core service (module off, or not installed). */
    static Traders start(EconomyMarketModule m) throws Exception {
        final TraderPool pool = m.ctx().services().find(TraderPool.class).orElse(null);
        return (pool == null) ? null : new Traders(m, pool);
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
        final boolean night = TraderPresence.night(LocalTime.now().getHour());
        final long day = LocalDate.now().toEpochDay();
        final double nightStay = m.ctx().config().getDouble("NightStay");
        final long restockMs = Math.max(1, m.ctx().config().getInt("RestockMinutes")) * 60_000L;
        final Set<String> wanted = new HashSet<>();
        int opened = 0;
        boolean refreshed = false;
        for (String town : m.quota().active().keySet()) {
            for (Store s : town(town)) {
                wanted.add(s.spot.id());
                final Player p = s.open ? pool.player(s.row.botId()) : null;
                if (s.open && ((p == null) || !p.isOnline())) {
                    // the stock store code logged it out (a player bought it empty: store NONE without a client)
                    close(s, "sold out, logged out by the stock store code");
                    s.reopenAt = now + REOPEN_AFTER_LOGOUT_MS;
                    s.forceRestock = true;
                    continue;
                }
                if (s.open && (s.soldOutAt > 0)) {
                    s.forceRestock = true;
                    refreshInPlace(s, p, now, "sold out");
                    continue;
                }
                if (s.open && !refreshed && ((now - s.row.touchedAt()) >= restockMs)) {
                    refreshed = true; // one refresh per tick: spread over time
                    refreshInPlace(s, p, now, "restock timer");
                    continue;
                }
                if (!s.open && (now >= s.reopenAt) && (opened < OPEN_PER_TICK)) {
                    opened++;
                    try {
                        open(s, now, night, day, nightStay);
                    } catch (Exception e) {
                        // one bad place must not stop the others (nor trip the module's circuit breaker every tick)
                        warnOnce("open " + s.spot.id() + " " + e.getClass().getSimpleName(), "trader store at " + s.spot.id() + " could not open: " + e);
                        s.reopenAt = now + (10 * 60_000L);
                    }
                }
            }
        }
        for (Store s : stores.values()) {
            if (s.open && !wanted.contains(s.spot.id())) {
                close(s, "place no longer used (StoresPerTown)");
            }
        }
        if ((lastNight == null) || (lastNight != night)) {
            lastNight = night;
            for (Store s : stores.values()) {
                final Player p = s.open ? pool.player(s.row.botId()) : null;
                if (p != null) {
                    applyLook(s, p, night && TraderPresence.staysAtNight(s.row.botId(), day, nightStay));
                }
            }
        }
        if (m.ctx().config().getBoolean("SelfTest") && !selfTested) {
            selfTest();
        }
    }

    private void applyLook(Store s, Player p, boolean offline) {
        if (s.offlineLook == offline) {
            return;
        }
        s.offlineLook = offline;
        p.getAppearance().setNameColor(offline ? OfflineTradeConfig.OFFLINE_NAME_COLOR : 0xFFFFFF);
        p.broadcastUserInfo();
    }

    /** The stores of a town: generated places (once, then from the database) plus its service traders. */
    private List<Store> town(String town) throws Exception {
        final List<Store> have = towns.get(town);
        final int want = m.quota().stores(town);
        if ((have != null) && townWant.getOrDefault(town, -1).equals(want)) {
            return have;
        }
        final List<Spot> places = new ArrayList<>(repo.spots(town));
        if (places.size() < want) {
            places.addAll(generate(town, places, want - places.size()));
        }
        final Map<String, StoreRow> rows = repo.stores(town);
        final List<Store> out = new ArrayList<>();
        for (MarketAreas.Service sv : areas.services(town)) {
            out.add(attach(sv.spot(), sv.items(), rows));
        }
        for (Spot spot : places.subList(0, Math.min(want, places.size()))) {
            out.add(attach(spot, null, rows));
        }
        towns.put(town, out);
        townWant.put(town, want);
        m.ctx().log().info("traders: " + ((areas.area(town) != null) ? areas.area(town).town() : town) + " has " + (out.size() - areas.services(town).size()) + " store places and " + areas.services(town).size() + " service traders");
        return out;
    }

    /** The store of a place with its trader from the database (a trader whose theme changed keeps its persona). */
    private Store attach(Spot spot, int[] serviceItems, Map<String, StoreRow> rows) throws Exception {
        final Store s = stores.computeIfAbsent(spot.id(), k -> new Store(spot, serviceItems));
        if (s.row == null) {
            final StoreRow r = rows.get(spot.id());
            if ((r != null) && pool.known(r.botId())) {
                if (r.profile().equals(spot.profile())) {
                    s.row = r;
                    s.lines = new ArrayList<>(repo.lines(r.botId(), m.book().items()));
                } else {
                    s.row = new StoreRow(r.botId(), spot.town(), spot.id(), TraderPresence.kind(spot.kind(), spot.profile(), rnd.nextDouble()), spot.profile(), "", r.openedAt(), r.touchedAt(), 0);
                }
            } else if (r != null) {
                repo.dropStore(r.botId());
            }
        }
        geoZ(s);
        return s;
    }

    /** New places for a town (seeded; kept in the database at once). */
    private List<Spot> generate(String town, List<Spot> kept, int count) throws Exception {
        final SpotGenerator.Area a = areas.area(town);
        if (a == null) {
            warnOnce("area " + town, "traders: no market area for " + town + " in data/market-areas.xml");
            return List.of();
        }
        final GeoEngine geo = GeoEngine.getInstance();
        final boolean hasGeo = geo.hasGeo(a.cx(), a.cy());
        final SpotGenerator.Ground ground = new SpotGenerator.Ground() {
            @Override
            public Integer z(int x, int y, int z) {
                return hasGeo ? (geo.hasGeo(x, y) ? Integer.valueOf(geo.getHeight(x, y, z)) : null) : Integer.valueOf(z);
            }

            @Override
            public boolean walk(int x1, int y1, int z1, int x2, int y2, int z2) {
                return !hasGeo || geo.canMoveToTarget(x1, y1, z1, x2, y2, z2, 0);
            }
        };
        final List<SpotGenerator.Blocker> blockers = new ArrayList<>();
        for (WorldObject o : World.getVisibleObjects()) {
            if ((o instanceof Npc npc) && (o.getX() >= (a.minX() - a.doorGap())) && (o.getX() <= (a.maxX() + a.doorGap())) && (o.getY() >= (a.minY() - a.doorGap()))
                && (o.getY() <= (a.maxY() + a.doorGap()))) {
                final boolean door = (npc instanceof org.l2jmobius.gameserver.entity.actor.instance.Teleporter) || (npc instanceof org.l2jmobius.gameserver.entity.actor.instance.Warehouse);
                blockers.add(new SpotGenerator.Blocker(o.getX(), o.getY(), door ? a.doorGap() : a.npcGap()));
            }
        }
        for (MarketAreas.Service sv : areas.services(town)) {
            blockers.add(new SpotGenerator.Blocker(sv.spot().x(), sv.spot().y(), a.spacing()));
        }
        final List<SpotGenerator.Point> keptPts = new ArrayList<>();
        for (Spot s : kept) {
            keptPts.add(new SpotGenerator.Point(s.x(), s.y(), s.z(), s.heading()));
        }
        final long seed = m.seedFor(town) + kept.size();
        final Random r = new Random(seed);
        final List<SpotGenerator.Point> pts = SpotGenerator.generate(a, keptPts, count, blockers, ground, r, 60_000);
        final List<Spot> legacyLooks = legacy.spots(town).stream().filter(sp -> !"4UDO".equals(sp.legacy())).toList();
        final List<Spot> plan = MarketAreas.plan(a.town(), pts, kept.size(), legacyLooks, r);
        for (int k = 0; k < plan.size(); k++) {
            repo.addSpot(plan.get(k), kept.size() + k);
        }
        m.ctx().log().info("traders: " + plan.size() + " new store places generated in " + a.town() + " (" + (hasGeo ? "geodata" : "no geodata: flat") + ", " + blockers.size() + " NPCs kept clear"
            + ((plan.size() < count) ? ", only " + plan.size() + " of " + count + " fit" : "") + ")");
        return plan;
    }

    // ---- open

    private void open(Store s, long now, boolean night, long day, double nightStay) throws Exception {
        final Spot spot = s.spot;
        if ((s.row == null) || !pool.known(s.row.botId())) {
            final int botId = adoptOrCreate(spot);
            s.row = new StoreRow(botId, spot.town(), spot.id(), s.service() ? "SERVICE" : TraderPresence.kind(spot.kind(), spot.profile(), rnd.nextDouble()), spot.profile(), "", now, now, 0);
            s.lines = new ArrayList<>();
        }
        final int botId = s.row.botId();
        if (!s.craft()) {
            prepareLines(s, now);
            if (s.lines.isEmpty()) {
                warnOnce("empty " + spot.id(), "spot " + spot.id() + ": nothing to put in a " + s.row.profile() + " store at stage " + m.stage());
                s.reopenAt = now + (10 * 60_000L);
                return;
            }
        }
        repo.saveStore(s.row, s.craft() ? List.of() : s.lines); // the persona keeps its place even after a crash
        final int z = geoZ(s);
        final boolean offline = night && TraderPresence.staysAtNight(botId, day, nightStay);
        s.offlineLook = false;
        s.soldOutAt = 0;
        final String why = pool.enter(botId, spot.x(), spot.y(), z, spot.heading(), TraderPresence.level(botId), p -> {
            openStore(p, s);
            applyLook(s, p, offline);
        });
        if (why != null) {
            warnOnce("enter " + spot.id(), "trader of " + spot.id() + " could not come: " + why);
            s.reopenAt = now + (10 * 60_000L);
        }
    }

    /**
     * A persona for a place: a trader with the wanted name that stands at another place (an older layout) gives it up,
     * so 4UDO and the former Giran traders keep their names; else a new persona.
     */
    private int adoptOrCreate(Spot spot) throws Exception {
        if ((spot.legacy() != null) && !spot.legacy().isEmpty()) {
            for (StoreRow r : repo.allStores()) {
                if (!r.spot().equals(spot.id()) && pool.known(r.botId()) && spot.legacy().equalsIgnoreCase(pool.name(r.botId()))) {
                    final Store other = stores.get(r.spot());
                    if ((other != null) && other.open) {
                        continue;
                    }
                    if (other != null) {
                        other.row = null;
                        other.lines = new ArrayList<>();
                    }
                    repo.dropStore(r.botId());
                    m.ctx().log().info("trader " + spot.legacy() + " moves from " + r.spot() + " to " + spot.id());
                    return r.botId();
                }
            }
        }
        return pool.create(new TraderPool.Look(spot.classId(), "FEMALE".equalsIgnoreCase(spot.sex()), spot.legacy()));
    }

    /** Lines of a sell, package, buy or service store now: the paper life since the last look, refill when needed. */
    private void prepareLines(Store s, long now) throws Exception {
        final int botId = s.row.botId();
        final MarketBook book = m.book();
        final int stage = m.stage();
        if (s.service()) {
            final List<Line> lines = new ArrayList<>();
            for (int id : s.serviceItems) {
                final MarketItem it = book.items().get(id);
                if ((it != null) && (it.price() > 0)) {
                    lines.add(new Line(id, it.name(), SERVICE_STOCK, it.price())); // the NPC price, a full stock every time
                }
            }
            s.lines = lines;
            final String title = "RU".equals(pool.lang(botId)) ? "Соски, BSpS D-B по цене NPC" : "SS + BSpS D/C/B at NPC price";
            s.row = new StoreRow(botId, s.row.town(), s.row.spot(), "SERVICE", s.row.profile(), StoreTitle.fit(title), s.row.openedAt(), now, lines.size());
            return;
        }
        final Assortment.Profile profile = assortment.profile(s.row.profile());
        if (profile == null) {
            warnOnce("profile " + s.row.profile(), "spot " + s.spot.id() + ": no trader profile '" + s.row.profile() + "'");
            s.lines = new ArrayList<>();
            return;
        }
        final double greed = pool.greed(botId);
        final boolean buy = s.buy();
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
        if (s.forceRestock || PaperStore.needsRestock(s.lines.size(), linesStart)) {
            s.forceRestock = false;
            s.lines = new ArrayList<>(assortment.stock(profile, book, stage, greed, now, rnd, this::cheapestSeller));
            if (!buy) {
                addResale(s, profile, greed, stage, now);
            }
            linesStart = s.lines.size();
            title = StoreTitle.of(profile, s.lines, book.items(), pool.lang(botId), rnd);
        }
        if (title.isEmpty() && !s.lines.isEmpty()) {
            title = StoreTitle.of(profile, s.lines, book.items(), pool.lang(botId), rnd);
        }
        s.row = new StoreRow(botId, s.row.town(), s.row.spot(), s.row.kind(), s.row.profile(), title, s.row.openedAt(), now, linesStart);
    }

    /** In place, without a logout: what was sold leaves the lines, the paper life runs, the store opens again refilled. */
    private void refreshInPlace(Store s, Player p, long now, String why) throws Exception {
        synchronized (s) {
            if (!s.craft()) {
                s.lines = remaining(s, p);
            }
        }
        if (s.craft()) {
            s.row = new StoreRow(s.row.botId(), s.row.town(), s.row.spot(), s.row.kind(), s.row.profile(), s.row.title(), s.row.openedAt(), now, s.row.linesStart());
            s.lines = new ArrayList<>(); // recipes are chosen again (stage, level)
        } else {
            prepareLines(s, now);
            repo.saveStore(s.row, s.lines);
        }
        s.soldOutAt = 0;
        openStore(p, s);
        m.ctx().log().info("trader " + p.getName() + " refilled at " + s.spot.id() + " (" + why + ", " + s.lines.size() + " lines)");
    }

    /** Height of the place from geodata (once per place in the log: the place z against the ground). */
    private int geoZ(Store s) {
        final Spot spot = s.spot;
        final GeoEngine geo = GeoEngine.getInstance();
        final boolean has = geo.hasGeoPos(geo.getGeoX(spot.x()), geo.getGeoY(spot.y()));
        final int z = has ? geo.getHeight(spot.x(), spot.y(), spot.z()) : spot.z();
        if (!s.geoLogged) {
            s.geoLogged = true;
            final String msg = "spot " + spot.id() + " (" + (spot.legacy().isEmpty() ? spot.kind() + " " + spot.profile() : spot.legacy()) + ") at " + spot.x() + "," + spot.y() + ": z " + spot.z() + ", "
                + (has ? ("geodata z " + z) : "no geodata");
            if (has && (Math.abs(z - spot.z()) > 32)) {
                m.ctx().log().warn(msg + " - the place is off the ground by " + (z - spot.z()));
            } else if (s.service() || (spot.id().endsWith("-01"))) {
                m.ctx().log().info(msg);
            }
        }
        return z;
    }

    /** World thread: real goods, the stock store, the sign. Also used to refill in place. */
    private void openStore(Player p, Store s) {
        try {
            final boolean buy = s.buy();
            final boolean pkg = "PACKAGE".equals(s.kind());
            emptyBag(p);
            p.getSellList().clear();
            p.getBuyList().clear();
            s.shown.clear();
            int put = 0;
            if (s.craft()) {
                put = crafts.open(p, s, pool.lang(s.row.botId()));
            } else if (buy) {
                final List<Line> shown = new ArrayList<>(s.lines.subList(0, Math.min(p.getPrivateBuyStoreLimit(), s.lines.size())));
                final int day = (int) LocalDate.now().toEpochDay();
                final long wallet = walletFor(s);
                final TradeList list = p.getBuyList();
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
                if (s.service()) {
                    p.removeStatsOwner(this);
                    p.addStatFunc(new FuncAdd(Stat.P_SELL_LIM, 0x40, this, SERVICE_EXTRA_SLOTS, null)); // six lines: SS and BSpS D, C, B
                }
                final List<Line> shown = new ArrayList<>(s.lines.subList(0, Math.min(p.getPrivateSellStoreLimit(), s.lines.size())));
                final TradeList list = p.getSellList();
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
            s.charId = p.getObjectId();
            byChar.put(p.getObjectId(), s);
            s.open = true;
            if (put == 0) {
                emptyBag(p);
                s.soldOutAt = 0;
                s.forceRestock = true;
                m.ctx().log().warn("trader " + p.getName() + " at " + s.spot.id() + " has nothing to show (" + s.row.profile() + "): refilled on the restock timer");
                return;
            }
            p.sitDown();
            if (s.craft()) {
                p.setStoreName(s.row.title());
                p.setPrivateStoreType(PrivateStoreType.MANUFACTURE);
                p.broadcastUserInfo();
                World.broadcastToSelfAndVisiblePlayers(p, new RecipeShopMsg(p));
            } else {
                p.setPrivateStoreType(buy ? PrivateStoreType.BUY : (pkg ? PrivateStoreType.PACKAGE_SELL : PrivateStoreType.SELL));
                p.broadcastUserInfo();
                p.broadcastPacket(buy ? new PrivateStoreMsgBuy(p) : new PrivateStoreMsgSell(p));
            }
            m.ctx().log().info("trader " + p.getName() + " opened " + s.row.kind() + " '" + s.row.title() + "' at " + s.spot.id() + " (" + put + " lines)");
            if (p.isInsideZone(ZoneId.NO_STORE)) {
                m.ctx().log().warn("spot " + s.spot.id() + " is inside a no-store zone");
            }
            m.ctx().metrics().counter("stores opened").inc();
        } catch (Exception e) {
            m.ctx().log().warn("trader store at " + s.spot.id() + " failed: " + e, e);
            s.open = true;
            s.charId = p.getObjectId();
            s.forceRestock = true;
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
            if ((s.row == null) || s.buy() || s.craft()) {
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
                if (!s.craft()) {
                    s.lines = remaining(s, p);
                }
            }
            if (s.craft()) {
                crafts.close(p, s);
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
                // a client's purchase now makes the stock packet set the store to NONE, which logs a client-less owner
                // out at once: the next tick sees it gone, removes the bag rows that save wrote and brings it back
                // refilled; a deal without the packet (self-test) is refilled in place
                s.soldOutAt = System.currentTimeMillis();
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
                out.add(s.spot.id() + " " + s.spot.x() + "," + s.spot.y() + " " + pool.name(s.row.botId()) + " " + s.row.kind() + " " + s.row.profile() + " '" + s.row.title() + "' " + s.lines.size() + " lines" + (s.open ? " OPEN" : ""));
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
            if (!s.open || (s.soldOutAt > 0) || s.service() || s.craft() || (pool.player(s.row.botId()) == null) || s.lines.isEmpty()) {
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
