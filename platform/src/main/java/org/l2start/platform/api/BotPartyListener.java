package org.l2start.platform.api;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Since API 1.5 (hook P6-BOT-PARTY). A real player invited a bot to a party: the stock invitation is pending (the bot
 * has the player as its active requester for 15 s). Called on the game thread of the request, must be quick; the
 * module answers later, on the bot's own thread, the way the stock {@code RequestAnswerJoinParty} does.
 * <p>
 * Since API 1.8: {@link #onPartyBusy} when the invited bot is already in a party (bot-only pair/trio from phase 4g):
 * the module may redirect socially instead of the stock "member of another party" message.
 * @return true when the module takes care of the answer, false = not its bot
 */
@FunctionalInterface
public interface BotPartyListener {
    boolean onPartyRequest(Player requester, Player bot);

    /**
     * Since API 1.8. The player invited a bot that is already in a party. Default: false (stock message). Modules that
     * own bot-only parties return true after posting a redirect to the invited bot (and its leader).
     */
    default boolean onPartyBusy(Player requester, Player bot) {
        return false;
    }
}
