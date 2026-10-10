package org.l2start.platform.hooks;

import java.io.File;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2start.platform.Platform;

/**
 * The only entry points used by core/ (lines marked [HOOK:P1-*] and [HOOK:P2-*], see docs/PLATFORM_HOOKS.md).
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
}
