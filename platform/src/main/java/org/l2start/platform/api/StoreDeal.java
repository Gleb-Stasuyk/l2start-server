package org.l2start.platform.api;

/**
 * Since API 1.3. One line of a successful deal in a stock private store (hook P5-STORE-DEAL): {@code buyerId} paid
 * {@code price} adena a piece for {@code count} of {@code itemId} to {@code sellerId}. Object ids of players
 * (bots included). The deal itself is already done; listeners cannot change it.
 * @param buyStore true = a "buy" store (the store owner bought from the visitor), false = a sell / package store
 */
public record StoreDeal(int sellerId, int buyerId, int itemId, long count, long price, boolean buyStore) {
    public long total() {
        return count * price;
    }
}
