package org.l2start.bots.director;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.data.xml.TeleporterData;
import org.l2jmobius.gameserver.entity.actor.enums.player.TeleportType;
import org.l2jmobius.gameserver.entity.teleporter.TeleportHolder;
import org.l2jmobius.gameserver.entity.teleporter.TeleportLocation;
import org.l2start.bots.api.Pos;

/**
 * Gatekeeper teleport graph for party catch-up (KT12 F1): towns linked by stock {@link TeleporterData} NORMAL
 * destinations. BFS yields one hop toward a destination; never an air teleport to a player.
 */
public final class GkGraph {
    /** Town id → main gatekeeper NPC template id (Interlude town GKs). */
    static final Map<String, Integer> TOWN_GK_NPC = Map.ofEntries(
        Map.entry("dark_elven_village", 30134),
        Map.entry("dion_castle_town", 30059),
        Map.entry("dwarven_village", 30540),
        Map.entry("elven_village", 30146),
        Map.entry("giran_castle_town", 30080),
        Map.entry("gludin_village", 30320),
        Map.entry("gludio_castle_town", 30256),
        Map.entry("heine", 30899),
        Map.entry("hunter_village", 30233),
        Map.entry("orc_village", 30576),
        Map.entry("rune_castle_town", 31320),
        Map.entry("talking_island", 30006),
        Map.entry("town_of_aden", 30848),
        Map.entry("town_of_goddard", 31275),
        Map.entry("town_of_oren", 30177),
        Map.entry("town_of_schuttgart", 31964));

    /** How close a teleport landing must be to a town centre to count as that town. */
    static final int LAND_MARGIN = 4000;

    /** One stock GK hop: from this NPC, take location {@code locId} of the NORMAL list, land at {@code land}. */
    public record Hop(int npcId, int locId, Pos land, String toTown) {
    }

    private final WorldCatalog catalog;
    /** npcId → edges to take (locId + landing town). */
    private final Map<Integer, List<Hop>> fromNpc = new HashMap<>();
    private final Map<Integer, String> npcTown = new HashMap<>();

    public GkGraph(WorldCatalog catalog, Map<Integer, List<Hop>> edges) {
        this.catalog = catalog;
        for (Map.Entry<Integer, List<Hop>> e : edges.entrySet()) {
            fromNpc.put(e.getKey(), List.copyOf(e.getValue()));
        }
        for (WorldCatalog.Town t : catalog.towns()) {
            final Integer npc = TOWN_GK_NPC.get(t.id());
            if (npc != null) {
                npcTown.put(npc, t.id());
            }
        }
    }

    /** Build from the live TeleporterData NORMAL lists and the world catalogue. */
    public static GkGraph fromTeleporterData(WorldCatalog catalog) {
        final Map<Integer, List<Hop>> edges = new HashMap<>();
        for (WorldCatalog.Town t : catalog.towns()) {
            final Integer npcId = TOWN_GK_NPC.get(t.id());
            if (npcId == null) {
                continue;
            }
            final TeleportHolder holder = TeleporterData.getInstance().getHolder(npcId, TeleportType.NORMAL.name());
            if (holder == null) {
                continue;
            }
            final List<Hop> hops = new ArrayList<>();
            for (TeleportLocation loc : holder.getLocations()) {
                final Pos land = new Pos(loc.getX(), loc.getY(), loc.getZ());
                final WorldCatalog.Town dest = townNear(catalog, land);
                if (dest != null) {
                    hops.add(new Hop(npcId, loc.getId(), land, dest.id()));
                }
            }
            edges.put(npcId, hops);
        }
        return new GkGraph(catalog, edges);
    }

    /** Nearest gatekeeper walk target to {@code from}, or null. */
    public Pos nearestGk(Pos from) {
        Pos best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (WorldCatalog.Town t : catalog.towns()) {
            for (Pos g : t.anchors("gk")) {
                final double d = from.distance2d(g);
                if (d < bestD) {
                    bestD = d;
                    best = g;
                }
            }
        }
        return best;
    }

    /** Town whose centre/polygon is near {@code p}, or null. */
    public WorldCatalog.Town townNear(Pos p) {
        return townNear(catalog, p);
    }

    static WorldCatalog.Town townNear(WorldCatalog catalog, Pos p) {
        WorldCatalog.Town byPoly = catalog.townAt(p, 200);
        if (byPoly != null) {
            return byPoly;
        }
        WorldCatalog.Town best = null;
        double bestD = LAND_MARGIN;
        for (WorldCatalog.Town t : catalog.towns()) {
            final double d = t.center().distance2d(p);
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    /**
     * Next stock hop from standing at {@code npcId} toward {@code dest}. Null when already in the dest town, no route,
     * or the NPC is unknown.
     */
    public Hop nextHop(int npcId, Pos dest) {
        final WorldCatalog.Town goal = townNear(dest);
        if (goal == null) {
            return closestLanding(npcId, dest);
        }
        final String here = npcTown.get(npcId);
        if (goal.id().equals(here)) {
            return null; // already at the right town: run to the mate
        }
        final List<Hop> first = bfs(npcId, goal.id());
        if ((first == null) || first.isEmpty()) {
            return closestLanding(npcId, dest);
        }
        return first.get(0);
    }

    /** BFS path of hops from this NPC's town to {@code goalTown}; returns the hops in order (first to take first). */
    List<Hop> bfs(int fromNpcId, String goalTown) {
        final String start = npcTown.get(fromNpcId);
        if (start == null) {
            return null;
        }
        if (start.equals(goalTown)) {
            return List.of();
        }
        final ArrayDeque<String> q = new ArrayDeque<>();
        final Map<String, Hop> via = new HashMap<>(); // town reached → hop that got there
        final Set<String> seen = new HashSet<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty()) {
            final String cur = q.poll();
            final Integer npc = TOWN_GK_NPC.get(cur);
            if (npc == null) {
                continue;
            }
            for (Hop h : fromNpc.getOrDefault(npc, List.of())) {
                if (!seen.add(h.toTown())) {
                    continue;
                }
                via.put(h.toTown(), h);
                if (h.toTown().equals(goalTown)) {
                    return reconstruct(via, start, goalTown);
                }
                q.add(h.toTown());
            }
        }
        return null;
    }

    private static List<Hop> reconstruct(Map<String, Hop> via, String start, String goal) {
        final ArrayList<Hop> path = new ArrayList<>();
        String cur = goal;
        while (!cur.equals(start)) {
            final Hop h = via.get(cur);
            if (h == null) {
                return null;
            }
            path.add(0, h);
            final String prev = townOfNpc(h.npcId());
            if (prev == null) {
                return null;
            }
            cur = prev;
        }
        return path;
    }

    private static String townOfNpc(int npcId) {
        for (Map.Entry<String, Integer> e : TOWN_GK_NPC.entrySet()) {
            if (e.getValue() == npcId) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Greedy: land closest to dest among this NPC's destinations. */
    private Hop closestLanding(int npcId, Pos dest) {
        Hop best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Hop h : fromNpc.getOrDefault(npcId, List.of())) {
            final double d = h.land().distance2d(dest);
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    public int edges() {
        int n = 0;
        for (List<Hop> h : fromNpc.values()) {
            n += h.size();
        }
        return n;
    }
}
