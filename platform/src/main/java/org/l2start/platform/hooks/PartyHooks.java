package org.l2start.platform.hooks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2start.platform.api.BotPartyListener;

/** Listeners of hook P6-BOT-PARTY, each owned by a module (added and removed by the platform). */
public final class PartyHooks {
    private record Owned(String moduleId, BotPartyListener listener) {
    }

    private static final List<Owned> LISTENERS = new CopyOnWriteArrayList<>();

    private PartyHooks() {
    }

    public static void add(String moduleId, BotPartyListener l) {
        LISTENERS.add(new Owned(moduleId, l));
    }

    public static void remove(String moduleId, BotPartyListener l) {
        LISTENERS.removeIf(o -> o.moduleId().equals(moduleId) && (o.listener() == l));
    }

    public static int size() {
        return LISTENERS.size();
    }

    static boolean request(Player requester, Player bot) {
        for (Owned o : LISTENERS) {
            if (o.listener().onPartyRequest(requester, bot)) {
                return true;
            }
        }
        return false;
    }

    /** Phase 6d: bot already in a party — first listener that takes it wins (API 1.8). */
    static boolean busy(Player requester, Player bot) {
        for (Owned o : LISTENERS) {
            if (o.listener().onPartyBusy(requester, bot)) {
                return true;
            }
        }
        return false;
    }
}
