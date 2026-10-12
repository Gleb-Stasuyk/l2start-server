package org.l2start.economy.market;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.SendTradeRequest;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;
import org.l2jmobius.gameserver.network.serverpackets.TradeOtherAdd;
import org.l2start.bots.trade.TraderPool;
import org.l2start.economy.market.Traders.Store;
import org.l2start.economy.market.model.MarketAreas;
import org.l2start.economy.market.model.MarketItem;
import org.l2start.economy.market.model.SpotGenerator;
import org.l2start.economy.market.model.TradeMeet;
import org.l2start.economy.market.model.TradeTalk;
import org.l2start.economy.market.model.TraderPresence;

/**
 * Step 5f: a whispered WTS/WTB deal done in the stock trade window (hook P6-BOT-TRADE). On the player's "ok" a
 * walking persona (not a sitting trader) comes to a gatekeeper, warehouse keeper or Priest of Dawn of the player's
 * town, waits 3 minutes, asks the player for a trade when he is near (or accepts his request), puts the agreed item or
 * adena and confirms only when the player's side is confirmed and exactly the deal; then thanks and walks off.
 * <p>
 * Safety: the bot's goods are created when it arrives and its whole bag is destroyed when it leaves (they exist only
 * while it stands there); the bot confirms last, under the locks of both trade lists, so the stock exchange runs with
 * exactly the checked lists; the result is checked in the bag before anything is booked; one meeting per player, a few
 * in the world; buys within the players' day budget (5d) and booked like store deals (wallet, flows, player day).
 * Runs on the market's task thread; the trade request hook only starts the window.
 */
final class Meetings {
    static final int MAX = 3;
    static final long WALK_MS = 45_000L;
    static final long WAIT_MS = 3 * 60_000L;
    static final long ASK_EVERY_MS = 15_000L;
    static final int NEAR = 140;

    static final class Meet {
        final int playerId;
        final Store store;
        final boolean weSell;
        final MarketItem item;
        final long count;
        final long price;
        final TradeMeet.Spot spot;
        final int botId;
        final String lang;
        final int ex, ey, ez;
        volatile int phase; // 0 walking, 1 waiting, 2 walking off
        volatile int botCharId;
        long at;
        long until;
        long lastAsk;
        boolean goods;
        boolean warned;

        Meet(int playerId, Store store, boolean weSell, MarketItem item, long count, long price, TradeMeet.Spot spot, int botId, String lang, int ex, int ey, int ez, long now) {
            this.playerId = playerId;
            this.store = store;
            this.weSell = weSell;
            this.item = item;
            this.count = count;
            this.price = price;
            this.spot = spot;
            this.botId = botId;
            this.lang = lang;
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
            at = now;
        }

        long total() {
            return count * price;
        }
    }

    private final EconomyMarketModule m;
    private final Traders traders;
    private final TraderPool pool;
    private final Random rnd = new Random();
    private final Map<Integer, Meet> byPlayer = new ConcurrentHashMap<>();
    private boolean selfTested;
    private long firstTick;

    Meetings(EconomyMarketModule m, Traders traders, TraderPool pool) {
        this.m = m;
        this.traders = traders;
        this.pool = pool;
    }

    boolean busy(int playerId) {
        return byPlayer.containsKey(playerId);
    }

