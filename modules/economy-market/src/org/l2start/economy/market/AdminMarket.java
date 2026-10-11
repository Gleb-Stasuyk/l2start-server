package org.l2start.economy.market;

import java.util.StringTokenizer;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2start.economy.market.lw.StorePricing;
import org.l2start.economy.market.model.MarketBook;

/** //market (GM): stage, index, deals, town stores; //market price &lt;itemId&gt;: what the model says. */
final class AdminMarket implements IAdminCommandHandler {
    private final EconomyMarketModule m;

    AdminMarket(EconomyMarketModule m) {
        this.m = m;
    }

    @Override
    public String[] getCommandList() {
        return new String[] { "admin_market" };
    }

    @Override
    public boolean onCommand(String command, Player gm) {
        final StringTokenizer st = new StringTokenizer(command);
        st.nextToken();
        final String sub = st.hasMoreTokens() ? st.nextToken().toLowerCase() : "";
        if ("price".equals(sub) && st.hasMoreTokens()) {
            final int id;
            try {
                id = Integer.parseInt(st.nextToken());
            } catch (NumberFormatException e) {
                gm.sendMessage("Usage: //market price <itemId>");
                return true;
            }
            final MarketBook.Quote q = m.book().quote(id, m.stage(), System.currentTimeMillis());
            if (q == null) {
                gm.sendMessage("Item " + id + " is not on the bot market.");
                return true;
            }
            gm.sendMessage(q.item().name() + " (" + q.item().grade() + ", stage " + q.item().stage() + (q.item().npc() ? ", NPC " + StorePricing.priceText(q.item().price()) : "") + ")");
            gm.sendMessage("base " + q.base() + ", market " + q.market() + String.format(" (drift %+.1f%%, %d real deals in 14 days)", (q.drift() - 1) * 100, q.recentDeals()));
            gm.sendMessage("bots sell " + q.lowSell() + "-" + q.highSell() + ", bots buy " + q.lowBid() + "-" + q.highBid());
            return true;
        }
        long today = -1;
        try {
            today = m.repo().dealsSince(System.currentTimeMillis() - 86_400_000L);
        } catch (Exception e) {
            // shown as unknown
        }
        gm.sendMessage("Market " + (m.marketOn() ? "on" : "off (log and index only)") + ", stage " + m.stage() + " (auto " + m.autoStage() + "), " + m.book().items().size() + " items");
        gm.sendMessage("Index: " + m.book().indexed() + " items; deals since start: " + m.book().realDeals() + " real, " + m.book().botDeals() + " bot; last 24 h: " + (today < 0 ? "?" : today));
        gm.sendMessage("Stores per town: " + m.quota().active() + " (of " + m.quota().total() + ")");
        gm.sendMessage("//market price <itemId> - prices of one item");
        return true;
    }
}
