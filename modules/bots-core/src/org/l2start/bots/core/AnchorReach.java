package org.l2start.bots.core;

import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.pathfinding.PathFinding;
import org.l2start.bots.api.Pos;

/** Can a bot run from {@code from} to an NPC at {@code to}? By geodata: straight or by pathfinding, to the NPC or right next to it. */
final class AnchorReach {
    private static final int[][] AROUND = { { 0, 0 }, { 80, 0 }, { -80, 0 }, { 0, 80 }, { 0, -80 } };

    private AnchorReach() {
    }

    /** Pathfinding is on and both points have geodata: a real check is possible (else every anchor is kept). */
    static boolean checkable(Pos from, Pos to) {
        final GeoEngine geo = GeoEngine.getInstance();
        return (GeoEngineConfig.PATHFINDING != 0) && geo.hasGeo(from.x(), from.y()) && geo.hasGeo(to.x(), to.y());
    }

    static boolean reachable(Pos from, Pos to) {
        if (!checkable(from, to)) {
            return true;
        }
        final GeoEngine geo = GeoEngine.getInstance();
        final int fz = geo.getHeight(from.x(), from.y(), from.z());
        for (int[] d : AROUND) {
            final int x = to.x() + d[0];
            final int y = to.y() + d[1];
            final int z = geo.getHeight(x, y, to.z());
            if (Math.abs(z - to.z()) > 200) {
                continue;
            }
            if (geo.canMoveToTarget(from.x(), from.y(), fz, x, y, z, 0) || (PathFinding.getInstance().findPath(from.x(), from.y(), fz, x, y, z, 0, true) != null)) {
                return true;
            }
        }
        return false;
    }
}
