package org.l2start.bots.api;

import java.util.List;

/**
 * The bot itself at the start of the tick.
 * @param targetId object id of the current target (0 = none)
 * @param inPeaceZone town / peace zone (no fights)
 * @param busy casting, attacking or moving right now (stock AI is doing something)
 * @param intention stock AI intention name (IDLE, ACTIVE, MOVE_TO, ATTACK, CAST, PICK_UP, FOLLOW, REST, INTERACT)
 */
public record SelfView(int objectId, String name, int classId, boolean mage, int level, double expPercent, long sp,
    double hp, int maxHp, double mp, int maxMp, Pos pos, boolean dead, boolean sitting, boolean moving, boolean inCombat,
    boolean attacking, boolean casting, int targetId, boolean inPeaceZone, boolean teleporting, String intention, boolean hasWeapon,
    List<SkillView> skills) {
    public double hpPct() {
        return maxHp <= 0 ? 0 : (hp * 100) / maxHp;
    }

    public double mpPct() {
        return maxMp <= 0 ? 0 : (mp * 100) / maxMp;
    }
}
