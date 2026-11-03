package org.l2start.modules.chbuff;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.ClanHallManager;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.OnNpcSkillFinished;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * Clan hall manager buffs last {@code DurationSec} for players up to {@code MaxLevel} (level at cast time).
 * The remaining time of a buff is computed from its abnormal time ({@link BuffInfo#getTime()}), so changing it after the buff started works.
 */
public final class ChBuffDurationModule implements GameModule {
    private volatile int maxLevel = 39;
    private volatile int durationSec = 3600;
    private volatile boolean includeBots = true;
    private volatile Set<Integer> skills = Set.of();

    @Override
    public void onEnable(ModuleContext c) throws Exception {
        load(c);
        c.events().<OnNpcSkillFinished> onGlobal(EventType.ON_NPC_SKILL_FINISHED, e -> {
            if (!(e.getCaster() instanceof ClanHallManager) || (e.getSkill() == null)) {
                return;
            }
            final Player p = e.getTarget();
            if (p == null) {
                return;
            }
            final int skillId = e.getSkill().getId();
            if (!applies(skills, maxLevel, includeBots, skillId, p.getLevel(), org.l2start.platform.hooks.CoreHooks.isBot(p))) {
                return;
            }
            final int dur = durationSec;
            if (!extend(p, skillId, dur)) {
                c.scheduler().schedule("ch-buff-retry", Duration.ofMillis(100), () -> extend(p, skillId, dur));
            }
        });
        c.log().info("enabled: clan hall buffs " + durationSec + " s up to level " + maxLevel + ", " + skills.size() + " skills, bots " + includeBots);
    }

    @Override
    public void onConfigReload(ModuleContext c) {
        load(c);
    }

    private void load(ModuleContext c) {
        maxLevel = c.config().getInt("MaxLevel");
        durationSec = c.config().getInt("DurationSec");
        includeBots = c.config().getBoolean("IncludeBots");
        skills = parseSkills(c.config().getList("Skills"));
    }

    /** Pure: the long duration applies to this cast. */
    static boolean applies(Set<Integer> skills, int maxLevel, boolean includeBots, int skillId, int level, boolean bot) {
        return (maxLevel > 0) && skills.contains(skillId) && (level <= maxLevel) && (includeBots || !bot);
    }

    /** Pure: "4342-4360", "4400" -> ids. Bad entries are skipped. */
    static Set<Integer> parseSkills(List<String> entries) {
        final Set<Integer> out = new HashSet<>();
        if (entries == null) {
            return out;
        }
        for (String raw : entries) {
            final String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                final int dash = s.indexOf('-');
                if (dash > 0) {
                    final int a = Integer.parseInt(s.substring(0, dash).trim());
                    final int b = Integer.parseInt(s.substring(dash + 1).trim());
                    for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                        out.add(i);
                    }
                } else {
                    out.add(Integer.parseInt(s));
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return Set.copyOf(out);
    }

    /** Sets the buff's duration; false when the buff is not on the player (yet). */
    static boolean extend(Player p, int skillId, int durationSec) {
        if ((p == null) || !p.isOnline()) {
            return true;
        }
        final BuffInfo info = p.getEffectList().getBuffInfoBySkillId(skillId);
        if (info == null) {
            return false;
        }
        info.setAbnormalTime(durationSec + (info.getAbnormalTime() - info.getTime()));
        p.updateEffectIcons();
        return true;
    }
}