package org.l2start.platform.api;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Since API 1.7 (hook P8-TELEPORT-NAME). The name of a destination in a gatekeeper teleport list (before the stock fee
 * text): the listener may change it (world-info adds the monster levels). Called on the game thread, must be quick.
 */
@FunctionalInterface
public interface TeleportNameListener {
    /**
     * @param where the destination point
     * @param name the name so far (stock: from the teleporter data)
     * @return the name to show (stock: {@code name})
     */
    String onTeleportName(Player player, Location where, String name);
}
