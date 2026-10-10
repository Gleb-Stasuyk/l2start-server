package org.l2start.fixtures.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.AdminCommandHandler;
import org.l2jmobius.gameserver.handler.BypassHandler;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.OnNpcFirstTalk;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * Smoke fixture that exercises every platform mechanism and logs one verdict line:
 * "FIXTURE fx-core checks OK (...)" or "FIXTURE fx-core checks FAILED: [...]". Loaded only by the smoke image.
 */
public final class FxCore implements GameModule {
    static final int NPC_ID = 95000;
    private final AtomicInteger ticks = new AtomicInteger();
    private ModuleContext ctx;

    @Override
    public void onEnable(ModuleContext context) {
        ctx = context;
        ctx.events().<OnPlayerLogin> onPlayers(EventType.ON_PLAYER_LOGIN, e -> e.getPlayer().sendMessage(ctx.i18n().get(e.getPlayer(), "fx.greet", e.getPlayer().getName())));
        ctx.events().<OnNpcFirstTalk> onNpc(NPC_ID, EventType.ON_NPC_FIRST_TALK, e -> page(e.getNpc().getObjectId(), e.getPlayer()));
        ctx.handlers().voiced(new IVoicedCommandHandler() {
            @Override
            public boolean onCommand(String command, Player player, String params) {
                player.sendMessage("fx-core voiced");
                return true;
            }

            @Override
            public String[] getCommandList() {
                return new String[] { "fxcore" };
            }
        });
        ctx.handlers().admin(new IAdminCommandHandler() {
            @Override
            public boolean onCommand(String command, Player player) {
                player.sendMessage("fx-core admin");
                return true;
            }

            @Override
            public String[] getCommandList() {
                return new String[] { "admin_fxcore" };
            }
        });
        ctx.handlers().bypass(new IBypassHandler() {
            @Override
            public boolean onCommand(String command, Player player, Creature origin) {
                if ((origin == null) || !origin.isNpc()) {
                    return false;
                }
                page(origin.getObjectId(), player);
                return true;
            }

            @Override
            public String[] getCommandList() {
                return new String[] { "fxcore_page" };
            }
        });
        ctx.services().provide(FxCoreApi.class, () -> 42);
        ctx.log().info("FIXTURE fx-core config Label=" + ctx.config().getString("Label") + " (" + ctx.config().source("Label") + "), Limit=" + ctx.config().getInt("Limit") + " (" + ctx.config().source("Limit") + ")");
    }

    @Override
    public void onServerReady(ModuleContext context) throws Exception {
        final List<String> ok = new ArrayList<>();
        final List<String> bad = new ArrayList<>();
        check(NpcData.getInstance().getTemplate(NPC_ID) != null, "npc", ok, bad);
        check((NpcData.getInstance().getTemplate(NPC_ID) != null) && NpcData.getInstance().getTemplate(NPC_ID).hasListener(EventType.ON_NPC_FIRST_TALK), "npc-listener", ok, bad);
        check(!SpawnTable.getInstance().getSpawns(NPC_ID).isEmpty(), "spawn", ok, bad);
        final String html = HtmCache.getInstance().getHtm(null, ctx.resources().html("95000.htm"));
        check((html != null) && html.contains("FXCORE-PAGE"), "html", ok, bad);
        check(VoicedCommandHandler.getInstance().getHandler("fxcore") != null, "voiced", ok, bad);
        check(AdminCommandHandler.getInstance().getHandler("admin_fxcore") != null, "admin", ok, bad);
        check(BypassHandler.getInstance().getHandler("fxcore_page") != null, "bypass", ok, bad);
        check("Greetings x".equals(ctx.i18n().get("en", "fx.greet", "x")) && "Privet x".equals(ctx.i18n().get("ru", "fx.greet", "x")), "i18n", ok, bad);
        final String table = ctx.db().table("counter");
        ctx.db().update("INSERT INTO " + table + " (id, n) VALUES (1, 1) ON DUPLICATE KEY UPDATE n = n + 1");
        final int n = ctx.db().queryOne("SELECT n FROM " + table + " WHERE id = 1", rs -> rs.getInt(1)).orElse(-1);
        check(n > 0, "db", ok, bad);
        if (bad.isEmpty()) {
            ctx.log().info("FIXTURE fx-core checks OK (" + String.join(", ", ok) + ")");
        } else {
            ctx.log().warn("FIXTURE fx-core checks FAILED: " + bad + " (ok: " + ok + ")");
        }
        ctx.log().info("FIXTURE fx-core db n=" + n);

        final Duration period = ctx.config().getDuration("TickPeriod");
        ctx.scheduler().fixedRate("tick", period, period, () -> {
            final int t = ticks.incrementAndGet();
            if (t <= 3) {
                ctx.log().info("FIXTURE fx-core tick " + t);
            }
        });
        // Exercise //module reload without a client: same code path as the admin command.
        ctx.scheduler().schedule("reload", Duration.ofSeconds(3), () -> ctx.log().info("FIXTURE fx-core reload result: " + org.l2start.platform.Platform.get().reload(ctx.self().id())));
    }

    @Override
    public void onConfigReload(ModuleContext context) {
        ctx.log().info("FIXTURE fx-core config reloaded, Limit=" + ctx.config().getInt("Limit"));
    }

    @Override
    public void onShutdown(ModuleContext context) {
        ctx.log().info("FIXTURE fx-core shutdown, ticks=" + ticks.get());
    }

    private void page(int objectId, Player player) {
        final NpcHtmlMessage msg = new NpcHtmlMessage(objectId);
        msg.setFile(player, ctx.resources().html("95000.htm"));
        msg.replace("%objectId%", String.valueOf(objectId));
        player.sendPacket(msg);
    }

    private static void check(boolean condition, String name, List<String> ok, List<String> bad) {
        (condition ? ok : bad).add(name);
    }
}
