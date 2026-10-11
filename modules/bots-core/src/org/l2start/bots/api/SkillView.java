package org.l2start.bots.api;

/**
 * A skill the bot knows.
 * @param kind NUKE = magic damage on one enemy, STRIKE = physical damage on one enemy, BUFF = a lasting good effect the
 *        bot can put on itself (2 minutes or longer), TOGGLE = a stance switched on and off,
 *        SUMMON = calls a servitor (it may need crystals: {@link #itemId}), CORPSE = used on a dead monster (Sweeper), OTHER = everything else
 * @param ready not on cooldown (reuse) right now
 * @param buffLeft BUFF: seconds left of an effect of the same kind (abnormal type) on the bot, 0 = none; TOGGLE: -1 = on,
 *        0 = off
 * @param itemId SUMMON: item the skill consumes (0 = none) and {@code itemCount} of it per cast
 */
public record SkillView(int id, int level, Kind kind, int mpCost, int castRange, boolean ready, int buffLeft, int itemId, int itemCount) {
    public enum Kind {
        NUKE, STRIKE, BUFF, TOGGLE, SUMMON, CORPSE, OTHER
    }

    /** Before phase 4c (no item consumed). */
    public SkillView(int id, int level, Kind kind, int mpCost, int castRange, boolean ready, int buffLeft) {
        this(id, level, kind, mpCost, castRange, ready, buffLeft, 0, 0);
    }

    /** Before phase 4b (no effect on the bot). */
    public SkillView(int id, int level, Kind kind, int mpCost, int castRange, boolean ready) {
        this(id, level, kind, mpCost, castRange, ready, 0, 0, 0);
    }
}
