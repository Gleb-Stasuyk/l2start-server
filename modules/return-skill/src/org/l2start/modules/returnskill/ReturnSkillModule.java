package org.l2start.modules.returnskill;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * Every player (new and old characters, every class) knows skill {@link #SKILL_ID} from login: given on each login
 * without storing it (no skill-tree check, nothing in the database).
 */
public final class ReturnSkillModule implements GameModule {
    public static final int SKILL_ID = 95100;

    @Override
    public void onEnable(ModuleContext c) throws Exception {
        final Skill s = SkillData.getInstance().getSkill(SKILL_ID, 1);
        if (s == null) {
            throw new IllegalStateException("skill " + SKILL_ID + " is not loaded (data/skills)");
        }
        c.events().<OnPlayerLogin> onGlobal(EventType.ON_PLAYER_LOGIN, e -> give(e.getPlayer()));
        c.log().info("enabled: skill " + SKILL_ID + " '" + s.getName() + "', cast " + s.getHitTime() + " ms, reuse " + s.getReuseDelay() + " ms");
    }

    static void give(Player p) {
        if ((p == null) || (p.getKnownSkill(SKILL_ID) != null)) {
            return;
        }
        final Skill s = SkillData.getInstance().getSkill(SKILL_ID, 1);
        if (s != null) {
            p.addSkill(s, false);
            p.sendSkillList();
        }
    }
}
