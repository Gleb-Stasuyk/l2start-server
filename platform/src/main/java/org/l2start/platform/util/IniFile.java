package org.l2start.platform.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Simple INI: "Key = Value" lines, '#' or ';' comments (whole line, or inline after whitespace),
 * optional [section] headers. Keys and section names are case-insensitive.
 * Keys before the first section belong to the root section "".
 */
public final class IniFile {
    private final Map<String, Map<String, Entry>> sections = new LinkedHashMap<>();

    public record Entry(String key, String value, int line) {
    }

    public static IniFile parse(String text) {
        final IniFile ini = new IniFile();
        String section = "";
        ini.sections.put("", new LinkedHashMap<>());
        final String[] lines = text.replace("\uFEFF", "").split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                ini.sections.computeIfAbsent(section, k -> new LinkedHashMap<>());
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("line " + (i + 1) + ": expected 'Key = Value' but got '" + line + "'");
            }
            final String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            value = stripInlineComment(value);
            ini.sections.get(section).put(key.toLowerCase(Locale.ROOT), new Entry(key, value, i + 1));
        }
        return ini;
    }

    private static String stripInlineComment(String value) {
        for (int i = 1; i < value.length(); i++) {
            final char c = value.charAt(i);
            if ((c == '#' || c == ';') && Character.isWhitespace(value.charAt(i - 1))) {
                return value.substring(0, i).trim();
            }
        }
        return value;
    }

    /** Parses the file, or returns an empty ini when it does not exist. */
    public static IniFile load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return parse("");
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Root keys overlaid with keys of [profile]. Result keys keep their original spelling; map is keyed lower-case. */
    public Map<String, Entry> resolve(String profile) {
        final Map<String, Entry> out = new LinkedHashMap<>(sections.getOrDefault("", Map.of()));
        if (profile != null) {
            out.putAll(sections.getOrDefault(profile.toLowerCase(Locale.ROOT), Map.of()));
        }
        return out;
    }

    public Map<String, Entry> section(String name) {
        return sections.getOrDefault(name.toLowerCase(Locale.ROOT), Map.of());
    }

    public boolean isEmpty() {
        for (Map<String, Entry> m : sections.values()) {
            if (!m.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
