package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Pos;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

class BasicBrainTest {
    static final Pos TOWN = new Pos(0, 0, 0);
    static final List<Village> VILLAGES = List.of(new Village("TEST", TOWN, 500));
    static final long MIN = 60_000;
    static final long HOUR = 60 * MIN;

    static LevelZones zones() {
        return new LevelZones(List.of(
            new LevelZone("low", "TEST", new Pos(3000, 0, 0), 900, 1, 3, 12, false, Set.of(1)),
            new LevelZone("mid", "TEST", new Pos(0, 4000, 0), 900, 4, 7, 12, false, Set.of(2)),
            new LevelZone("high", "TEST", new Pos(-4000, 0, 0), 900, 8, 12, 12, false, Set.of(3))));
    }

    /** Monsters of the three zones (levels like the zones). */
    static FakeWorld world(long seed, boolean mage) {
        final FakeWorld w = new FakeWorld(seed, TOWN, mage);
        final java.util.Random r = new java.util.Random(seed);
        for (int i = 0; i < 12; i++) {
            w.addMob(1, 1 + r.nextInt(3), new Pos(3000 + r.nextInt(1400) - 700, r.nextInt(1400) - 700, 0));
            w.addMob(2, 4 + r.nextInt(4), new Pos(r.nextInt(1400) - 700, 4000 + r.nextInt(1400) - 700, 0));
            w.addMob(3, 8 + r.nextInt(5), new Pos(-4000 + r.nextInt(1400) - 700, r.nextInt(1400) - 700, 0));
        }
        return w;
    }

    static BotInfo info(boolean mage) {
        return new BotInfo(1, 100, "Bot", mage ? 10 : 0, false, "RU", 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 700, TOWN, 42);
    }

    static BrainHarness harness(FakeWorld w, boolean mage, long seed) {
        final BasicBrain b = new BasicBrain(1, zones(), VILLAGES, new MobClaims());
        return new BrainHarness(b, info(mage), w, seed);
    }

    @Test
    void warriorGoesFromTownToHuntingAndLevelsUp() {
        final FakeWorld w = world(1, false);
        final BrainHarness h = harness(w, false, 1);
        h.run(2 * HOUR);
        assertTrue(h.states().get(0).startsWith("TOWN_IDLE"), h.states().toString());
        assertTrue(h.states().stream().anyMatch(s -> s.startsWith("LEVEL")), "never hunted");
        assertTrue(h.states().stream().anyMatch(s -> s.startsWith("REST")), "never rested");
        assertTrue(w.kills > 30, "kills " + w.kills);
        assertTrue(w.level() >= 6, "level " + w.level() + " after 2 h, kills " + w.kills + ", deaths " + w.deaths);
        assertTrue(h.act().countOk("attack") > 0);
        assertEquals(0, h.act().count("cast"), "a warrior does not cast");
        assertTrue(h.act().count("learnSkills") >= w.level(), "learnSkills on every level-up");
        assertTrue(w.pickedUp > 0, "picks up its drops");
        assertEquals(0, w.stolenHits);
    }

