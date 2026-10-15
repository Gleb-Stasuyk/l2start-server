package org.l2start.platform.hooks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2start.platform.api.BotDuelListener;

/** Listeners of hook P7-BOT-DUEL, each owned by a module. */
public final class DuelHooks {
    private record Owned(String moduleId, BotDuelListener listener) {
    }

    private static final List<Owned> LISTENERS = new CopyOnWriteArrayList<>();

    private DuelHooks() {
    }

    public static void add(String moduleId, BotDuelListener l) {
        LISTENERS.add(new Owned(moduleId, l));
    }

    public static void remove(String moduleId, BotDuelListener l) {
        LISTENERS.removeIf(o -> o.moduleId().equals(moduleId) && (o.listener() == l));
    }

    public static int size() {
        return LISTENERS.size();
    }

    static boolean request(Player requester, Player bot, boolean partyDuel) {
        for (Owned o : LISTENERS) {
            if (o.listener().onDuelRequest(requester, bot, partyDuel)) {
                return true;
            }
        }
        return false;
    }
}
