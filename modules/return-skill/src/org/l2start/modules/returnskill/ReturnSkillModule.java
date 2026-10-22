package org.l2start.modules.returnskill;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLevelChanged;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * Every player (new and old characters, every class) knows skill {@link #SKILL_ID} from login (stock "Escape: 5 minutes",
 * overridden by data/skills: the client shows its icon and name, a module-only id had none and the client could not use it): given on each login
 * without storing it (no skill-tree check, nothing in the database).
 */
public final class ReturnSkillModule implements GameModule {
    public static final int SKILL_ID = 2099;

    @Override
    public void onEnable(ModuleContext c) throws Exception {
        final Skill s = SkillData.getInstance().getSkill(SKILL_ID, 1);
        if (s == null) {
            throw new IllegalStateException("skill " + SKILL_ID + " is not loaded (data/skills)");
        }
        autoLearnMax = c.config().getInt("AutoLearnMaxLevel");
        c.events().<OnPlayerLogin> onGlobal(EventType.ON_PLAYER_LOGIN, e -> {
            give(e.getPlayer());
            autoLearn(e.getPlayer(), autoLearnMax);
        });
        // a level up: the new level is set right after the event, so the skills are given a moment later
        c.events().<OnPlayerLevelChanged> onPlayers(EventType.ON_PLAYER_LEVEL_CHANGED,
            e -> c.scheduler().schedule("auto-learn", java.time.Duration.ofMillis(500), () -> autoLearn(e.getPlayer(), autoLearnMax)));
        c.log().info("enabled: skill " + SKILL_ID + " '" + s.getName() + "', cast " + s.getHitTime() + " ms, reuse " + s.getReuseDelay() + " ms");
    }

    private volatile int autoLearnMax = 19;

    @Override
    public void onConfigReload(ModuleContext c) {
        autoLearnMax = c.config().getInt("AutoLearnMaxLevel");
    }

    /** Pure: a live player of this level learns his skills automatically (stock AutoLearnSkills has no level limit). */
    static boolean autoLearns(boolean bot, int level, int max) {
        return !bot && (level <= max);
    }

    /** Live players up to {@code max}: every skill of the class up to the level, like stock AutoLearnSkills. Bots: never. */
    static void autoLearn(Player p, int max) {
        if ((p == null) || !p.isOnline() || !autoLearns(org.l2start.platform.hooks.CoreHooks.isBot(p), p.getLevel(), max)) {
            return;
        }
        if (p.giveAvailableSkills(false, true, true) > 0) {
            p.sendSkillList();
        }
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
