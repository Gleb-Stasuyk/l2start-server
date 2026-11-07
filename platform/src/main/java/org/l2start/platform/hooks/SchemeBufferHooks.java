package org.l2start.platform.hooks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2start.platform.api.SchemeBufferBuffListener;

/** Listeners of hook P11-BUFFER-TIME, each owned by a module (added and removed by the platform). */
public final class SchemeBufferHooks {
    private record Owned(String moduleId, SchemeBufferBuffListener listener) {
    }

    private static final List<Owned> LISTENERS = new CopyOnWriteArrayList<>();

    private SchemeBufferHooks() {
    }

    public static void add(String moduleId, SchemeBufferBuffListener l) {
        LISTENERS.add(new Owned(moduleId, l));
    }

    public static void remove(String moduleId, SchemeBufferBuffListener l) {
        LISTENERS.removeIf(o -> o.moduleId().equals(moduleId) && (o.listener() == l));
    }

    public static int size() {
        return LISTENERS.size();
    }

    static void applied(Creature buffer, Creature target, int skillId) {
        for (Owned o : LISTENERS) {
            o.listener().onSchemeBufferBuff(buffer, target, skillId);
        }
    }
}
