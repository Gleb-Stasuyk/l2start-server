package org.l2start.modules.hello;

import java.time.LocalDate;
import java.util.Optional;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.OnNpcFirstTalk;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/** Demo module: see MODULE.md. */
public final class HelloModule implements GameModule {
    public static final int NPC_ID = 95000;

    private ModuleContext ctx;
    private HelloRepository repo;

    @Override
    public void onEnable(ModuleContext context) {
        ctx = context;
        repo = new HelloRepository(ctx.db());

        ctx.events().<OnPlayerLogin> onPlayers(EventType.ON_PLAYER_LOGIN, e -> {
            if (ctx.config().getBoolean("GreetOnLogin")) {
                e.getPlayer().sendMessage(ctx.i18n().get(e.getPlayer(), "hello.login", e.getPlayer().getName()));
                ctx.metrics().counter("greetings").inc();
            }
        });
        ctx.events().<OnNpcFirstTalk> onNpc(NPC_ID, EventType.ON_NPC_FIRST_TALK, e -> showPage(e.getNpc(), e.getPlayer()));

        ctx.handlers().voiced(new IVoicedCommandHandler() {
            @Override
            public boolean onCommand(String command, Player player, String params) {
                hello(player);
                return true;
            }

            @Override
            public String[] getCommandList() {
                return new String[] { "hello" };
            }
        });
        ctx.handlers().admin(new IAdminCommandHandler() {
            @Override
            public boolean onCommand(String command, Player player) {
                player.sendMessage(statsLine(player));
                return true;
            }

            @Override
            public String[] getCommandList() {
                return new String[] { "admin_hello_stats" };
            }
        });
        ctx.handlers().bypass(new IBypassHandler() {
            @Override
            public boolean onCommand(String command, Player player, Creature origin) {
                if ((origin == null) || !origin.isNpc()) {
                    return false;
                }
                final NpcHtmlMessage html = new NpcHtmlMessage(origin.getObjectId());
                html.setFile(player, ctx.resources().html("95000-stats.htm"));
                html.replace("%stats%", statsLine(player));
                html.replace("%mine%", String.valueOf(todayCount(player)));
                html.replace("%objectId%", String.valueOf(origin.getObjectId()));
                player.sendPacket(html);
                return true;
            }

            @Override
            public String[] getCommandList() {
                return new String[] { "hello_stats" };
            }
        });
        ctx.log().info("enabled: GreetOnLogin=" + ctx.config().getBoolean("GreetOnLogin") + " (" + ctx.config().source("GreetOnLogin") + "), MaxHelloPerDay=" + ctx.config().getInt("MaxHelloPerDay") + ", profile " + ctx.self().profile());
    }

    @Override
    public void onServerReady(ModuleContext context) {
        ctx.scheduler().fixedRate("online-stats", ctx.config().getDuration("StatsLogPeriod"), ctx.config().getDuration("StatsLogPeriod"), () -> ctx.log().info("players online: " + World.getPlayers().size()));
        ctx.log().info("server ready, NPC " + NPC_ID + " page: " + ctx.resources().html("95000.htm"));
    }

    @Override
    public void onConfigReload(ModuleContext context) {
        ctx.log().info("config reloaded: GreetOnLogin=" + ctx.config().getBoolean("GreetOnLogin") + ", MaxHelloPerDay=" + ctx.config().getInt("MaxHelloPerDay"));
    }

    @Override
    public void onShutdown(ModuleContext context) {
        ctx.log().info("shutdown, greetings this session: " + ctx.metrics().counter("greetings").get() + ", .hello this session: " + ctx.metrics().counter("hello").get());
    }

    private void showPage(Npc npc, Player player) {
        final NpcHtmlMessage html = new NpcHtmlMessage(npc.getObjectId());
        html.setFile(player, ctx.resources().html("95000.htm"));
        html.replace("%player%", player.getName());
        html.replace("%online%", String.valueOf(World.getPlayers().size()));
        html.replace("%objectId%", String.valueOf(npc.getObjectId()));
        player.sendPacket(html);
    }

    private void hello(Player player) {
        final int max = ctx.config().getInt("MaxHelloPerDay");
        try {
            final Optional<Greeting.Counter> stored = repo.load(player.getObjectId());
            final Greeting.Counter next = Greeting.next(stored.orElse(null), Greeting.day(LocalDate.now()), max);
            if (next == null) {
                player.sendMessage(ctx.i18n().get(player, "hello.limit", player.getName(), max));
                return;
            }
            repo.save(player.getObjectId(), next);
            ctx.metrics().counter("hello").inc();
            player.sendMessage(ctx.i18n().get(player, "hello.reply", player.getName(), next.count(), max));
            ctx.log().info(".hello by " + player.getName() + " (" + player.getObjectId() + "): " + next.count() + " today, " + next.total() + " total");
        } catch (Exception e) {
            ctx.log().warn(".hello failed for " + player.getName(), e);
            player.sendMessage(ctx.i18n().get(player, "hello.error"));
        }
    }

    private int todayCount(Player player) {
        try {
            return repo.load(player.getObjectId()).filter(c -> c.day() == Greeting.day(LocalDate.now())).map(Greeting.Counter::count).orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private String statsLine(Player player) {
        try {
            final long[] s = repo.stats();
            return ctx.i18n().get(player, "hello.stats", s[0], s[1]);
        } catch (Exception e) {
            ctx.log().warn("stats failed", e);
            return ctx.i18n().get(player, "hello.error");
        }
    }
}
