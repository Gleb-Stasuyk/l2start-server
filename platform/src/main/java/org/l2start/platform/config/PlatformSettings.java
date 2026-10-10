package org.l2start.platform.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.l2start.platform.util.Durations;
import org.l2start.platform.util.IniFile;

/**
 * config/platform.ini (+[profile]) -> config/platform.local.ini (+[profile]) -> env L2_PLATFORM__&lt;KEY&gt;.
 * Profile: env L2_PROFILE (default solo).
 */
public final class PlatformSettings {
    public enum FailurePolicy {
        /** Start without the broken module (log it). */
        CONTINUE,
        /** Refuse to start when an enabled module fails validation, resolution, migration, loading or enabling. */
        ABORT
    }

    private final String profile;
    private final List<String> moduleRoots;
    private final FailurePolicy failurePolicy;
    private final String failurePolicySource;
    private final int breakerErrors;
    private final Duration breakerWindow;
    private final Duration shutdownTimeout;
    private final Duration slowCall;
    private final boolean disableAll;
    private final String defaultLanguage;
    private final List<String> warnings;

    private PlatformSettings(String profile, Map<String, String> v, Map<String, String> src, List<String> warnings) {
        this.profile = profile;
        this.warnings = warnings;
        final List<String> roots = new ArrayList<>();
        for (String r : v.getOrDefault("moduleroots", "modules").split(",")) {
            if (!r.isBlank()) {
                roots.add(r.trim());
            }
        }
        this.moduleRoots = List.copyOf(roots);
        final String fp = v.get("failurepolicy");
        if (fp == null) {
            // Built-in defaults: online refuses to start with a broken module, everything else skips it.
            failurePolicy = profile.equals("online") ? FailurePolicy.ABORT : FailurePolicy.CONTINUE;
            failurePolicySource = "built-in default for profile " + profile;
        } else {
            FailurePolicy p;
            try {
                p = FailurePolicy.valueOf(fp.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                p = profile.equals("online") ? FailurePolicy.ABORT : FailurePolicy.CONTINUE;
                warnings.add("FailurePolicy '" + fp + "' is not continue|abort, using " + p.name().toLowerCase());
            }
            failurePolicy = p;
            failurePolicySource = src.get("failurepolicy");
        }
        breakerErrors = intValue(v, "breakererrors", 20);
        breakerWindow = duration(v, "breakerwindow", "60s");
        shutdownTimeout = duration(v, "shutdowntimeout", "10s");
        slowCall = duration(v, "slowcall", "100ms");
        disableAll = bool(v.getOrDefault("disableall", "false"));
        defaultLanguage = v.getOrDefault("defaultlanguage", "en").trim().toLowerCase(Locale.ROOT);
    }

    public static PlatformSettings load(Path configDir, Map<String, String> env) throws IOException {
        final String profile = profile(env);
        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, String> sources = new LinkedHashMap<>();
        for (String file : new String[] { "platform.ini", "platform.local.ini" }) {
            for (IniFile.Entry e : IniFile.load(configDir.resolve(file)).resolve(profile).values()) {
                values.put(e.key().toLowerCase(Locale.ROOT), e.value());
                sources.put(e.key().toLowerCase(Locale.ROOT), file);
            }
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().toUpperCase(Locale.ROOT).startsWith("L2_PLATFORM__")) {
                final String key = Env.norm(e.getKey().substring("L2_PLATFORM__".length()));
                values.put(key, e.getValue());
                sources.put(key, "env " + e.getKey());
            }
        }
        return new PlatformSettings(profile, values, sources, new ArrayList<>());
    }

    public static PlatformSettings defaults(String profile) {
        return new PlatformSettings(profile, Map.of(), Map.of(), new ArrayList<>());
    }

    public static String profile(Map<String, String> env) {
        final String p = env.get("L2_PROFILE");
        return (p == null || p.isBlank()) ? "solo" : p.trim().toLowerCase(Locale.ROOT);
    }

    static boolean bool(String s) {
        final String l = s.trim().toLowerCase(Locale.ROOT);
        return l.equals("true") || l.equals("on") || l.equals("yes") || l.equals("1");
    }

    private int intValue(Map<String, String> v, String key, int def) {
        try {
            return v.containsKey(key) ? Integer.parseInt(v.get(key).trim()) : def;
        } catch (NumberFormatException e) {
            warnings.add(key + ": '" + v.get(key) + "' is not a number, using " + def);
            return def;
        }
    }

    private Duration duration(Map<String, String> v, String key, String def) {
        try {
            return Durations.parse(v.getOrDefault(key, def));
        } catch (IllegalArgumentException e) {
            warnings.add(key + ": " + e.getMessage() + ", using " + def);
            return Durations.parse(def);
        }
    }

    public String profile() { return profile; }
    public List<String> moduleRoots() { return moduleRoots; }
    public FailurePolicy failurePolicy() { return failurePolicy; }
    public String failurePolicySource() { return failurePolicySource; }
    public int breakerErrors() { return breakerErrors; }
    public Duration breakerWindow() { return breakerWindow; }
    public Duration shutdownTimeout() { return shutdownTimeout; }
    public Duration slowCall() { return slowCall; }
    public boolean disableAll() { return disableAll; }
    public String defaultLanguage() { return defaultLanguage; }
    public List<String> warnings() { return warnings; }
}
