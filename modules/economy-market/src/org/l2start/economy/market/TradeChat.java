package org.l2start.economy.market;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.ChatHandler;
import org.l2jmobius.gameserver.handler.IChatHandler;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerChat;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2start.economy.market.Traders.Store;
import org.l2start.economy.market.lw.StorePricing;
import org.l2start.economy.market.model.Assortment.Line;
import org.l2start.economy.market.model.MarketBook;
import org.l2start.economy.market.model.MarketItem;
import org.l2start.economy.market.model.TradeTalk;
import org.l2start.platform.api.StoreDeal;

/**
 * Step 5f: WTS/WTB in the trade chat. Bot traders now and then write what is in their store ({@code TradeChatAds} per
 * minute for the whole world, by their chattiness; RU/EN templates). A real player's WTB/WTS (LW parser, RU words too)
 * gets a whisper from a trader that has the item (or a buyer): count and price inside the market corridors (5a) and
 * the players' day budget (5d); two items fit = "which one?". The player answers "ok": the trader puts the line first
 * in its store at that price for 3 minutes (the deal goes through the stock store window); "no" or silence ends it.
 * Limits: offers per minute for the world and per player, one answer per player and item in 2 minutes.
 * Chat events come on game threads and are queued; all work runs in the traders' tick.
 */
final class TradeChat {
    static final long OFFER_MS = 2 * 60_000L;
    static final long HOLD_MS = 3 * 60_000L;
    static final long SAME_AD_MS = 2 * 60_000L;

    private record Heard(int playerId, ChatType type, String text, int targetId) {
    }

    private static final class Pending {
        final Store s;
        final boolean weSell;
        List<MarketItem> options;
        MarketItem item;
        long count;
        long price;
        final long until;

        Pending(Store s, boolean weSell, List<MarketItem> options, long until) {
            this.s = s;
            this.weSell = weSell;
            this.options = options;
            this.until = until;
        }
    }

    private static final class Hold {
        final Store s;
        final int playerId;
        final int itemId;
        final String name;
        final long oldPrice;
        final long price;
        final boolean weSell;
        final long until;
        volatile boolean dealt;

        Hold(Store s, int playerId, int itemId, String name, long oldPrice, long price, boolean weSell, long until) {
            this.s = s;
            this.playerId = playerId;
            this.itemId = itemId;
            this.name = name;
            this.oldPrice = oldPrice;
            this.price = price;
            this.weSell = weSell;
            this.until = until;
        }
    }

    private final EconomyMarketModule m;
    private final Traders traders;
    private final Random rnd = new Random();
    private final ConcurrentLinkedQueue<Heard> heard = new ConcurrentLinkedQueue<>();
    private final TradeTalk.Limit worldOffers = new TradeTalk.Limit(6);
    private final Map<Integer, TradeTalk.Limit> playerOffers = new HashMap<>();
    private final Map<String, Long> recent = new HashMap<>();
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final List<Hold> holds = new ArrayList<>();
    private double adCredit;
    private long lastTick;

    TradeChat(EconomyMarketModule m, Traders traders) {
        this.m = m;
        this.traders = traders;
    }

    /** Game thread of the chat packet: real players' trade lines and whispers to traders, queued. */
    void onChat(OnPlayerChat e) {
        final Player p = e.getPlayer();
        if ((p == null) || traders.isBotStore(p.getObjectId()) || org.l2start.platform.hooks.CoreHooks.isBot(p)) {
            return;
        }
        final ChatType t = e.getChatType();
        if ((t == ChatType.TRADE) || (t == ChatType.SHOUT)) {
            heard.add(new Heard(p.getObjectId(), t, e.getText(), 0));
        } else if ((t == ChatType.WHISPER) && (e.getTarget() != null) && traders.isBotStore(e.getTarget().getObjectId())) {
            heard.add(new Heard(p.getObjectId(), t, e.getText(), e.getTarget().getObjectId()));
        }
    }

    /** Game thread of a store deal on a bot store: a held line was taken. */
    void onDeal(Store s, StoreDeal d) {
        synchronized (holds) {
            for (Hold h : holds) {
                if ((h.s == s) && (h.itemId == d.itemId()) && ((d.buyerId() == h.playerId) || (d.sellerId() == h.playerId))) {
                    h.dealt = true;
                }
            }
        }
    }

    void tick(long now) throws Exception {
        final long dt = (lastTick == 0) ? 0 : Math.min(60_000L, now - lastTick);
        lastTick = now;
        holds(now);
        pending.values().removeIf(p -> now > p.until);
        recent.values().removeIf(t -> (now - t) > SAME_AD_MS);
        Heard h;
        while ((h = heard.poll()) != null) {
            final Player p = World.getPlayer(h.playerId());
            if (p == null) {
                continue;
            }
            if (h.type() == ChatType.WHISPER) {
                answer(p, h, now);
            } else if (m.ctx().config().getBoolean("TradeChatReplies")) {
                heardAd(p, h.text(), now);
            }
        }
        ads(dt);
    }

