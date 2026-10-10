package org.l2start.bots.api;

/**
 * A skill the bot knows.
 * @param kind NUKE = magic damage on one enemy, STRIKE = physical damage on one enemy, OTHER = everything else
 * @param ready not on cooldown (reuse) right now
 */
public record SkillView(int id, int level, Kind kind, int mpCost, int castRange, boolean ready) {
    public enum Kind {
        NUKE, STRIKE, OTHER
    }
}
