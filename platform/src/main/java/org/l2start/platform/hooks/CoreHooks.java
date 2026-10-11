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
}
