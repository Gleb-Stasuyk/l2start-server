package org.l2start.bots.core;

import java.util.Optional;
import java.util.Random;

import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2start.bots.api.Geo;
import org.l2start.bots.api.Pos;

/** {@link Geo} on the stock GeoEngine (without geodata it answers "yes, flat"). */
final class MobiusGeo implements Geo {
    static final MobiusGeo INSTANCE = new MobiusGeo();

    @Override
    public boolean canMoveTo(Pos a, Pos b) {
        return GeoEngine.getInstance().canMoveToTarget(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), 0);
    }

    @Override
    public boolean canSee(Pos a, Pos b) {
        return GeoEngine.getInstance().canSeeTarget(a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
    }

    @Override
    public boolean pathfinding() {
        return org.l2jmobius.gameserver.config.GeoEngineConfig.PATHFINDING > 0;
    }

    @Override
    public int height(Pos p) {
        return GeoEngine.getInstance().getHeight(p.x(), p.y(), p.z());
    }

    @Override
    public Optional<Pos> randomReachablePoint(Pos center, int radius, Random rnd) {
        final GeoEngine geo = GeoEngine.getInstance();
        for (int i = 0; i < 10; i++) {
            final double a = rnd.nextDouble() * Math.PI * 2;
            final double d = Math.sqrt(rnd.nextDouble()) * radius;
            final int x = center.x() + (int) (Math.cos(a) * d);
            final int y = center.y() + (int) (Math.sin(a) * d);
            final int z = geo.getHeight(x, y, center.z());
            if ((Math.abs(z - center.z()) < 500) && geo.canMoveToTarget(center.x(), center.y(), center.z(), x, y, z, 0)) {
                return Optional.of(new Pos(x, y, z));
            }
        }
        return Optional.empty();
    }
}
