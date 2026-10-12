package org.l2start.platform.api;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Since API 1.4 (hook P6-BOT-TRADE). A real player asked a bot for a trade (the stock request is already sent: the bot
 * is the player's requested partner). Called on the game thread of the request, must be quick.
 * @return true when the module answered for the bot (accepted or declined), false = not its bot
 */
@FunctionalInterface
public interface BotTradeListener {
    boolean onTradeRequest(Player requester, Player bot);
}
