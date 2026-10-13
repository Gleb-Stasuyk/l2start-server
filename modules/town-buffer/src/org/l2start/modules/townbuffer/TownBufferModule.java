package org.l2start.modules.townbuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.OnNpcFirstTalk;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillLearn;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/** EXTRAS_PLAN §5: paid Prophet buffs in 5 towns, adena per buff, stock duration. */
public final class TownBufferModule implements GameModule {
    private final Map<Integer, Map<Integer, Integer>> buffs = new ConcurrentHashMap<>();
    private ModuleContext ctx;

    @Override
    public void onEnable(ModuleContext c) {
        ctx = c;
        for (Towns.Town t : Towns.ALL) {
            c.events().<OnNpcFirstTalk> onNpc(t.npcId(), EventType.ON_NPC_FIRST_TALK, e -> page(e.getNpc(), e.getPlayer()));
        }
        c.handlers().bypass(new IBypassHandler() {
            @Override
            public boolean onCommand(String command, Player player, Creature origin) {
                if ((origin == null) || !origin.isNpc()) {
                    return false;
                }
                buy(origin.asNpc(), player, command.substring("townbuff".length()).trim());
                return true;
            }

            @Override
            public String[] getCommandList() {
                return new String[] { "townbuff" };
            }
        });
        c.log().info("enabled: 5 buffers, prices " + Towns.ALL.stream().map(t -> t.name() + " " + Towns.title(t.price())).toList());
    }

    private Map<Integer, Integer> buffsOf(Towns.Town t) {
        return buffs.computeIfAbsent(t.level(), lv -> {
            final List<BuffLevels.Learn> tree = new ArrayList<>();
            for (SkillLearn l : SkillTreeData.getInstance().getCompleteClassSkillTree(lv >= 76 ? PlayerClass.HIEROPHANT : PlayerClass.PROPHET).values()) {
                tree.add(new BuffLevels.Learn(l.getSkillId(), l.getSkillLevel(), l.getGetLevel()));
            }
            return BuffLevels.at(tree, Towns.BUFFS, lv);
        });
    }

    private long price(Towns.Town t) {
        return Math.round(t.price() * ctx.config().getDouble("PriceMultiplier"));
    }

    private void page(Npc npc, Player player) {
        final Towns.Town t = Towns.byNpc(npc.getId());
        if (t == null) {
            return;
        }
        final long price = price(t);
        final Map<Integer, Integer> list = buffsOf(t);
        final StringBuilder rows = new StringBuilder();
        for (Map.Entry<Integer, Integer> b : list.entrySet()) {
            final Skill s = SkillData.getInstance().getSkill(b.getKey(), b.getValue());
            if (s != null) {
                rows.append("<tr><td width=200><a action=\"bypass -h npc_%objectId%_townbuff ").append(b.getKey()).append("\">").append(s.getName()).append("</a></td><td width=60>").append(b.getValue()).append("</td></tr>");
            }
        }
        final NpcHtmlMessage msg = new NpcHtmlMessage(npc.getObjectId());
        msg.setFile(player, ctx.resources().html("buffer.htm"));
        msg.replace("%rows%", rows.toString());
        msg.replace("%level%", String.valueOf(t.level()));
        msg.replace("%price%", String.format("%,d", price));
        msg.replace("%all%", String.format("%,d", price * list.size()));
        msg.replace("%objectId%", String.valueOf(npc.getObjectId()));
        player.sendPacket(msg);
    }

    private void buy(Npc npc, Player player, String arg) {
        final Towns.Town t = Towns.byNpc(npc.getId());
        if (t == null) {
            return;
        }
        final Map<Integer, Integer> list = buffsOf(t);
        final List<Skill> wanted = new ArrayList<>();
        if ("all".equals(arg)) {
            list.forEach((id, lv) -> wanted.add(SkillData.getInstance().getSkill(id, lv)));
        } else {
            try {
                final int id = Integer.parseInt(arg);
                if (list.containsKey(id)) {
                    wanted.add(SkillData.getInstance().getSkill(id, list.get(id)));
                }
            } catch (NumberFormatException e) {
                return;
            }
        }
        wanted.removeIf(s -> s == null);
        if (wanted.isEmpty()) {
            return;
        }
        final long cost = price(t) * wanted.size();
        if ((cost > Integer.MAX_VALUE) || (player.getAdena() < cost) || !player.reduceAdena(ItemProcessType.FEE, (int) cost, npc, true)) {
            player.sendMessage("Не хватает адены: нужно " + String.format("%,d", cost) + ".");
            page(npc, player);
            return;
        }
        for (Skill s : wanted) {
            s.applyEffects(npc, player);
        }
        ctx.metrics().counter("buffs").add(wanted.size());
        page(npc, player);
    }
}
