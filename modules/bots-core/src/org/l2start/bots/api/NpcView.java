package org.l2start.bots.api;

/**
 * An NPC or monster the bot sees.
 * @param monster can be fought (stock Monster)
 * @param aggressive attacks players on sight
 * @param targetId who it is fighting (object id, 0 = nobody)
 * @param engagedByOther someone other than this bot is fighting it (hit it or is hated by it): no kill-steal
 */
public record NpcView(int objectId, int npcId, String name, int level, Pos pos, double distance, double hpPct, boolean dead,
    boolean monster, boolean aggressive, boolean attackable, int targetId, boolean engagedByOther) {
}
