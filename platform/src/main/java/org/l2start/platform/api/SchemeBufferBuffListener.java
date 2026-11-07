package org.l2start.platform.api;

import org.l2jmobius.gameserver.entity.actor.Creature;

/**
 * Since API 1.13 (hook P11-BUFFER-TIME). The stock Scheme Buffer NPC applied one buff through
 * {@code Skill.applyEffects}. Called only from that NPC, after the buff is on the target.
 */
@FunctionalInterface
public interface SchemeBufferBuffListener {
    /**
     * @param buffer the Scheme Buffer NPC that cast
     * @param target player or summon that received the buff
     * @param skillId buff skill id
     */
    void onSchemeBufferBuff(Creature buffer, Creature target, int skillId);
}
