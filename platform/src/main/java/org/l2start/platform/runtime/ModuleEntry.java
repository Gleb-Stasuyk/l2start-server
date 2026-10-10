package org.l2start.platform.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.l2start.platform.api.GameModule;
import org.l2start.platform.config.ResolvedConfig;
import org.l2start.platform.db.Migration;
import org.l2start.platform.loader.DataType;
import org.l2start.platform.loader.ModuleClassLoader;
import org.l2start.platform.loader.ModuleManifest;
import org.l2start.platform.loader.ModuleState;

/** Mutable bookkeeping of one discovered module. */
public final class ModuleEntry {
    public final String folderId;
    public final Path dir;
    public volatile ModuleManifest manifest;
    public volatile ModuleState state = ModuleState.DISCOVERED;
    public volatile String reason;
    public String switchSource;
    public final List<String> warnings = new ArrayList<>();
    public final Map<DataType, TreeSet<Integer>> ids = new EnumMap<>(DataType.class);
    public final Map<DataType, Set<Integer>> loadedIds = new EnumMap<>(DataType.class);
    public List<Migration> migrations = List.of();
    public int appliedMigrations;
    public List<String> visibleDependencies = List.of();
    public volatile ResolvedConfig config;
    public ModuleClassLoader classLoader;
    public GameModule instance;
    public ModuleRuntime runtime;

    public ModuleEntry(String folderId, Path dir) {
        this.folderId = folderId;
        this.dir = dir;
    }

    public String id() {
        return manifest != null ? manifest.id() : folderId;
    }

    public String version() {
        return manifest != null ? manifest.version().toString() : "?";
    }

    public void set(ModuleState s, String why) {
        state = s;
        reason = why;
    }

    public Path dataDir(DataType t) {
        final String rel = manifest == null ? null : manifest.data().get(t);
        return rel == null ? null : dir.resolve(rel).normalize();
    }
}
