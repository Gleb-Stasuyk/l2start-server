package org.l2start.bots.api;

/**
 * Another member of the bot's party (phase 4g: bot pairs and trios), wherever it is.
 * @param targetId what it has targeted (0 = nothing): the followers assist the leader on it
 * @param leader the party leader
 */
public record PartyMember(int objectId, String name, int classId, int level, Pos pos, double distance, double hpPct, double mpPct, boolean dead, int targetId,
    boolean leader) {
}
