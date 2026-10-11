package org.l2start.economy.market;

import java.util.HashSet;
import java.util.Set;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.zone.ZoneRegion;
import org.l2jmobius.gameserver.entity.zone.ZoneType;
import org.l2jmobius.gameserver.entity.zone.type.NoStoreZone;
import org.l2jmobius.gameserver.entity.zone.type.PeaceZone;
import org.l2jmobius.gameserver.managers.ZoneManager;

/**
 * Setting StoresAnywhere (decision of 12.10.2026): private stores for everybody anywhere. The stock rules come from
 * zones only: the NoStoreZone zones (data/zones/custom_no_store.xml, Giran temple, gatekeepers, ...) and peace zones
 * with allowStore=false (Seven Signs). Done once at the start, before any player or bot enters: the no-store zones
 * are taken out of the zone regions (nobody gets the NO_STORE flag from them), the peace zones allow stores. Jail
 * stays without stores. Off = stock.
 */
final class StoresAnywhere {
    private StoresAnywhere() {
    }

    /** @return {no-store zones off, peace zones that now allow stores} */
    static int[] apply() {
        final ZoneManager zm = ZoneManager.getInstance();
        final Set<Integer> ids = new HashSet<>();
        for (NoStoreZone z : zm.getAllZones(NoStoreZone.class)) {
            ids.add(z.getId());
        }
        if (!ids.isEmpty()) {
            final int step = 1 << 15; // ZoneManager region size
            final Set<ZoneRegion> seen = new HashSet<>();
            for (int x = World.WORLD_X_MIN; x <= World.WORLD_X_MAX; x += step) {
                for (int y = World.WORLD_Y_MIN; y <= World.WORLD_Y_MAX; y += step) {
                    final ZoneRegion r = zm.getRegion(x, y);
                    if ((r != null) && seen.add(r)) {
                        r.getZones().keySet().removeAll(ids);
                    }
                }
            }
        }
        int peace = 0;
        for (PeaceZone z : zm.getAllZones(PeaceZone.class)) {
            if (!z.getAllowStore()) {
                ((ZoneType) z).setParameter("allowStore", "true");
                peace++;
            }
        }
        return new int[] { ids.size(), peace };
    }
}
