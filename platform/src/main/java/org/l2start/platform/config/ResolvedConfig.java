package org.l2start.platform.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.l2start.platform.api.ModuleConfig;
import org.l2start.platform.loader.ConfigKeySpec;
import org.l2start.platform.loader.ModuleManifest;
import org.l2start.platform.util.Durations;
import org.l2start.platform.util.IniFile;

/** Module configuration resolved against the schema in module.json. Immutable; a reload creates a new instance. */
public final class ResolvedConfig implements ModuleConfig {
    private final Map<String, String> values; // key (schema spelling) -> normalized value
    private final Map<String, String> sources;
    private final Map<String, String> lower; // lower-case key -> schema spelling
    private final List<String> warnings;

    private ResolvedConfig(Map<String, String> values, Map<String, String> sources, List<String> warnings) {
        this.values = Collections.unmodifiableMap(values);
        this.sources = Collections.unmodifiableMap(sources);
        this.warnings = List.copyOf(warnings);
        final Map<String, String> l = new LinkedHashMap<>();
        for (String k : values.keySet()) {
            l.put(k.toLowerCase(Locale.ROOT), k);
        }
        this.lower = l;
    }

    public static final class ConfigException extends Exception {
        private static final long serialVersionUID = 1L;

        public ConfigException(String message) {
            super(message);
        }
    }

    /** Reads modules/&lt;id&gt;/config/defaults.ini and config/modules/&lt;id&gt;.ini from disk. */
    public static ResolvedConfig load(ModuleManifest m, Path moduleDir, Path configDir, String profile, Map<String, String> env) throws ConfigException {
        try {
            return resolve(m, profile, IniFile.load(moduleDir.resolve("config").resolve("defaults.ini")), IniFile.load(configDir.resolve("modules").resolve(m.id() + ".ini")), env);
        } catch (IOException | IllegalArgumentException e) {
            throw new ConfigException("config: " + e.getMessage());
        }
    }

    public static ResolvedConfig resolve(ModuleManifest m, String profile, IniFile defaults, IniFile overrides, Map<String, String> env) throws ConfigException {
        final Map<String, ConfigKeySpec> schema = m.config();
        final Map<String, String> byLower = new LinkedHashMap<>();
        for (String k : schema.keySet()) {
            byLower.put(k.toLowerCase(Locale.ROOT), k);
        }
        final Map<String, String> raw = new LinkedHashMap<>();
        final Map<String, String> src = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();
        for (ConfigKeySpec ks : schema.values()) {
            raw.put(ks.name(), ks.defaultValue());
            src.put(ks.name(), "default");
        }
        layer(defaults, profile, "modules/" + m.id() + "/config/defaults.ini", byLower, raw, src, warnings);
        layer(overrides, profile, "config/modules/" + m.id() + ".ini", byLower, raw, src, warnings);
        final String prefix = "L2_MOD_" + Env.idPart(m.id()) + "__";
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!e.getKey().toUpperCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            final String want = Env.norm(e.getKey().substring(prefix.length()));
            String match = null;
            for (String k : schema.keySet()) {
                if (Env.norm(k).equals(want)) {
                    match = k;
                }
            }
            if (match == null) {
                warnings.add("env " + e.getKey() + ": no such config key, ignored");
                continue;
            }
            raw.put(match, e.getValue());
            src.put(match, "env " + e.getKey());
        }
        final Map<String, String> values = new LinkedHashMap<>();
        final List<String> errors = new ArrayList<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            final ConfigKeySpec ks = schema.get(e.getKey());
            if (ks == null) {
                values.put(e.getKey(), e.getValue());
                continue;
            }
            try {
                values.put(e.getKey(), ks.normalize(e.getValue()));
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage() + " (from " + src.get(e.getKey()) + ")");
            }
        }
        if (!errors.isEmpty()) {
            throw new ConfigException("config: " + String.join("; ", errors));
        }
        return new ResolvedConfig(values, src, warnings);
    }

    private static void layer(IniFile ini, String profile, String name, Map<String, String> byLower, Map<String, String> raw, Map<String, String> src, List<String> warnings) {
        for (IniFile.Entry e : ini.resolve(profile).values()) {
            final String key = byLower.get(e.key().toLowerCase(Locale.ROOT));
            if (key == null) {
                warnings.add(name + ": unknown key '" + e.key() + "' (not in module.json config), kept as string");
                raw.put(e.key(), e.value());
                src.put(e.key(), name);
                continue;
            }
            raw.put(key, e.value());
            src.put(key, name + (ini.section(profile).containsKey(e.key().toLowerCase(Locale.ROOT)) ? " [" + profile + "]" : ""));
        }
    }

    public List<String> warnings() {
        return warnings;
    }

    private String value(String key) {
        final String k = lower.get(key.toLowerCase(Locale.ROOT));
        if (k == null) {
            throw new IllegalArgumentException("unknown config key '" + key + "'");
        }
        return values.get(k);
    }

    @Override
    public boolean has(String key) {
        return lower.containsKey(key.toLowerCase(Locale.ROOT));
    }

    @Override
    public String getString(String key) {
        return value(key);
    }

    @Override
    public boolean getBoolean(String key) {
        return PlatformSettings.bool(value(key));
    }

    @Override
    public int getInt(String key) {
        return Integer.parseInt(value(key).trim());
    }

    @Override
    public long getLong(String key) {
        return Long.parseLong(value(key).trim());
    }

    @Override
    public double getDouble(String key) {
        return Double.parseDouble(value(key).trim());
    }

    @Override
    public Duration getDuration(String key) {
        return Durations.parse(value(key));
    }

    @Override
    public List<String> getList(String key) {
        final String v = value(key);
        return v.isEmpty() ? List.of() : Arrays.asList(v.split(","));
    }

    @Override
    public <E extends Enum<E>> E getEnum(String key, Class<E> type) {
        final String v = value(key);
        for (E e : type.getEnumConstants()) {
            if (e.name().equalsIgnoreCase(v)) {
                return e;
            }
        }
        throw new IllegalArgumentException(key + ": '" + v + "' is not a " + type.getSimpleName());
    }

    @Override
    public String source(String key) {
        final String k = lower.get(key.toLowerCase(Locale.ROOT));
        return k == null ? null : sources.get(k);
    }

    @Override
    public Map<String, String> asMap() {
        return values;
    }
}
