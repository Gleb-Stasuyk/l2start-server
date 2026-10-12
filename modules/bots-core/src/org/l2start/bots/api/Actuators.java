package org.l2start.bots.api;

/**
 * What a bot can do. Every action is what a client could ask for (stock AI intentions and requests) and goes through
 * a validator first: alive, distance, peace zone, geodata, the target exists.
 */
public interface Actuators {
    /** Go there (stock MOVE_TO with pathfinding), running unless {@link #setWalking} asked to walk. Long ways: call again with the next leg. */
    ActionResult moveTo(Pos to);

    ActionResult follow(int objectId);

    ActionResult setTarget(int objectId);

    /** Stock ATTACK intention: walks up and hits until the target is dead. */
    ActionResult attack(int objectId);

    ActionResult cast(int skillId, int objectId);

    /** Use (double click) the first inventory item with this item id. */
    ActionResult useItem(int itemId);

    ActionResult pickUp(int dropObjectId);

    ActionResult sit();

    ActionResult stand();

    /** Say in the normal (white) chat around the bot; at most 6 phrases a minute. */
    ActionResult say(String text);

    /** To the nearest town (like "To village" after death, or a scroll of escape when alive). */
    ActionResult returnToTown();

    /**
     * Learn every class skill the bot can afford with SP (simplification until phase 4). The learning itself runs
     * right after, off the bot's thread (it writes the database). @return skills it can learn now
     */
    int learnSkills();

    /**
     * Gait for every following move, like the client's run/walk toggle. Bots run by default (as real players do);
     * walk only when a behaviour explicitly needs it, and switch back to running after.
     */
    ActionResult setWalking(boolean walk);

    /** Stop: drop the current intention. */
    ActionResult stop();

    /** Log out of the world (it comes back only by //bots spawn or the schedule). */
    ActionResult logout();

    /** Send its own summon at this target (like the pet window "Attack"). Phase 4c. */
    default ActionResult petAttack(int objectId) {
        return ActionResult.INVALID;
    }

    /** Call its own summon back to follow the bot (like "Stop" in the pet window). Phase 4c. */
    default ActionResult petFollow() {
        return ActionResult.INVALID;
    }

    /** Dismiss its own summon (like "Unsummon"); it is not stored for a later login. Phase 4c. */
    default ActionResult unsummon() {
        return ActionResult.INVALID;
    }

    // ------------------------------------------------------------------ phase 6: party and chat (stock sequences)

    /**
     * Answer the pending party invitation like the client's dialog (stock {@code RequestAnswerJoinParty}): yes = join
     * (or found) the inviter's party, no = "declined". INVALID when nothing is pending any more (expired).
     */
    default ActionResult answerParty(boolean accept) {
        return ActionResult.INVALID;
    }

    /** Leave its party (stock {@code RequestWithDrawalParty}). */
    default ActionResult leaveParty() {
        return ActionResult.INVALID;
    }

    /** Hand the party lead to this member (stock {@code RequestChangePartyLeader}; only the leader can). */
    default ActionResult giveLeader(int objectId) {
        return ActionResult.INVALID;
    }

    /** Write in the party chat (stock {@code ChatParty}); at most 6 lines a minute with {@link #say}. */
    default ActionResult sayParty(String text) {
        return ActionResult.INVALID;
    }

    /** Whisper to a player who is online (stock {@code ChatWhisper}): only as an answer, never first (phase 6). */
    default ActionResult whisper(String to, String text) {
        return ActionResult.INVALID;
    }

    /** A social action (/socials: 2 greeting, 3 victory, 5 yes, 6 no, 7 bow, ...) seen by everybody around. */
    default ActionResult social(int id) {
        return ActionResult.INVALID;
    }
}
