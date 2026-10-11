package org.l2start.bots.api;

/** A point of the game world. */
public record Pos(int x, int y, int z) {
    public double distance2d(Pos o) {
        return Math.hypot((double) x - o.x, (double) y - o.y);
    }

    public double distance(Pos o) {
        final double dx = (double) x - o.x;
        final double dy = (double) y - o.y;
        final double dz = (double) z - o.z;
        return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
    }

    /** The point {@code step} units from here towards {@code to} (or {@code to} itself when closer). */
    public Pos toward(Pos to, double step) {
        final double d = distance2d(to);
        if (d <= step) {
            return to;
        }
        final double k = step / d;
        return new Pos((int) Math.round(x + ((to.x - x) * k)), (int) Math.round(y + ((to.y - y) * k)), z + (int) Math.round((to.z - z) * k));
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
