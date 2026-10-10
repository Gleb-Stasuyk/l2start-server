package org.l2start.platform.api;

import java.util.function.Consumer;

import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.ListenersContainer;
import org.l2jmobius.gameserver.mechanics.events.holders.IBaseEvent;

/** Stock Mobius events. Every listener is guarded (exceptions are caught and counted) and owned by the module. */
public interface ModuleEvents {
    <T extends IBaseEvent> void onGlobal(EventType type, Consumer<T> callback);

    <T extends IBaseEvent> void onPlayers(EventType type, Consumer<T> callback);

    <T extends IBaseEvent> void onNpcs(EventType type, Consumer<T> callback);

    <T extends IBaseEvent> void onMonsters(EventType type, Consumer<T> callback);

    /** Listener on one NPC template (e.g. ON_NPC_FIRST_TALK of the module's own NPC). */
    <T extends IBaseEvent> void onNpc(int npcId, EventType type, Consumer<T> callback);

    <T extends IBaseEvent> void on(ListenersContainer container, EventType type, Consumer<T> callback);
}