    /**
     * The player said "ok": a courier goes to the meeting place.
     * @return the whisper text for the trader that made the offer, or null when no meeting is possible now
     */
    String start(Player p, Store s, boolean weSell, MarketItem item, long count, long price, String lang) throws Exception {
        if (busy(p.getObjectId()) || (byPlayer.size() >= MAX) || (count <= 0) || (price <= 0) || ((count * price) > Integer.MAX_VALUE)) {
            return null;
        }
        if (!weSell && ((count * price) > traders.budgetLeft())) {
            return null; // 5d
        }
        final TradeMeet.Spot spot = TradeMeet.pick(candidates(p), rnd);
        if (spot == null) {
            return null;
        }
        final int botId = persona();
        if (botId <= 0) {
            return null;
        }
        final WorldObject npc = World.findObject(spot.npcObjectId());
        final String town = (npc instanceof Npc n) ? MapRegionData.getInstance().getClosestTownName(n) : "";
        // comes from 300-450 away, from where it can walk to the NPC
        final GeoEngine geo = GeoEngine.getInstance();
        int x = spot.x() + 300;
        int y = spot.y();
        int z = spot.z();
        for (int t = 0; t < 16; t++) {
            final double a = rnd.nextDouble() * 2 * Math.PI;
            final int d = 300 + rnd.nextInt(150);
            final int cx = spot.x() + (int) (Math.cos(a) * d);
            final int cy = spot.y() + (int) (Math.sin(a) * d);
            final int cz = geo.getHeight(cx, cy, spot.z());
            if ((Math.abs(cz - spot.z()) < 64) && geo.canMoveToTarget(cx, cy, cz, spot.x(), spot.y(), spot.z(), 0)) {
                x = cx;
                y = cy;
                z = cz;
                break;
            }
        }
        final int fx = x;
        final int fy = y;
        final String why = pool.enter(botId, x, y, z, SpotGenerator.headingTo(x, y, spot.x(), spot.y()), TraderPresence.level(botId), b -> {
            final double d = Math.max(1, Math.hypot(fx - spot.x(), fy - spot.y()));
            b.setRunning();
            b.getAI().setIntentionMoveTo(new Location(spot.x() + (int) (((fx - spot.x()) * 80) / d), spot.y() + (int) (((fy - spot.y()) * 80) / d), spot.z()));
        });
        if (why != null) {
            m.ctx().log().warn("trade meeting: " + pool.name(botId) + " could not come: " + why);
            return null;
        }
        final long now = System.currentTimeMillis();
        final Meet mt = new Meet(p.getObjectId(), s, weSell, item, count, price, spot, botId, lang, x, y, z, now);
        final Player b = pool.player(botId);
        mt.botCharId = (b == null) ? 0 : b.getObjectId();
        mt.until = now + WALK_MS + WAIT_MS;
        byPlayer.put(p.getObjectId(), mt);
        m.ctx().metrics().counter("trade meetings").inc();
        m.ctx().log().info("trade meeting: " + pool.name(botId) + " goes to " + spot.kind() + " (" + town + ") to " + (weSell ? "sell " : "buy ") + count + " x " + item.name() + " @" + price + " with " + p.getName());
        return TradeTalk.meet(weSell, item.name(), count, price, spot.kind(), town, pool.name(botId), lang, rnd);
    }

    /** Gatekeepers, warehouse keepers and Priests of Dawn within 8000 of the player (Giran's when there are none). */
    private static List<TradeMeet.Spot> candidates(Player p) {
        List<TradeMeet.Spot> out = around(p.getX(), p.getY(), 8000);
        if (out.isEmpty()) {
            out = around(83000, 148000, 4000); // Giran
        }
        return out;
    }

    static List<TradeMeet.Spot> around(int x, int y, int range) {
        final List<TradeMeet.Spot> out = new ArrayList<>();
        for (WorldObject o : World.getVisibleObjects()) {
            if (!(o instanceof Npc npc) || (o.getInstanceId() > 0)) {
                continue;
            }
            final double d = Math.hypot(o.getX() - x, o.getY() - y);
            if (d > range) {
                continue;
            }
            final TradeMeet.Kind k = kind(npc);
            if (k != null) {
                out.add(new TradeMeet.Spot(k, o.getObjectId(), o.getX(), o.getY(), o.getZ(), d));
            }
        }
        return out;
    }

    static TradeMeet.Kind kind(Npc npc) {
        final String title = (npc.getTitle() == null) ? "" : npc.getTitle();
        if ((npc instanceof org.l2jmobius.gameserver.entity.actor.instance.Teleporter) && title.contains("Gatekeeper")) {
            return TradeMeet.Kind.GATEKEEPER;
        }
        if ((npc instanceof org.l2jmobius.gameserver.entity.actor.instance.Warehouse) && title.contains("Warehouse")) {
            return TradeMeet.Kind.WAREHOUSE;
        }
        if ("Priest of Dawn".equals(npc.getName())) {
            return TradeMeet.Kind.PRIEST;
        }
        return null;
    }

