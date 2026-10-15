package org.l2start.platform.hooks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2start.platform.api.BotAttackableListener;

/** Listeners of hook P7-ATTACKABLE, each owned by a module. */
public final class AttackableHooks {
    private record Owned(String moduleId, BotAttackableListener listener) {
    }

    private static final List<Owned> LISTENERS = new CopyOnWriteArrayList<>();

    private AttackableHooks() {
    }

    public static void add(String moduleId, BotAttackableListener l) {
        LISTENERS.add(new Owned(moduleId, l));
    }

    public static void remove(String moduleId, BotAttackableListener l) {
        LISTENERS.removeIf(o -> o.moduleId().equals(moduleId) && (o.listener() == l));
    }

    public static int size() {
        return LISTENERS.size();
    }

    static boolean force(Player target, Creature attacker) {
        for (Owned o : LISTENERS) {
            if (o.listener().forceAttackable(target, attacker)) {
                return true;
            }
        }
        return false;
    }
}
