package org.l2start.bots.runtime;

import java.util.ArrayList;
import java.util.List;

import org.l2start.bots.api.Pos;

/** Groups of points joined by links of at most {@code link} (//bots spots: bots standing in a pile). */
public final class Piles {
    private Piles() {
    }

    /** @return index groups of 2 or more points, biggest first */
    public static List<List<Integer>> of(List<Pos> pts, int link) {
        final int n = pts.size();
        final int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (pts.get(i).distance2d(pts.get(j)) <= link) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        final java.util.Map<Integer, List<Integer>> by = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            by.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        final List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> g : by.values()) {
            if (g.size() > 1) {
                out.add(g);
            }
        }
        out.sort((a, b) -> b.size() - a.size());
        return out;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }
}
