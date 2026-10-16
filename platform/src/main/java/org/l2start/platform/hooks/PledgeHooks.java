package org.l2start.platform.hooks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2start.platform.api.BotPledgeListener;

/** Listeners of hook P7-BOT-PLEDGE, each owned by a module. */
public final class PledgeHooks {
    private record Owned(String moduleId, BotPledgeListener listener) {
    }

    private static final List<Owned> LISTENERS = new CopyOnWriteArrayList<>();

    private PledgeHooks() {
    }

    public static void add(String moduleId, BotPledgeListener l) {
        LISTENERS.add(new Owned(moduleId, l));
    }

    public static void remove(String moduleId, BotPledgeListener l) {
        LISTENERS.removeIf(o -> o.moduleId().equals(moduleId) && (o.listener() == l));
    }

    public static int size() {
        return LISTENERS.size();
    }

    static boolean request(Player requester, Player bot, int pledgeType) {
        for (Owned o : LISTENERS) {
            if (o.listener().onPledgeRequest(requester, bot, pledgeType)) {
                return true;
            }
        }
        return false;
    }
}
