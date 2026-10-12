package org.l2start.bots.trade;

/**
 * Service a market module provides (phase 5d, economy-market): a hunter bot pays for its soulshots and potions from
 * its wallet when it comes into the world. Without the service bots-core gives them as before (phase 4).
 */
public interface Supplies {
    /**
     * The bot buys up to {@code want} pieces of {@code itemId} at the market price; its wallet pays.
     * @return pieces bought (0..want): fewer when the wallet is thin
     */
    int buy(int botId, int level, int itemId, int want);
}
