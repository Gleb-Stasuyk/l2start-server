package org.l2start.platform.config;

import java.util.Locale;
import java.util.Map;

/** Environment variable naming used by the platform. */
public final class Env {
    private Env() {
    }

    /** "bots-core" -> "BOTS_CORE". */
    public static String idPart(String id) {
        return id.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** Case-insensitive key comparison ignoring '_' and '.': "GreetOnLogin" == "GREETONLOGIN" == "greet_on_login". */
    public static String norm(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace(".", "");
    }

    /** Looks up PREFIX + key in env with {@link #norm} comparison of the key part. */
    public static String find(Map<String, String> env, String prefix, String key) {
        final String want = norm(key);
        for (Map.Entry<String, String> e : env.entrySet()) {
            final String k = e.getKey();
            if (k.length() > prefix.length() && k.regionMatches(true, 0, prefix, 0, prefix.length()) && norm(k.substring(prefix.length())).equals(want)) {
                return e.getValue();
            }
        }
        return null;
    }
}
