package org.l2start.modules.townbuffer;

import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * EXTRAS_PLAN §5: paid buffer bots like the TownBuff of the x100 server ({@link Buffers}). Without bots-core (online) the
 * module only logs that the buffers are off: the bots-core classes are never touched then.
 */
public final class TownBufferModule implements GameModule {
    private volatile Object buffers;

    @Override
    public void onEnable(ModuleContext c) {
        c.onBotTradeRequest((requester, bot) -> (buffers != null) && ((Buffers) buffers).onTradeRequest(requester, bot)); // P6-BOT-TRADE
    }

    @Override
    public void onServerReady(ModuleContext c) {
        try {
            buffers = Buffers.start(c);
        } catch (NoClassDefFoundError e) {
            buffers = null; // bots-core not installed
        }
        c.log().info("enabled: " + ((buffers == null) ? "no bots-core, buffers off"
            : "7 buffers (PP Gludio/Dion/Giran/Aden/Goddard + SE Giran64/Goddard78), in the world while a player is near"));
    }

    @Override
    public void onShutdown(ModuleContext c) {
        if (buffers != null) {
            ((Buffers) buffers).stop();
        }
    }
}
