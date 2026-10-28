package org.l2start.bots.olympiad;

import java.util.List;
import java.util.function.Consumer;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Service of bots-core for Grand Olympiad nobles (phase 9, module bots-olympiad): personas kept for the olympiad
 * roster (status OLYMPIAD: the presence director never uses them), logged in near the Grand Olympiad Manager with
 * a 3rd profession, S-grade gear and {@code setNoble(true)}. They keep the basic brain: in a stadium match it
 * fights 1v1 (phase 9 D); gather/idle is "far from starting villages" strolling around the manager.
 */
public interface OlympiadPool {
    /** A new olympiad noble: 3rd profession (combat allow-list) and the sex of the generated persona. */
    record Look(int thirdClass) {
    }

    /** A new olympiad persona (account, character, persona, status OLYMPIAD). @return its bot id */
    int create(Look look) throws Exception;

    /** An olympiad persona with this bot id exists (and still is an olympiad noble). */
    boolean known(int botId);

    String name(int botId);

    /** Intended 3rd profession of the persona (parent of {@code futureClassId} at 76). */
    int thirdClass(int botId);

    /** Every olympiad noble (in the world or not). */
    List<Integer> all();

    /**
     * Logs the noble in at x, y, z facing {@code heading}, at {@code level} (76-80) with the 3rd profession, skills
     * and olympiad-grade gear, then {@code setNoble(true)}. {@code ready} runs in the world (may be null).
     * @return null when done, else why not
     */
    String enter(int botId, int x, int y, int z, int heading, int level, Consumer<Player> ready);

    /** Logs it out (saved like a player). */
    boolean leave(int botId);

    /** The Player in the world, or null. */
    Player player(int botId);
}
