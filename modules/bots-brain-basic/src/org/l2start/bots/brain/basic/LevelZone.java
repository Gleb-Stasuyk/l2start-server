package org.l2start.bots.brain.basic;

import java.util.Set;

import org.l2start.bots.api.Pos;

/** A hunting ground: centre and radius of stock monster spawns of minLevel..maxLevel near one village. */
public record LevelZone(String id, String village, Pos center, int radius, int minLevel, int maxLevel, int monsters, boolean aggressive, Set<Integer> npcIds) {
    /** Monsters of this zone overlap the bot's target range [level-3, level+2]. */
    public boolean fits(int level) {
        return (minLevel <= (level + 2)) && (maxLevel >= (level - 3));
    }

    public double mid() {
        return (minLevel + maxLevel) / 2.0;
    }
}
