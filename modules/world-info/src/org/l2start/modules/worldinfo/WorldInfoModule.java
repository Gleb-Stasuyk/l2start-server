package org.l2start.modules.worldinfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilderFactory;

import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.managers.TownManager;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** EXTRAS_PLAN §4: monster levels next to teleport destinations (hook P8-TELEPORT-NAME). */
public final class WorldInfoModule implements GameModule {
    private record Manual(int x, int y, String levels) {
    }

    private final Map<Long, String> cache = new ConcurrentHashMap<>();
    private final List<Manual> manual = new ArrayList<>();
    private ModuleContext ctx;

    @Override
    public void onEnable(ModuleContext c) throws Exception {
        ctx = c;
        loadManual(c.resources().path("data/teleport-levels.xml"));
        c.onTeleportName((player, where, name) -> c.config().getBoolean("TeleportLevels") ? LevelRange.label(name, range(where)) : name);
        c.log().info("enabled: radius " + c.config().getInt("Radius") + ", manual points " + manual.size());
    }

    private void loadManual(Path file) throws Exception {
        if (!Files.exists(file)) {
            return;
        }
        final NodeList l = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getElementsByTagName("point");
        for (int i = 0; i < l.getLength(); i++) {
            final Element e = (Element) l.item(i);
            manual.add(new Manual(Integer.parseInt(e.getAttribute("x")), Integer.parseInt(e.getAttribute("y")), e.getAttribute("levels")));
        }
    }

    private String range(Location where) {
        for (Manual m : manual) {
            if ((Math.abs(m.x() - where.getX()) <= 300) && (Math.abs(m.y() - where.getY()) <= 300)) {
                return m.levels();
            }
        }
        final long key = (((long) where.getX()) << 32) ^ (where.getY() & 0xffffffffL);
        final String r = cache.computeIfAbsent(key, k -> compute(where));
        return r.isEmpty() ? null : r;
    }

    private String compute(Location where) {
        if (TownManager.getTown(where.getX(), where.getY(), where.getZ()) != null) {
            return "";
        }
        final long r2 = (long) ctx.config().getInt("Radius") * ctx.config().getInt("Radius");
        final List<Integer> levels = new ArrayList<>();
        for (Set<Spawn> set : SpawnTable.getInstance().getSpawnTable().values()) {
            for (Spawn s : set) {
                final NpcTemplate t = s.getTemplate();
                if ((t == null) || !t.isType("Monster")) {
                    continue; // not raids, guards, NPCs
                }
                final long dx = s.getX() - where.getX();
                final long dy = s.getY() - where.getY();
                if (((dx * dx) + (dy * dy)) <= r2) {
                    levels.add((int) t.getLevel());
                }
            }
        }
        final String r = LevelRange.of(levels.stream().mapToInt(Integer::intValue).toArray(), ctx.config().getInt("MinSpawns"), ctx.config().getInt("LowPercentile"), ctx.config().getInt("HighPercentile"));
        return r == null ? "" : r;
    }
}
