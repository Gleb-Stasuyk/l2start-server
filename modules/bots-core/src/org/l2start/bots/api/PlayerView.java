package org.l2start.bots.api;

/**
 * Another player the bot sees.
 * @param bot only for brains: never shown outside
 */
public record PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
    boolean inCombat, int targetId, boolean bot) {
}
