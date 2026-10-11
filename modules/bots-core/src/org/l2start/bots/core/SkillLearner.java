package org.l2start.bots.core;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillLearn;

/**
 * Learning class skills with SP, the way RequestAcquireSkill (CLASS) does it at a trainer, but without walking to
 * one (approved simplification until phase 4). Skills that need items (spellbooks) are skipped.
 */
final class SkillLearner {
    private SkillLearner() {
    }

    /** Skills the player can afford right now (first round only; no change). */
    static int preview(Player p) {
        int n = 0;
        long sp = p.getSp();
        for (SkillLearn s : SkillTreeData.getInstance().getAvailableSkills(p, p.getPlayerClass(), false, false)) {
            if ((s.getGetLevel() <= p.getLevel()) && s.getRequiredItems().isEmpty()) {
                final int cost = s.getCalculatedLevelUpSp(p.getPlayerClass(), p.getLearningClass());
                if (cost <= sp) {
                    sp -= cost;
                    n++;
                }
            }
        }
        return n;
    }

    /** @return skills learned (writes character_skills: call it off the shard threads) */
    static int learn(Player p) {
        int learned = 0;
        for (int round = 0; round < 20; round++) { // a learned level can make the next one available
            boolean any = false;
            for (SkillLearn s : SkillTreeData.getInstance().getAvailableSkills(p, p.getPlayerClass(), false, false)) {
                if ((s.getGetLevel() > p.getLevel()) || !s.getRequiredItems().isEmpty()) {
                    continue;
                }
                boolean preReq = true;
                for (SkillHolder h : s.getPreReqSkills()) {
                    if (p.getSkillLevel(h.getSkillId()) < h.getSkillLevel()) {
                        preReq = false;
                        break;
                    }
                }
                if (!preReq) {
                    continue;
                }
                final int cost = s.getCalculatedLevelUpSp(p.getPlayerClass(), p.getLearningClass());
                if (cost > p.getSp()) {
                    continue;
                }
                final Skill skill = SkillData.getInstance().getSkill(s.getSkillId(), s.getSkillLevel());
                if (skill == null) {
                    continue;
                }
                if (cost > 0) {
                    p.setSp(p.getSp() - cost);
                }
                p.addSkill(skill, true);
                learned++;
                any = true;
            }
            if (!any) {
                break;
            }
        }
        if (learned > 0) {
            p.sendSkillList();
            p.updateUserInfo();
        }
        return learned;
    }
}
