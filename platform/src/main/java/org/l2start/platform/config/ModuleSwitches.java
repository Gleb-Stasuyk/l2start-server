package org.l2start.platform.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.l2start.platform.util.IniFile;

/**
 * Which modules are on: defaultEnabled[profile] -> modules.ini (+[profile]) -> modules.local.ini (+[profile])
 * -> env L2_MODULE_&lt;ID&gt;=on|off.
 */
public final class ModuleSwitches {
    private final String profile;
    private final IniFile main;
    private final IniFile local;
    private final Map<String, String> env;

    public record Switch(boolean enabled, String source) {
    }

    public ModuleSwitches(String profile, IniFile main, IniFile local, Map<String, String> env) {
        this.profile = profile;
        this.main = main;
        this.local = local;
        this.env = env;
    }

    public static ModuleSwitches load(Path configDir, String profile, Map<String, String> env) throws IOException {
        return new ModuleSwitches(profile, IniFile.load(configDir.resolve("modules.ini")), IniFile.load(configDir.resolve("modules.local.ini")), env);
    }

    /** @param defaultEnabled from module.json, null when the manifest could not be read */
    public Switch resolve(String id, Boolean defaultEnabled) {
        Switch s = new Switch(defaultEnabled != null && defaultEnabled, "defaultEnabled." + profile + " in module.json");
        s = apply(s, main, "modules.ini", id);
        s = apply(s, local, "modules.local.ini", id);
        final String e = env.get("L2_MODULE_" + Env.idPart(id));
        if (e != null) {
            final Boolean b = parse(e);
            if (b != null) {
                s = new Switch(b, "env L2_MODULE_" + Env.idPart(id));
            }
        }
        return s;
    }

    /** Explicitly configured (not from defaultEnabled). */
    public boolean isExplicit(String id) {
        return main.resolve(profile).containsKey(id.toLowerCase(Locale.ROOT)) || local.resolve(profile).containsKey(id.toLowerCase(Locale.ROOT)) || env.containsKey("L2_MODULE_" + Env.idPart(id));
    }

    private Switch apply(Switch current, IniFile ini, String file, String id) {
        final IniFile.Entry e = ini.resolve(profile).get(id.toLowerCase(Locale.ROOT));
        if (e == null) {
            return current;
        }
        final Boolean b = parse(e.value());
        if (b == null) {
            return current;
        }
        return new Switch(b, file + (ini.section(profile).containsKey(id.toLowerCase(Locale.ROOT)) ? " [" + profile + "]" : ""));
    }

    static Boolean parse(String v) {
        final String l = v.trim().toLowerCase(Locale.ROOT);
        if (l.equals("on") || l.equals("true") || l.equals("yes") || l.equals("1")) {
            return Boolean.TRUE;
        }
        if (l.equals("off") || l.equals("false") || l.equals("no") || l.equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
