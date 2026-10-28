package org.l2start.platform.api;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Since API 1.11 (hook P7-BOT-PLEDGE). A real player invited a bot into a clan (or academy): the stock ask is pending
 * and the bot has the player as its request partner. The module answers later on the bot thread (stock
 * {@code RequestAnswerJoinPledge} sequence). Only when the target is a bot; inert without listeners.
 * @return true when the module takes care of the answer
 */
@FunctionalInterface
public interface BotPledgeListener {
    boolean onPledgeRequest(Player requester, Player bot, int pledgeType);
}
