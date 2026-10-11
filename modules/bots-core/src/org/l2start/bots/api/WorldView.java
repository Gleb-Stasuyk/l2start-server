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

    /** How many of these items the bot carries (potions, arrows, scrolls); 0 when unknown. */
    default long itemCount(int itemId) {
        return 0;
    }

    /** The other members of its party (phase 4g), empty when it is alone. */
    default List<PartyMember> party() {
        return List.of();
    }

    /** It leads its party (or has none). */
    default boolean partyLeader() {
        return true;
    }
}
