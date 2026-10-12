package org.l2start.rates.floating;

import java.util.Arrays;

/** A piecewise linear curve "x:y,x:y,..." (sorted by x); flat before the first and after the last point. Pure. */
public final class Curve {
    private final double[] xs;
    private final double[] ys;

    private Curve(double[] xs, double[] ys) {
        this.xs = xs;
        this.ys = ys;
    }

    public static Curve parse(String spec) {
        final String[] parts = spec.trim().split("\\s*,\\s*");
        final double[][] pts = new double[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            final String[] p = parts[i].split("\\s*:\\s*");
            if (p.length != 2) {
                throw new IllegalArgumentException("curve point '" + parts[i] + "' is not x:y");
            }
            pts[i] = new double[] { Double.parseDouble(p[0]), Double.parseDouble(p[1]) };
        }
        Arrays.sort(pts, (a, b) -> Double.compare(a[0], b[0]));
        final double[] xs = new double[pts.length];
        final double[] ys = new double[pts.length];
        for (int i = 0; i < pts.length; i++) {
            xs[i] = pts[i][0];
            ys[i] = pts[i][1];
        }
        if (xs.length == 0) {
            throw new IllegalArgumentException("empty curve");
        }
        return new Curve(xs, ys);
    }

    public double at(double x) {
        if (x <= xs[0]) {
            return ys[0];
        }
        for (int i = 1; i < xs.length; i++) {
            if (x <= xs[i]) {
                final double t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
                return ys[i - 1] + ((ys[i] - ys[i - 1]) * t);
            }
        }
        return ys[ys.length - 1];
    }
}
