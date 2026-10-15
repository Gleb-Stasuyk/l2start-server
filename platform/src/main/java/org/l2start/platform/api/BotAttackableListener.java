package org.l2start.platform.api;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Since API 1.10 (hook P7-ATTACKABLE). Stock {@code Player.isAutoAttackable} returned false; a module may still allow
 * the attack (PK bots opening on whites outside mutual PvP). Without listeners the stock result stands.
 */
@FunctionalInterface
public interface BotAttackableListener {
    /**
     * @return true = force attackable (override stock false); false = leave stock (do not force)
     */
    boolean forceAttackable(Player target, Creature attacker);
}
