package org.l2start.bots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;
import org.l2start.bots.api.Memory;
import org.l2start.platform.api.ModuleContext;

/**
 * Phase 6f: party mate gifts consumables (shots / HP potions) through the stock trade window (hook P6-BOT-TRADE).
 * The bot puts nothing, confirms last under both {@link TradeList} locks (same safe path as Meetings / town-buffer).
 * Outside a shared party the hunter declines; traders are left to economy-market.
 */
final class PartyTrades {
    private static final Set<Integer> GIFTS = Materializer.partyGiftIds();
    private static final long ACCEPT_MS = 1_200L;
    private static final long CONFIRM_MS = 800L;
    private static final long TIMEOUT_MS = 90_000L;

    private static final String[] THANKS_RU = { "спс", "спасибо", "спс за банки", "о, спс", "благодарю" };
    private static final String[] THANKS_EN = { "ty", "thx", "thanks", "ty for pots", "tyvm" };

    private final ModuleContext ctx;
    private final BotRegistry registry;
    private final MemoryStore memories;
    /** bot char objectId → pending deal */
    private final Map<Integer, Deal> deals = new ConcurrentHashMap<>();

    private record Deal(int playerId, long acceptAt, long tradeSince, long confirmAt) {
        Deal withTrade(long since) {
            return new Deal(playerId, acceptAt, since, 0);
        }

        Deal withConfirm(long at) {
            return new Deal(playerId, acceptAt, tradeSince, at);
        }
    }

    PartyTrades(ModuleContext ctx, BotRegistry registry, MemoryStore memories) {
        this.ctx = ctx;
        this.registry = registry;
        this.memories = memories;
    }

    /**
     * Hook P6-BOT-TRADE (game thread).
     * @return true when this module answered for a hunter bot
     */
    boolean onTradeRequest(Player requester, Player bot) {
        final BotRecord r = registry.byCharId(bot.getObjectId());
        if ((r == null) || !r.inWorld() || (r.status() == BotRecord.Status.TRADER)) {
            return false; // not ours, or a store trader (economy-market / town-buffer)
        }
        if (!sameParty(requester, bot)) {
            deny(requester, bot);
            return true;
        }
        if ((bot.getActiveTradeList() != null) || deals.containsKey(bot.getObjectId())) {
            deny(requester, bot);
            return true;
        }
        final long delay = ACCEPT_MS + ThreadLocalRandom.current().nextInt(1_200);
        deals.put(bot.getObjectId(), new Deal(requester.getObjectId(), System.currentTimeMillis() + delay, 0, 0));
        return true;
    }