    // ---- hook P6-BOT-TRADE (game thread)

    /** A real player asked a bot for a trade. Our courier accepts its own player; our other bots say no. */
    boolean onTradeRequest(Player requester, Player bot) {
        for (Meet mt : byPlayer.values()) {
            if (mt.botCharId != bot.getObjectId()) {
                continue;
            }
            if ((mt.playerId == requester.getObjectId()) && (mt.phase == 1) && (bot.getActiveTradeList() == null)) {
                bot.startTrade(requester); // like AnswerTradeRequest with "yes"
                bot.setActiveRequester(null);
                requester.onTransactionResponse();
                return true;
            }
            deny(requester, bot);
            return true;
        }
        if (traders.isBotStore(bot.getObjectId())) {
            deny(requester, bot);
            return true;
        }
        return false;
    }

    private static void deny(Player requester, Player bot) {
        bot.setActiveRequester(null);
        requester.onTransactionResponse();
        final SystemMessage msg = new SystemMessage(SystemMessageId.S1_HAS_DENIED_YOUR_REQUEST_TO_TRADE);
        msg.addString(bot.getName());
        requester.sendPacket(msg);
    }

    // ---- tick (task thread)

    void tick(long now) {
        if (!selfTested && m.ctx().config().getBoolean("SelfTest")) {
            if (firstTick == 0) {
                firstTick = now;
            } else if ((now - firstTick) > 60_000L) {
                selfTested = true;
                selfTest();
            }
        }
        for (Meet mt : new ArrayList<>(byPlayer.values())) {
            try {
                step(mt, now);
            } catch (Exception e) {
                m.ctx().log().warn("trade meeting: " + e);
                end(mt, "error");
            }
        }
    }

    private void step(Meet mt, long now) throws Exception {
        final Player b = pool.player(mt.botId);
        final Player p = World.getPlayer(mt.playerId);
        if ((b == null) || !b.isOnline()) {
            end(mt, "courier gone");
            return;
        }
        if (mt.phase == 2) {
            if ((now - mt.at) > 10_000L) {
                end(mt, null);
            }
            return;
        }
        if ((p == null) || !p.isOnline()) {
            walkOff(mt, b, now, "the player left");
            return;
        }
        if (now > mt.until) {
            if (b.getActiveTradeList() != null) {
                b.cancelActiveTrade();
            }
            whisper(b, p, TradeTalk.expired(mt.lang, rnd));
            walkOff(mt, b, now, "waited out");
            return;
        }
        if (mt.phase == 0) {
            if ((Math.hypot(b.getX() - mt.spot.x(), b.getY() - mt.spot.y()) <= 130) || ((now - mt.at) > WALK_MS)) {
                b.getAI().setIntentionActive();
                if (!giveGoods(mt, b)) {
                    walkOff(mt, b, now, "no goods");
                    return;
                }
                mt.phase = 1;
                mt.at = now;
                mt.until = now + WAIT_MS;
                whisper(b, p, TradeTalk.arrived(mt.lang, rnd));
            }
            return;
        }
        // phase 1: waiting, trading
        final TradeList list = b.getActiveTradeList();
        if (list == null) {
            if ((b.calculateDistance3D(p) <= NEAR) && (now - mt.lastAsk) > ASK_EVERY_MS && !p.isProcessingRequest() && !p.isProcessingTransaction() && !b.isProcessingRequest()
                && !p.isInStoreMode() && !p.getTradeRefusal()) {
                mt.lastAsk = now;
                b.setHeading(SpotGenerator.headingTo(b.getX(), b.getY(), p.getX(), p.getY()));
                b.broadcastUserInfo();
                b.onTransactionRequest(p); // like TradeRequest from the bot
                p.sendPacket(new SendTradeRequest(b.getObjectId()));
            }
            return;
        }
        if ((list.getPartner() == null) || (list.getPartner().getObjectId() != mt.playerId)) {
            b.cancelActiveTrade(); // not our player
            return;
        }
        if (list.getItemCount() == 0) {
            if (!putGoods(mt, b, list, p)) {
                b.cancelActiveTrade();
                walkOff(mt, b, now, "goods missing");
            }
            return;
        }
        final int r = exchange(mt, b, list, p);
        if (r == 0) {
            return;
        }
        if (r < 0) {
            b.cancelActiveTrade();
            if (!mt.warned) {
                mt.warned = true;
                whisper(b, p, TradeTalk.mismatch(mt.weSell, mt.item.name(), mt.count, mt.price, mt.lang, rnd));
            }
            m.ctx().log().info("trade meeting: " + p.getName() + " put something else in the window, cancelled");
            return;
        }
        book(mt, p);
        whisper(b, p, TradeTalk.thanks(mt.lang, rnd));
        walkOff(mt, b, now, null);
    }

