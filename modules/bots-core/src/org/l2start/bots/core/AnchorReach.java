package org.l2start.bots.core;

import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.pathfinding.PathFinding;
import org.l2start.bots.api.Pos;
import org.l2start.bots.director.WorldCatalog;

/**
 * Can a bot run from {@code from} to an NPC at {@code to}? By geodata: straight or by stock pathfinding, to the NPC or
 * right next to it. Only short hops are judged: the stock path search has fixed buffers (the biggest one ~3500 game
 * units) and gives up on longer ways, which is not "no way".
 */
final class AnchorReach {
    private static final int[][] AROUND = { { 0, 0 }, { 80, 0 }, { -80, 0 }, { 0, 80 }, { 0, -80 }, { 60, 60 }, { -60, 60 }, { 60, -60 }, { -60, -60 } };
    /** Geodata cells (16 units) one path search can surely cover with the stock buffers (500 nodes, 64 + 2 x distance). */
    private static final int MAX_CELLS = 200;
    private static final int HEIGHT_TOLERANCE = 300;

    private AnchorReach() {
    }

    static Pos reach(Pos from, Pos to) {
        final GeoEngine geo = GeoEngine.getInstance();
        if ((GeoEngineConfig.PATHFINDING == 0) || !geo.hasGeo(from.x(), from.y()) || !geo.hasGeo(to.x(), to.y())) {
            return WorldCatalog.UNKNOWN;
        }
        if ((Math.max(Math.abs(from.x() - to.x()), Math.abs(from.y() - to.y())) / 16) > MAX_CELLS) {
            return WorldCatalog.UNKNOWN;
        }
        final int fz = geo.getHeight(from.x(), from.y(), from.z());
        boolean ground = false;
        for (int[] d : AROUND) {
            final int x = to.x() + d[0];
            final int y = to.y() + d[1];
            final int z = geo.getHeight(x, y, to.z());
            if (Math.abs(z - to.z()) > HEIGHT_TOLERANCE) {
                continue;
            }
            ground = true;
            if (geo.canMoveToTarget(from.x(), from.y(), fz, x, y, z, 0) || (PathFinding.getInstance().findPath(from.x(), from.y(), fz, x, y, z, 0, true) != null)) {
                return new Pos(x, y, z);
            }
        }
        return ground ? null : WorldCatalog.UNKNOWN;
    }
}
