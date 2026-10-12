package org.l2start.economy.market.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * StoresPerTown: "Giran:20,Dion:0,...". Towns not named and bad entries have no stores. For now only Giran has a
 * number above 0; the other towns are listed so that turning them on is one setting.
 */
public final class TownQuota {
    private final Map<String, Integer> perTown;

    private TownQuota(Map<String, Integer> perTown) {
        this.perTown = Collections.unmodifiableMap(perTown);
    }

    public static TownQuota parse(String setting) {
        final Map<String, Integer> m = new LinkedHashMap<>();
        if (setting != null) {
            for (String part : setting.split(",")) {
                final int c = part.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                final String town = part.substring(0, c).trim();
                try {
                    m.put(key(town), Math.max(0, Math.min(200, Integer.parseInt(part.substring(c + 1).trim()))));
                } catch (NumberFormatException e) {
                    // bad entry: no stores there
                }
            }
        }
        return new TownQuota(m);
    }

    public int stores(String town) {
        return (town == null) ? 0 : perTown.getOrDefault(key(town), 0);
    }

    public boolean enabled(String town) {
        return stores(town) > 0;
    }

    /** Towns with stores, in the setting's order. */
    public Map<String, Integer> active() {
        final Map<String, Integer> m = new LinkedHashMap<>();
        perTown.forEach((t, n) -> {
            if (n > 0) {
                m.put(t, n);
            }
        });
        return m;
    }

    public int total() {
        return perTown.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String key(String town) {
        return town.trim().toLowerCase(Locale.ROOT);
    }
}
