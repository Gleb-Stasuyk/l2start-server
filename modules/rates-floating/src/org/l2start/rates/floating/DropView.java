package org.l2start.rates.floating;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.holders.npc.DropGroupHolder;
import org.l2jmobius.gameserver.entity.actor.holders.npc.DropHolder;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.util.MathUtil;

/**
 * EXTRAS_PLAN §2: the drop and spoil lists of shift+click ({@code NpcViewMod dropList DROP|SPOIL <objectId> [page]})
 * with the chances and amounts of rates-floating for the viewer; every other NpcViewMod page stays stock.
 */
final class DropView implements IBypassHandler {
    private static final int PER_PAGE = 12;
    private final RatesFloatingModule m;
    private final IBypassHandler stock;

    DropView(RatesFloatingModule m, IBypassHandler stock) {
        this.m = m;
        this.stock = stock;
    }

    @Override
    public String[] getCommandList() {
        return stock.getCommandList();
    }

    /** Chance shown for a stock chance {@code p} (0..1) with {@code c} rolls on average, an item got at most once. */
    static double shown(double p, double c) {
        final double q = Math.max(0, Math.min(1, p));
        if (c <= 1) {
            return q * Math.max(0, c);
        }
        final int whole = (int) Math.floor(c);
        final double frac = c - whole;
        final double none = Math.pow(1 - q, whole) * (1 - (frac * q));
        return 1 - none;
    }

    @Override
    public boolean onCommand(String command, Player player, Creature origin) {
        final StringTokenizer st = new StringTokenizer(command);
        st.nextToken();
        if (!m.enabled() || !st.hasMoreTokens() || !"droplist".equalsIgnoreCase(st.nextToken()) || (st.countTokens() < 2)) {
            return stock.onCommand(command, player, origin);
        }
        final boolean spoil = "SPOIL".equalsIgnoreCase(st.nextToken());
        final WorldObject o;
        int page = 0;
        try {
            o = World.findObject(Integer.parseInt(st.nextToken()));
            if (st.hasMoreTokens()) {
                page = Integer.parseInt(st.nextToken());
            }
        } catch (NumberFormatException e) {
            return false;
        }
        if (!(o instanceof Npc npc) || !npc.isMonster() || (npc instanceof GrandBoss)) {
            return stock.onCommand(command, player, origin);
        }
        player.sendPacket(page(player, npc, spoil, Math.max(0, page)));
        return true;
    }

    private record Line(int id, String name, String amount, double chance) {
    }

