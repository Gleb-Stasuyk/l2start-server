package org.l2start.bots.api;

/**
 * Another player the bot sees.
 * @param bot only for brains: never shown outside
 * @param inStore private store / craft (phase 6g: bots stay quiet toward them)
 * @param withNpc talking to an NPC (dialog open)
 * @param inPvpZone not in peace/town (phase 7a; siege ignored until phase 8)
 * @param clanId phase 7d clan id (0 = none)
 * @param hpPct current HP percent (phase 9 D: olympiad playstyle TARGET_HP_*)
 * @param heading where they look, client units (phase 9 D: dagger rear)
 * @param olympiadMode stock {@code isInOlympiadMode} (contestant, not an observer)
 * @param olympiadGameId stock game id ({@code -1} = none)
 */
public record PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
    boolean inCombat, int targetId, boolean bot, boolean inStore, boolean withNpc, boolean inPvpZone, int clanId,
    double hpPct, int heading, boolean olympiadMode, int olympiadGameId) {

    /** Before phase 9 D (no olympiad / HP / heading). */
    public PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
        boolean inCombat, int targetId, boolean bot, boolean inStore, boolean withNpc, boolean inPvpZone, int clanId) {
        this(objectId, name, level, classId, pos, distance, karma, pvpFlag, inCombat, targetId, bot, inStore, withNpc, inPvpZone, clanId, 100, 0, false, -1);
    }

    /** Before phase 7d (no clan). */
    public PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
        boolean inCombat, int targetId, boolean bot, boolean inStore, boolean withNpc, boolean inPvpZone) {
        this(objectId, name, level, classId, pos, distance, karma, pvpFlag, inCombat, targetId, bot, inStore, withNpc, inPvpZone, 0);
    }

    /** Before phase 7a (no zone flag): assumes not in a PvP field. */
    public PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
        boolean inCombat, int targetId, boolean bot, boolean inStore, boolean withNpc) {
        this(objectId, name, level, classId, pos, distance, karma, pvpFlag, inCombat, targetId, bot, inStore, withNpc, false, 0);
    }

    /** Before phase 6g (no store / NPC dialog / zone flags). */
    public PlayerView(int objectId, String name, int level, int classId, Pos pos, double distance, int karma, boolean pvpFlag,
        boolean inCombat, int targetId, boolean bot) {
        this(objectId, name, level, classId, pos, distance, karma, pvpFlag, inCombat, targetId, bot, false, false, false, 0);
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
