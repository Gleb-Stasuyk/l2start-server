package org.l2start.platform.hooks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2start.platform.api.StoreDeal;
import org.l2start.platform.api.StoreDealListener;

/** Listeners of hook P5-STORE-DEAL, each owned by a module (added and removed by the platform). */
public final class StoreHooks {
    private record Owned(String moduleId, StoreDealListener listener) {
    }

    private static final List<Owned> LISTENERS = new CopyOnWriteArrayList<>();

    private StoreHooks() {
    }

    public static void add(String moduleId, StoreDealListener l) {
        LISTENERS.add(new Owned(moduleId, l));
    }

    public static void remove(String moduleId, StoreDealListener l) {
        LISTENERS.removeIf(o -> o.moduleId().equals(moduleId) && (o.listener() == l));
    }

    public static int size() {
        return LISTENERS.size();
    }

    static void deal(StoreDeal d) {
        for (Owned o : LISTENERS) {
            o.listener().onDeal(d); // the module's listener is wrapped by its guard
        }
    }
}
