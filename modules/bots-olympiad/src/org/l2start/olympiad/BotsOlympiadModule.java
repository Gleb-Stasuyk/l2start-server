package org.l2start.olympiad;

import java.time.Duration;
import java.util.List;

import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.olympiad.OnOlympiadMatchResult;
import org.l2jmobius.gameserver.mechanics.olympiad.Olympiad;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * Phase 9 MVP A–D: olympiad nobles via the bots-core pool, gather at NPC 31688, fill live queues,
 * run bot vs bot, and fight in the stadium (brain olympiad stance). Stock olympiad is unchanged when Enabled=false
 * or bots-core is off. The bots-core types live only in {@link OlympiadBots}: this class never touches them, so
 * online without bots-core stays idle (same pattern as town-buffer / economy-market).
 */
public final class BotsOlympiadModule implements GameModule {
    private ModuleContext ctx;
    /** {@link OlympiadBots}; the class is never loaded when bots-core is off. */
    private Object bots;

    @Override
    public void onEnable(ModuleContext context) throws Exception {
        ctx = context;
        try {
            bots = OlympiadBots.start(ctx);
        } catch (NoClassDefFoundError e) {
            bots = null; // bots-core not installed
        }
        if (bots == null) {
            ctx.log().info("bots-core is off: idle (stock olympiad only)");
            return;
        }
        final OlympiadBots o = (OlympiadBots) bots;
        ctx.events().<OnOlympiadMatchResult> on(Olympiad.getInstance(), EventType.ON_OLYMPIAD_MATCH_RESULT, o::onMatch);
        ctx.handlers().admin(new AdminOly(this));
        ctx.cleanup("olympiad idle logout", o::idleLogout);
        ctx.log().info("olympiad bots: roster " + ctx.config().getInt("RosterSize") + ", gather NPC " + ctx.config().getInt("GatherNpcId") + ", Enabled=" + ctx.config().getBoolean("Enabled"));
    }

    @Override
    public void onServerReady(ModuleContext context) {
        if (bots == null) {
            return;
        }
        final OlympiadBots o = (OlympiadBots) bots;
        ctx.scheduler().fixedRate("olympiad roster", Duration.ofSeconds(2), Duration.ofSeconds(2), o::tickRoster);
        ctx.scheduler().fixedRate("olympiad signup", Duration.ofSeconds(30), Duration.ofSeconds(30), o::tickSignup);
    }

    @Override
    public void onConfigReload(ModuleContext context) {
        if (bots != null) {
            ((OlympiadBots) bots).setCfg(OlympiadBots.readCfg(ctx));
        }
    }

    @Override
    public void onShutdown(ModuleContext context) {
        if (bots != null) {
            ((OlympiadBots) bots).idleLogout();
        }
    }

    List<String> diag() {
        if (bots == null) {
            return List.of("bots-olympiad: idle (bots-core is off)");
        }
        return ((OlympiadBots) bots).diag();
    }
}
