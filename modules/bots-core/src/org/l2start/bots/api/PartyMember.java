package org.l2start.bots.api;

/**
 * Another member of the bot's party (phase 4g: bot pairs and trios), wherever it is.
 * @param targetId what it has targeted (0 = nothing): the followers assist the leader on it
 * @param leader the party leader
 * @param bot another bot (phase 6: false = a real player, the bot plays with him)
 * @param sitting sitting now (phase 6c: the bot rests when its player does)
 */
public record PartyMember(int objectId, String name, int classId, int level, Pos pos, double distance, double hpPct, double mpPct, boolean dead, int targetId,
    boolean leader, boolean bot, boolean sitting) {
    /** Phase 4g: bot pairs and trios only. */
    public PartyMember(int objectId, String name, int classId, int level, Pos pos, double distance, double hpPct, double mpPct, boolean dead, int targetId, boolean leader) {
        this(objectId, name, classId, level, pos, distance, hpPct, mpPct, dead, targetId, leader, true, false);
    }
}
