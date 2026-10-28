package org.l2start.platform.api;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Since API 1.6 (hook P7-QUEST-REWARD). A quest gives a reward item through the stock {@code Quest.rewardItems}: the
 * listener may change the count the stock rates made of it. Called on the game thread, must be quick.
 */
@FunctionalInterface
public interface QuestRewardListener {
    /**
     * @param base the count the quest script asked for
     * @param rated the count after the stock rates of Rates.ini
     * @return the count to give (stock: {@code rated})
     */
    long onQuestReward(Player player, int itemId, long base, long rated);
}
