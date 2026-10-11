package org.l2start.platform.api;

/**
 * Since API 1.3. One line of a successful deal in a stock private store (hook P5-STORE-DEAL): {@code buyerId} paid
 * {@code price} adena a piece for {@code count} of {@code itemId} to {@code sellerId}. Object ids of players
 * (bots included). The deal itself is already done; listeners cannot change it.
 * @param buyStore true = a "buy" store (the store owner bought from the visitor), false = a sell / package store
 * @param sellerBot the seller is a bot (by the module given to {@code ctx.botPresence}; false without one)
 * @param buyerBot the buyer is a bot
 */
public record StoreDeal(int sellerId, int buyerId, int itemId, long count, long price, boolean buyStore, boolean sellerBot, boolean buyerBot) {
    public long total() {
        return count * price;
    }

    /** The store owner set the price: the buyer of a buy store, else the seller. */
    public int ownerId() {
        return buyStore ? buyerId : sellerId;
    }

    /** The price was set by a real player (the store owner is not a bot). */
    public boolean ownerReal() {
        return buyStore ? !buyerBot : !sellerBot;
    }
}
