package org.l2start.platform.loader;

import java.util.Locale;

/** Module data folders that the core loads through platform hooks. */
public enum DataType {
    NPCS(true),
    SPAWNS(false),
    MULTISELL(true),
    BUYLISTS(true),
    HTML(false);

    private final boolean hasIds;

    DataType(boolean hasIds) {
        this.hasIds = hasIds;
    }

    /** True when the folder defines ids that must be reserved and must not collide with stock data. */
    public boolean hasIds() {
        return hasIds;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
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
