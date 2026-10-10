package org.l2start.platform;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.AdminCommandHandler;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2start.platform.db.Migration;
import org.l2start.platform.loader.ModuleManifest.Dependency;
import org.l2start.platform.loader.ModuleState;
import org.l2start.platform.runtime.ModuleEntry;
import org.l2start.platform.runtime.ModuleGuard;

/**
 * //modules, //module info|reload|reload-all|disable &lt;id&gt;. Registered by the platform itself.
 * Without an AdminCommands.xml entry the stock AdminData grants these commands to the highest access level.
 */
final class AdminModules implements IAdminCommandHandler {
    private static final Logger LOGGER = Logger.getLogger("Platform");
    private static final String[] COMMANDS = { "admin_modules", "admin_module" };
    private final Platform platform;

    private AdminModules(Platform platform) {
        this.platform = platform;
    }

    static void register(Platform platform) {
        if (AdminCommandHandler.getInstance().getHandler(COMMANDS[0]) == null) {
            AdminCommandHandler.getInstance().registerHandler(new AdminModules(platform));
        }
    }

    @Override
    public String[] getCommandList() {
        return COMMANDS;
    }

    @Override
    public boolean onCommand(String command, Player player) {
        final String[] p = command.trim().split("\\s+");
        if (p[0].equals("admin_modules") || p.length < 2) {
            list(player);
            return true;
        }
        final String sub = p[1].toLowerCase();
        final String id = p.length > 2 ? p[2] : null;
        LOGGER.info("Platform: GM " + player.getName() + " used //module " + String.join(" ", java.util.Arrays.copyOfRange(p, 1, p.length)));
        switch (sub) {
            case "info":
                if (id == null || platform.entry(id) == null) {
                    player.sendMessage("Usage: //module info <id>");
                } else {
                    info(player, platform.entry(id));
                }
                return true;
            case "reload":
                player.sendMessage(id == null ? "Usage: //module reload <id>" : platform.reload(id));
                return true;
            case "reload-all":
                for (ModuleEntry e : platform.entries()) {
                    if (e.state.isActive()) {
                        player.sendMessage(platform.reload(e.id()));
                    }
                }
                return true;
            case "disable":
                if (id == null) {
                    player.sendMessage("Usage: //module disable <id>");
                } else {
                    player.sendMessage(platform.degrade(id, "disabled by GM " + player.getName()) ? id + " disabled until restart (use config/modules.ini to keep it off)" : id + " is not running");
                }
                return true;
            default:
                player.sendMessage("Usage: //modules | //module info|reload|reload-all|disable <id>");
                return true;
        }
    }

    private void list(Player player) {
        final StringBuilder sb = new StringBuilder("<html><title>Modules</title><body>");
        if (!platform.isActive()) {
            sb.append("Platform is disabled (DisableAll = true).");
        } else {
            sb.append("Profile: <font color=LEVEL>").append(platform.settings().profile()).append("</font>, failure policy: ").append(platform.settings().failurePolicy().name().toLowerCase()).append("<br>");
            final Map<ModuleState, Integer> c = platform.counts();
            sb.append("Running ").append(c.getOrDefault(ModuleState.RUNNING, 0)).append(", failed ").append(c.getOrDefault(ModuleState.FAILED, 0)).append(", refused ").append(c.getOrDefault(ModuleState.REFUSED, 0)).append(", disabled ").append(c.getOrDefault(ModuleState.DISABLED, 0)).append("<br><br>");
            sb.append("<table width=280><tr><td>id</td><td>ver</td><td>state</td><td>err</td><td>reg</td></tr>");
            for (ModuleEntry e : platform.entries()) {
                final ModuleGuard g = e.runtime == null ? null : e.runtime.guard();
                sb.append("<tr><td><a action=\"bypass -h admin_module info ").append(e.id()).append("\">").append(esc(e.id())).append("</a></td><td>").append(esc(e.version())).append("</td><td><font color=").append(color(e.state)).append(">").append(e.state).append("</font></td><td>").append(g == null ? "-" : g.breaker().total()).append("</td><td>").append(e.runtime == null ? "-" : e.runtime.ownership().size()).append("</td></tr>");
            }
            sb.append("</table>");
        }
        sb.append("</body></html>");
        player.sendPacket(new NpcHtmlMessage(0, sb.toString()));
        for (ModuleEntry e : platform.entries()) {
            player.sendMessage(e.id() + " " + e.version() + " " + e.state + (e.reason == null ? "" : ": " + e.reason));
        }
    }

    private void info(Player player, ModuleEntry e) {
        final StringBuilder sb = new StringBuilder("<html><title>Module ").append(esc(e.id())).append("</title><body>");
        sb.append("<font color=LEVEL>").append(esc(e.manifest == null ? e.id() : e.manifest.name())).append("</font> ").append(esc(e.version())).append("<br>");
        sb.append("State: <font color=").append(color(e.state)).append(">").append(e.state).append("</font><br>");
        if (e.reason != null) {
            sb.append("Reason: ").append(esc(e.reason)).append("<br>");
        }
        sb.append("Switch: ").append(esc(String.valueOf(e.switchSource))).append("<br>");
        if (e.manifest != null) {
            sb.append("Kind: ").append(e.manifest.kind().name().toLowerCase()).append("<br>");
            if (!e.manifest.dependencies().isEmpty()) {
                sb.append("Depends on: ");
                for (Dependency d : e.manifest.dependencies()) {
                    sb.append(esc(d.id())).append(' ').append(esc(d.version())).append(d.optional() ? " (optional) " : " ");
                }
                sb.append("<br>");
            }
            sb.append("Data: ").append(e.manifest.data().keySet()).append(" ids ").append(e.ids).append("<br>");
        }
        sb.append("Migrations: ");
        for (Migration m : e.migrations) {
            sb.append("V").append(m.version()).append(' ');
        }
        sb.append("(new this start: ").append(e.appliedMigrations).append(")<br>");
        if (e.runtime != null) {
            final ModuleGuard g = e.runtime.guard();
            sb.append("Registrations: ").append(e.runtime.ownership().counts()).append("<br>");
            sb.append("Calls: ").append(g.calls()).append(", time ").append(g.totalMillis()).append(" ms, slow ").append(g.slowCalls()).append(", errors ").append(g.breaker().total()).append("<br>");
            final List<String> errors = g.recentErrors();
            for (String err : errors) {
                sb.append("<font color=FF6666>").append(esc(err.length() > 120 ? err.substring(0, 120) : err)).append("</font><br>");
            }
            final Map<String, Long> metrics = e.runtime.metrics().snapshot();
            if (!metrics.isEmpty()) {
                sb.append("Metrics: ").append(esc(metrics.toString())).append("<br>");
            }
        }
        if (e.config != null) {
            sb.append("<br>Config:<br>");
            for (Map.Entry<String, String> kv : e.config.asMap().entrySet()) {
                sb.append(esc(kv.getKey())).append(" = ").append(esc(kv.getValue())).append(" <font color=808080>(").append(esc(e.config.source(kv.getKey()))).append(")</font><br>");
            }
        }
        sb.append("<br><a action=\"bypass -h admin_module reload ").append(e.id()).append("\">Reload config</a> | <a action=\"bypass -h admin_modules\">Back</a>");
        sb.append("</body></html>");
        player.sendPacket(new NpcHtmlMessage(0, sb.toString()));
    }

    private static String color(ModuleState s) {
        switch (s) {
            case RUNNING:
            case ENABLED:
                return "00FF00";
            case DISABLED:
            case STOPPED:
                return "808080";
            case DEGRADED:
                return "FFA500";
            default:
                return "FF4444";
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
