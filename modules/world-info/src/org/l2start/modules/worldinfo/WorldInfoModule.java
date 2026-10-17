package org.l2start.modules.worldinfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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

/**
 * EXTRAS_PLAN §4: monster levels next to teleport destinations (hook P8-TELEPORT-NAME).
 * Spawn index is built once (not a full SpawnTable scan per list row on the game thread).
 */
public final class WorldInfoModule implements GameModule {
    private record Manual(int x, int y, String levels) {
    }

    private record Monster(int x, int y, int level) {
    }

    private final Map<Long, String> cache = new ConcurrentHashMap<>();
    private final List<Manual> manual = new ArrayList<>();
    private final AtomicReference<List<Monster>> index = new AtomicReference<>(List.of());
    private ModuleContext ctx;

    @Override
    public void onEnable(ModuleContext c) throws Exception {
        ctx = c;
        loadManual(c.resources().path("data/teleport-levels.xml"));
        c.onTeleportName((player, where, name) -> c.config().getBoolean("TeleportLevels") ? LevelRange.label(name, range(where)) : name);
        c.log().info("enabled: radius " + c.config().getInt("Radius") + ", manual points " + manual.size());
    }

    @Override
    public void onServerReady(ModuleContext c) {
        // background: build spawn index + prefill cache for manual points (GK open must stay cheap)
        c.scheduler().schedule("world-info-warmup", java.time.Duration.ofSeconds(2), () -> {
            try {
                final int n = rebuildIndex();
                int warmed = 0;
                for (Manual m : manual) {
                    if ((m.levels() != null) && !m.levels().isEmpty()) {
                        continue; // manual string, no compute
                    }
                    range(new Location(m.x(), m.y(), 0));
                    warmed++;
                }
                c.log().info("warmup: " + n + " monster spawns indexed, " + cache.size() + " cache entries (+" + warmed + " manual locs)");
            } catch (Exception e) {
                c.log().warn("warmup failed: " + e.getMessage());
            }
        });
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
                return m.levels().isEmpty() ? null : m.levels();
            }
        }
        final long key = (((long) where.getX()) << 32) ^ (where.getY() & 0xffffffffL);
        final String r = cache.computeIfAbsent(key, k -> compute(where));
        return r.isEmpty() ? null : r;
    }

    /** One pass over SpawnTable → flat list for cheap radius queries. */
    int rebuildIndex() {
        final List<Monster> out = new ArrayList<>();
        for (Set<Spawn> set : SpawnTable.getInstance().getSpawnTable().values()) {
            for (Spawn s : set) {
                final NpcTemplate t = s.getTemplate();
                if ((t == null) || !t.isType("Monster")) {
                    continue;
                }
                out.add(new Monster(s.getX(), s.getY(), t.getLevel()));
            }
        }
        index.set(List.copyOf(out));
        return out.size();
    }

    private String compute(Location where) {
        if (TownManager.getTown(where.getX(), where.getY(), where.getZ()) != null) {
            return "";
        }
        List<Monster> monsters = index.get();
        if (monsters.isEmpty()) {
            rebuildIndex();
            monsters = index.get();
        }
        final long r2 = (long) ctx.config().getInt("Radius") * ctx.config().getInt("Radius");
        final List<Integer> levels = new ArrayList<>();
        final int wx = where.getX();
        final int wy = where.getY();
        for (Monster m : monsters) {
            final long dx = m.x() - wx;
            final long dy = m.y() - wy;
            if (((dx * dx) + (dy * dy)) <= r2) {
                levels.add(m.level());
            }
        }
        final String r = LevelRange.of(levels.stream().mapToInt(Integer::intValue).toArray(), ctx.config().getInt("MinSpawns"), ctx.config().getInt("LowPercentile"), ctx.config().getInt("HighPercentile"));
        return r == null ? "" : r;
    }
}
