package org.l2start.bots.trade;

import java.util.List;
import java.util.function.Consumer;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Service of bots-core for store traders (phase 5, module economy-market): personas kept for a store place (status
 * TRADER: the director and the brain never use them), logged in at an exact place with a level, profession and gear
 * of their look, and out again. No brain ticks for them; the caller opens and closes the stock private store.
 */
public interface TraderPool {
    /** The look of a new trader. {@code profession}: the 2nd profession it shows (its starting class follows); {@code name}: wanted name, another one when taken or invalid. */
    record Look(int profession, boolean female, String name) {
    }

    /** A new trader persona (account, character, persona, status TRADER). @return its bot id */
    int create(Look look) throws Exception;

    /** A trader persona with this bot id exists (and still is a trader). */
    boolean known(int botId);

    String name(int botId);

    /** Persona chat language: "RU", "EN" or "MIX". */
    String lang(int botId);

    /** Persona greed 0..1 (prices). */
    double greed(int botId);

    /** Persona chattiness 0..1 (how often it writes in the trade chat; 0.5.2). */
    default double chattiness(int botId) {
        return 0.5;
    }

    /**
     * Logs the trader in at x, y, z facing {@code heading}, at {@code level} (never lower than it is) with the profession,
     * skills and gear of that level and an empty bag. {@code ready} runs in the world (open the store there).
     * @return null when done, else why not
     */
    String enter(int botId, int x, int y, int z, int heading, int level, Consumer<Player> ready);

    /** Logs it out (saved like a player; the caller emptied the store and the bag first). */
    boolean leave(int botId);

    /** The Player in the world, or null. */
    Player player(int botId);

    /** Where real players (not bots) are, plus the director's test anchors: x, y, z each. */
    List<int[]> watchers();
}
