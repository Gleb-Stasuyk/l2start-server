package org.l2start.platform.api;

/** Everything a module may use. Registrations made through it are owned by the module and undone on disable. */
public interface ModuleContext {
    ModuleInfo self();

    ModuleLog log();

    ModuleConfig config();

    ModuleEvents events();

    ModuleHandlers handlers();

    ModuleScheduler scheduler();

    ModuleDatabase db();

    ModuleResources resources();

    ModuleI18n i18n();

    ServiceRegistry services();

    ModuleMetrics metrics();

    /**
     * Since API 1.2. Declares which players are bots (see {@link BotPresence}); one provider per server.
     * Removed automatically when the module is disabled, degraded or stopped.
     * @throws IllegalStateException when another module already provides it
     */
    void botPresence(BotPresence presence);

    /**
     * Since API 1.2. An action run when the module is disabled, degraded, rolled back or stopped
     * (after onShutdown), newest first, like every other registration. For state the module put into
     * the world itself (e.g. bots logged in by the module).
     */
    void cleanup(String name, Runnable action);

    /**
     * Since API 1.3. Told about every line of a successful deal in a stock private store (hook P5-STORE-DEAL),
     * bots included. Guarded like every listener; removed when the module is disabled.
     */
    default void onStoreDeal(StoreDealListener listener) {
        throw new UnsupportedOperationException("onStoreDeal needs platform API 1.3");
    }

    /**
     * Since API 1.4. A real player asked a bot for a trade (hook P6-BOT-TRADE): the listener may answer for the bot.
     * Guarded like every listener; removed when the module is disabled.
     */
    default void onBotTradeRequest(BotTradeListener listener) {
        throw new UnsupportedOperationException("onBotTradeRequest needs platform API 1.4");
    }

    /**
     * Since API 1.6. The drop and spoil of dead monsters (hook P7-DROP-FLOAT): the listener may calculate them out of
     * stock rolls. Guarded like every listener; removed when the module is disabled.
     */
    default void onDrops(DropListener listener) {
        throw new UnsupportedOperationException("onDrops needs platform API 1.6");
    }

    /**
     * Since API 1.6. Quest reward items after the stock rates (hook P7-QUEST-REWARD). Guarded; removed when the module is
     * disabled.
     */
    default void onQuestReward(QuestRewardListener listener) {
        throw new UnsupportedOperationException("onQuestReward needs platform API 1.6");
    }
}
