package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SelfView;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 9 D: pure stadium rules (match gate, consumables, rival pick). */
class OlympiadCombatTest {
    @Test
    void inMatchWhenModeOrGameId() {
        assertFalse(OlympiadCombat.inMatch(false, -1));
        assertTrue(OlympiadCombat.inMatch(true, -1));
        assertTrue(OlympiadCombat.inMatch(false, 0));
        assertTrue(OlympiadCombat.inMatch(true, 3));
    }

    @Test
    void consumablesForbiddenInMatch() {
        assertTrue(OlympiadCombat.allowConsumables(false));
        assertFalse(OlympiadCombat.allowConsumables(true));
    }

    @Test
    void fightOnlyAfterStartWhileAlive() {
        assertFalse(OlympiadCombat.shouldFight(false, true, false));
        assertFalse(OlympiadCombat.shouldFight(true, false, false));
        assertFalse(OlympiadCombat.shouldFight(true, true, true));
        assertTrue(OlympiadCombat.shouldFight(true, true, false));
        assertTrue(OlympiadCombat.shouldWait(true, false, false));
        assertFalse(OlympiadCombat.shouldWait(true, true, false));
        assertFalse(OlympiadCombat.shouldWait(true, false, true));
    }

    @Test
    void rivalIsTheOtherContestantSameGameNotAnObserver() {
        final FakeWorld w = new FakeWorld(1, BasicBrainTest.TOWN, false);
        w.setOlympiad(7, true);
        final SelfView me = w.self();
        final PlayerView foe = contestant(800, "Rival", new Pos(100, 0, 0), 100, 7);
        final PlayerView observer = new PlayerView(801, "Obs", 80, 0, new Pos(50, 0, 0), 50, 0, false, false, 0, false, false, false, true, 0, 100, 0, false, 7);
        final PlayerView otherGame = contestant(802, "Else", new Pos(80, 0, 0), 80, 8);
        assertSame(foe, OlympiadCombat.rival(me, List.of(observer, otherGame, foe)));
        assertNull(OlympiadCombat.rival(me, List.of(observer, otherGame)));
        assertNull(OlympiadCombat.rival(me, List.of()));
    }

    @Test
    void asTargetKeepsHpHeadingAndLook() {
        final PlayerView p = new PlayerView(800, "Rival", 78, 88, new Pos(10, 20, 3), 40, 0, false, true, 9, false, false, false, true, 0, 42, 12345, true, 1);
        final var n = OlympiadCombat.asTarget(p);
        assertEquals(800, n.objectId());
        assertEquals(42, n.hpPct(), 1e-9);
        assertEquals(12345, n.heading());
        assertEquals(9, n.targetId());
        assertEquals("Rival", n.name());
    }

    static PlayerView contestant(int id, String name, Pos at, double dist, int gameId) {
        return new PlayerView(id, name, 78, 88, at, dist, 0, false, true, 0, false, false, false, true, 0, 100, 0, true, gameId);
    }
}
