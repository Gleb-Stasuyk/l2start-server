package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;

/** KT12 F1: BFS hops between towns without air-teleporting to a player. */
class GkGraphTest {
    static final Pos GIRAN_GK = new Pos(83396, 147904, -3404);
    static final Pos DION_GK = new Pos(15631, 142885, -2704);
    static final Pos GLUDIO_GK = new Pos(-12736, 122816, -3114);

    static WorldCatalog.Town town(String id, Pos center, Pos gk) {
        return new WorldCatalog.Town(id, id, center, 3000, 1, 80, new int[] { center.x() - 2000, center.x() + 2000, center.x() + 2000, center.x() - 2000 },
            new int[] { center.y() - 2000, center.y() - 2000, center.y() + 2000, center.y() + 2000 }, List.of(new Assignment.Anchor("gk", gk)));
    }

    static GkGraph graph() {
        final WorldCatalog catalog = new WorldCatalog(List.of(
            town("giran_castle_town", new Pos(83800, 147550, -3494), GIRAN_GK),
            town("dion_castle_town", new Pos(18339, 144607, -3040), DION_GK),
            town("gludio_castle_town", new Pos(-14281, 123784, -3103), GLUDIO_GK)), List.of());
        final Map<Integer, List<GkGraph.Hop>> edges = new HashMap<>();
        edges.put(30080, List.of(new GkGraph.Hop(30080, 0, DION_GK, "dion_castle_town"), new GkGraph.Hop(30080, 1, GLUDIO_GK, "gludio_castle_town")));
        edges.put(30059, List.of(new GkGraph.Hop(30059, 0, GIRAN_GK, "giran_castle_town"), new GkGraph.Hop(30059, 1, GLUDIO_GK, "gludio_castle_town")));
        edges.put(30256, List.of(new GkGraph.Hop(30256, 0, GIRAN_GK, "giran_castle_town"), new GkGraph.Hop(30256, 1, DION_GK, "dion_castle_town")));
        return new GkGraph(catalog, edges);
    }

    @Test
    void nearestGkIsClosestAnchor() {
        final GkGraph g = graph();
        final Pos nearGiran = new Pos(84000, 148000, -3400);
        assertEquals(GIRAN_GK, g.nearestGk(nearGiran));
    }

    @Test
    void nextHopDirectWhenLinked() {
        final GkGraph g = graph();
        final GkGraph.Hop hop = g.nextHop(30080, GLUDIO_GK);
        assertNotNull(hop);
        assertEquals("gludio_castle_town", hop.toTown());
    }

    @Test
    void nextHopNullWhenAlreadyInTown() {
        final GkGraph g = graph();
        assertNull(g.nextHop(30080, GIRAN_GK));
    }

    @Test
    void bfsFindsPath() {
        final GkGraph g = graph();
        final List<GkGraph.Hop> path = g.bfs(30080, "gludio_castle_town");
        assertNotNull(path);
        assertTrue(path.size() >= 1);
        assertEquals("gludio_castle_town", path.get(path.size() - 1).toTown());
    }
}
