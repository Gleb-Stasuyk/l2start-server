package org.l2start.platform.loader;

import java.util.Locale;

/** Module data folders that the core loads through platform hooks. */
public enum DataType {
    NPCS(true),
    SPAWNS(false),
    MULTISELL(true),
    BUYLISTS(true),
    HTML(false),
    /** Starting items per class (stock format of data/stats/players/initialEquipment.xml); replaces the listed classes. Since API 1.1. */
    INITIAL_EQUIPMENT(false, "initialEquipment"),
    /** Skill XML files (stock format of data/stats/skills); loaded after the stock and custom skills. Since API 1.12. */
    SKILLS(false, "skills");

    private final boolean hasIds;
    private final String key;

    DataType(boolean hasIds) {
        this(hasIds, null);
    }

    DataType(boolean hasIds, String key) {
        this.hasIds = hasIds;
        this.key = key;
    }

    /** True when the folder defines ids that must be reserved and must not collide with stock data. */
    public boolean hasIds() {
        return hasIds;
    }

    /** Key in module.json "data" (matched case-insensitively). */
    public String key() {
        return key != null ? key : name().toLowerCase(Locale.ROOT);
    }

    public static DataType byKey(String key) {
        for (DataType t : values()) {
            if (t.key().equalsIgnoreCase(key)) {
                return t;
            }
        }
        return null;
    }
}
