package org.l2start.platform.api;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Since API 1.9 (hook P7-BOT-DUEL). A real player challenged a bot to a duel: the stock {@code ExDuelAskStart} was
 * sent and the bot has the player as its active requester. The module answers later on the bot thread (stock
 * {@code RequestDuelAnswerStart} sequence). Only when the target is a bot; inert without listeners.
 * @return true when the module takes care of the answer
 */
@FunctionalInterface
public interface BotDuelListener {
    boolean onDuelRequest(Player requester, Player bot, boolean partyDuel);
}