    /**
     * The bot confirms last, and only an exact deal: both lists locked in the stock order, so what is checked is what
     * the exchange moves; the result is checked in the bot's bag.
     * @return 1 done, 0 not ready (the player has not confirmed, or the stock exchange did not run), -1 not the deal
     */
    static int exchange(Meet mt, Player b, TradeList list, Player p) {
        final TradeList theirs = p.getActiveTradeList();
        if ((theirs == null) || !theirs.isConfirmed()) {
            return 0;
        }
        final TradeList sync1 = (b.getObjectId() > p.getObjectId()) ? theirs : list;
        final TradeList sync2 = (sync1 == list) ? theirs : list;
        final long beforeAdena = b.getAdena();
        final long beforeItem = have(b, mt.item.id());
        synchronized (sync1) {
            synchronized (sync2) {
                if ((p.getActiveTradeList() != theirs) || (b.getActiveTradeList() != list) || !theirs.isConfirmed() || theirs.isLocked() || list.isLocked()) {
                    return 0;
                }
                final List<TradeMeet.Line> side = new ArrayList<>();
                for (TradeItem ti : theirs.getItems()) {
                    side.add(new TradeMeet.Line(ti.getItem().getId(), ti.getCount()));
                }
                final List<TradeMeet.Line> mine = new ArrayList<>();
                for (TradeItem ti : list.getItems()) {
                    mine.add(new TradeMeet.Line(ti.getItem().getId(), ti.getCount()));
                }
                if (!TradeMeet.matches(mt.weSell, mt.item.id(), mt.count, mt.price, side) || (mine.size() != 1) || !mine.get(0).equals(TradeMeet.botSide(mt.weSell, mt.item.id(), mt.count, mt.price))) {
                    return -1;
                }
                list.confirm(); // the partner is confirmed: the stock exchange runs now
            }
        }
        final long gotAdena = b.getAdena() - beforeAdena;
        final long gaveItem = beforeItem - have(b, mt.item.id());
        return (mt.weSell ? ((gotAdena == mt.total()) && (gaveItem == mt.count)) : ((gaveItem == -mt.count) && (-gotAdena == mt.total()))) ? 1 : 0;
    }

    /** The courier's goods (or adena) come into being when it arrives. */
    private boolean giveGoods(Meet mt, Player b) {
        Traders.emptyBag(b);
        if (mt.weSell) {
            if (mt.item.stackable()) {
                b.addItem(ItemProcessType.REWARD, mt.item.id(), (int) mt.count, null, false);
            } else {
                for (int k = 0; k < mt.count; k++) {
                    b.addItem(ItemProcessType.REWARD, mt.item.id(), 1, null, false);
                }
            }
            return have(b, mt.item.id()) == mt.count;
        }
        b.addAdena(ItemProcessType.REWARD, (int) mt.total(), null, false);
        return b.getAdena() == mt.total();
    }

    private boolean putGoods(Meet mt, Player b, TradeList list, Player p) {
        final TradeMeet.Line own = TradeMeet.botSide(mt.weSell, mt.item.id(), mt.count, mt.price);
        final Item it = mt.weSell ? b.getInventory().getItemByItemId(mt.item.id()) : b.getInventory().getAdenaInstance();
        if ((it == null) || (it.getCount() < own.count()) || (!it.isStackable() && (own.count() != 1))) {
            return false;
        }
        final TradeItem ti = list.addItem(it.getObjectId(), (int) own.count());
        if (ti == null) {
            return false;
        }
        p.sendPacket(new TradeOtherAdd(ti));
        return true;
    }

