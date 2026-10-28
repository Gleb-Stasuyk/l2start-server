package org.l2start.bots.api;

import java.util.List;

/**
 * What the presence director wants a bot to do (step 3e). The brain follows it; a new object (higher {@code seq})
 * replaces the previous one. Null in {@link BrainContext#assignment()} = no director (manual mode, phase 3d).
 * @param seq increasing number of this order
 * @param kind TOWN (live in a town), HUNT (hunt in a ground), LEAVE_RUN (run to {@code exit} and log out),
 *     LEAVE_GK (run to the gatekeeper at {@code exit} and log out there: for a client that is a teleport),
 *     TRANSIT (appeared at a gatekeeper "after a teleport": stand a few seconds, then teleport on = log out),
 *     PASS (a passer-by on a road: run past the player to {@code exit}, vanish out of sight),
 *     LEAVE_SOE (end of a hunt, phase 4d: read a scroll of escape where it stands; in town the director logs it out
 *     unseen or sends it on ERRANDS), LEAVE_SPOT (log out right here, sitting, as a "restart": only with nobody near),
 *     ERRANDS (back in town from a hunt in front of a player: 2-3 NPCs - shop, warehouse, trainer - now and then AFK,
 *     then the gatekeeper at {@code exit} and "teleport" = log out),
 *     NEWBIE (docs/STARTER_ZONES_PLAN.md S6: a beginner near a new live character: run to the gremlins at {@code center},
 *     10-15 kills, then run to its village at {@code exit}; there the director gives it town life or lets it vanish)
 * @param place id of the town or hunting ground (world catalogue)
 * @param center centre of the place
 * @param radius radius of the place
 * @param minLevel lowest monster level of the place (town: level band of the town)
 * @param maxLevel highest monster level of the place
 * @param spots hunting spots (territory centres) of a ground
 * @param anchors points of interest of a town (gatekeeper, shops, warehouse, trainers, restart points)
 * @param exit where a leaving bot goes (null unless leaving)
 */
public record Assignment(long seq, Kind kind, String place, Pos center, int radius, int minLevel, int maxLevel, List<Pos> spots, List<Anchor> anchors, Pos exit) {
    public enum Kind {
        TOWN, HUNT, LEAVE_RUN, LEAVE_GK, TRANSIT, PASS, LEAVE_SOE, LEAVE_SPOT, ERRANDS, NEWBIE
    }

    /** Kills a NEWBIE companion makes before it goes back to its village: 10-15, stable for the order and the bot. */
    public static int newbieKills(long seq, int botId) {
        return 10 + Math.floorMod(Long.hashCode((seq * 31) + botId) * 0x9E3779B1, 6);
    }

    /** A point of interest in a town: kind = gk, shop, wh, trainer, restart. */
    public record Anchor(String kind, Pos pos) {
    }

    public boolean leaving() {
        return (kind == Kind.LEAVE_RUN) || (kind == Kind.LEAVE_GK) || (kind == Kind.PASS) || (kind == Kind.LEAVE_SOE) || (kind == Kind.LEAVE_SPOT);
    }

    /** Only passing through (transit at a gatekeeper, passer-by, leaving): not one of the bots that live at a place. */
    public boolean passing() {
        return leaving() || (kind == Kind.TRANSIT) || (kind == Kind.ERRANDS);
    }

    /** The same place, now leaving towards {@code exit}. */
    public Assignment leave(long newSeq, Kind how, Pos to) {
        return new Assignment(newSeq, how, place, center, radius, minLevel, maxLevel, spots, anchors, to);
    }

    @Override
    public String toString() {
        return kind + " " + place + (exit == null ? "" : " -> " + exit.x() + "," + exit.y());
    }
}
