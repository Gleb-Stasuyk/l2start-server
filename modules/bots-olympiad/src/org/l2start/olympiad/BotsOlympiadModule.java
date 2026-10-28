package org.l2start.olympiad;

import java.time.Duration;
import java.util.List;

import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.olympiad.OnOlympiadMatchResult;
import org.l2jmobius.gameserver.mechanics.olympiad.Olympiad;
import org.l2start.bots.olympiad.OlympiadPool;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * Phase 9 MVP A–C: olympiad nobles via bots-core {@link OlympiadPool}, gather at NPC 31688, fill live queues and
 * run bot vs bot in the background. Stock olympiad is unchanged when Enabled=false or bots-core is off.
 */
public final class BotsOlympiadModule implements GameModule {
    private ModuleContext ctx;
    private OlympiadBots bots;

    @Override
    public void onEnable(ModuleContext context) throws Exception {
        ctx = context;
        final OlympiadPool pool = ctx.services().find(OlympiadPool.class).orElse(null);
        if (pool == null) {
            ctx.log().info("bots-core is off: idle (stock olympiad only)");
            return;
        }
        bots = new OlympiadBots(ctx, pool, readCfg());
        ctx.events().<OnOlympiadMatchResult> on(Olympiad.getInstance(), EventType.ON_OLYMPIAD_MATCH_RESULT, bots::onMatch);
        ctx.handlers().admin(new AdminOly(this));
        ctx.cleanup("olympiad idle logout", () -> bots.idleLogout());
        ctx.log().info("olympiad bots: roster " + ctx.config().getInt("RosterSize") + ", gather NPC " + ctx.config().getInt("GatherNpcId") + ", Enabled=" + ctx.config().getBoolean("Enabled"));
    }

    @Override
    public void onServerReady(ModuleContext context) {
        if (bots == null) {
            return;
        }
        ctx.scheduler().fixedRate("olympiad roster", Duration.ofSeconds(2), Duration.ofSeconds(2), bots::tickRoster);
        ctx.scheduler().fixedRate("olympiad signup", Duration.ofSeconds(30), Duration.ofSeconds(30), bots::tickSignup);
    }

    @Override
    public void onConfigReload(ModuleContext context) {
        if (bots != null) {
            bots.setCfg(readCfg());
        }
    }

    @Override
    public void onShutdown(ModuleContext context) {
        if (bots != null) {
            bots.idleLogout();
        }
    }

    List<String> diag() {
        if (bots == null) {
            return List.of("bots-olympiad: idle (bots-core is off)");
        }
        return bots.diag();
    }

    private OlympiadBots.Cfg readCfg() {
        int min = ctx.config().getInt("MinLevel");
        int max = ctx.config().getInt("MaxLevel");
        if (max < min) {
            max = min;
        }
        return new OlympiadBots.Cfg(ctx.config().getBoolean("Enabled"), ctx.config().getInt("RosterSize"), min, max, ctx.config().getInt("RivalsPerClass"),
            ctx.config().getInt("SignUpChancePercent"), ctx.config().getInt("RestSeconds"), ctx.config().getInt("GatherNpcId"));
    }
}