    private static long have(Player b, int itemId) {
        long n = 0;
        for (Item it : b.getInventory().getItems()) {
            if ((it.getId() == itemId) && !it.isEquipped()) {
                n += it.getCount();
            }
        }
        return n;
    }

    /** Like a store deal of the trader that made the offer: its wallet, the flows, the player's day (5d). */
    private void book(Meet mt, Player p) {
        final long total = mt.total();
        final int day = (int) LocalDate.now().toEpochDay();
        if (!mt.weSell) {
            traders.spent(total);
        }
        traders.write(() -> {
            try {
                final MarketRepository repo = m.repo();
                final long w = repo.wallet(mt.store.row.botId());
                repo.setWallet(mt.store.row.botId(), Math.max(0, w) + (mt.weSell ? total : -total));
                if (!mt.weSell) {
                    repo.addResale(mt.item.id(), mt.count / 2);
                    repo.addPlayerDay(mt.playerId, mt.item.id(), day, mt.count);
                    repo.addPlayerDay(mt.playerId, 0, day, total);
                }
                repo.addFlow(day, mt.weSell ? "from_players" : "to_players", total);
            } catch (Exception e) {
                m.ctx().log().warn("trade meeting write failed: " + e);
            }
        });
        m.ctx().metrics().counter("trade meeting deals").inc();
        m.ctx().log().info("trade meeting: " + pool.name(mt.botId) + (mt.weSell ? " sold " : " bought ") + mt.count + " x " + mt.item.name() + " @" + mt.price + (mt.weSell ? " to " : " from ") + p.getName()
            + " in the trade window (" + total + " adena)");
    }

    private void walkOff(Meet mt, Player b, long now, String why) {
        mt.phase = 2;
        mt.at = now;
        Traders.emptyBag(b); // nothing it carried stays in the world
        b.getAI().setIntentionMoveTo(new Location(mt.ex, mt.ey, mt.ez));
        if (why != null) {
            m.ctx().log().info("trade meeting: " + b.getName() + " leaves: " + why);
        }
    }

    private void end(Meet mt, String why) {
        byPlayer.remove(mt.playerId, mt);
        final Player b = pool.player(mt.botId);
        if (b != null) {
            if (b.getActiveTradeList() != null) {
                b.cancelActiveTrade();
            }
            Traders.emptyBag(b);
        }
        pool.leave(mt.botId);
        if (why != null) {
            m.ctx().log().info("trade meeting: " + pool.name(mt.botId) + " left (" + why + ")");
        }
    }

    void stop() {
        for (Meet mt : new ArrayList<>(byPlayer.values())) {
            end(mt, "server stop");
        }
    }

    private static void whisper(Player bot, Player to, String text) {
        to.sendPacket(new CreatureSay(bot, ChatType.WHISPER, bot.getName(), text));
    }

