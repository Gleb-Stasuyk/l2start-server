package org.l2start.platform.hooks;

import java.io.File;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2start.platform.Platform;

/**
 * The only entry points used by core/ (lines marked [HOOK:P1-*], [HOOK:P2-*] and [HOOK:P3-*], see docs/PLATFORM_HOOKS.md).
 * Every method is safe: an unexpected platform error is logged and the stock server continues.
 */
public final class CoreHooks {
    private static final Logger LOGGER = Logger.getLogger("Platform");

    private CoreHooks() {
    }

    /** [HOOK:P1-BOOT] GameServer: discover, validate, resolve, migrate, load modules. */
    public static void bootstrap() {
        try {
            Platform.get().bootstrap();
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Platform: bootstrap failed, continuing without modules", t);
        }
    }

    /** [HOOK:P1-ENABLE] GameServer: after stock scripts, before spawns. */
    public static void enableModules() {
        try {
            Platform.get().enableModules();
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Platform: enabling modules failed", t);
        }
    }

    /** [HOOK:P1-READY] GameServer: world loaded, network not open yet. */
    public static void serverReady() {
        try {
            Platform.get().serverReady();
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Platform: serverReady failed", t);
        }
    }

    /** [HOOK:P1-SHUTDOWN] Shutdown.startShutdownActions: before players are saved. */
    public static void shutdown() {
        try {
            Platform.get().shutdown();
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Platform: shutdown of modules failed", t);
        }
    }

    /**
     * [HOOK:P1-DATA-*] [HOOK:P2-DATA-INITEQUIP] Module data folders of one type
     * ("npcs", "spawns", "multisell", "buylists", "initialequipment"; matched case-insensitively).
     * @param exists id already defined by stock data (null when ids do not matter)
     */
    public static List<File> dataDirs(String type, IntPredicate exists) {
        try {
            return Platform.get().dataDirs(type, exists);
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Platform: module " + type + " folders failed", t);
            return List.of();
        }
    }

    /** [HOOK:P1-HTML] HtmCache.getHtm miss for "data/html/modules/&lt;id&gt;/...". */
    public static String moduleHtml(String prefix, String path) {
        try {
            return Platform.get().moduleHtml(prefix, path);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: module html " + path + " failed", t);
            return null;
        }
    }

    /**
     * [HOOK:P3-BOT-CHARINFO] [HOOK:P3-BOT-WHISPER] Is this player a bot (a stock Player without a client,
     * managed by the module that provides the bot presence)? Always false without such a module.
     */
    public static boolean isBot(org.l2jmobius.gameserver.entity.actor.Player player) {
        try {
            return BotHooks.isBot(player);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: bot presence failed", t);
            return false;
        }
    }

    /**
     * [HOOK:P5-STORE-DEAL] One line of a successful private store deal (TradeList.privateStoreBuy / privateStoreSell):
     * the item has moved. Tells the listening modules; never changes the deal.
     * @param buyStore true for a "buy" store (the owner bought), false for a sell / package store
     */
    public static void storeDeal(org.l2jmobius.gameserver.entity.actor.Player seller, org.l2jmobius.gameserver.entity.actor.Player buyer, int itemId, long count, long price,
        boolean buyStore) {
        try {
            if ((StoreHooks.size() == 0) || (seller == null) || (buyer == null)) {
                return;
            }
            StoreHooks.deal(new org.l2start.platform.api.StoreDeal(seller.getObjectId(), buyer.getObjectId(), itemId, count, price, buyStore, isBot(seller), isBot(buyer)));
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: store deal listener failed", t);
        }
    }

    /**
     * [HOOK:P6-BOT-TRADE] TradeRequest, after the stock request went to the partner: a bot has no client to answer,
     * so a module that trades for its bots (economy-market, whispered WTS/WTB deals) may answer for it. Never changes
     * a request between real players; inert without listeners.
     */
    public static void botTradeRequest(org.l2jmobius.gameserver.entity.actor.Player requester, org.l2jmobius.gameserver.entity.actor.Player partner) {
        try {
            if ((TradeHooks.size() == 0) || (requester == null) || (partner == null) || !isBot(partner)) {
                return;
            }
            TradeHooks.request(requester, partner);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: bot trade listener failed", t);
        }
    }

    /**
     * [HOOK:P6-BOT-PARTY] RequestJoinParty, after the stock invitation went to the target (its active requester is
     * set): a bot has no client to answer, so the bot module answers for it later. Only when the target is a bot;
     * inert without listeners. (The guard prefix of the same hook, {@link #isBot}, lets the invitation reach a bot at
     * all instead of the stock "Player is in offline mode.")
     */
    public static void botPartyRequest(org.l2jmobius.gameserver.entity.actor.Player requester, org.l2jmobius.gameserver.entity.actor.Player target) {
        try {
            if ((PartyHooks.size() == 0) || (requester == null) || (target == null) || !isBot(target)) {
                return;
            }
            PartyHooks.request(requester, target);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: bot party listener failed", t);
        }
    }

