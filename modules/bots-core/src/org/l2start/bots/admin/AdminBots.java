package org.l2start.bots.admin;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2start.bots.core.BotRecord;
import org.l2start.bots.core.BotsCoreModule;
import org.l2start.bots.persona.ClassPicker;

/**
 * //bots ... (GM only: no AdminCommands.xml entry, so the stock rule "highest access level" applies).
 * Phase 3a: create, spawn, despawn, list, info, plus small actions (sit, stand, gear, return, goto) to check
 * in the client that players see bots like players.
 */
public final class AdminBots implements IAdminCommandHandler {
    private static final int PAGE = 15;
    private final BotsCoreModule core;

    public AdminBots(BotsCoreModule core) {
        this.core = core;
    }

    @Override
    public String[] getCommandList() {
        return new String[] { "admin_bots" };
    }

    @Override
    public boolean onCommand(String command, Player gm) {
        final StringTokenizer st = new StringTokenizer(command);
        st.nextToken();
        final String sub = st.hasMoreTokens() ? st.nextToken().toLowerCase() : "";
        try {
            switch (sub) {
                case "":
                case "list":
                    showList(gm, st.hasMoreTokens() ? parseInt(st.nextToken(), 1) : 1);
                    return true;
                case "create": {
                    final int n = st.hasMoreTokens() ? parseInt(st.nextToken(), 1) : 1;
                    final int classId = st.hasMoreTokens() ? parseInt(st.nextToken(), -1) : -1;
                    if ((classId >= 0) && !ClassPicker.isStartingClass(classId)) {
                        gm.sendMessage("Class " + classId + " is not a starting class (0,10,18,25,31,38,44,49,53).");
                        return true;
                    }
                    gm.sendMessage("Creating " + n + " bot(s) ...");
                    core.ctx().scheduler().schedule("admin create", java.time.Duration.ZERO, () -> {
                        final List<String> errors = new ArrayList<>();
                        final int done = core.create(Math.min(n, 500), classId, errors);
                        gm.sendMessage("Created " + done + " bot(s)." + (errors.isEmpty() ? "" : " Problem: " + errors.get(0)));
                    });
                    return true;
                }
                case "spawn": {
                    final String what = st.hasMoreTokens() ? st.nextToken() : "1";
                    final boolean here = st.hasMoreTokens() && "here".equalsIgnoreCase(st.nextToken());
                    final Location near = here ? new Location(gm.getX(), gm.getY(), gm.getZ()) : null;
                    final List<String> errors = new ArrayList<>();
                    final BotRecord named = isNumber(what) ? null : core.registry().byName(what);
                    if (!isNumber(what) && (named == null)) {
                        gm.sendMessage("No bot named " + what + ".");
                        return true;
                    }
                    final int done = named != null ? (core.spawnOne(named, near, errors) ? 1 : 0) : core.spawn(Math.min(parseInt(what, 1), 500), near, errors);
                    gm.sendMessage("In world: +" + done + (errors.isEmpty() ? "" : ". Problem: " + errors.get(0)));
                    return true;
                }
                case "despawn": {
                    final String what = st.hasMoreTokens() ? st.nextToken() : "";
                    if ("all".equalsIgnoreCase(what)) {
                        gm.sendMessage("Logged out " + core.logoutAll("//bots despawn all by " + gm.getName()) + " bot(s).");
                    } else {
                        final BotRecord r = core.registry().byName(what);
                        gm.sendMessage(r == null ? "No bot named " + what + "." : core.despawn(r) ? r.name() + " logged out." : r.name() + " is not in the world.");
                    }
                    return true;
                }
                case "info": {
                    final BotRecord r = core.registry().byName(st.hasMoreTokens() ? st.nextToken() : "");
                    if (r == null) {
                        gm.sendMessage("Usage: //bots info <name>");
                    } else {
                        showInfo(gm, r);
                    }
                    return true;
                }
                case "sit":
                case "stand":
                case "gear":
                case "return":
                    act(gm, sub, st.hasMoreTokens() ? st.nextToken() : "");
                    return true;
                case "goto": {
                    final BotRecord r = core.registry().byName(st.hasMoreTokens() ? st.nextToken() : "");
                    if ((r == null) || !r.inWorld()) {
                        gm.sendMessage("No such bot in the world.");
                    } else {
                        gm.teleToLocation(r.player(), true);
                    }
                    return true;
                }
                default:
                    gm.sendMessage("//bots [list [page]] | create <n> [classId] | spawn <n|name> [here] | despawn <name|all> | info <name> | sit|stand|gear|return <name|all> | goto <name>");
                    return true;
            }
        } catch (RuntimeException e) {
            core.ctx().log().warn("//bots " + sub + " failed", e);
            gm.sendMessage("//bots " + sub + " failed: " + e.getMessage());
            return true;
        }
    }

