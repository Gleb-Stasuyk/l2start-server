package org.l2start.bots.api;

/**
 * A skill the bot knows.
 * @param kind NUKE = magic damage on one enemy, STRIKE = physical damage on one enemy, BUFF = a lasting good effect the
 *        bot can put on itself (2 minutes or longer), TOGGLE = a stance switched on and off, OTHER = everything else
 * @param ready not on cooldown (reuse) right now
 * @param buffLeft BUFF: seconds left of an effect of the same kind (abnormal type) on the bot, 0 = none; TOGGLE: -1 = on,
 *        0 = off
 */
public record SkillView(int id, int level, Kind kind, int mpCost, int castRange, boolean ready, int buffLeft) {
    public enum Kind {
        NUKE, STRIKE, BUFF, TOGGLE, OTHER
    }

    /** Before phase 4b (no effect on the bot). */
    public SkillView(int id, int level, Kind kind, int mpCost, int castRange, boolean ready) {
        this(id, level, kind, mpCost, castRange, ready, 0);
    }
}
