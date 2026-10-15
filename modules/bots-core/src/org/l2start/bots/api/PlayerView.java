package org.l2start.bots.api;

/**
 * Another player the bot sees.
 * @param bot only for brains: never shown outside
 * @param inStore private store / craft (phase 6g: bots stay quiet toward them)
 * @param withNpc talking to an NPC (dialog open)
 * @param inPvpZone not in peace/town (phase 7a; siege ignored until phase 8)
 */
public record PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
    boolean inCombat, int targetId, boolean bot, boolean inStore, boolean withNpc, boolean inPvpZone) {

    /** Before phase 7a (no zone flag): assumes not in a PvP field. */
    public PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
        boolean inCombat, int targetId, boolean bot, boolean inStore, boolean withNpc) {
        this(objectId, name, level, classId, pos, distance, karma, pvpFlag, inCombat, targetId, bot, inStore, withNpc, false);
    }

    /** Before phase 6g (no store / NPC dialog / zone flags). */
    public PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
        boolean inCombat, int targetId, boolean bot) {
        this(objectId, name, level, classId, pos, distance, karma, pvpFlag, inCombat, targetId, bot, false, false, false);
    }

    /** Phase 6g: in combat, private store, or NPC dialog — bots should not chat at them (except urgent party lines). */
    public boolean socialBusy() {
        return inCombat || inStore || withNpc;
    }

    /** Phase 7a: flagged or chaotic — stock-visible PvP state. */
    public boolean pvpMarked() {
        return pvpFlag || (karma > 0);
    }
}
