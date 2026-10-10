package org.l2start.platform.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Typed module configuration. Resolution order (last wins): default from module.json,
 * modules/&lt;id&gt;/config/defaults.ini (+[profile]), config/modules/&lt;id&gt;.ini (+[profile]), env L2_MOD_&lt;ID&gt;__&lt;KEY&gt;.
 */
public interface ModuleConfig {
    boolean has(String key);

    String getString(String key);

    boolean getBoolean(String key);

    int getInt(String key);

    long getLong(String key);

    double getDouble(String key);

    Duration getDuration(String key);

    List<String> getList(String key);

    <E extends Enum<E>> E getEnum(String key, Class<E> type);

    /** Where the current value came from: default, defaults.ini, config/modules/&lt;id&gt;.ini, env. */
    String source(String key);

    Map<String, String> asMap();
}
