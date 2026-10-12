package org.l2start.platform.api;

import java.util.List;
import java.util.function.Supplier;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;

/**
 * Since API 1.6 (hook P7-DROP-FLOAT). The drop or spoil of a dead monster is being calculated (stock
 * {@code NpcTemplate.calculateDrops}); the listener may replace it. Called on the game thread of the kill, must be quick.
 */
@FunctionalInterface
public interface DropListener {
    /**
     * @param spoil true = the spoil (sweep) list, false = the death drop
     * @param victim the dead monster
     * @param killer who gets the drop (stock: most damage; may be a non-player)
     * @param stockRoll one stock roll of the same list (groups, level gap penalty, manor seeding all stock); call it as
     *            often as needed, it never comes back to a listener; null = nothing dropped
     * @return the drop (empty = nothing), or null = keep the stock calculation
     */
    List<ItemHolder> onDrops(boolean spoil, Creature victim, Creature killer, Supplier<List<ItemHolder>> stockRoll);
}
