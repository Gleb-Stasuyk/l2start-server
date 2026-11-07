package org.l2start.modules.stocknpcs;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Playable;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;

/** Duration of a buff the stock Scheme Buffer just applied. Other casters are left alone. */
public final class StockNpcBuffs {
    private StockNpcBuffs() {
    }

    /**
     * Abnormal time to store so the remaining time is {@code durationSec}.
     * {@code remaining} is the time left on the buff before the change.
     */
    public static int abnormalTimeFor(int currentAbnormal, int remaining, int durationSec) {
        final int elapsed = Math.max(0, currentAbnormal - remaining);
        return durationSec + elapsed;
    }

    /**
     * Stretch only the buff this NPC just put on the target (its effector is {@code buffer}).
     * A failed cast leaves the previous buff, whose effector is someone else, untouched.
     * @return true when the duration was changed
     */
    public static boolean extend(Creature target, Creature buffer, int skillId, int durationSec) {
        if ((target == null) || (buffer == null) || (skillId <= 0) || (durationSec <= 0)) {
            return false;
        }
        final BuffInfo info = target.getEffectList().getBuffInfoBySkillId(skillId);
        if ((info == null) || (info.getEffector() != buffer)) {
            return false;
        }
        info.setAbnormalTime(abnormalTimeFor(info.getAbnormalTime(), info.getTime(), durationSec));
        if (target instanceof Playable playable) {
            playable.updateEffectIcons();
        }
        return true;
    }
}