    // ---- bot ads

    private void ads(long dt) {
        final double rate = Math.max(0, m.ctx().config().getDouble("TradeChatAds"));
        adCredit = Math.min(2, adCredit + ((rate * dt) / 60_000.0));
        if ((adCredit < 1) || !someoneListens()) {
            return;
        }
        adCredit -= 1;
        final List<Store> open = traders.openStores();
        double total = 0;
        for (Store s : open) {
            total += weight(s);
        }
        double r = rnd.nextDouble() * total;
        for (Store s : open) {
            r -= weight(s);
            if (r <= 0) {
                ad(s);
                return;
            }
        }
    }

    private double weight(Store s) {
        final double c = traders.pool().chattiness(s.row.botId());
        return s.lines.isEmpty() ? 0 : 0.05 + (c * c);
    }

    private void ad(Store s) {
        final List<Line> lines = s.lines;
        if (lines.isEmpty()) {
            return;
        }
        final Line l = lines.get(rnd.nextInt(Math.min(3, lines.size())));
        final Player p = traders.pool().player(s.row.botId());
        if (p == null) {
            return;
        }
        final String text = TradeTalk.ad(!s.buy(), l.name(), l.price(), l.count(), traders.pool().lang(s.row.botId()), rnd);
        say(p, text);
        m.ctx().metrics().counter("trade chat ads").inc();
        m.ctx().log().info("trade chat ad: " + p.getName() + ": " + text);
    }

    /** A real player with a client in the world (else nobody reads the chat). */
    private static boolean someoneListens() {
        for (Player p : World.getPlayers()) {
            if ((p != null) && (p.isOnlineInt() == 1) && !org.l2start.platform.hooks.CoreHooks.isBot(p)) {
                return true;
            }
        }
        return false;
    }

    /** Through the stock trade chat handler (region or global as the server is set, level 20+). */
    private void say(Player bot, String text) {
        final IChatHandler h = ChatHandler.getInstance().getHandler(ChatType.TRADE);
        if (h != null) {
            h.onChat(ChatType.TRADE, bot, null, text);
        }
    }

    private static void whisper(Player bot, Player to, String text) {
        to.sendPacket(new CreatureSay(bot, ChatType.WHISPER, bot.getName(), text));
    }

    // ---- a player's ad

    private void heardAd(Player p, String text, long now) {
        final MarketBook book = m.book();
        final TradeTalk.Ad ad = TradeTalk.parse(text, book.items().all(), m.stage());
        if (ad == null) {
            return;
        }
        final String key = p.getObjectId() + ":" + ad.items().get(0).id() + ":" + ad.playerSells();
        if (recent.containsKey(key)) {
            return;
        }
        recent.put(key, now);
        if (!playerOffers.computeIfAbsent(p.getObjectId(), k -> new TradeTalk.Limit(2)).allow(now) || !worldOffers.allow(now)) {
            return;
        }
        final boolean weSell = !ad.playerSells();
        final Store s = responder(ad.items().get(0).id(), weSell);
        if (s == null) {
            return;
        }
        final Player bot = traders.pool().player(s.row.botId());
        if (bot == null) {
            return;
        }
        final String lang = traders.pool().lang(s.row.botId());
        final Pending pd = new Pending(s, weSell, ad.items(), now + OFFER_MS);
        if (ad.items().size() > 1) {
            pending.put(p.getObjectId(), pd);
            whisperLater(bot, p, TradeTalk.which(ad.items().get(0), ad.items().get(1), lang, rnd));
            return;
        }
        if (offer(pd, ad.items().get(0), ad.count(), ad.price())) {
            pending.put(p.getObjectId(), pd);
            whisperLater(bot, p, TradeTalk.offer(weSell, pd.item.name(), pd.count, pd.price, lang, rnd));
            m.ctx().log().info("trade chat: " + p.getName() + " '" + text + "' -> " + bot.getName() + " offers " + (weSell ? "to sell " : "to buy ") + pd.count + " x " + pd.item.name() + " @" + pd.price);
        }
    }

    /** A trader whose store has the item (else any trader of that side, not 4UDO). */
    private Store responder(int itemId, boolean weSell) {
        final List<Store> have = new ArrayList<>();
        final List<Store> side = new ArrayList<>();
        for (Store s : traders.openStores()) {
            if ((s.buy() == weSell) || (s.service() && !weSell) || "PACKAGE".equals(s.kind())) {
                continue;
            }
            if (s.lines.stream().anyMatch(l -> l.itemId() == itemId)) {
                have.add(s);
            } else if (!s.service()) {
                side.add(s);
            }
        }
        final List<Store> from = have.isEmpty() ? side : have;
        return from.isEmpty() ? null : from.get(rnd.nextInt(from.size()));
    }

