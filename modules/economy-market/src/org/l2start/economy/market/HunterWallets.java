package org.l2start.economy.market;

import java.time.LocalDate;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2start.bots.trade.Supplies;
import org.l2start.economy.market.model.Budget;
import org.l2start.economy.market.model.MarketBook;
import org.l2start.economy.market.model.MarketItem;

/**
 * Step 5d (bots-core service {@link Supplies}): a hunter bot pays its soulshots and potions from its wallet when it
 * comes into the world. The wallet grows on paper with the hours it hunted unseen (a share of an hour of farming at
 * its level, {@link Budget}); a thin wallet means fewer potions and shots. The price is the market price (the shots
 * of 4UDO: NPC price). Called on a stock pool thread (database writes are fine there). Loaded only with bots-core.
 */
final class HunterWallets implements Supplies {
    private final EconomyMarketModule m;

    HunterWallets(EconomyMarketModule m) {
        this.m = m;
    }

    @Override
    public synchronized int buy(int botId, int level, int itemId, int want) {
        if (!m.marketOn() || (want <= 0)) {
            return want; // market off: phase 4 behaviour (given)
        }
        try {
            final long now = System.currentTimeMillis();
            final long[] row = m.repo().hunter(botId);
            final long wallet = Budget.hunterWallet((row == null) ? -1 : row[0], level, (row == null) ? 0 : (now - row[1]) / 3_600_000.0);
            final long unit = unitPrice(itemId);
            final int got = Budget.affordable(wallet, unit, want);
            final long paid = got * unit;
            m.repo().saveHunter(botId, wallet - paid, now, paid);
            if (paid > 0) {
                m.repo().addFlow((int) LocalDate.now().toEpochDay(), "hunters", paid);
            }
            m.ctx().metrics().counter("hunter supplies adena").add(paid);
            return got;
        } catch (Exception e) {
            m.ctx().log().warn("hunter wallet of bot " + botId + ": " + e);
            return want;
        }
    }

    /** Market price of a piece (NPC price for what NPCs sell: shots, potions), else the item's reference price. */
    long unitPrice(int itemId) {
        final MarketBook book = m.book();
        final MarketItem it = book.items().get(itemId);
        if (it != null) {
            if (it.npc() && (it.price() > 0)) {
                return it.price();
            }
            final long market = book.quote(itemId, m.stage(), System.currentTimeMillis()).market();
            if (market > 0) {
                return market;
            }
        }
        final ItemTemplate t = ItemData.getInstance().getTemplate(itemId);
        return (t == null) ? 0 : Math.max(1, t.getReferencePrice());
    }
}
