package org.l2start.platform.api;

/** Since API 1.3. Told about every line of a successful private store deal; called on the game thread of the deal, must be quick. */
@FunctionalInterface
public interface StoreDealListener {
    void onDeal(StoreDeal deal);
}
