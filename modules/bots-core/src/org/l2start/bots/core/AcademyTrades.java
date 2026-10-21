package org.l2start.bots.core;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.SendTradeRequest;
import org.l2jmobius.gameserver.network.serverpackets.TradeOtherAdd;
import org.l2jmobius.gameserver.network.serverpackets.TradeOwnAdd;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2start.platform.api.ModuleContext;

/**
 * Academy join reward by trade: right after a recruiter bot took a player into the academy, the bot asks for a trade
 * and puts the adena in. Declined or ignored: it whispers (in Russian) that the player can open the trade himself when
 * he needs the money, and accepts that trade later. Paid once (the ledger is written on a successful exchange).
 */
final class AcademyTrades {
    enum State { ASKED, WAITING, TRADING }

    static final long ASK_TIMEOUT_MS = 20_000L;
    static final long WAIT_MS = 30 * 60_000L;
    static final long ACCEPT_DELAY_MS = 1_200L;
    static final String WHISPER_RU = "Если нужны деньги за академию — открой со мной трейд сам, я приму.";

    private static final class Deal {
        final int botOid;
        final int playerOid;
        final long amount;
        final Runnable onPaid;
        volatile State state = State.ASKED;
        volatile long since;
        volatile long acceptAt;
        volatile boolean added;
        volatile int adenaBefore;

        Deal(int botOid, int playerOid, long amount, Runnable onPaid, long now) {
            this.botOid = botOid;
            this.playerOid = playerOid;
            this.amount = amount;
            this.onPaid = onPaid;
            this.since = now;
        }
    }

    private final ModuleContext ctx;
    /** player objectId -> deal */
    private final Map<Integer, Deal> deals = new ConcurrentHashMap<>();

    AcademyTrades(ModuleContext ctx) {
        this.ctx = ctx;
    }

    /** Pure: what an unanswered request turns into. */
    static State afterAsk(boolean tradeOpen, boolean requestOver, long age) {
        if (tradeOpen) {
            return State.TRADING;
        }
        return (requestOver || (age > ASK_TIMEOUT_MS)) ? State.WAITING : State.ASKED;
    }

    /** Pure: the exchange is done when the bot's adena went down by the amount (a cancel leaves it as it was). */
    static boolean paid(int adenaBefore, int adenaNow, long amount) {
        return (adenaBefore - adenaNow) >= amount;
    }

    /** Game thread (clan join event). @return false = no bot to pay by trade */
    boolean offer(Player bot, Player player, long amount, Runnable onPaid) {
        if ((bot == null) || (player == null) || !bot.isOnline() || !player.isOnline() || (amount <= 0) || (amount > Integer.MAX_VALUE)
            || (bot.calculateDistance3D(player) > 2000) || deals.containsKey(player.getObjectId())) {
            return false;
        }
        final Deal d = new Deal(bot.getObjectId(), player.getObjectId(), amount, onPaid, System.currentTimeMillis());
        deals.put(player.getObjectId(), d);
        if (bot.isProcessingTransaction() || player.isProcessingRequest()) {
            d.state = State.WAITING; // busy right now: it tells him to come for it
            whisper(bot, player);
            return true;
        }
        bot.onTransactionRequest(player);
        player.sendPacket(new SendTradeRequest(bot.getObjectId()));
        return true;
    }

    /** Hook P6-BOT-TRADE: the player asks the bot that owes him the academy money. @return true when handled */
    boolean onTradeRequest(Player requester, Player bot) {
        final Deal d = deals.get(requester.getObjectId());
        if ((d == null) || (d.botOid != bot.getObjectId()) || (d.state != State.WAITING) || (bot.getActiveTradeList() != null)) {
            return false;
        }
        d.state = State.TRADING;
        d.acceptAt = System.currentTimeMillis() + ACCEPT_DELAY_MS;
        d.added = false;
        return true;
    }

    /** Task thread. */
    void tick() {
        final long now = System.currentTimeMillis();
        for (Deal d : new ArrayList<>(deals.values())) {
            final Player bot = World.getPlayer(d.botOid);
            final Player p = World.getPlayer(d.playerOid);
            if ((bot == null) || (p == null) || !bot.isOnline() || !p.isOnline() || ((now - d.since) > WAIT_MS)) {
                deals.remove(d.playerOid);
                continue;
            }
            try {
                step(d, bot, p, now);
            } catch (Exception e) {
                ctx.log().warn("academy trade " + bot.getName() + " -> " + p.getName() + ": " + e.getMessage());
                if (bot.getActiveTradeList() != null) {
                    bot.cancelActiveTrade();
                }
                deals.remove(d.playerOid);
            }
        }
    }

    private void step(Deal d, Player bot, Player p, long now) {
        switch (d.state) {
            case ASKED -> {
                final boolean open = (bot.getActiveTradeList() != null) && (bot.getActiveTradeList().getPartner() == p);
                final State next = afterAsk(open, (p.getActiveRequester() != bot) && !open, now - d.since);
                if (next == State.WAITING) {
                    d.state = State.WAITING;
                    if (p.getActiveRequester() == bot) {
                        p.setActiveRequester(null);
                    }
                    bot.onTransactionResponse();
                    whisper(bot, p);
                } else if (next == State.TRADING) {
                    d.state = State.TRADING;
                    d.acceptAt = 0;
                    d.added = false;
                }
            }
            case WAITING -> {
                // nothing: onTradeRequest wakes it up
            }
            case TRADING -> trading(d, bot, p, now);
        }
    }

    private void trading(Deal d, Player bot, Player p, long now) {
        if (d.acceptAt != 0) {
            if (now < d.acceptAt) {
                return;
            }
            d.acceptAt = 0;
            if ((bot.getActiveRequester() != p) || p.isRequestExpired()) {
                d.state = State.WAITING;
                bot.setActiveRequester(null);
                return;
            }
            bot.startTrade(p);
            bot.setActiveRequester(null);
            p.onTransactionResponse();
            return;
        }
        final TradeList list = bot.getActiveTradeList();
        if (!d.added) {
            if ((list == null) || (list.getPartner() != p)) {
                d.state = State.WAITING;
                return;
            }
            if (bot.getAdena() < d.amount) {
                bot.addAdena(ItemProcessType.REWARD, (int) (d.amount - bot.getAdena()), null, false);
            }
            d.adenaBefore = bot.getAdena();
            final TradeItem it = list.addItem(bot.getInventory().getAdenaInstance().getObjectId(), (int) d.amount);
            if (it == null) {
                bot.cancelActiveTrade();
                d.state = State.WAITING;
                return;
            }
            bot.sendPacket(new TradeOwnAdd(it));
            p.sendPacket(new TradeOtherAdd(it));
            d.added = true;
            list.confirm(); // its part is done: he confirms and gets the money
            return;
        }
        if (list != null) {
            return; // window still open
        }
        if (paid(d.adenaBefore, bot.getAdena(), d.amount)) {
            deals.remove(d.playerOid);
            d.onPaid.run();
            ctx.log().info("academy join reward " + d.amount + " adena paid by trade " + bot.getName() + " -> " + p.getName());
        } else {
            d.state = State.WAITING; // cancelled: he may open it himself later
            whisper(bot, p);
        }
    }

    private static void whisper(Player bot, Player p) {
        p.sendPacket(new CreatureSay(bot, ChatType.WHISPER, bot.getName(), WHISPER_RU));
    }
}
