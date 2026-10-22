package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Activity;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;
import org.l2start.bots.runtime.IdleWatch;

/** Live test 2026-10-22, next pass: ad-hoc band, GK crowd, posts in plain view, AFK_TOWN in the idle watch. */
class StarterPassDirectorTest {
    @Test
    void adHocBandFollowsThePlayerAndTheMobs() {
        // FaRmEr lv 11 next to ork lv 45 in a 40+ area: the band is around 45 now
        final int lvl = Director.adHocLevel(45, List.of(42, 46));
        assertTrue((lvl >= 40) && (lvl <= 48), "level " + lvl);
        assertEquals(12, Director.adHocLevel(12, List.of()));
        assertEquals(44, Director.adHocLevel(0, List.of(44)));
        assertEquals(10, Director.adHocLevel(0, List.of()));
        // never far from the player even when the mobs are much higher
        assertTrue(Director.adHocLevel(20, List.of(60)) <= 23);
        final int[] band = Director.huntLevels(new WorldCatalog.Ground("adhoc_1", null, new Pos(0, 0, 0), 3000, Math.max(1, lvl - 5), lvl - 2, 10, 0.0, false, List.of()));
        assertTrue((band[0] <= 45) && (band[1] >= 45), band[0] + ".." + band[1]);
    }

    @Test
    void crowdAtTheGatekeeperOfTheBigTowns() {
        for (String t : List.of("dion_castle_town", "giran_castle_town", "town_of_aden", "town_of_goddard")) {
            for (int h = 0; h < 24; h++) {
                final int n = Director.gkCrowd(t, h);
                assertTrue((n >= 5) && (n <= 15), t + " " + n);
            }
        }
        assertEquals(0, Director.gkCrowd("talking_island", 12));
        final Assignment crowd = new Assignment(1, Assignment.Kind.TOWN, "giran_castle_town", new Pos(0, 0, 0), 100, 1, 80, List.of(), List.of(new Assignment.Anchor(Director.GK_AFK, new Pos(1, 1, 0))), null);
        assertTrue(Director.isCrowd(crowd));
        assertFalse(Director.isCrowd(new Assignment(1, Assignment.Kind.TOWN, "x", new Pos(0, 0, 0), 100, 1, 80, List.of(), List.of(), null)));
    }

    @Test
    void afkTownIsAnIntentionalStateWithItsMaximum() {
        final Activity afk = new Activity(Activity.Kind.AFK_TOWN, false, 0);
        assertTrue(IdleWatch.standsOnPurpose(afk, 119 * 60_000L));
        assertFalse(IdleWatch.standsOnPurpose(afk, IdleWatch.AFK_TOWN_MAX_MS + 1));
    }

    /** A wall east of the centre: a post there is reachable around it but not in plain view. */
    @Test
    void postIsInPlainViewNeverBehindAWall() {
        final Scatter.Ground g = new Scatter.Ground() {
            @Override public boolean hasGeo(int x, int y) { return true; }
            @Override public int height(int x, int y, int z) { return z; }
            @Override public boolean reachable(Pos from, Pos to) { return true; }
            @Override public boolean passable(int x, int y, int z) { return true; }
            @Override public boolean water(int x, int y, int z) { return false; }
            @Override public boolean direct(Pos from, Pos to) { return to.x() < 30; }
        };
        final Random rnd = new Random(5);
        for (int i = 0; i < 200; i++) {
            final Scatter.Result r = Scatter.pickInSight(new Pos(0, 0, 0), List.of(), g, rnd, 200);
            assertFalse(r.fallback());
            assertTrue(r.pos().x() < 30, "behind the wall: " + r.pos());
            final double d = r.pos().distance2d(new Pos(0, 0, 0));
            assertTrue((d >= Scatter.POST_MIN - 1) && (d <= 201), "radius " + d);
        }
    }
}
