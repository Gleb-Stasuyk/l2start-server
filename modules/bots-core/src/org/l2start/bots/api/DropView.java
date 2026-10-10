package org.l2start.bots.api;

/**
 * An item on the ground.
 * @param mine this bot may pick it up now (its own drop or the protection has ended)
 */
public record DropView(int objectId, int itemId, long count, Pos pos, double distance, boolean mine) {
}