    /** Small visible actions, to check that players around a bot see what it does (hook P3-BOT-CHARINFO). */
    private void act(Player gm, String action, String who) {
        final List<BotRecord> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(who)) {
            for (BotRecord r : core.registry().sorted()) {
                if (r.inWorld()) {
                    targets.add(r);
                }
            }
        } else {
            final BotRecord r = core.registry().byName(who);
            if ((r != null) && r.inWorld()) {
                targets.add(r);
            }
        }
        if (targets.isEmpty()) {
            gm.sendMessage("No such bot in the world.");
            return;
        }
        for (BotRecord r : targets) {
            final Player p = r.player();
            switch (action) {
                case "sit" -> p.sitDown();
                case "stand" -> p.standUp();
                case "gear" -> toggleWeapon(p);
                case "return" -> core.world().returnToTown(r);
                default -> {
                }
            }
        }
        gm.sendMessage(action + ": " + targets.size() + " bot(s).");
    }

    /** Take the weapon off, or put the first weapon of the inventory on: same path as a double click in the client. */
    private static void toggleWeapon(Player p) {
        final Item worn = p.getActiveWeaponInstance();
        if (worn != null) {
            p.useEquippableItem(worn, true);
            return;
        }
        for (Item it : p.getInventory().getItems()) {
            if (it.isWeapon()) {
                p.useEquippableItem(it, true);
                return;
            }
        }
    }

    private void showList(Player gm, int page) {
        final List<BotRecord> all = core.registry().sorted();
        final int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        final int pg = Math.max(1, Math.min(page, pages));
        final StringBuilder sb = new StringBuilder("<html><title>Bots</title><body>");
        sb.append("<font color=\"LEVEL\">bots-core</font> known ").append(all.size()).append(", in world ").append(core.registry().inWorld()).append("<br>");
        final Map<String, Long> m = core.ctx().metrics().snapshot();
        sb.append("<font color=\"808080\">created ").append(m.getOrDefault("created", 0L)).append(", logins ").append(m.getOrDefault("logins", 0L)).append(", deaths ").append(m.getOrDefault("deaths", 0L)).append(", whispers ").append(m.getOrDefault("whispersReceived", 0L)).append("</font><br>");
        sb.append("<table width=280><tr><td><button value=\"Create 5\" action=\"bypass -h admin_bots create 5\" width=70 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td>");
        sb.append("<td><button value=\"Spawn 5 here\" action=\"bypass -h admin_bots spawn 5 here\" width=90 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td>");
        sb.append("<td><button value=\"Despawn all\" action=\"bypass -h admin_bots despawn all\" width=80 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td></tr></table><br>");
        sb.append("<table width=280><tr><td width=110>Name</td><td width=40>Lv</td><td width=80>Class</td><td width=50>State</td></tr>");
        for (int i = (pg - 1) * PAGE; i < Math.min(all.size(), pg * PAGE); i++) {
            final BotRecord r = all.get(i);
            final Player p = r.player();
            final String state = r.status() == BotRecord.Status.RETIRED ? "<font color=\"808080\">retired</font>" : p != null ? (p.isDead() ? "<font color=\"FF6666\">dead</font>" : "<font color=\"66FF66\">world</font>") : "offline";
            sb.append("<tr><td><a action=\"bypass -h admin_bots info ").append(r.name()).append("\">").append(r.name()).append("</a></td><td>").append(p != null ? p.getLevel() : "-").append("</td><td>")
                .append(className(r.classId())).append("</td><td>").append(state).append("</td></tr>");
        }
        sb.append("</table><br>");
        if (pages > 1) {
            for (int i = 1; i <= pages; i++) {
                sb.append(i == pg ? "[" + i + "] " : "<a action=\"bypass -h admin_bots list " + i + "\">" + i + "</a> ");
            }
        }
        sb.append("</body></html>");
        gm.sendPacket(new NpcHtmlMessage(sb.toString()));
    }

    private void showInfo(Player gm, BotRecord r) {
        final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        final Player p = r.player();
        final StringBuilder sb = new StringBuilder("<html><title>Bot ").append(r.name()).append("</title><body>");
        sb.append("<font color=\"LEVEL\">").append(r.name()).append("</font> (bot ").append(r.botId()).append(", char ").append(r.charId()).append(")<br>");
        sb.append("Class: ").append(className(r.classId())).append("<br>Account: ").append(r.accountName()).append(" (banned in login)<br>");
        sb.append("Status: ").append(r.status()).append(p != null ? ", in world since " + f.format(new Date(r.inWorldSince())) : ", offline").append("<br>");
        sb.append("Last login: ").append(r.lastLogin() > 0 ? f.format(new Date(r.lastLogin())) : "-").append(", last logout: ").append(r.lastLogout() > 0 ? f.format(new Date(r.lastLogout())) : "-").append("<br>");
        if (p != null) {
            sb.append("Level ").append(p.getLevel()).append(", HP ").append((int) p.getCurrentHp()).append("/").append(p.getMaxHp()).append(p.isDead() ? " (dead)" : "").append(p.isSitting() ? " (sitting)" : "").append("<br>");
            sb.append("Loc ").append(p.getX()).append(", ").append(p.getY()).append(", ").append(p.getZ()).append("<br>");
            final Item w = p.getActiveWeaponInstance();
            sb.append("Weapon: ").append(w == null ? "-" : w.getName()).append("<br>");
        }
        sb.append("Deaths: ").append(r.deaths()).append(", whispers received: ").append(r.whispers()).append("<br>");
        if (r.lastWhisper() != null) {
            sb.append("Last whisper: ").append(escape(r.lastWhisper())).append("<br>");
        }
        sb.append("<br><table width=280><tr>");
        button(sb, p == null ? "Spawn here" : "Despawn", p == null ? "spawn " + r.name() + " here" : "despawn " + r.name());
        button(sb, "Go to", "goto " + r.name());
        button(sb, "Sit/Stand", (p != null) && p.isSitting() ? "stand " + r.name() : "sit " + r.name());
        button(sb, "Weapon", "gear " + r.name());
        sb.append("</tr></table><br><a action=\"bypass -h admin_bots list\">Back</a></body></html>");
        gm.sendPacket(new NpcHtmlMessage(sb.toString()));
    }

    private static void button(StringBuilder sb, String label, String cmd) {
        sb.append("<td><button value=\"").append(label).append("\" action=\"bypass -h admin_bots ").append(cmd).append("\" width=68 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td>");
    }

    private static String className(int classId) {
        final PlayerClass c = PlayerClass.getPlayerClass(classId);
        return c == null ? String.valueOf(classId) : c.toString();
    }

    private static String escape(String s) {
        return s.replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean isNumber(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
