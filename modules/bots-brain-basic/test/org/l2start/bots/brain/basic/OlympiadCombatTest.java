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

    @Test
    void roleByClass() {
        assertEquals(OlympiadCombat.Role.TANK, OlympiadCombat.role(90, false, false));
        assertEquals(OlympiadCombat.Role.TANK, OlympiadCombat.role(91, false, false));
        assertEquals(OlympiadCombat.Role.ARCHER, OlympiadCombat.role(92, true, false));
        assertEquals(OlympiadCombat.Role.ARCHER, OlympiadCombat.role(7, true, false));
        assertEquals(OlympiadCombat.Role.MAGE, OlympiadCombat.role(94, false, true));
        assertEquals(OlympiadCombat.Role.SUMMONER, OlympiadCombat.role(95, false, true));
        assertEquals(OlympiadCombat.Role.MELEE, OlympiadCombat.role(88, false, false));
    }

    @Test
    void archerClosesFromSpawnNotFromInsideFightRange() {
        final int fight = OlympiadCombat.fightRange(OlympiadCombat.Role.ARCHER, 500, 0);
        assertEquals(400, fight);
        assertTrue(OlympiadCombat.shouldCloseIn(OlympiadCombat.Role.ARCHER, 700, fight));
        assertTrue(OlympiadCombat.shouldCloseIn(OlympiadCombat.Role.ARCHER, 500, fight)); // spawn inside max bow, still too far
        assertFalse(OlympiadCombat.shouldCloseIn(OlympiadCombat.Role.ARCHER, 250, fight));
        assertFalse(OlympiadCombat.shouldCloseIn(OlympiadCombat.Role.MELEE, 700, 80), "melee walks in via attack()");
        assertFalse(OlympiadCombat.shouldCloseIn(OlympiadCombat.Role.TANK, 700, 80));
        final int mageFight = OlympiadCombat.fightRange(OlympiadCombat.Role.MAGE, 40, 600);
        assertEquals(520, mageFight);
        assertTrue(OlympiadCombat.shouldCloseIn(OlympiadCombat.Role.MAGE, 700, mageFight));
        assertTrue(OlympiadCombat.shouldCloseIn(OlympiadCombat.Role.SUMMONER, 700, mageFight));
        final Pos me = new Pos(0, 0, 0);
        final Pos foe = new Pos(800, 0, 0);
        final Pos to = OlympiadCombat.closeInPoint(me, foe, 400);
        assertEquals(400, (int) Math.round(to.distance2d(foe)));
    }
}