    /** Count and price of the offer, inside the corridors and the budget. @return false = nothing to offer */
    private boolean offer(Pending pd, MarketItem it, int saidCount, int saidPrice) {
        final long now = System.currentTimeMillis();
        final MarketBook.Quote q = m.book().quote(it.id(), m.stage(), now);
        final Line have = pd.s.lines.stream().filter(l -> l.itemId() == it.id()).findFirst().orElse(null);
        long count;
        if (!it.stackable()) {
            count = 1;
        } else if (saidCount > 0) {
            count = Math.min(saidCount, 50_000);
        } else if (have != null) {
            count = have.count();
        } else {
            count = StorePricing.bulkAmountRange((int) Math.max(1, Math.min(Integer.MAX_VALUE, it.price())))[0];
        }
        long price;
        if (pd.weSell) {
            final long ask = (have != null) ? have.price() : traders.priceFor(pd.s, it.id(), false);
            if (ask <= 0) {
                return false;
            }
            price = ((saidPrice > 0) && (saidPrice < ask) && (saidPrice >= q.lowSell())) ? saidPrice : ask; // meets a fair price, never below the floor
        } else {
            final long bid = traders.priceFor(pd.s, it.id(), true);
            if (bid <= 0) {
                return false;
            }
            price = ((saidPrice > 0) && (saidPrice <= Math.max(bid, q.highBid()))) ? Math.min(saidPrice, Math.max(bid, q.highBid())) : bid;
            count = Math.min(count, traders.budgetLeft() / Math.max(1, price)); // 5d: the day budget
            if (count <= 0) {
                return false;
            }
        }
        pd.item = it;
        pd.count = count;
        pd.price = price;
        pd.options = List.of(it);
        return true;
    }

    // ---- the player's whisper to a trader

    private void answer(Player p, Heard h, long now) throws Exception {
        final Pending pd = pending.get(p.getObjectId());
        if ((pd == null) || (pd.s.charId != h.targetId())) {
            return;
        }
        final Player bot = traders.pool().player(pd.s.row.botId());
        if (bot == null) {
            pending.remove(p.getObjectId());
            return;
        }
        final String lang = traders.pool().lang(pd.s.row.botId());
        if (pd.item == null) { // "which one?"
            final int k = TradeTalk.pick(h.text(), pd.options);
            if ((k >= 0) && offer(pd, pd.options.get(k), 0, 0)) {
                whisperLater(bot, p, TradeTalk.offer(pd.weSell, pd.item.name(), pd.count, pd.price, lang, rnd));
            } else if (TradeTalk.no(h.text())) {
                pending.remove(p.getObjectId());
                whisperLater(bot, p, TradeTalk.declined(lang, rnd));
            }
            return;
        }
        if (TradeTalk.no(h.text())) {
            pending.remove(p.getObjectId());
            whisperLater(bot, p, TradeTalk.declined(lang, rnd));
            return;
        }
        if (!TradeTalk.yes(h.text())) {
            return;
        }
        pending.remove(p.getObjectId());
        final long old = traders.hold(pd.s, pd.item.id(), pd.item.name(), pd.count, pd.price);
        if (old < 0) {
            return;
        }
        synchronized (holds) {
            holds.add(new Hold(pd.s, p.getObjectId(), pd.item.id(), pd.item.name(), old, pd.price, pd.weSell, now + HOLD_MS));
        }
        whisperLater(bot, p, TradeTalk.held(pd.weSell, pd.item.name(), pd.price, lang, rnd));
        m.ctx().metrics().counter("trade chat deals held").inc();
        m.ctx().log().info("trade chat: " + bot.getName() + " holds " + pd.count + " x " + pd.item.name() + " @" + pd.price + " for " + p.getName() + " (3 min, " + (pd.weSell ? "sell" : "buy") + " store)");
    }

    /** Held lines: taken = thanks and the old price back; run out = off the store. */
    private void holds(long now) throws Exception {
        final List<Hold> done = new ArrayList<>();
        synchronized (holds) {
            for (Iterator<Hold> it = holds.iterator(); it.hasNext();) {
                final Hold h = it.next();
                if (h.dealt || (now > h.until)) {
                    it.remove();
                    done.add(h);
                }
            }
        }
        for (Hold h : done) {
            traders.unhold(h.s, h.itemId, h.oldPrice);
            final Player bot = traders.pool().player(h.s.row.botId());
            final Player p = World.getPlayer(h.playerId);
            if ((bot != null) && (p != null) && p.isOnline()) {
                final String lang = traders.pool().lang(h.s.row.botId());
                whisper(bot, p, h.dealt ? TradeTalk.thanks(lang, rnd) : TradeTalk.expired(lang, rnd));
            }
            m.ctx().log().info("trade chat: hold of " + h.name + " at " + h.s.spot.id() + (h.dealt ? " taken" : " ran out"));
        }
    }

    /** A person types: the whisper comes 2-5 seconds later. */
    private void whisperLater(Player bot, Player to, String text) {
        org.l2jmobius.commons.threads.ThreadPool.schedule(() -> {
            if (to.isOnline() && bot.isOnline()) {
                whisper(bot, to, text);
            }
        }, 2_000 + rnd.nextInt(3_000));
    }
}
