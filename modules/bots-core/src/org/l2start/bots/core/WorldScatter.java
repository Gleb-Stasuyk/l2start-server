package org.l2start.bots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.geoengine.geodata.Cell;
import org.l2jmobius.gameserver.geoengine.pathfinding.PathFinding;
import org.l2start.bots.api.Pos;
import org.l2start.bots.director.Scatter;

/** {@link Scatter} on the stock world: players and bots in the world are "occupied", stock geodata and pathfinding. */
final class WorldScatter {
    /** Players farther than this cannot be closer than {@link Scatter#CLEAR} to any point of the last ring. */
    static final int OCCUPIED_RADIUS = 3000;

    static final Scatter.Ground GROUND = new Scatter.Ground() {
        @Override
        public boolean hasGeo(int x, int y) {
            return GeoEngine.getInstance().hasGeo(x, y);
        }

        @Override
        public int height(int x, int y, int z) {
            return GeoEngine.getInstance().getHeight(x, y, z);
        }

        @Override
        public boolean reachable(Pos from, Pos to) {
            final GeoEngine geo = GeoEngine.getInstance();
            if (geo.canMoveToTarget(from.x(), from.y(), from.z(), to.x(), to.y(), to.z(), 0)) {
                return true;
            }
            return (GeoEngineConfig.PATHFINDING > 0) && (PathFinding.getInstance().findPath(from.x(), from.y(), from.z(), to.x(), to.y(), to.z(), 0, true) != null);
        }

        @Override
        public boolean passable(int x, int y, int z) {
            final GeoEngine geo = GeoEngine.getInstance();
            if (!geo.hasGeo(x, y)) {
                return true;
            }
            final int geoX = GeoEngine.getGeoX(x);
            final int geoY = GeoEngine.getGeoY(y);
            final int nearestZ = geo.getNearestZ(geoX, geoY, z);
            if (Math.abs(nearestZ - z) > 100) {
                return false;
            }
            return geo.checkNearestNswe(geoX, geoY, nearestZ, Cell.NSWE_ALL) || geo.checkNearestNswe(geoX, geoY, nearestZ, Cell.NSWE_NORTH) || geo.checkNearestNswe(geoX, geoY, nearestZ, Cell.NSWE_SOUTH) || geo.checkNearestNswe(geoX, geoY, nearestZ, Cell.NSWE_EAST)
                || geo.checkNearestNswe(geoX, geoY, nearestZ, Cell.NSWE_WEST);
        }

        @Override
        public boolean water(int x, int y, int z) {
            return ZoneManager.getInstance().getZones(x, y, z).stream().anyMatch(zone -> zone instanceof org.l2jmobius.gameserver.entity.zone.type.WaterZone);
        }
    };

    private WorldScatter() {
    }

    static Pos pos(Location l) {
        return new Pos(l.getX(), l.getY(), l.getZ());
    }

    static Location loc(Pos p) {
        return new Location(p.x(), p.y(), p.z());
    }

    /** Players and bots in the world near {@code center}, without {@code self}. */
    static List<Pos> occupied(Pos center, Player self) {
        final List<Pos> out = new ArrayList<>();
        for (Player o : World.getPlayers()) {
            if ((o == self) || !o.isSpawned()) {
                continue;
            }
            if ((Math.abs(o.getX() - center.x()) <= OCCUPIED_RADIUS) && (Math.abs(o.getY() - center.y()) <= OCCUPIED_RADIUS)) {
                out.add(new Pos(o.getX(), o.getY(), o.getZ()));
            }
        }
        return out;
    }

    static Scatter.Result pick(Pos center, Player self) {
        return pick(center, occupied(center, self), ThreadLocalRandom.current());
    }

    static Scatter.Result pick(Pos center, List<Pos> occupied, Random rnd) {
        return Scatter.pick(center, occupied, GROUND, rnd);
    }
}
