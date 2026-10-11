package org.l2start.bots.brain.basic;

import org.l2start.bots.api.Pos;

/** A starting village: centre and how far bots stroll around it in TOWN_IDLE. */
public record Village(String id, Pos center, int radius) {
    /** The five starting villages (phase 3 bot area). */
    public static final java.util.List<Village> STARTING = java.util.List.of(
        new Village("TALKING_ISLAND", new Pos(-84318, 244579, -3730), 700),
        new Village("ELVEN", new Pos(46934, 51467, -2977), 600),
        new Village("DARK_ELVEN", new Pos(9745, 15606, -4574), 600),
        new Village("ORC", new Pos(-44836, -112524, -235), 700),
        new Village("DWARVEN", new Pos(115113, -178212, -901), 700));

    public static Village nearest(java.util.List<Village> list, Pos p) {
        Village best = null;
        double d = Double.MAX_VALUE;
        for (Village v : list) {
            final double x = v.center.distance2d(p);
            if (x < d) {
                d = x;
                best = v;
            }
        }
        return best;
    }
}
