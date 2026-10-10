package org.l2start.platform.runtime;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** i18n/messages_&lt;lang&gt;.properties (UTF-8). Lookup: lang, default language, en, then the key itself. */
public final class Messages {
    private static final Pattern FILE = Pattern.compile("^messages_([a-z]{2,3})\\.properties$");
    private final Map<String, Properties> bundles;
    private final String defaultLanguage;

    private Messages(Map<String, Properties> bundles, String defaultLanguage) {
        this.bundles = bundles;
        this.defaultLanguage = defaultLanguage;
    }

    public static Messages load(Path dir, String defaultLanguage) throws IOException {
        final Map<String, Properties> bundles = new HashMap<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "messages_*.properties")) {
                for (Path f : ds) {
                    final Matcher m = FILE.matcher(f.getFileName().toString());
                    if (!m.matches()) {
                        continue;
                    }
                    final Properties p = new Properties();
                    try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                        p.load(r);
                    }
                    bundles.put(m.group(1), p);
                }
            }
        }
        return new Messages(bundles, defaultLanguage);
    }

    public static Messages of(Map<String, Properties> bundles, String defaultLanguage) {
        return new Messages(bundles, defaultLanguage);
    }

    public String get(String lang, String key, Object... args) {
        String pattern = null;
        String used = null;
        for (String l : new String[] { lang, defaultLanguage, "en" }) {
            if (l == null) {
                continue;
            }
            final Properties p = bundles.get(l.toLowerCase(Locale.ROOT));
            if (p != null && p.getProperty(key) != null) {
                pattern = p.getProperty(key);
                used = l;
                break;
            }
        }
        if (pattern == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return pattern.replace("''", "'");
        }
        return new MessageFormat(pattern, Locale.forLanguageTag(used)).format(args);
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    public boolean has(String lang) {
        return bundles.containsKey(lang);
    }
}