    /** Task thread: accept pending requests and finish safe exchanges. */
    void tick() {
        final long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Deal> e : new ArrayList<>(deals.entrySet())) {
            final int botOid = e.getKey();
            Deal d = e.getValue();
            final BotRecord r = registry.byCharId(botOid);
            final Player bot = r == null ? null : r.player();
            if ((bot == null) || !bot.isOnline()) {
                deals.remove(botOid);
                continue;
            }
            try {
                if (d.tradeSince() == 0) {
                    d = accept(botOid, d, bot, now);
                }
                if ((d != null) && (d.tradeSince() != 0)) {
                    trade(botOid, d, bot, r, now);
                }
            } catch (Exception ex) {
                ctx.log().warn("party trade " + bot.getName() + ": " + ex.getMessage());
                if (bot.getActiveTradeList() != null) {
                    bot.cancelActiveTrade();
                }
                deals.remove(botOid);
            }
        }
    }

    private Deal accept(int botOid, Deal d, Player bot, long now) {
        final Player r = World.getPlayer(d.playerId());
        if ((r == null) || !r.isOnline() || (bot.getActiveRequester() != r) || r.isRequestExpired()) {
            deals.remove(botOid);
            bot.setActiveRequester(null);
            return null;
        }
        if (!sameParty(r, bot)) {
            deny(r, bot);
            deals.remove(botOid);
            return null;
        }
        if (now < d.acceptAt()) {
            return d;
        }
        bot.startTrade(r);
        bot.setActiveRequester(null);
        r.onTransactionResponse();
        final Deal next = d.withTrade(now);
        deals.put(botOid, next);
        return next;
    }

    private void trade(int botOid, Deal d, Player bot, BotRecord rec, long now) {
        final TradeList list = bot.getActiveTradeList();
        if (list == null) {
            deals.remove(botOid);
            return;
        }
        final Player p = list.getPartner();
        if ((p == null) || !p.isOnline() || (p.getObjectId() != d.playerId())) {
            bot.cancelActiveTrade();
            deals.remove(botOid);
            return;
        }
        if ((now - d.tradeSince()) > TIMEOUT_MS) {
            bot.cancelActiveTrade();
            deals.remove(botOid);
            return;
        }
        final TradeList theirs = p.getActiveTradeList();
        if ((theirs == null) || !theirs.isConfirmed()) {
            if (d.confirmAt() != 0) {
                deals.put(botOid, d.withConfirm(0));
            }
            return;
        }
        if (d.confirmAt() == 0) {
            deals.put(botOid, d.withConfirm(now + CONFIRM_MS + ThreadLocalRandom.current().nextInt(600)));
            return;
        }
        if (now < d.confirmAt()) {
            return;
        }
        final int result = exchange(bot, list, p);
        if (result == 0) {
            return; // race: try next tick
        }
        deals.remove(botOid);
        if (result < 0) {
            bot.cancelActiveTrade();
            return;
        }
        // success: items already moved by stock; remember and thank in party chat
        memories.of(rec.botId()).note(p.getObjectId(), 3, Memory.Tag.TRADED, true);
        enableShots(bot);
        thanks(bot);
        ctx.log().info("party trade: " + p.getName() + " → " + bot.getName() + " consumables");
    }

    /**
     * Confirm last under both locks. @return 1 done, 0 not ready, -1 refuse (bot put something / bad items)
     */
    static int exchange(Player bot, TradeList list, Player p) {
        final TradeList theirs = p.getActiveTradeList();
        if ((theirs == null) || !theirs.isConfirmed()) {
            return 0;
        }
        final TradeList sync1 = (bot.getObjectId() > p.getObjectId()) ? theirs : list;
        final TradeList sync2 = (sync1 == list) ? theirs : list;
        synchronized (sync1) {
            synchronized (sync2) {
                if ((p.getActiveTradeList() != theirs) || (bot.getActiveTradeList() != list) || !theirs.isConfirmed() || theirs.isLocked()
                    || list.isLocked()) {
                    return 0;
                }
                if (!giftsOk(theirs, list.getItems().size())) {
                    return -1;
                }
                list.confirm();
            }
        }
        return 1;
    }

    /** Player side only allowed consumables and non-empty; bot side must be empty. */
    static boolean giftsOk(TradeList theirs, int botSideCount) {
        if ((botSideCount != 0) || (theirs == null) || (theirs.getItemCount() <= 0)) {
            return false;
        }
        for (TradeItem ti : theirs.getItems()) {
            if ((ti.getItem() == null) || !GIFTS.contains(ti.getItem().getId())) {
                return false;
            }
        }
        return true;
    }

    /** Pure check for unit tests (item ids on the player's side). */
    static boolean giftsOk(List<Integer> playerItemIds, int botSideCount) {
        if ((botSideCount != 0) || (playerItemIds == null) || playerItemIds.isEmpty()) {
            return false;
        }
        for (Integer id : playerItemIds) {
            if ((id == null) || !GIFTS.contains(id)) {
                return false;
            }
        }
        return true;
    }

    private static void enableShots(Player bot) {
        for (int id : Materializer.SOULSHOTS) {
            if (bot.getInventory().getItemByItemId(id) != null) {
                bot.addAutoSoulShot(id);
            }
        }
        for (int id : Materializer.SPIRITSHOTS) {
            if (bot.getInventory().getItemByItemId(id) != null) {
                bot.addAutoSoulShot(id);
            }
        }
    }

    private static void thanks(Player bot) {
        final Party party = bot.getParty();
        if (party == null) {
            return;
        }
        final String[] bank = looksRussian(bot) ? THANKS_RU : THANKS_EN;
        final String line = bank[ThreadLocalRandom.current().nextInt(bank.length)];
        party.broadcastCreatureSay(new CreatureSay(bot, ChatType.PARTY, bot.getName(), line), bot);
    }

    private static boolean looksRussian(Player bot) {
        // personas with RU/MIX usually have Cyrillic-friendly names; default RU for Interlude solo
        return true;
    }

    private static boolean sameParty(Player a, Player b) {
        final Party pa = a.getParty();
        return (pa != null) && (pa == b.getParty());
    }

    private static void deny(Player requester, Player bot) {
        bot.setActiveRequester(null);
        requester.onTransactionResponse();
        final SystemMessage msg = new SystemMessage(SystemMessageId.S1_HAS_DENIED_YOUR_REQUEST_TO_TRADE);
        msg.addString(bot.getName());
        requester.sendPacket(msg);
    }
}
