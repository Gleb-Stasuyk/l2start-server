package org.l2start.platform.loader;

import java.util.List;
import java.util.Map;

import org.l2start.platform.util.Semver;
import org.l2start.platform.util.VersionRange;

/** Parsed and validated module.json. */
public record ModuleManifest(
    String id,
    String name,
    String description,
    Semver version,
    Kind kind,
    String platform,
    VersionRange platformRange,
    String entrypoint,
    List<String> authors,
    List<Dependency> dependencies,
    List<Dependency> conflicts,
    Map<String, Boolean> defaultEnabled,
    Map<DataType, String> data,
    Map<DataType, List<int[]>> reserves,
    String migrations,
    Map<String, ConfigKeySpec> config,
    List<String> warnings) {

    public enum Kind {
        JAR, DATA
    }

    public record Dependency(String id, String version, VersionRange range, boolean optional) {
    }

    public boolean defaultEnabled(String profile) {
        final Boolean b = defaultEnabled.get(profile);
        return b != null && b;
    }

    /** Reserved ranges contain the id. */
    public boolean reserves(DataType type, int id) {
        for (int[] r : reserves.getOrDefault(type, List.of())) {
            if (id >= r[0] && id <= r[1]) {
                return true;
            }
        }
        return false;
    }
}
