package org.l2start.bots.api;

import java.util.List;

/**
 * The bot itself at the start of the tick.
 * @param targetId object id of the current target (0 = none)
 * @param inPeaceZone town / peace zone (no fights)
 * @param busy casting, attacking or moving right now (stock AI is doing something)
 * @param intention stock AI intention name (IDLE, ACTIVE, MOVE_TO, ATTACK, CAST, PICK_UP, FOLLOW, REST, INTERACT)
 * @param attackRange physical attack range of the weapon (bow about 500, melee about 40)
 * @param bow a bow is in the hands
 * @param charges sonic or force charges the bot holds now (Gladiator, Tyrant lines)
 * @param petId object id of its own summon (servitor or pet) in the world, 0 = none
 * @param petHpPct hit points of that summon in percent (0 when none or dead)
 * @param petTargetId object id of what that summon is fighting or going to (0 = nothing)
 * @param clanId phase 7d stock / bot clan id (0 = none)
 */
public record SelfView(int objectId, String name, int classId, boolean mage, int level, double expPercent, long sp,
    double hp, int maxHp, double mp, int maxMp, Pos pos, boolean dead, boolean sitting, boolean moving, boolean inCombat,
    boolean attacking, boolean casting, int targetId, boolean inPeaceZone, boolean teleporting, String intention, boolean hasWeapon,
    List<SkillView> skills, double cp, int maxCp, int attackRange, boolean bow, int charges, int petId, double petHpPct, int petTargetId,
    int clanId) {
    /** Before phase 7d (no clan). */
    public SelfView(int objectId, String name, int classId, boolean mage, int level, double expPercent, long sp,
        double hp, int maxHp, double mp, int maxMp, Pos pos, boolean dead, boolean sitting, boolean moving, boolean inCombat,
        boolean attacking, boolean casting, int targetId, boolean inPeaceZone, boolean teleporting, String intention, boolean hasWeapon,
        List<SkillView> skills, double cp, int maxCp, int attackRange, boolean bow, int charges, int petId, double petHpPct, int petTargetId) {
        this(objectId, name, classId, mage, level, expPercent, sp, hp, maxHp, mp, maxMp, pos, dead, sitting, moving, inCombat, attacking, casting,
            targetId, inPeaceZone, teleporting, intention, hasWeapon, skills, cp, maxCp, attackRange, bow, charges, petId, petHpPct, petTargetId, 0);
    }

    /** Before phase 4c (no charges, no summon). */
    public SelfView(int objectId, String name, int classId, boolean mage, int level, double expPercent, long sp, double hp, int maxHp, double mp, int maxMp, Pos pos,
        boolean dead, boolean sitting, boolean moving, boolean inCombat, boolean attacking, boolean casting, int targetId, boolean inPeaceZone, boolean teleporting,
        String intention, boolean hasWeapon, List<SkillView> skills, double cp, int maxCp, int attackRange, boolean bow) {
        this(objectId, name, classId, mage, level, expPercent, sp, hp, maxHp, mp, maxMp, pos, dead, sitting, moving, inCombat, attacking, casting, targetId, inPeaceZone,
            teleporting, intention, hasWeapon, skills, cp, maxCp, attackRange, bow, 0, 0, 0, 0, 0);
    }

    /** Before phase 4 (no CP, melee weapon). */
    public SelfView(int objectId, String name, int classId, boolean mage, int level, double expPercent, long sp, double hp, int maxHp, double mp, int maxMp, Pos pos,
        boolean dead, boolean sitting, boolean moving, boolean inCombat, boolean attacking, boolean casting, int targetId, boolean inPeaceZone, boolean teleporting,
        String intention, boolean hasWeapon, List<SkillView> skills) {
        this(objectId, name, classId, mage, level, expPercent, sp, hp, maxHp, mp, maxMp, pos, dead, sitting, moving, inCombat, attacking, casting, targetId, inPeaceZone,
            teleporting, intention, hasWeapon, skills, 0, 0, NpcView.MELEE, false, 0, 0, 0, 0, 0);
    }

    public double hpPct() {
        return maxHp <= 0 ? 0 : (hp * 100) / maxHp;
    }

    public double mpPct() {
        return maxMp <= 0 ? 0 : (mp * 100) / maxMp;
    }

    public double cpPct() {
        return maxCp <= 0 ? 0 : (cp * 100) / maxCp;
    }

    /** It has its own summon in the world. */
    public boolean hasPet() {
        return petId != 0;
    }

    /**
     * Phase 7a: PvP is allowed here (not peace / town). Siege flags are ignored until phase 8.
     */
    public boolean inPvpZone() {
        return !inPeaceZone;
    }
}