    @Test
    void mageNukesSpendsMpAndRests() {
        final FakeWorld w = world(2, true);
        final BrainHarness h = harness(w, true, 2);
        h.run(HOUR);
        assertTrue(h.act().countOk("cast") > 20, "casts " + h.act().countOk("cast"));
        assertTrue(h.act().countOk("sit") > 0, "never sat down");
        assertTrue(w.level() >= 4, "level " + w.level());
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("REST")), "no REST");
    }

    @Test
    void mageSkipsANukeTheGameRefuses() {
        final FakeWorld w = world(2, true);
        w.undeadOnlyNuke = true;
        final BrainHarness h = harness(w, true, 2);
        h.run(HOUR);
        assertTrue(h.act().count("cast") - h.act().countOk("cast") <= 10, "refused casts " + (h.act().count("cast") - h.act().countOk("cast")));
        assertTrue(h.act().countOk("cast") > 20, "casts " + h.act().countOk("cast"));
        assertTrue(w.level() >= 4, "level " + w.level());
    }

    @Test
    void noKillSteal() {
        final FakeWorld w = new FakeWorld(3, TOWN, false);
        for (int i = 0; i < 8; i++) {
            w.addMob(1, 2, new Pos(3000 + (i * 100), 0, 0)).engagedBy = 777; // someone else is fighting all of them
        }
        final BrainHarness h = harness(w, false, 3);
        h.run(20 * MIN);
        assertEquals(0, w.stolenHits);
        assertEquals(0, h.act().count("attack"));
        assertTrue(h.states().stream().anyMatch(s -> s.startsWith("LEVEL/search")), h.states().toString());
    }

    @Test
    void lowLevelAvoidsAggressiveButDefendsItself() {
        final FakeWorld w = new FakeWorld(4, TOWN, false);
        for (int i = 0; i < 3; i++) {
            w.addMob(1, 2, new Pos(2600 + (i * 500), 300, 0)).aggressive = true;
        }
        final BrainHarness h = harness(w, false, 4);
        h.run(30 * MIN);
        // it never picks an aggressive one; they come to it, and then it fights back
        assertTrue(h.notes().stream().noneMatch(n -> n.startsWith("fight") && !n.contains("(it attacked)") && n.contains("Mob1")), h.notes().toString());
        assertTrue(w.kills > 0, "defended itself");
    }

    @Test
    void deathThenTownThenHuntingAgain() {
        final FakeWorld w = world(5, false);
        final BrainHarness h = harness(w, false, 5);
        h.setReturnAfterDeath(0);
        h.run(15 * MIN);
        assertTrue(h.states().stream().anyMatch(s -> s.startsWith("LEVEL")));
        w.kill();
        h.run(10_000);
        assertEquals(BasicBrain.Phase.DEAD, ((BasicBrain) h.brain()).phase());
        assertEquals(0, h.act().count("returnToTown"), "bots-core takes the dead to town, not the brain");
        w.actuators().returnToTown(); // what bots-core does after 5-40 s
        h.run(5_000);
        assertEquals(BasicBrain.Phase.TOWN_IDLE, ((BasicBrain) h.brain()).phase());
        final int kills = w.kills;
        h.run(15 * MIN);
        assertTrue(w.kills > kills, "hunts again after death");
        final List<String> s = h.states();
        assertTrue(s.indexOf("DEAD/waiting") < s.lastIndexOf("TOWN_IDLE/revived"), s.toString());
    }

    @Test
    void findsItsWayAroundAWall() {
        final FakeWorld w = world(6, false);
        w.addWall(1500, -2000, 1600, 1200); // between town and the low zone, open in the south
        final BrainHarness h = harness(w, false, 6);
        h.run(40 * MIN);
        assertTrue(w.kills > 5, "reached the hunting ground: kills " + w.kills + " notes " + h.notes().subList(Math.max(0, h.notes().size() - 15), h.notes().size()));
    }

    @Test
    void fastEnough() {
        final FakeWorld w = world(7, true);
        final BrainHarness h = harness(w, true, 7);
        final long t0 = System.nanoTime();
        h.run(3 * HOUR);
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(h.ticks() > 2000, "ticks " + h.ticks());
        assertTrue(ms < 20_000, "3 h of game took " + ms + " ms");
        assertFalse(w.dead() && (w.deaths > 50), "deaths " + w.deaths);
    }

    @Test
    void farFromTheStartingVillagesItDoesNotWalkAcrossTheWorld() {
        final FakeWorld w = world(8, false);
        w.teleport(new Pos(100_000, 100_000, 0));
        final BrainHarness h = harness(w, false, 8);
        h.run(20 * MIN);
        assertEquals(BasicBrain.Phase.TOWN_IDLE, ((BasicBrain) h.brain()).phase());
        assertTrue(h.notes().get(0).contains("far from the starting villages"), h.notes().toString());
        assertTrue(w.pos().distance2d(new Pos(100_000, 100_000, 0)) < 1000, "stayed where it is: " + w.pos());
        assertEquals(0, w.kills);
    }

    @Test
    void placedAtAnotherVillageItHuntsThere() {
        final List<Village> two = List.of(new Village("TEST", TOWN, 500), new Village("OTHER", new Pos(20_000, 0, 0), 500));
        final LevelZones z = new LevelZones(List.of(new LevelZone("home", "TEST", new Pos(3000, 0, 0), 900, 1, 3, 12, false, Set.of(1)),
            new LevelZone("there", "OTHER", new Pos(23_000, 0, 0), 900, 1, 3, 12, false, Set.of(1))));
        final FakeWorld w = new FakeWorld(9, new Pos(20_000, 0, 0), false);
        for (int i = 0; i < 6; i++) {
            w.addMob(1, 2, new Pos(23_000 + (i * 100), 200, 0));
            w.addMob(1, 2, new Pos(3000 + (i * 100), 200, 0));
        }
        final BrainHarness h = new BrainHarness(new BasicBrain(1, z, two, new MobClaims()), info(false), w, 9); // home = TEST (0,0)
        h.run(15 * MIN);
        assertEquals("there", ((BasicBrain) h.brain()).zone().id());
        assertTrue(w.kills > 0);
    }

    @Test
    void claimsKeepTwoBotsOffOneMonster() {
        final MobClaims c = new MobClaims();
        assertTrue(c.claim(10, 1, 0, 1000));
        assertFalse(c.claim(10, 2, 500, 1000));
        assertTrue(c.takenByOther(10, 2, 500));
        assertTrue(c.claim(10, 2, 1500, 1000), "expired");
        c.release(10, 2);
        assertFalse(c.takenByOther(10, 1, 1600));
        c.claim(11, 1, 0, 1000);
        c.claim(12, 1, 0, 1000);
        c.releaseAll(1);
        assertEquals(0, c.size());
    }

    @Test
    void zonePickFitsLevel() {
        final LevelZones z = zones();
        final java.util.Random r = new java.util.Random(1);
        assertEquals("low", z.pick(1, "TEST", TOWN, r).id());
        for (int i = 0; i < 20; i++) {
            assertEquals("mid", z.pick(6, "TEST", TOWN, r).id());
            assertEquals("high", z.pick(10, "TEST", TOWN, r).id());
        }
        assertNotNull(z.pick(10, "OTHER", TOWN, r), "falls back to any village");
    }
}
