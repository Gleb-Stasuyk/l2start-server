package org.l2start.platform.hooks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2start.platform.api.BotTradeListener;

/** Listeners of hook P6-BOT-TRADE, each owned by a module (added and removed by the platform). */
public final class TradeHooks {
    private record Owned(String moduleId, BotTradeListener listener) {
    }

    private static final List<Owned> LISTENERS = new CopyOnWriteArrayList<>();

    private TradeHooks() {
    }

    public static void add(String moduleId, BotTradeListener l) {
        LISTENERS.add(new Owned(moduleId, l));
    }

    public static void remove(String moduleId, BotTradeListener l) {
        LISTENERS.removeIf(o -> o.moduleId().equals(moduleId) && (o.listener() == l));
    }

    public static int size() {
        return LISTENERS.size();
    }

    static boolean request(Player requester, Player bot) {
        for (Owned o : LISTENERS) {
            if (o.listener().onTradeRequest(requester, bot)) {
                return true;
            }
        }
        return false;
    }
}
