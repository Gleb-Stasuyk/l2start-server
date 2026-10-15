package org.l2start.bots.api;

/**
 * Another player the bot sees.
 * @param bot only for brains: never shown outside
 * @param inStore private store / craft (phase 6g: bots stay quiet toward them)
 * @param withNpc talking to an NPC (dialog open)
 */
public record PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
    boolean inCombat, int targetId, boolean bot, boolean inStore, boolean withNpc) {

    /** Before phase 6g (no store / NPC dialog flags). */
    public PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
        boolean inCombat, int targetId, boolean bot) {
        this(objectId, name, level, classId, pos, distance, karma, pvpFlag, inCombat, targetId, bot, false, false);
    }

    /** Phase 6g: in combat, private store, or NPC dialog — bots should not chat at them (except urgent party lines). */
    public boolean socialBusy() {
        return inCombat || inStore || withNpc;
    }
}
