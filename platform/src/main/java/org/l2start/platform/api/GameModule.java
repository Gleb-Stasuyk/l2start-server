package org.l2start.platform.api;

/**
 * Entry point of a jar module (named by "entrypoint" in module.json). Needs a public no-arg constructor.
 * Every callback runs inside the platform guard: an exception marks the module FAILED (startup) and
 * rolls back everything it registered through the {@link ModuleContext}.
 */
public interface GameModule {
    /** Register events, commands, tasks and services. Data (NPC, spawns, multisell, html) is already loaded. */
    void onEnable(ModuleContext ctx) throws Exception;

    /** The world is loaded and the network is still closed. */
    default void onServerReady(ModuleContext ctx) throws Exception {
    }

    /** After //module reload &lt;id&gt;: config, i18n and html of the module were re-read. */
    default void onConfigReload(ModuleContext ctx) throws Exception {
    }

    /** Server shutdown, players are still online and the database is alive. Has a timeout. */
    default void onShutdown(ModuleContext ctx) throws Exception {
    }
}
