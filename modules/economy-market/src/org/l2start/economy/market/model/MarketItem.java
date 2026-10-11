package org.l2start.economy.market.model;

/**
 * One item of the bot market (data/market-items.xml): stock CT0 facts the price model needs.
 * @param price the stock reference price (what NPC shops ask, twice what they pay)
 * @param npc sold by a stock merchant: bots never sell it cheaper nor buy it dearer than the arbitrage guard allows
 * @param stage the server stage 1-5 from which it shows up in bot stores
 */
public record MarketItem(int id, String name, String kind, Grade grade, long price, boolean stackable, boolean npc, int stage) {
    public enum Grade {
        NONE, D, C, B, A, S;

        public static Grade of(String s) {
            try {
                return (s == null) ? NONE : valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }
    }
}
