package org.l2start.platform.hooks;

import java.util.concurrent.atomic.AtomicReference;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2start.platform.api.BotPresence;

/** Holder of the single {@link BotPresence} provider (set and cleared by the platform on behalf of a module). */
public final class BotHooks {
    private static final AtomicReference<Owned> PROVIDER = new AtomicReference<>();

    private record Owned(String moduleId, BotPresence presence) {
    }

    private BotHooks() {
    }

    /** @throws IllegalStateException when another module already provides the presence */
    public static void install(String moduleId, BotPresence presence) {
        final Owned now = new Owned(moduleId, presence);
        if (!PROVIDER.compareAndSet(null, now)) {
            throw new IllegalStateException("bot presence is already provided by module " + PROVIDER.get().moduleId());
        }
    }

    public static void uninstall(String moduleId) {
        PROVIDER.updateAndGet(o -> (o != null) && o.moduleId().equals(moduleId) ? null : o);
    }

    public static String provider() {
        final Owned o = PROVIDER.get();
        return o == null ? null : o.moduleId();
    }

    static boolean isBot(Player player) {
        final Owned o = PROVIDER.get();
        return (o != null) && (player != null) && o.presence().isBot(player);
    }
}
