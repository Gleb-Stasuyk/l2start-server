package org.l2start.modules.lwfixes;

/** Decision of the cursed weapon fix, kept apart from game objects so it can be unit-tested. */
final class CursedRefresh {
    private CursedRefresh() {
    }

    /**
     * @param online the former owner is still in game
     * @param stillCursed the owner still holds a cursed weapon (drop did not happen)
     * @return true when the client must be refreshed
     */
    static boolean needed(boolean online, boolean stillCursed) {
        return online && !stillCursed;
    }
}
