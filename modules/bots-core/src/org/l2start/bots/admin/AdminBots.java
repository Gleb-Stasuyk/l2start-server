package org.l2start.bots.admin;

import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import org.l2start.bots.persona.Persona;
import org.l2start.bots.persona.Traits;
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
                        gm.sendMessage("Logged out " + core.logoutAll("//bots despawn all by " + gm.getName(), false) + " bot(s).");
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
                case "stats":
                    showStats(gm);
                    return true;
                case "social":
                    showSocial(gm);
                    return true;
                case "pvp":
                    showPvp(gm);
                    return true;
                case "pk":
                    makePk(gm, st.hasMoreTokens() ? st.nextToken() : "");
                    return true;
                case "pause":
                case "resume":
                    core.brains().setPaused("pause".equals(sub));
                    gm.sendMessage("Bot runtime " + ("pause".equals(sub) ? "paused: bots stand still (stock AI only)." : "resumed."));
                    core.ctx().log().info("runtime " + sub + "d by " + gm.getName());
                    return true;
                case "director": {
                    final org.l2start.bots.core.DirectorRunner d = core.director();
                    if (d == null) {
                        gm.sendMessage("Presence = manual: no director (module key Presence).");
                        return true;
                    }
                    final String op = st.hasMoreTokens() ? st.nextToken().toLowerCase() : "";
                    if ("pause".equals(op) || "resume".equals(op)) {
                        d.setPaused("pause".equals(op));
                        gm.sendMessage("Director " + op + ("pause".equals(op) ? "d: no bot comes or goes (bots in the world keep playing)." : "d."));
                        core.ctx().log().info("director " + op + "d by " + gm.getName());
                    }
                    showDirector(gm, d);
                    return true;
                }
                case "near":
                    showNear(gm);
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
                    gm.sendMessage("//bots [list [page]] | director [pause|resume] | near | create <n> [classId] | spawn <n|name> [here] | despawn <name|all> | info <name> | stats | social | pvp | pk <name> | pause | resume | sit|stand|gear|return <name|all> | goto <name>");
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
        sb.append("<td><button value=\"Despawn all\" action=\"bypass -h admin_bots despawn all\" width=80 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td></tr>");
        sb.append("<tr><td><button value=\"Create 50\" action=\"bypass -h admin_bots create 50\" width=70 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td>");
        sb.append("<td><button value=\"Spawn 10\" action=\"bypass -h admin_bots spawn 10\" width=90 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td>");
        sb.append("<td><button value=\"Stats\" action=\"bypass -h admin_bots stats\" width=80 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td></tr></table><br>");
        sb.append("<table width=280><tr><td width=95>Name</td><td width=22>Lv</td><td width=75>Class</td><td width=20>Sex</td><td width=68>State</td></tr>");
        for (int i = (pg - 1) * PAGE; i < Math.min(all.size(), pg * PAGE); i++) {
            final BotRecord r = all.get(i);
            final Player p = r.player();
            final String state = r.status() == BotRecord.Status.RETIRED ? "<font color=\"808080\">retired</font>" : p != null ? (p.isDead() ? "<font color=\"FF6666\">dead</font>" : "<font color=\"66FF66\">" + shortGoal(r.goal()) + "</font>") : "offline";
            sb.append("<tr><td><a action=\"bypass -h admin_bots info ").append(r.name()).append("\">").append(r.name()).append("</a></td><td>").append(p != null ? p.getLevel() : "-").append("</td><td>")
                .append(className(r.classId())).append("</td><td>").append(r.persona() == null ? "-" : r.persona().female() ? "F" : "M").append("</td><td>").append(state).append("</td></tr>");
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
            sb.append("Level ").append(p.getLevel()).append(", HP ").append((int) p.getCurrentHp()).append("/").append(p.getMaxHp()).append(p.isDead() ? " (dead)" : "").append(p.isSitting() ? " (sitting)" : "").append(p.isRunning() ? ", runs" : ", walks").append("<br>");
            sb.append("Loc ").append(p.getX()).append(", ").append(p.getY()).append(", ").append(p.getZ()).append("<br>");
            final Item w = p.getActiveWeaponInstance();
            sb.append("Weapon: ").append(w == null ? "-" : w.getName()).append("<br>");
        }
        final Persona ps = r.persona();
        if (ps != null) {
            sb.append("<br><font color=\"LEVEL\">Persona</font> (seed ").append(ps.seed()).append(")<br>");
            sb.append("Name style ").append(ps.nameStyle()).append(", chat ").append(ps.lang()).append(", ").append(ps.female() ? "female" : "male").append(", face ").append(ps.face()).append(", hair ").append(ps.hairStyle()).append("/").append(ps.hairColor()).append("<br>");
            sb.append("Future class: ").append(className(ps.futureClassId())).append("<br>");
            final ZonedDateTime local = ZonedDateTime.now(ZoneId.of(ps.timezone()));
            sb.append("Plays: ").append(ps.chronotype()).append(", ").append(ps.timezone()).append(" (now ").append(String.format("%02d:%02d", local.getHour(), local.getMinute())).append(", their time ")
                .append(Math.round(ps.chronotype().activity(local.getHour(), local.getDayOfWeek()) * 100)).append("%, ").append(ps.chronotype().hoursPerDay()).append(" h a day)<br>");
            final Traits t = ps.traits();
            sb.append("Traits: aggr ").append(t.aggression()).append(", social ").append(t.sociability()).append(", greed ").append(t.greed()).append("<br>");
            sb.append("patience ").append(t.patience()).append(", skill ").append(t.skill()).append(" (reaction ").append(t.reactionMs()).append(" ms), chat ").append(t.chattiness()).append("<br>");
        }
        sb.append("Home ").append(r.homeX()).append(", ").append(r.homeY()).append(", ").append(r.homeZ()).append(", goal ").append(r.goal()).append(r.restore() ? ", back after restart" : "").append("<br><br>");
        final org.l2start.bots.api.Assignment a = r.assignment();
        sb.append("Order: ").append(a == null ? "none (manual)" : escape(a.toString())).append(", paper level ").append(r.paperLevel(System.currentTimeMillis())).append("<br>");
        sb.append("Deaths: ").append(r.deaths()).append(", whispers received: ").append(r.whispers()).append("<br>");
        final org.l2start.bots.core.BotsCoreModule.MemoryView mem = core.memoryOf(r);
        if (!mem.lines().isEmpty()) {
            sb.append("Memory: ");
            for (String l : mem.lines()) {
                sb.append(escape(l)).append("<br>");
            }
        }
        if (r.trouble() != null) {
            sb.append("<font color=\"FF6666\">Last trouble: ").append(escape(r.trouble())).append("</font><br>");
        }
        final org.l2start.bots.runtime.BotSlot slot = r.slot();
        if (slot != null) {
            final long now = System.currentTimeMillis();
            final double live = core.brains().nearestLive(r);
            sb.append("<br><font color=\"LEVEL\">Brain</font> ").append(slot.brain() == null ? "none" : escape(slot.brain().state())).append(", LOD ").append(slot.lod().level())
                .append(Double.isInfinite(live) ? "" : " (live player " + (int) live + ")").append("<br>");
            final String more = slot.brain() == null ? "" : slot.brain().details();
            if ((more != null) && !more.isEmpty()) {
                sb.append(escape(more).replace("\n", "<br>")).append("<br>");
            }
            sb.append(String.format(java.util.Locale.ROOT, "Ticks %d, last tick %.2f ms, next in %d ms, mailbox %d", slot.ticks(), slot.lastTickNanos() / 1e6, Math.max(0, slot.nextTickAt() - now), slot.mailbox().size())).append("<br>");
            sb.append("Errors ").append(slot.totalErrors()).append(slot.safeIdle(now) ? " <font color=\"FF6666\">SAFE_IDLE</font>" : "").append(slot.lastError() == null ? "" : ", last: " + escape(slot.lastError())).append("<br>");
            final SimpleDateFormat hm = new SimpleDateFormat("HH:mm:ss");
            for (org.l2start.bots.runtime.ActionLog.Entry e : slot.log().entries()) {
                sb.append("<font color=\"808080\">").append(hm.format(new Date(e.at()))).append("</font> ").append(escape(e.text())).append("<br>");
            }
        }
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

    private static String shortGoal(String g) {
        if ((g == null) || g.isEmpty()) {
            return "world";
        }
        return g.length() > 12 ? g.substring(0, 12) : g;
    }

    private void showStats(Player gm) {
        final org.l2start.bots.runtime.Ticker t = core.brains().ticker();
        final StringBuilder sb = new StringBuilder("<html><title>Bots: runtime</title><body>");
        sb.append("<font color=\"LEVEL\">Runtime</font>").append(t != null && t.paused() ? " <font color=\"FF6666\">PAUSED</font>" : "").append("<br>");
        sb.append("Brain: ").append(core.brains().brainId()).append(core.brains().brainRegistered() ? "" : " <font color=\"FF6666\">(not registered: bots stand)</font>").append("<br>");
        if (t != null) {
            int active = 0;
            int present = 0;
            final java.util.Map<String, Integer> goals = new java.util.TreeMap<>();
            for (org.l2start.bots.runtime.BotSlot s : t.slots()) {
                if (s.lod().level() == org.l2start.bots.runtime.Lod.Level.ACTIVE) {
                    active++;
                } else {
                    present++;
                }
                final Object o = s.brain() == null ? "-" : s.brain().state();
                final String g = String.valueOf(o);
                final int cut = g.indexOf('/');
                goals.merge(cut > 0 ? g.substring(0, cut) : g, 1, Integer::sum);
            }
            sb.append("Ticking ").append(t.size()).append(": ACTIVE ").append(active).append(", PRESENT ").append(present).append("<br>");
            sb.append("States: ").append(goals).append("<br>");
            sb.append("Shards ").append(t.shardCount()).append(", ticks ").append(t.ticks()).append(", overruns ").append(t.overruns()).append(", errors ").append(t.errors()).append("<br>");
            sb.append(String.format(java.util.Locale.ROOT, "Tick p50 %.2f ms, p99 %.2f ms, max %.2f ms<br>", t.percentileNanos(50) / 1e6, t.percentileNanos(99) / 1e6, t.percentileNanos(100) / 1e6));
            for (int i = 0; i < t.shardCount(); i++) {
                final org.l2start.bots.runtime.Shard s = t.shard(i);
                sb.append(String.format(java.util.Locale.ROOT, "&nbsp;shard %d: %d bots, waves %d, overrun %d, p99 %.2f ms<br>", i, s.size(), s.waves(), s.overruns(), s.stats().percentile(99) / 1e6));
            }
        }
        final Map<String, Long> m = core.ctx().metrics().snapshot();
        sb.append("<br>Actions ").append(m.getOrDefault("actions", 0L)).append(" (rejected ").append(m.getOrDefault("actionsRejected", 0L)).append("), moves ").append(m.getOrDefault("moves", 0L)).append(", attacks ").append(m.getOrDefault("attacks", 0L)).append("<br>");
        sb.append("Kills ").append(m.getOrDefault("kills", 0L)).append(", deaths ").append(m.getOrDefault("deaths", 0L)).append(", level-ups ").append(m.getOrDefault("levelUps", 0L)).append(", brain errors ").append(m.getOrDefault("brainErrors", 0L)).append("<br>");
        sb.append("<br><table width=280><tr>");
        sb.append("<td><button value=\"Refresh\" action=\"bypass -h admin_bots stats\" width=70 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td>");
        final boolean paused = (t != null) && t.paused();
        sb.append("<td><button value=\"").append(paused ? "Resume" : "Pause").append("\" action=\"bypass -h admin_bots ").append(paused ? "resume" : "pause").append("\" width=70 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td>");
        sb.append("<td><button value=\"List\" action=\"bypass -h admin_bots list\" width=70 height=20 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\"></td></tr></table>");
        sb.append("</body></html>");
        gm.sendPacket(new NpcHtmlMessage(sb.toString()));
    }

    /** Phase 7b/7c: hourly PvP counters + online PK bots. */
    private void showPvp(Player gm) {
        final org.l2start.bots.chat.PvpBoard board = core.brains().pvp();
        final long now = System.currentTimeMillis();
        final java.util.Map<org.l2start.bots.chat.PvpBoard.Stat, Integer> c = board.hourCounts(now);
        final StringBuilder sb = new StringBuilder("<html><title>Bots: PvP</title><body>");
        sb.append("<font color=\"LEVEL\">PvP (last hour)</font><br>");
        sb.append("First attacks: ").append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.ATTACK, 0)).append("<br>");
        sb.append("Defence: ").append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.DEFEND, 0)).append("<br>");
        sb.append("Revenge: ").append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.REVENGE, 0)).append("<br>");
        sb.append("Flees: ").append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.FLEE, 0)).append("<br>");
        sb.append("Call help: ").append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.CALL_HELP, 0)).append("<br>");
        sb.append("PK hunts: ").append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.PK_HUNT, 0)).append("<br>");
        sb.append("Duels accepted: ").append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.DUEL_OK, 0)).append(", declined: ")
            .append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.DUEL_NO, 0)).append("<br>");
        sb.append("Blocked by cooldown: ").append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.LIMIT, 0)).append("<br>");
        sb.append("Blocked by peace zone: ").append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.ZONE, 0)).append("<br>");
        sb.append("PK kills: ").append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.PK_KILL, 0)).append(", deaths: ")
            .append(c.getOrDefault(org.l2start.bots.chat.PvpBoard.Stat.PK_DEATH, 0)).append("<br>");
        sb.append("<br><font color=\"LEVEL\">PK bots online</font><br>");
        int n = 0;
        for (BotRecord r : core.registry().all()) {
            if (!r.inWorld() || (r.persona() == null) || !r.persona().pk()) {
                continue;
            }
            n++;
            final Player p = r.player();
            final int karma = p == null ? 0 : p.getKarma();
            sb.append(r.name()).append(" karma ").append(karma).append(" K/D ")
                .append(board.pkKillsHour(r.botId(), now)).append('/').append(board.pkDeathsHour(r.botId(), now)).append("<br>");
        }
        if (n == 0) {
            sb.append("(none — //bots pk &lt;name&gt;)<br>");
        }
        sb.append("<br><table width=280><tr>");
        button(sb, "Refresh", "pvp");
        button(sb, "Social", "social");
        button(sb, "List", "list");
        sb.append("</tr></table></body></html>");
        gm.sendPacket(new NpcHtmlMessage(sb.toString()));
    }

    /** Phase 7c: mark persona as PK and spawn next to the GM for testing. */
    private void makePk(Player gm, String name) {
        if (name == null || name.isBlank()) {
            gm.sendMessage("Usage: //bots pk <name>");
            return;
        }
        try {
            final String msg = core.makePkNear(name, gm);
            gm.sendMessage(msg);
        } catch (Exception e) {
            gm.sendMessage("pk failed: " + e.getMessage());
            core.ctx().log().warn("//bots pk failed", e);
        }
    }

    /** Phase 6g: hourly social counters and who is ANNOYED. */
    private void showSocial(Player gm) {
        final org.l2start.bots.chat.SocialBoard board = core.brains().social();
        if (board == null) {
            gm.sendMessage("Social board is not ready.");
            return;
        }
        final long now = System.currentTimeMillis();
        final java.util.Map<org.l2start.bots.chat.SocialBoard.Stat, Integer> c = board.hourCounts(now);
        final StringBuilder sb = new StringBuilder("<html><title>Bots: social</title><body>");
        sb.append("<font color=\"LEVEL\">Social (last hour)</font><br>");
        sb.append("LFP shouts: ").append(c.getOrDefault(org.l2start.bots.chat.SocialBoard.Stat.SHOUT, 0)).append(" / ").append(board.shoutsPerHour()).append("<br>");
        sb.append("LFP replies: ").append(c.getOrDefault(org.l2start.bots.chat.SocialBoard.Stat.LFP_REPLY, 0)).append("<br>");
        sb.append("Whisper answers: ").append(c.getOrDefault(org.l2start.bots.chat.SocialBoard.Stat.WHISPER, 0)).append(" (cap ").append(board.whispersPerHour()).append("/bot)<br>");
        sb.append("Invites accepted: ").append(c.getOrDefault(org.l2start.bots.chat.SocialBoard.Stat.INVITE_OK, 0)).append(", declined: ")
            .append(c.getOrDefault(org.l2start.bots.chat.SocialBoard.Stat.INVITE_NO, 0)).append("<br>");
        sb.append("Party reactions: ").append(c.getOrDefault(org.l2start.bots.chat.SocialBoard.Stat.EMOTE, 0)).append("<br>");
        sb.append("Limit hits: ").append(c.getOrDefault(org.l2start.bots.chat.SocialBoard.Stat.LIMIT, 0)).append("<br>");
        sb.append("<br><font color=\"LEVEL\">ANNOYED</font><br>");
        final java.util.List<String> annoyed = core.annoyedSummary();
        if (annoyed.isEmpty()) {
            sb.append("(none)<br>");
        } else {
            for (String line : annoyed) {
                sb.append(escape(line)).append("<br>");
            }
        }
        sb.append("<br><table width=280><tr>");
        button(sb, "Refresh", "social");
        button(sb, "Stats", "stats");
        button(sb, "List", "list");
        sb.append("</tr></table></body></html>");
        gm.sendPacket(new NpcHtmlMessage(sb.toString()));
    }

    private void showDirector(Player gm, org.l2start.bots.core.DirectorRunner d) {
        final StringBuilder sb = new StringBuilder("<html><title>Bots: presence director</title><body>");
        sb.append("<font color=\"LEVEL\">Director</font>").append(d.paused() ? " <font color=\"FF6666\">PAUSED</font>" : "").append("<br>");
        sb.append("<font color=\"808080\">").append(escape(d.statsLine())).append("</font><br>");
        for (String line : d.director().kindLines()) {
            sb.append(escape(line)).append("<br>");
        }
        sb.append("<br>");
        sb.append("<table width=280><tr><td width=130>Place</td><td width=50>players</td><td width=50>bots</td><td width=50>target</td></tr>");
        int n = 0;
        for (org.l2start.bots.director.Director.PlaceState p : d.director().places()) {
            if (n++ >= 20) {
                break;
            }
            sb.append("<tr><td>").append(p.town() ? "<font color=\"LEVEL\">" + p.id() + "</font>" : p.id()).append("</td><td>").append(p.players()).append("</td><td>")
                .append(p.present()).append(p.pending() > 0 ? "+" + p.pending() : "").append("</td><td>").append(p.target()).append("</td></tr>");
        }
        sb.append("</table><br><font color=\"LEVEL\">Last decisions</font><br>");
        for (String line : d.director().decisions()) {
            sb.append(escape(line)).append("<br>");
        }
        sb.append("<br><table width=280><tr>");
        button(sb, "Refresh", "director");
        button(sb, d.paused() ? "Resume" : "Pause", "director " + (d.paused() ? "resume" : "pause"));
        button(sb, "Near me", "near");
        button(sb, "List", "list");
        sb.append("</tr></table></body></html>");
        gm.sendPacket(new NpcHtmlMessage(sb.toString()));
    }

    private void showNear(Player gm) {
        final org.l2start.bots.core.DirectorRunner d = core.director();
        final StringBuilder sb = new StringBuilder("<html><title>Bots: around me</title><body>");
        final org.l2start.bots.api.Pos me = new org.l2start.bots.api.Pos(gm.getX(), gm.getY(), gm.getZ());
        if (d != null) {
            final org.l2start.bots.director.WorldCatalog w = d.director().catalog();
            final org.l2start.bots.director.WorldCatalog.Town t = w.townAt(me, d.director().config().townMargin());
            sb.append("Town: ").append(t == null ? "-" : t.name() + " (lv " + t.minLevel() + "-" + t.maxLevel() + ", " + t.anchors().size() + " points)").append("<br>");
            sb.append("Hunting grounds within ").append(d.director().config().groundReach()).append(":<br>");
            int n = 0;
            for (org.l2start.bots.director.WorldCatalog.Ground g : w.groundsNear(me, d.director().config().groundReach())) {
                if (n++ >= 8) {
                    break;
                }
                sb.append("&nbsp;").append(g.id()).append(" lv ").append(g.minLevel()).append("-").append(g.maxLevel()).append(", ").append(g.monsters()).append(" mobs, ")
                    .append((int) g.center().distance2d(me)).append(" away").append(g.indoor() ? ", indoor" : "").append("<br>");
            }
        } else {
            sb.append("Presence = manual: no director.<br>");
        }
        sb.append("<br><font color=\"LEVEL\">Bots within 6000</font><br>");
        final long now = System.currentTimeMillis();
        for (BotRecord r : core.registry().sorted()) {
            final Player p = r.player();
            if ((p == null) || (p.calculateDistance2D(gm) > 6000)) {
                continue;
            }
            final org.l2start.bots.api.Assignment a = r.assignment();
            sb.append("<a action=\"bypass -h admin_bots info ").append(r.name()).append("\">").append(r.name()).append("</a> lv ").append(p.getLevel()).append(", ")
                .append((int) p.calculateDistance2D(gm)).append(" away, ").append(a == null ? "no order" : escape(a.toString())).append("<br>");
        }
        sb.append("<br><a action=\"bypass -h admin_bots director\">Director</a> <a action=\"bypass -h admin_bots list\">List</a></body></html>");
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