    private NpcHtmlMessage page(Player player, Npc npc, boolean spoil, int page) {
        final Rules r = m.rules();
        final NpcTemplate t = npc.getTemplate();
        final boolean raid = npc.isRaid() && !npc.isRaidMinion();
        final boolean champion = !raid && npc.isChampion();
        final int level = RatesFloatingModule.refLevel(player);
        final double f = raid ? 1 : r.f(level, npc.getLevel());
        final double c = (raid ? r.raidChance() : r.chance(spoil, f)) * (champion ? r.championChance() : 1);
        final double adena = (raid ? r.raidAdena() : r.adenaRate()) * (champion ? r.championAdena() : 1);
        final int diff = npc.getLevel() - player.getLevel();
        final double gapItem = MathUtil.scaleToRange(diff, -RatesConfig.DROP_ITEM_MAX_LEVEL_DIFFERENCE, -RatesConfig.DROP_ITEM_MIN_LEVEL_DIFFERENCE, RatesConfig.DROP_ITEM_MIN_LEVEL_GAP_CHANCE, 100d) / 100;
        final double gapAdena = MathUtil.scaleToRange(diff, -RatesConfig.DROP_ADENA_MAX_LEVEL_DIFFERENCE, -RatesConfig.DROP_ADENA_MIN_LEVEL_DIFFERENCE, RatesConfig.DROP_ADENA_MIN_LEVEL_GAP_CHANCE, 100d) / 100;

        final List<Line> lines = new ArrayList<>();
        final List<DropHolder> flat = new ArrayList<>();
        final List<Double> stockChance = new ArrayList<>();
        if (spoil) {
            if (t.getSpoilList() != null) {
                for (DropHolder d : t.getSpoilList()) {
                    flat.add(d);
                    stockChance.add(d.getChance() / 100);
                }
            }
        } else {
            if (t.getDropGroups() != null) {
                for (DropGroupHolder g : t.getDropGroups()) {
                    for (DropHolder d : g.getDropList()) {
                        flat.add(d);
                        stockChance.add((d.getChance() / 100) * (g.getChance() / 100));
                    }
                }
            }
            if (t.getDropList() != null) {
                for (DropHolder d : t.getDropList()) {
                    flat.add(d);
                    stockChance.add(d.getChance() / 100);
                }
            }
        }
        for (int i = 0; i < flat.size(); i++) {
            final DropHolder d = flat.get(i);
            final ItemTemplate it = ItemData.getInstance().getTemplate(d.getItemId());
            final String name = (it == null) ? String.valueOf(d.getItemId()) : it.getName();
            final double p = stockChance.get(i);
            final Kind k = m.kinds().of(d.getItemId());
            switch (k) {
                case ADENA -> lines.add(new Line(d.getItemId(), name, Math.round(d.getMin() * adena) + "-" + Math.round(d.getMax() * adena), p * gapAdena));
                case STOCK -> lines.add(new Line(d.getItemId(), name, d.getMin() + "-" + d.getMax(), p * gapItem));
                case EXACT -> lines.add(new Line(d.getItemId(), name, "1", shown(p * gapItem, c)));
                case STACK -> {
                    final double hi = raid ? 1 : r.amountMax();
                    lines.add(new Line(d.getItemId(), name, d.getMin() + "-" + Math.round(d.getMax() * hi), shown(p * gapItem, c)));
                }
            }
        }
        lines.sort((a, b) -> Double.compare(b.chance(), a.chance()));

        final StringBuilder sb = new StringBuilder(2048);
        sb.append("<html><title>").append(spoil ? "Spoil" : "Drop").append(": ").append(npc.getName()).append(" (").append(npc.getLevel()).append(")</title><body>");
        sb.append(String.format(Locale.ROOT, "<font color=LEVEL>F %.2f, chance x%.2f%s</font><br1>", f, c, champion ? ", champion" : raid ? ", raid" : ""));
        sb.append("<font color=808080>for level ").append(level).append(", adena x").append(String.format(Locale.ROOT, "%.0f", adena)).append("</font><br>");
        sb.append("<table width=280>");
        final int pages = Math.max(1, (lines.size() + PER_PAGE - 1) / PER_PAGE);
        final int pg = Math.min(page, pages - 1);
        for (int i = pg * PER_PAGE; i < Math.min(lines.size(), (pg + 1) * PER_PAGE); i++) {
            final Line l = lines.get(i);
            sb.append("<tr><td width=150>").append(l.name()).append("</td><td width=60>").append(l.amount()).append("</td><td width=70 align=right>")
                .append(String.format(Locale.ROOT, l.chance() >= 0.0995 ? "%.0f%%" : l.chance() >= 0.00995 ? "%.1f%%" : "%.3f%%", Math.min(100, l.chance() * 100))).append("</td></tr>");
        }
        sb.append("</table><br>");
        if (lines.isEmpty()) {
            sb.append("Nothing.<br>");
        }
        final String type = spoil ? "SPOIL" : "DROP";
        for (int i = 0; i < pages; i++) {
            sb.append(i == pg ? "[" + (i + 1) + "] " : "<a action=\"bypass NpcViewMod dropList " + type + " " + npc.getObjectId() + " " + i + "\">" + (i + 1) + "</a> ");
        }
        sb.append("<br><a action=\"bypass NpcViewMod dropList ").append(spoil ? "DROP" : "SPOIL").append(" ").append(npc.getObjectId()).append("\">").append(spoil ? "Drop" : "Spoil")
            .append("</a> | <a action=\"bypass NpcViewMod view ").append(npc.getObjectId()).append("\">Back</a></body></html>");
        final NpcHtmlMessage msg = new NpcHtmlMessage(npc.getObjectId());
        msg.setHtml(sb.toString());
        return msg;
    }
}
