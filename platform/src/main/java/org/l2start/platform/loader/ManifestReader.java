package org.l2start.platform.loader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.l2start.platform.loader.ModuleManifest.Dependency;
import org.l2start.platform.loader.ModuleManifest.Kind;
import org.l2start.platform.util.Json;
import org.l2start.platform.util.Semver;
import org.l2start.platform.util.VersionRange;

/** Reads and validates modules/&lt;id&gt;/module.json. Collects all errors instead of stopping at the first one. */
public final class ManifestReader {
    public static final String FILE = "module.json";
    public static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");
    private static final Pattern FQCN = Pattern.compile("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+$");
    private static final Set<String> KNOWN = Set.of("id", "name", "description", "version", "kind", "platform", "entrypoint", "authors", "dependencies", "conflicts", "defaultEnabled", "data", "reserves", "migrations", "config");

    private ManifestReader() {
    }

    public static ModuleManifest read(Path moduleDir) throws ManifestException {
        final Path file = moduleDir.resolve(FILE);
        final String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ManifestException(List.of("cannot read " + FILE + ": " + e.getMessage()));
        }
        final Object root;
        try {
            root = Json.parse(text);
        } catch (Json.JsonException e) {
            throw new ManifestException(List.of(FILE + ": " + e.getMessage()));
        }
        if (!(root instanceof Map)) {
            throw new ManifestException(List.of(FILE + ": top level must be an object"));
        }
        return parse(moduleDir, castMap(root));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    static ModuleManifest parse(Path moduleDir, Map<String, Object> m) throws ManifestException {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        for (String key : m.keySet()) {
            if (!KNOWN.contains(key)) {
                warnings.add("unknown field '" + key + "' ignored");
            }
        }

        final String id = str(m, "id", true, errors);
        if (id != null) {
            if (!ID.matcher(id).matches()) {
                errors.add("id '" + id + "' must match " + ID.pattern());
            } else if (moduleDir.getFileName() != null && !moduleDir.getFileName().toString().equals(id)) {
                errors.add("id '" + id + "' must equal the folder name '" + moduleDir.getFileName() + "'");
            }
        }
        final String name = str(m, "name", true, errors);
        final String description = str(m, "description", false, errors);

        Semver version = null;
        final String versionText = str(m, "version", true, errors);
        if (versionText != null) {
            try {
                version = Semver.parse(versionText);
            } catch (IllegalArgumentException e) {
                errors.add("version: " + e.getMessage());
            }
        }

        Kind kind = null;
        final String kindText = str(m, "kind", true, errors);
        if (kindText != null) {
            if (kindText.equals("jar")) {
                kind = Kind.JAR;
            } else if (kindText.equals("data")) {
                kind = Kind.DATA;
            } else if (kindText.equals("script")) {
                errors.add("kind 'script' is not supported yet (use 'jar' or 'data')");
            } else {
                errors.add("kind must be 'jar' or 'data', got '" + kindText + "'");
            }
        }

        final String platform = str(m, "platform", true, errors);
        VersionRange platformRange = null;
        if (platform != null) {
            platformRange = range("platform", platform, errors);
        }

        final String entrypoint = str(m, "entrypoint", false, errors);
        if (kind == Kind.JAR) {
            if (entrypoint == null) {
                errors.add("entrypoint is required for kind 'jar'");
            } else if (!FQCN.matcher(entrypoint).matches()) {
                errors.add("entrypoint '" + entrypoint + "' is not a fully qualified class name");
            }
        } else if (kind == Kind.DATA && entrypoint != null) {
            errors.add("entrypoint is not allowed for kind 'data'");
        }

        final List<String> authors = new ArrayList<>();
        final Object a = m.get("authors");
        if (a != null) {
            if (a instanceof List<?> list) {
                for (Object o : list) {
                    authors.add(String.valueOf(o));
                }
            } else {
                errors.add("authors must be an array of strings");
            }
        }

        final List<Dependency> dependencies = deps(m, "dependencies", true, errors);
        final List<Dependency> conflicts = deps(m, "conflicts", false, errors);
        if (id != null) {
            for (Dependency d : dependencies) {
                if (d.id().equals(id)) {
                    errors.add("module cannot depend on itself");
                }
            }
        }

        final Map<String, Boolean> defaultEnabled = new LinkedHashMap<>();
        final Object de = m.get("defaultEnabled");
        if (de != null) {
            if (de instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getValue() instanceof Boolean b) {
                        defaultEnabled.put(String.valueOf(e.getKey()).toLowerCase(), b);
                    } else {
                        errors.add("defaultEnabled." + e.getKey() + " must be true or false");
                    }
                }
            } else if (de instanceof Boolean b) {
                defaultEnabled.put("solo", b);
                defaultEnabled.put("online", b);
            } else {
                errors.add("defaultEnabled must be an object {\"solo\": bool, \"online\": bool}");
            }
        }

        final Map<DataType, String> data = new EnumMap<>(DataType.class);
        final Object d = m.get("data");
        if (d != null) {
            if (d instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    final DataType type = DataType.byKey(String.valueOf(e.getKey()));
                    if (type == null) {
                        errors.add("data." + e.getKey() + ": unknown data type (npcs, spawns, multisell, buylists, html)");
                        continue;
                    }
                    if (!(e.getValue() instanceof String rel)) {
                        errors.add("data." + e.getKey() + " must be a folder path");
                        continue;
                    }
                    final Path p = safePath(moduleDir, rel, "data." + e.getKey(), errors);
                    if (p != null) {
                        if (!Files.isDirectory(p)) {
                            errors.add("data." + e.getKey() + ": folder '" + rel + "' does not exist");
                        } else {
                            data.put(type, rel);
                        }
                    }
                }
            } else {
                errors.add("data must be an object");
            }
        }

        final Map<DataType, List<int[]>> reserves = new EnumMap<>(DataType.class);
        final Object r = m.get("reserves");
        if (r != null) {
            if (r instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    final DataType type = DataType.byKey(String.valueOf(e.getKey()));
                    if (type == null || !type.hasIds()) {
                        errors.add("reserves." + e.getKey() + ": only npcs, multisell, buylists can be reserved");
                        continue;
                    }
                    final List<int[]> ranges = new ArrayList<>();
                    if (e.getValue() instanceof List<?> list) {
                        for (Object o : list) {
                            if (o instanceof List<?> pair && pair.size() == 2 && pair.get(0) instanceof Long lo && pair.get(1) instanceof Long hi && lo >= 0 && hi >= lo && hi <= Integer.MAX_VALUE) {
                                ranges.add(new int[] { lo.intValue(), hi.intValue() });
                            } else {
                                errors.add("reserves." + e.getKey() + ": each range must be [from, to] with 0 <= from <= to");
                            }
                        }
                    } else {
                        errors.add("reserves." + e.getKey() + " must be an array of [from, to]");
                    }
                    reserves.put(type, ranges);
                }
            } else {
                errors.add("reserves must be an object");
            }
        }
        for (DataType t : data.keySet()) {
            if (t.hasIds() && reserves.getOrDefault(t, List.of()).isEmpty()) {
                errors.add("data." + t.key() + " needs reserves." + t.key() + " (id ranges owned by the module)");
            }
        }

        String migrations = "sql";
        final Object mg = m.get("migrations");
        if (mg != null) {
            if (mg instanceof String s) {
                if (safePath(moduleDir, s, "migrations", errors) != null) {
                    migrations = s;
                }
            } else {
                errors.add("migrations must be a folder path");
            }
        }

        final Map<String, ConfigKeySpec> config = new LinkedHashMap<>();
        final Object c = m.get("config");
        if (c != null) {
            if (c instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    final String key = String.valueOf(e.getKey());
                    if (!key.matches("^[A-Za-z][A-Za-z0-9_.]*$")) {
                        errors.add("config key '" + key + "' must be alphanumeric");
                        continue;
                    }
                    if (!(e.getValue() instanceof Map<?, ?> spec)) {
                        errors.add("config." + key + " must be an object with type and default");
                        continue;
                    }
                    final ConfigKeySpec ks = configKey(key, castMap(spec), errors);
                    if (ks != null) {
                        for (String existing : config.keySet()) {
                            if (existing.equalsIgnoreCase(key)) {
                                errors.add("config key '" + key + "' duplicates '" + existing + "' (keys are case-insensitive)");
                            }
                        }
                        config.put(key, ks);
                    }
                }
            } else {
                errors.add("config must be an object");
            }
        }

        if (!errors.isEmpty()) {
            throw new ManifestException(errors);
        }
        return new ModuleManifest(id, name, description == null ? "" : description, version, kind, platform, platformRange, entrypoint, List.copyOf(authors), dependencies, conflicts, defaultEnabled, data, reserves, migrations, config, warnings);
    }

    private static ConfigKeySpec configKey(String key, Map<String, Object> spec, List<String> errors) {
        final Object typeObj = spec.get("type");
        final ConfigKeySpec.Type type = ConfigKeySpec.parseType(typeObj == null ? null : String.valueOf(typeObj));
        if (type == null) {
            errors.add("config." + key + ".type must be one of bool, int, long, double, string, enum, list, duration");
            return null;
        }
        if (!spec.containsKey("default")) {
            errors.add("config." + key + ".default is required");
            return null;
        }
        final String def = scalar(spec.get("default"));
        final List<String> values = new ArrayList<>();
        if (spec.get("values") instanceof List<?> list) {
            for (Object o : list) {
                values.add(String.valueOf(o));
            }
        }
        if (type == ConfigKeySpec.Type.ENUM && values.isEmpty()) {
            errors.add("config." + key + ": enum needs 'values'");
            return null;
        }
        final Object rel = spec.get("reloadable");
        final ConfigKeySpec ks = new ConfigKeySpec(key, type, def, scalar(spec.get("min")), scalar(spec.get("max")), values, spec.get("description") == null ? null : String.valueOf(spec.get("description")), rel instanceof Boolean b && b);
        try {
            ks.normalize(def);
        } catch (IllegalArgumentException e) {
            errors.add("config." + key + ".default: " + e.getMessage());
            return null;
        }
        return ks;
    }

    private static String scalar(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof List<?> list) {
            final List<String> s = new ArrayList<>();
            for (Object x : list) {
                s.add(String.valueOf(x));
            }
            return String.join(",", s);
        }
        return String.valueOf(o);
    }

    private static List<Dependency> deps(Map<String, Object> m, String field, boolean allowOptional, List<String> errors) {
        final List<Dependency> out = new ArrayList<>();
        final Object o = m.get(field);
        if (o == null) {
            return out;
        }
        if (!(o instanceof List<?> list)) {
            errors.add(field + " must be an array of {\"id\", \"version\"}");
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> dm)) {
                errors.add(field + ": each entry must be an object {\"id\", \"version\"}");
                continue;
            }
            final Object did = dm.get("id");
            if (!(did instanceof String depId) || !ID.matcher(depId).matches()) {
                errors.add(field + ": bad or missing id");
                continue;
            }
            final String ver = dm.get("version") == null ? "*" : String.valueOf(dm.get("version"));
            final VersionRange vr = range(field + "." + depId + ".version", ver, errors);
            final boolean optional = dm.get("optional") instanceof Boolean b && b;
            if (optional && !allowOptional) {
                errors.add(field + "." + depId + ": 'optional' is not allowed here");
            }
            for (Dependency existing : out) {
                if (existing.id().equals(depId)) {
                    errors.add(field + ": '" + depId + "' listed twice");
                }
            }
            if (vr != null) {
                out.add(new Dependency(depId, ver, vr, optional));
            }
        }
        return List.copyOf(out);
    }

    private static VersionRange range(String field, String text, List<String> errors) {
        try {
            return VersionRange.parse(text);
        } catch (IllegalArgumentException e) {
            errors.add(field + ": " + e.getMessage());
            return null;
        }
    }

    private static String str(Map<String, Object> m, String key, boolean required, List<String> errors) {
        final Object o = m.get(key);
        if (o == null) {
            if (required) {
                errors.add(key + " is required");
            }
            return null;
        }
        if (!(o instanceof String s) || s.isBlank()) {
            errors.add(key + " must be a non-empty string");
            return null;
        }
        return s.trim();
    }

    /** Relative path that stays inside the module folder, or null (error added). */
    public static Path safePath(Path moduleDir, String rel, String field, List<String> errors) {
        if (rel.isBlank() || rel.startsWith("/") || rel.startsWith("\\") || rel.matches("^[A-Za-z]:.*") || rel.contains("..") || rel.contains("\\")) {
            errors.add(field + ": '" + rel + "' must be a relative path inside the module (no '..', no '\\')");
            return null;
        }
        final Path base = moduleDir.toAbsolutePath().normalize();
        final Path p = base.resolve(rel).normalize();
        if (!p.startsWith(base)) {
            errors.add(field + ": '" + rel + "' leaves the module folder");
            return null;
        }
        return p;
    }
}
