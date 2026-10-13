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
 * @param heading where it looks, client units (0..65535 = a full turn, 0 = east, counter-clockwise like the map angle)
 * @param undead an undead monster (holy skills, Turn Undead)
 * @param spoiled somebody has already spoiled it (Spoil worked: Sweeper on the corpse gives the spoil)
 */
public record NpcView(int objectId, int npcId, String name, int level, Pos pos, double distance, double hpPct, boolean dead,
    boolean monster, boolean aggressive, boolean attackable, int targetId, boolean engagedByOther, int attackRange, boolean attackingNow, int aggroRange,
    int heading, boolean undead, boolean spoiled, boolean champion) {
    /** Melee range of a monster when nothing else is known. */
    public static final int MELEE = 40;
    /** Aggro range of an aggressive monster when nothing else is known (the usual value in the NPC data). */
    public static final int AGGRO = 300;

    /** Phase 4c (not a champion). */
    public NpcView(int objectId, int npcId, String name, int level, Pos pos, double distance, double hpPct, boolean dead, boolean monster, boolean aggressive, boolean attackable,
        int targetId, boolean engagedByOther, int attackRange, boolean attackingNow, int aggroRange, int heading, boolean undead, boolean spoiled) {
        this(objectId, npcId, name, level, pos, distance, hpPct, dead, monster, aggressive, attackable, targetId, engagedByOther, attackRange, attackingNow, aggroRange, heading, undead, spoiled, false);
    }

    /** Phase 4c, first part (no heading, not undead, not spoiled). */
    public NpcView(int objectId, int npcId, String name, int level, Pos pos, double distance, double hpPct, boolean dead, boolean monster, boolean aggressive, boolean attackable,
        int targetId, boolean engagedByOther, int attackRange, boolean attackingNow, int aggroRange) {
        this(objectId, npcId, name, level, pos, distance, hpPct, dead, monster, aggressive, attackable, targetId, engagedByOther, attackRange, attackingNow, aggroRange, 0, false, false, false);
    }

    /** Phase 4a (no aggro range known: the usual one for aggressive monsters). */
    public NpcView(int objectId, int npcId, String name, int level, Pos pos, double distance, double hpPct, boolean dead, boolean monster, boolean aggressive, boolean attackable,
        int targetId, boolean engagedByOther, int attackRange, boolean attackingNow) {
        this(objectId, npcId, name, level, pos, distance, hpPct, dead, monster, aggressive, attackable, targetId, engagedByOther, attackRange, attackingNow, aggressive ? AGGRO : 0, 0, false, false, false);
    }

    /** Before phase 4 (melee monster, not swinging). */
    public NpcView(int objectId, int npcId, String name, int level, Pos pos, double distance, double hpPct, boolean dead, boolean monster, boolean aggressive, boolean attackable,
        int targetId, boolean engagedByOther) {
        this(objectId, npcId, name, level, pos, distance, hpPct, dead, monster, aggressive, attackable, targetId, engagedByOther, MELEE, false, aggressive ? AGGRO : 0, 0, false, false, false);
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

    /**
     * The point is behind it: the way from it to the point is at least {@code minDeg} degrees off where it looks
     * (stock "behind" is about 120 = within 60 degrees of its back).
     */
    public boolean behind(Pos at, double minDeg) {
        final double dx = at.x() - pos.x();
        final double dy = at.y() - pos.y();
        if ((dx == 0) && (dy == 0)) {
            return false;
        }
        final double look = (heading * 360.0) / 65536.0;
        final double to = Math.toDegrees(Math.atan2(dy, dx));
        double diff = Math.abs(to - look) % 360;
        if (diff > 180) {
            diff = 360 - diff;
        }
        return diff >= minDeg;
    }

    /** A point {@code dist} straight behind it (opposite to where it looks). */
    public Pos behindPoint(double dist) {
        final double back = Math.toRadians((heading * 360.0) / 65536.0) + Math.PI;
        return new Pos((int) Math.round(pos.x() + (Math.cos(back) * dist)), (int) Math.round(pos.y() + (Math.sin(back) * dist)), pos.z());
    }
}
