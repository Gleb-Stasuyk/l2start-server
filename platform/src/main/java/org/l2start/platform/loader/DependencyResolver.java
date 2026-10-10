package org.l2start.platform.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.l2start.platform.loader.ModuleManifest.Dependency;
import org.l2start.platform.util.Semver;

/**
 * Pure dependency resolution: platform compatibility, downgrade, reservation overlap, conflicts, required and
 * optional dependencies with cascading refusals, cycles; deterministic topological order (ties by id).
 */
public final class DependencyResolver {
    private DependencyResolver() {
    }

    public record Result(List<String> order, Map<String, String> refused, Map<String, List<String>> visibleDependencies, List<String> warnings) {
    }

    /**
     * @param candidates enabled and valid modules
     * @param disabled ids of modules that exist but are switched off or invalid (for better messages), may be empty
     * @param platformApi platform API version
     * @param lastVersions last version that ran on this database (downgrade protection), may be empty
     */
    public static Result resolve(List<ModuleManifest> candidates, Set<String> disabled, Semver platformApi, Map<String, Semver> lastVersions) {
        final Map<String, ModuleManifest> byId = new TreeMap<>();
        for (ModuleManifest m : candidates) {
            byId.put(m.id(), m);
        }
        final Map<String, String> refused = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();

        for (ModuleManifest m : byId.values()) {
            if (!m.platformRange().matches(platformApi)) {
                refused.put(m.id(), "needs platform " + m.platform() + ", this platform is " + platformApi);
                continue;
            }
            final Semver last = lastVersions.get(m.id());
            if (last != null && m.version().compareTo(last) < 0) {
                refused.put(m.id(), "downgrade from " + last + " to " + m.version() + " is not allowed (database may have a newer schema)");
            }
        }

        // Reservation overlap between modules: both refused.
        final List<ModuleManifest> list = new ArrayList<>(byId.values());
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                final String overlap = overlap(list.get(i), list.get(j));
                if (overlap != null) {
                    refuse(refused, list.get(i).id(), "reserved " + overlap + " overlaps with module " + list.get(j).id());
                    refuse(refused, list.get(j).id(), "reserved " + overlap + " overlaps with module " + list.get(i).id());
                }
            }
        }

        // Conflicts: both sides refused.
        for (ModuleManifest m : byId.values()) {
            for (Dependency c : m.conflicts()) {
                final ModuleManifest other = byId.get(c.id());
                if (other != null && c.range().matches(other.version())) {
                    refuse(refused, m.id(), "conflicts with " + other.id() + " " + other.version());
                    refuse(refused, other.id(), "conflicts with " + m.id() + " " + m.version());
                }
            }
        }

        // Required dependencies, iterated to a fixpoint so refusals cascade.
        final Map<String, List<String>> visible = new TreeMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ModuleManifest m : byId.values()) {
                if (refused.containsKey(m.id())) {
                    continue;
                }
                for (Dependency d : m.dependencies()) {
                    if (d.optional()) {
                        continue;
                    }
                    final String reason = dependencyProblem(d, byId, refused, disabled);
                    if (reason != null) {
                        refused.put(m.id(), reason);
                        changed = true;
                        break;
                    }
                }
            }
        }

        // Graph of non-refused modules: edges dependency -> dependant (required + usable optional).
        final Map<String, List<String>> edges = new HashMap<>();
        final Map<String, Integer> indegree = new TreeMap<>();
        for (ModuleManifest m : byId.values()) {
            if (!refused.containsKey(m.id())) {
                indegree.put(m.id(), 0);
            }
        }
        for (String id : indegree.keySet()) {
            final ModuleManifest m = byId.get(id);
            final List<String> vis = new ArrayList<>();
            for (Dependency d : m.dependencies()) {
                if (d.optional()) {
                    final ModuleManifest dep = byId.get(d.id());
                    if (dep == null || refused.containsKey(d.id())) {
                        continue;
                    }
                    if (!d.range().matches(dep.version())) {
                        warnings.add(id + ": optional dependency " + d.id() + " " + dep.version() + " does not match " + d.version() + ", ignored");
                        continue;
                    }
                }
                vis.add(d.id());
                edges.computeIfAbsent(d.id(), k -> new ArrayList<>()).add(id);
                indegree.merge(id, 1, Integer::sum);
            }
            visible.put(id, List.copyOf(vis));
        }

        final List<String> order = new ArrayList<>();
        final PriorityQueue<String> ready = new PriorityQueue<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        while (!ready.isEmpty()) {
            final String id = ready.poll();
            order.add(id);
            for (String next : edges.getOrDefault(id, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (order.size() < indegree.size()) {
            final TreeSet<String> stuck = new TreeSet<>(indegree.keySet());
            stuck.removeAll(order);
            for (String id : stuck) {
                refused.put(id, "dependency cycle among " + String.join(", ", stuck));
                visible.remove(id);
            }
        }
        return new Result(List.copyOf(order), refused, visible, warnings);
    }

    private static void refuse(Map<String, String> refused, String id, String reason) {
        refused.putIfAbsent(id, reason);
    }

    private static String dependencyProblem(Dependency d, Map<String, ModuleManifest> byId, Map<String, String> refused, Set<String> disabled) {
        final ModuleManifest dep = byId.get(d.id());
        if (dep == null) {
            return disabled.contains(d.id()) ? "dependency " + d.id() + " is disabled or invalid" : "dependency " + d.id() + " is missing";
        }
        if (!d.range().matches(dep.version())) {
            return "dependency " + d.id() + " " + dep.version() + " does not match " + d.version();
        }
        if (refused.containsKey(d.id())) {
            return "depends on refused module " + d.id();
        }
        return null;
    }

    private static String overlap(ModuleManifest a, ModuleManifest b) {
        for (Map.Entry<DataType, List<int[]>> e : a.reserves().entrySet()) {
            for (int[] ra : e.getValue()) {
                for (int[] rb : b.reserves().getOrDefault(e.getKey(), List.of())) {
                    if (ra[0] <= rb[1] && rb[0] <= ra[1]) {
                        return e.getKey().key() + " " + Math.max(ra[0], rb[0]) + ".." + Math.min(ra[1], rb[1]);
                    }
                }
            }
        }
        return null;
    }
}
