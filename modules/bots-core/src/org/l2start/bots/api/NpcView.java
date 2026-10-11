package org.l2start.bots.api;

/**
 * An NPC or monster the bot sees.
 * @param monster can be fought (stock Monster)
 * @param aggressive attacks players on sight
 * @param targetId who it is fighting (object id, 0 = nobody)
 * @param engagedByOther someone other than this bot is fighting it (hit it or is hated by it): no kill-steal
 * @param attackRange its physical attack range (melee monsters about 40; archers and mages 300+)
 * @param attackingNow it is in the middle of an attack (swinging) right now
 * @param aggroRange how close a player may come before an aggressive monster attacks (0 = not aggressive)
 */
public record NpcView(int objectId, int npcId, String name, int level, Pos pos, double distance, double hpPct, boolean dead,
    boolean monster, boolean aggressive, boolean attackable, int targetId, boolean engagedByOther, int attackRange, boolean attackingNow, int aggroRange) {
    /** Melee range of a monster when nothing else is known. */
    public static final int MELEE = 40;
    /** Aggro range of an aggressive monster when nothing else is known (the usual value in the NPC data). */
    public static final int AGGRO = 300;

    /** Phase 4a (no aggro range known: the usual one for aggressive monsters). */
    public NpcView(int objectId, int npcId, String name, int level, Pos pos, double distance, double hpPct, boolean dead, boolean monster, boolean aggressive, boolean attackable,
        int targetId, boolean engagedByOther, int attackRange, boolean attackingNow) {
        this(objectId, npcId, name, level, pos, distance, hpPct, dead, monster, aggressive, attackable, targetId, engagedByOther, attackRange, attackingNow, aggressive ? AGGRO : 0);
    }

    /** Before phase 4 (melee monster, not swinging). */
    public NpcView(int objectId, int npcId, String name, int level, Pos pos, double distance, double hpPct, boolean dead, boolean monster, boolean aggressive, boolean attackable,
        int targetId, boolean engagedByOther) {
        this(objectId, npcId, name, level, pos, distance, hpPct, dead, monster, aggressive, attackable, targetId, engagedByOther, MELEE, false, aggressive ? AGGRO : 0);
    }

    /** A monster that hits in melee (it has to come close: it can be kited). */
    public boolean melee() {
        return attackRange < 200;
    }

    /**
     * An aggressive monster that is free (fights nobody) and would attack a player who comes this close to it: walking or
     * stepping back into its aggro range pulls it.
     */
    public boolean freeAggressive() {
        return !dead && monster && aggressive && (targetId == 0) && !engagedByOther;
    }
}
