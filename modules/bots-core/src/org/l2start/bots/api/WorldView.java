package org.l2start.bots.api;

import java.util.List;

/** What a bot knows at the start of a tick. Lists are nearest first and computed on first use. */
public interface WorldView {
    SelfView self();

    /** NPCs and monsters within the view radius (at most 64). */
    List<NpcView> npcs();

    /** Other players within the view radius (at most 64). */
    List<PlayerView> players();

    /** Items on the ground within the view radius (at most 64). */
    List<DropView> drops();

    /** Events since the previous tick, oldest first. */
    List<BotEvent> events();

    Geo geo();

    /** A monster or NPC by object id, wherever it is (null = gone). */
    NpcView npc(int objectId);
}
