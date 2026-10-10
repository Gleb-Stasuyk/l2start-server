package org.l2start.platform.api;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Since API 1.2. Tells the core which players are bots (stock Player objects without a game client).
 * Provided by one module at a time (bots-core) through {@link ModuleContext#botPresence(BotPresence)};
 * without a provider every player is a human and the core behaves like stock Mobius.
 * Called from hot paths (hooks P3-BOT-*): must be O(1), thread-safe and must not throw.
 */
@FunctionalInterface
public interface BotPresence {
    boolean isBot(Player player);
}
