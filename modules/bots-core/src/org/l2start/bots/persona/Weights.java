package org.l2start.bots.persona;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/** Weighted choice among a few values ("ru:35,en:30"), deterministic for a given {@link Random}. */
public final class Weights<T> {
    private final List<T> values;
    private final int[] cumulative;

    public Weights(Map<T, Integer> weights) {
        final List<T> v = new ArrayList<>();
        final int[] c = new int[weights.size()];
        int sum = 0;
        int i = 0;
        for (Map.Entry<T, Integer> e : weights.entrySet()) {
            if (e.getValue() < 0) {
                throw new IllegalArgumentException("negative weight for " + e.getKey());
            }
            sum += e.getValue();
            v.add(e.getKey());
            c[i++] = sum;
        }
        if (sum == 0) {
            throw new IllegalArgumentException("all weights are 0");
        }
        values = Collections.unmodifiableList(v);
        cumulative = c;
    }

    /** Parses "key:weight,key:weight" (list config). Order is kept, so results do not depend on map ordering. */
    public static <T> Weights<T> parse(List<String> entries, Function<String, T> key) {
        final Map<T, Integer> m = new LinkedHashMap<>();
        for (String raw : entries) {
            final String e = raw.trim();
            final int colon = e.lastIndexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("'" + e + "': expected key:weight");
            }
            final T k = key.apply(e.substring(0, colon).trim());
            final int w;
            try {
                w = Integer.parseInt(e.substring(colon + 1).trim());
            } catch (NumberFormatException x) {
                throw new IllegalArgumentException("'" + e + "': weight is not a number");
            }
            if (m.put(k, w) != null) {
                throw new IllegalArgumentException("'" + k + "' listed twice");
            }
        }
        return new Weights<>(m);
    }

    /** Plain weights by index ("3,3,2" -> 0,1,2). */
    public static Weights<Integer> indexed(List<String> entries) {
        final Map<Integer, Integer> m = new LinkedHashMap<>();
        int i = 0;
        for (String e : entries) {
            m.put(i++, Integer.parseInt(e.trim()));
        }
        return new Weights<>(m);
    }

    public T pick(Random rnd) {
        final int r = rnd.nextInt(cumulative[cumulative.length - 1]);
        for (int i = 0; i < cumulative.length; i++) {
            if (r < cumulative[i]) {
                return values.get(i);
            }
        }
        return values.get(values.size() - 1);
    }

    public List<T> values() {
        return values;
    }

    /** Share of {@code value} in percent (0 when absent). */
    public double share(T value) {
        final int i = values.indexOf(value);
        if (i < 0) {
            return 0;
        }
        final int w = cumulative[i] - (i == 0 ? 0 : cumulative[i - 1]);
        return (100.0 * w) / cumulative[cumulative.length - 1];
    }
}
