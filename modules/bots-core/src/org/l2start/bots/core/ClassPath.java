package org.l2start.bots.core;

import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;

/**
 * The profession a persona has at a level (plan 20.4): its starting class below 20, the 1st profession (parent of
 * its planned 2nd one) at 20-39, the planned 2nd profession (persona.futureClassId) at 40-75, the 3rd at 76+.
 * Uses the stock class tree.
 */
final class ClassPath {
    private ClassPath() {
    }

    static int at(int startClass, int futureClass, int level) {
        final PlayerClass second = PlayerClass.getPlayerClass(futureClass);
        if ((second == null) || (second.level() != 2) || (level < 20)) {
            return startClass;
        }
        if (level < 40) {
            final PlayerClass first = second.getParent();
            return first == null ? startClass : first.getId();
        }
        if (level >= 76) {
            for (PlayerClass third : second.getNextClasses()) {
                return third.getId();
            }
        }
        return second.getId();
    }

    /** True when {@code to} is {@code from} or a profession that comes after it (never step back). */
    static boolean forward(int from, int to) {
        final PlayerClass a = PlayerClass.getPlayerClass(from);
        final PlayerClass b = PlayerClass.getPlayerClass(to);
        return (a != null) && (b != null) && b.equalsOrChildOf(a);
    }
}
