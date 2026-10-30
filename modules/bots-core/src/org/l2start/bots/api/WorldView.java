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

    /**
     * A live player anywhere in the world, shaped like a party-window member (RB party 2 follows its caller who is
     * not in its party window), null = offline / unknown.
     */
    default PartyMember playerAnywhere(int objectId) {
        return null;
    }

    /** It leads its party (or has none). */
    default boolean partyLeader() {
        return true;
    }

    /** Not a boss. */
    int BOSS_NONE = 0;
    /** An ordinary raid boss. */
    int BOSS_RAID = 1;
    /** A grand (epic) boss: RB parties of bots never go there. */
    int BOSS_EPIC = 2;
    /** A minion of a raid boss. */
    int BOSS_MINION = 3;

    /** RB party (iteration 1): what kind of boss this NPC is ({@link #BOSS_NONE} when unknown or gone). */
    default int bossKind(int objectId) {
        return BOSS_NONE;
    }

    /** A summon (servitor or pet) of a party member or of the bot itself. */
    record PartyPet(int objectId, int ownerId, String name, Pos pos, double distance, boolean dead) {
    }

    /** RB party (iteration 2): the summons of its party, its own included; empty when unknown. */
    default List<PartyPet> partyPets() {
        return List.of();
    }

    /** {@link #buffState}: the game cannot tell. */
    int BUFF_UNKNOWN = -1;
    /** {@link #buffState}: not on it. */
    int BUFF_OFF = 0;
    /** {@link #buffState}: on it (this skill, or an effect of the same kind). */
    int BUFF_ON = 1;

    /** RB party (iteration 2): whether the effect of this buff is on a creature (the bot itself, a member, a summon). */
    default int buffState(int objectId, int skillId) {
        return BUFF_UNKNOWN;
    }

    /**
     * Seconds left of this buff on the creature (this skill or the same abnormal type). 0 = not on; negative = on, but
     * the remaining time is unknown. Used so Wind Walk is not recast while more than 10 minutes remain.
     */
    default int buffSecondsLeft(int objectId, int skillId) {
        return buffState(objectId, skillId) == BUFF_ON ? -1 : 0;
    }
}