    /**
     * [HOOK:P6-BOT-PARTY] RequestJoinParty, directly before the stock {@code target.isInParty()} check: when the
     * target is a bot already in a party, a module may handle the invite socially (pair redirect) and skip the stock
     * "member of another party" message. Only when the target is a bot and in a party; inert without listeners.
     * @return true = handled, do not run the stock busy path
     */
    public static boolean botPartyBusy(org.l2jmobius.gameserver.entity.actor.Player requester, org.l2jmobius.gameserver.entity.actor.Player target) {
        try {
            if ((PartyHooks.size() == 0) || (requester == null) || (target == null) || !isBot(target) || !target.isInParty()) {
                return false;
            }
            return PartyHooks.busy(requester, target);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: bot party busy listener failed", t);
            return false;
        }
    }

    /**
     * [HOOK:P7-BOT-DUEL] RequestDuelStart, after the stock duel ask went to the target: a bot has no client to answer,
     * so the bot module answers for it later. Only when the target is a bot; inert without listeners.
     */
    public static void botDuelRequest(org.l2jmobius.gameserver.entity.actor.Player requester, org.l2jmobius.gameserver.entity.actor.Player target, boolean partyDuel) {
        try {
            if ((DuelHooks.size() == 0) || (requester == null) || (target == null) || !isBot(target)) {
                return;
            }
            DuelHooks.request(requester, target, partyDuel);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: bot duel listener failed", t);
        }
    }

    /**
     * [HOOK:P7-BOT-PLEDGE] RequestJoinPledge, after the stock clan ask went to the target: a bot has no client to
     * answer, so the bot module answers for it later. Only when the target is a bot; inert without listeners.
     */
    public static void botPledgeRequest(org.l2jmobius.gameserver.entity.actor.Player requester, org.l2jmobius.gameserver.entity.actor.Player target, int pledgeType) {
        try {
            if ((PledgeHooks.size() == 0) || (requester == null) || (target == null) || !isBot(target)) {
                return;
            }
            PledgeHooks.request(requester, target, pledgeType);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: bot pledge listener failed", t);
        }
    }

    /**
     * [HOOK:P7-ATTACKABLE] Player.isAutoAttackable, when stock would return false: a module may still allow the attack
     * (PK bot opening on a white outside mutual PvP). Inert without listeners.
     */
    public static boolean forceAutoAttackable(org.l2jmobius.gameserver.entity.actor.Player target, org.l2jmobius.gameserver.entity.actor.Creature attacker) {
        try {
            if ((AttackableHooks.size() == 0) || (target == null) || (attacker == null)) {
                return false;
            }
            return AttackableHooks.force(target, attacker);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: attackable listener failed", t);
            return false;
        }
    }

    /**
     * [HOOK:P5-STORE-ADENA] TradeList.privateStoreBuy, after the buyer paid: guard of the stock
     * {@code playerIU.addItem(adenaItem)}. A buyer who paid with all his adena has no adena item any more and the stock
     * line threw a NullPointerException after the adena was taken and before the goods moved (adena lost, nothing
     * bought). With the platform active the line is skipped in that case (the buyer gets his full item list instead)
     * and the deal completes; without the platform the stock behaviour is unchanged.
     * @return true = run the stock line
     */
    public static boolean storeBuyerAdenaLeft(org.l2jmobius.gameserver.entity.actor.Player buyer, Object adenaItem) {
        try {
            if ((adenaItem != null) || !Platform.get().isActive()) {
                return true;
            }
            if (buyer != null) {
                buyer.sendItemList(false); // the client drops its adena line
            }
            return false;
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: store adena guard failed", t);
            return adenaItem != null;
        }
    }

    /**
     * [HOOK:P7-DROP-FLOAT] NpcTemplate.calculateDrops, first thing: a module (rates-floating) may calculate the drop or
     * spoil itself out of stock rolls ({@code stockRoll} runs the stock method, which then skips this hook). Inert without
     * listeners or when a listener fails: null = the stock calculation.
     */
    public static java.util.List<org.l2jmobius.gameserver.entity.item.holders.ItemHolder> drops(boolean spoil, org.l2jmobius.gameserver.entity.actor.Creature victim,
        org.l2jmobius.gameserver.entity.actor.Creature killer, java.util.function.Supplier<java.util.List<org.l2jmobius.gameserver.entity.item.holders.ItemHolder>> stockRoll) {
        try {
            if ((DropHooks.size() == 0) || (victim == null) || (killer == null)) {
                return null;
            }
            return DropHooks.drops(spoil, victim, killer, stockRoll);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: drop listener failed", t);
            return null;
        }
    }

    /**
     * [HOOK:P7-QUEST-REWARD] Quest.rewardItems, after the stock rates: a module may change the count (rates-floating keeps
     * valuable rewards x1). Without listeners the stock count.
     */
    public static int questReward(org.l2jmobius.gameserver.entity.actor.Player player, int itemId, int base, int rated) {
        try {
            if (DropHooks.size() == 0) {
                return rated;
            }
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, DropHooks.questReward(player, itemId, base, rated)));
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: quest reward listener failed", t);
            return rated;
        }
    }

    /**
     * [HOOK:P8-TELEPORT-NAME] TeleportHolder.showTeleportList: the name of a destination (world-info adds the monster
     * levels there). Without listeners the stock name.
     */
    public static String teleportName(org.l2jmobius.gameserver.entity.actor.Player player, org.l2jmobius.gameserver.entity.Location where, String name) {
        try {
            if (DropHooks.noNames()) {
                return name;
            }
            return DropHooks.teleportName(player, where, name);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Platform: teleport name listener failed", t);
            return name;
        }
    }
}