    /**
     * Smoke test (SelfTest): the meeting places of Giran are found, and two walking personas do a deal in the stock
     * trade window through {@link #exchange}: a wrong amount is refused, the exact one goes through.
     */
    private void selfTest() {
        int a = -1;
        int b = -1;
        try {
            final List<TradeMeet.Spot> spots = around(83000, 148000, 4000);
            final long g = spots.stream().filter(x -> x.kind() == TradeMeet.Kind.GATEKEEPER).count();
            final long w = spots.stream().filter(x -> x.kind() == TradeMeet.Kind.WAREHOUSE).count();
            final long pr = spots.stream().filter(x -> x.kind() == TradeMeet.Kind.PRIEST).count();
            m.ctx().log().info("SELFTEST trade meet points Giran: gatekeepers " + g + ", warehouse keepers " + w + ", priests of dawn " + pr);
            final TradeMeet.Spot spot = TradeMeet.pick(spots, rnd);
            final MarketItem item = m.book().items().all().stream().filter(MarketItem::stackable).filter(i -> i.id() != TradeMeet.ADENA).findFirst().orElse(null);
            a = persona();
            if ((spot == null) || (item == null) || (a <= 0) || (pool.enter(a, spot.x() + 60, spot.y(), spot.z(), 0, 20, x -> {}) != null)) {
                m.ctx().log().warn("SELFTEST trade window: FAILED (no place, item or courier)");
                return;
            }
            b = persona();
            if ((b <= 0) || (pool.enter(b, spot.x() + 120, spot.y(), spot.z(), 0, 20, x -> {}) != null)) {
                m.ctx().log().warn("SELFTEST trade window: FAILED (no second persona)");
                return;
            }
            final Player bot = pool.player(a);
            final Player buyer = pool.player(b);
            final Meet mt = new Meet(buyer.getObjectId(), null, true, item, 10, 10, spot, a, "EN", spot.x(), spot.y(), spot.z(), System.currentTimeMillis());
            if (!giveGoods(mt, bot)) {
                m.ctx().log().warn("SELFTEST trade window: FAILED (goods)");
                return;
            }
            Traders.emptyBag(buyer);
            buyer.addAdena(ItemProcessType.REWARD, 100, null, false);
            final int wrong = round(mt, bot, buyer, 99);
            final int exact = round(mt, bot, buyer, 100);
            final boolean ok = (wrong == -1) && (exact == 1) && (have(buyer, item.id()) == 10) && (buyer.getAdena() == 0) && (bot.getAdena() == 100) && (have(bot, item.id()) == 0);
            m.ctx().log().info("SELFTEST trade window: " + (ok ? "OK" : "FAILED") + " (wrong amount " + ((wrong == -1) ? "refused" : "result " + wrong) + ", exact deal " + ((exact == 1) ? "done" : "result " + exact)
                + ": " + bot.getName() + " gave 10 x " + item.name() + " to " + buyer.getName() + " for 100 adena)");
        } catch (Exception e) {
            m.ctx().log().warn("SELFTEST trade window: FAILED " + e);
        } finally {
            for (int id : new int[] { a, b }) {
                if ((id > 0) && (pool.player(id) != null)) {
                    final Player x = pool.player(id);
                    if (x.getActiveTradeList() != null) {
                        x.cancelActiveTrade();
                    }
                    Traders.emptyBag(x);
                    pool.leave(id);
                }
            }
        }
    }

    /** One trade window of the self-test: the buyer puts {@code adena} and confirms, then the bot decides. */
    private int round(Meet mt, Player bot, Player buyer, int adena) {
        bot.onTransactionRequest(buyer);
        buyer.startTrade(bot); // like AnswerTradeRequest
        buyer.setActiveRequester(null);
        bot.onTransactionResponse();
        final TradeList list = bot.getActiveTradeList();
        if ((list == null) || !putGoods(mt, bot, list, buyer)) {
            return -9;
        }
        final Item money = buyer.getInventory().getAdenaInstance();
        if ((money == null) || (buyer.getActiveTradeList().addItem(money.getObjectId(), adena) == null)) {
            return -8;
        }
        buyer.getActiveTradeList().confirm();
        final int r = exchange(mt, bot, list, buyer);
        if (bot.getActiveTradeList() != null) {
            bot.cancelActiveTrade();
        }
        return r;
    }

    /** A free walking persona (the shopper personas of 5e, more made as needed, at most 6). */
    private int persona() throws Exception {
        final List<Integer> ids = new ArrayList<>(m.repo().shoppers());
        ids.removeIf(id -> !pool.known(id));
        final List<Integer> free = new ArrayList<>();
        for (int id : ids) {
            if (pool.player(id) == null) {
                free.add(id);
            }
        }
        if (free.isEmpty() && (ids.size() < (Shoppers.PERSONAS + MAX))) {
            final int id = pool.create(new TraderPool.Look(MarketAreas.randomProfession(rnd), rnd.nextBoolean(), ""));
            m.repo().addShopper(id);
            free.add(id);
        }
        return free.isEmpty() ? -1 : free.get(rnd.nextInt(free.size()));
    }
}
