package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.l2start.bots.api.NpcView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Phase 4a: potions, leaving a lost fight, no sitting next to aggressive monsters, kiting of archers and mages. */
class SurvivalBrainTest {
    static final long MIN = 60_000;

    @AfterEach
    void defaults() {
        BasicBrain.potions = true;
        BasicBrain.potionLo = 40;
        BasicBrain.potionHi = 60;
        BasicBrain.fleeHp = 25;
        BasicBrain.kiteClose = 120;
        BasicBrain.kiteTo = 400;
    }

    /** Melee monsters of one level on the "mid" ground, the bot level 6. */
    static FakeWorld ground(long seed, boolean mage, int mobLevel, int mobs) {
        final FakeWorld w = new FakeWorld(seed, BasicBrainTest.TOWN, mage);
        final Random r = new Random(seed);
        for (int i = 0; i < mobs; i++) {
            w.addMob(2, mobLevel, new Pos(r.nextInt(1400) - 700, 4000 + r.nextInt(1400) - 700, 0));
        }
        w.setLevel(6);
        return w;
    }

    record Run(FakeWorld w, BrainHarness h, BasicBrain b) {
        double damagePerKill() {
            return w.damageTaken / Math.max(1, w.kills);
        }
    }

    static Run hunt(FakeWorld w, boolean mage, long seed, long ms) {
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, BasicBrainTest.info(mage), w, seed);
        h.setAssignment(PresenceBrainTest.hunt(1));
        h.run(ms);
        return new Run(w, h, b);
    }

    static boolean noted(Run r, String s) {
        return r.h().notes().stream().anyMatch(n -> n.contains(s));
    }

    @Test
    void mageKitesMeleeMonstersAndTakesLessDamage() {
        BasicBrain.kiteClose = 0;
        final Run still = hunt(ground(21, true, 5, 8), true, 21, 30 * MIN);
        BasicBrain.kiteClose = 120;
        final Run kiting = hunt(ground(21, true, 5, 8), true, 21, 30 * MIN);
        assertEquals(0, still.b().kites());
        assertTrue(kiting.b().kites() >= 5, "kites: " + kiting.b().kites() + " " + kiting.h().notes());
        assertTrue(kiting.w().kills >= 5, "kills: " + kiting.w().kills);
        assertTrue(kiting.damagePerKill() < (still.damagePerKill() * 0.7),
            String.format("damage per kill: kiting %.1f, standing %.1f", kiting.damagePerKill(), still.damagePerKill()));
    }

    @Test
    void archerKitesMeleeMonstersAndTakesLessDamage() {
        BasicBrain.kiteClose = 0;
        final FakeWorld a = ground(22, false, 5, 8);
        a.setArcher(true);
        final Run still = hunt(a, false, 22, 30 * MIN);
        BasicBrain.kiteClose = 120;
        final FakeWorld b = ground(22, false, 5, 8);
        b.setArcher(true);
        final Run kiting = hunt(b, false, 22, 30 * MIN);
        assertTrue(kiting.b().kites() >= 5, "kites: " + kiting.b().kites() + " " + kiting.h().notes());
        assertTrue(kiting.w().kills >= 5, "kills: " + kiting.w().kills);
        assertTrue(kiting.damagePerKill() < (still.damagePerKill() * 0.7),
            String.format("damage per kill: kiting %.1f, standing %.1f", kiting.damagePerKill(), still.damagePerKill()));
    }

    @Test
    void warriorDoesNotKite() {
        final Run r = hunt(ground(23, false, 5, 8), false, 23, 20 * MIN);
        assertEquals(0, r.b().kites());
        assertTrue(r.w().kills >= 3, "kills " + r.w().kills);
    }

    @Test
    void kitingStopsWhenTheMonsterKeepsUp() {
        final FakeWorld w = ground(24, true, 5, 8);
        w.mobSpeed = 1.3; // faster than the bot: stepping back only gives it free hits
        final Run r = hunt(w, true, 24, 20 * MIN);
        assertTrue(noted(r, "does not work"), r.h().notes().toString());
        assertTrue(r.b().kites() <= ((3 * Math.max(1, r.w().kills)) + 3), "kites " + r.b().kites() + ", kills " + r.w().kills);
    }

    @Test
    void drinksAPotionBelowItsThresholdNotMoreOftenThanItsReuse() {
        final FakeWorld w = ground(25, false, 7, 6); // a monster a level above: the fight hurts
        w.giveItem(1061, 10);
        final Run r = hunt(w, false, 25, 20 * MIN);
        assertTrue(w.potionsUsed >= 1, "no potion: " + r.h().notes());
        assertEquals(0, r.h().act().calls().stream().filter(c -> c.action().equals("useItem") && !c.result().ok()).count(), "tried within the reuse");
        assertTrue(noted(r, "potion 1061"), r.h().notes().toString());
    }

    @Test
    void noPotionsWhenSwitchedOff() {
        BasicBrain.potions = false;
        final FakeWorld w = ground(25, false, 7, 6);
        w.giveItem(1061, 10);
        hunt(w, false, 25, 10 * MIN);
        assertEquals(0, w.potionsUsed);
    }

    @Test
    void potionThresholdIsThePersonasOwnBetweenMinAndMax() {
        double lo = 100;
        double hi = 0;
        for (long seed = 1; seed < 200; seed++) {
            final double t = BasicBrain.potionAt(seed, 40, 60);
            assertTrue((t >= 40) && (t <= 60), "threshold " + t);
            lo = Math.min(lo, t);
            hi = Math.max(hi, t);
        }
        assertTrue((lo < 43) && (hi > 57), lo + ".." + hi);
        assertEquals(BasicBrain.potionAt(7, 40, 60), BasicBrain.potionAt(7, 40, 60));
    }

    @Test
    void withoutPotionsBadlyHurtItLeavesTheFight() {
        BasicBrain.kiteClose = 0;
        final FakeWorld w = ground(26, false, 7, 8);
        final Run r = hunt(w, false, 26, 30 * MIN);
        assertTrue(noted(r, "no potion at hand): runs"), r.h().notes().toString());
        assertTrue(noted(r, "scroll of escape") || noted(r, "got away"), r.h().notes().toString());
    }

    @Test
    void doesNotSitDownNextToAnAggressiveMonster() {
        final FakeWorld w = ground(27, false, 5, 0);
        final FakeWorld.Mob aggro = w.addMob(2, 5, new Pos(350, 4000, 0));
        aggro.aggressive = true;
        w.teleport(new Pos(0, 4000, 0));
        w.setHp(w.maxHp() * 0.3); // wants to rest
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, BasicBrainTest.info(false), w, 27);
        h.setAssignment(PresenceBrainTest.hunt(1));
        for (int i = 0; i < 120; i++) {
            h.run(500);
            if (w.sitting() && (aggro.targetId == 0) && !aggro.dead()) {
                assertTrue(w.pos().distance2d(aggro.pos) > BasicBrain.AGGRO_NEAR, "sat " + (int) w.pos().distance2d(aggro.pos) + " from an aggressive monster: " + h.notes());
            }
        }
    }

    @Test
    void kitePointIsAwayFromTheMonsterAndWalkable() {
        final FakeWorld w = new FakeWorld(1, BasicBrainTest.TOWN, true);
        final Pos me = new Pos(1000, 1000, 0);
        final Pos mob = new Pos(1080, 1000, 0);
        final Pos p = BasicBrain.kitePoint(me, mob, 400, w.geo());
        assertNotNull(p);
        assertTrue(p.distance2d(mob) > (me.distance2d(mob) + 100), p.toString());
        assertTrue(p.x() < me.x(), "away from the monster: " + p);
        // a wall right behind: it goes aside, never through the wall
        w.addWall(700, 900, 960, 1100);
        final Pos q = BasicBrain.kitePoint(me, mob, 400, w.geo());
        assertNotNull(q);
        assertTrue(w.geo().canMoveTo(me, q), q.toString());
        // boxed in: nowhere to go
        w.addWall(600, 600, 1500, 960);
        w.addWall(600, 1040, 1500, 1400);
        assertNull(BasicBrain.kitePoint(me, mob, 400, w.geo()));
    }

    static NpcView aggro(int id, Pos at) {
        return new NpcView(id, 9, "Aggro", 15, at, 0, 100, false, true, true, true, 0, false, 40, false, 300);
    }

    @Test
    void safePointDoesNotStepBackIntoAggressiveMonsters() {
        final FakeWorld w = new FakeWorld(1, BasicBrainTest.TOWN, true);
        final Pos me = new Pos(1000, 1000, 0);
        final Pos mob = new Pos(1080, 1000, 0);
        // an aggressive monster right behind the bot: straight back would walk into its aggro range
        final List<NpcView> behind = List.of(aggro(50, new Pos(550, 1000, 0)));
        final Pos p = BasicBrain.safePoint(me, mob, 400, BasicBrain.KITE_STEP, w.geo(), behind, BasicBrain.KITE_FAN, true);
        assertNotNull(p);
        assertTrue(BasicBrain.clearance(me, p, behind) >= 0, "into its aggro range: " + p);
        assertTrue(p.distance2d(new Pos(550, 1000, 0)) > (300 + BasicBrain.AGGRO_MARGIN), p.toString());
        assertTrue(p.distance2d(mob) > me.distance2d(mob), "still away from the fought monster: " + p);
        // the step is short
        assertTrue(p.distance2d(me) <= (BasicBrain.KITE_STEP + 90), "step " + (int) p.distance2d(me));
        // aggressive monsters behind and on both sides: no safe spot -> null (fight in place)
        final List<NpcView> around = List.of(aggro(50, new Pos(550, 1000, 0)), aggro(51, new Pos(1000, 1450, 0)), aggro(52, new Pos(1000, 550, 0)),
            aggro(53, new Pos(700, 1350, 0)), aggro(54, new Pos(700, 650, 0)));
        assertNull(BasicBrain.safePoint(me, mob, 400, BasicBrain.KITE_STEP, w.geo(), around, BasicBrain.KITE_FAN, true));
        // a monster that fights somebody else is not a danger
        final NpcView busy = new NpcView(55, 9, "Busy", 15, new Pos(550, 1000, 0), 0, 100, false, true, true, true, 777, true, 40, false, 300);
        assertTrue(!busy.freeAggressive());
    }

    @Test
    void clearanceCountsTheWayNotOnlyTheEnd() {
        // the end is far from the monster, but the way passes right next to it
        final List<NpcView> t = List.of(aggro(50, new Pos(500, 0, 0)));
        assertTrue(BasicBrain.clearance(new Pos(0, 0, 0), new Pos(1000, 0, 0), t) < 0);
        assertTrue(BasicBrain.clearance(new Pos(0, 0, 0), new Pos(0, 1000, 0), t) >= 0);
        assertEquals(Double.MAX_VALUE, BasicBrain.clearance(new Pos(0, 0, 0), new Pos(1000, 0, 0), List.of()));
    }

    /** Archer fights a passive monster with aggressive ones a bit behind it (outside their aggro range): it never pulls them. */
    void kitingDoesNotPull(boolean mage, long seed) {
        final FakeWorld w = ground(seed, mage, 5, 0);
        w.setArcher(!mage);
        w.addMob(2, 5, new Pos(0, 4180, 0));
        final List<FakeWorld.Mob> aggros = List.of(w.addMob(9, 15, new Pos(0, 3540, 0)), w.addMob(9, 15, new Pos(-280, 3600, 0)),
            w.addMob(9, 15, new Pos(280, 3600, 0)));
        aggros.forEach(m -> m.aggressive = true);
        w.teleport(new Pos(0, 4000, 0));
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, BasicBrainTest.info(mage), w, seed);
        h.setAssignment(PresenceBrainTest.hunt(1));
        for (int i = 0; (i < 240) && (w.kills == 0); i++) {
            h.run(250);
            for (FakeWorld.Mob m : aggros) {
                assertEquals(0, m.targetId, "pulled an aggressive monster at " + w.pos() + " (it stands at " + m.pos + "): " + h.notes());
            }
        }
        assertEquals(1, w.kills, "the fight did not end: " + h.notes());
        assertTrue((b.kites() > 0) || noted(new Run(w, h, b), "no safe spot"), h.notes().toString());
    }

    @Test
    void archerDoesNotKiteIntoAggressiveMonsters() {
        kitingDoesNotPull(false, 31);
    }

    @Test
    void mageDoesNotKiteIntoAggressiveMonsters() {
        kitingDoesNotPull(true, 32);
    }

    @Test
    void noKitingWithTwoMonstersOnIt() {
        final FakeWorld w = ground(33, true, 5, 0);
        final FakeWorld.Mob a = w.addMob(2, 5, new Pos(0, 4150, 0));
        final FakeWorld.Mob c = w.addMob(2, 5, new Pos(60, 4150, 0));
        a.targetId = FakeWorld.SELF_ID;
        c.targetId = FakeWorld.SELF_ID;
        w.teleport(new Pos(0, 4000, 0));
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, BasicBrainTest.info(true), w, 33);
        h.setAssignment(PresenceBrainTest.hunt(1));
        for (int i = 0; (i < 120) && (w.kills == 0); i++) {
            h.run(250);
        }
        assertEquals(0, b.kites(), h.notes().toString());
    }

    @Test
    void runsAwayNotIntoAggressiveMonstersOrReadsAScroll() {
        BasicBrain.kiteClose = 0;
        BasicBrain.potions = false;
        final FakeWorld w = ground(34, false, 7, 0);
        final FakeWorld.Mob mob = w.addMob(2, 7, new Pos(0, 4100, 0));
        mob.targetId = FakeWorld.SELF_ID;
        // aggressive monsters behind, left and right: no safe way out
        final List<FakeWorld.Mob> aggros = List.of(w.addMob(9, 15, new Pos(0, 3500, 0)), w.addMob(9, 15, new Pos(-500, 3900, 0)),
            w.addMob(9, 15, new Pos(500, 3900, 0)), w.addMob(9, 15, new Pos(-400, 3550, 0)), w.addMob(9, 15, new Pos(400, 3550, 0)));
        aggros.forEach(m -> m.aggressive = true);
        w.teleport(new Pos(0, 4000, 0));
        w.setHp(w.maxHp() * 0.15);
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, BasicBrainTest.info(false), w, 34);
        h.setAssignment(PresenceBrainTest.hunt(1));
        for (int i = 0; i < 40; i++) {
            h.run(250);
            for (FakeWorld.Mob m : aggros) {
                assertEquals(0, m.targetId, "ran into an aggressive monster at " + w.pos() + ": " + h.notes());
            }
        }
        assertTrue(noted(new Run(w, h, b), "no safe way out") || noted(new Run(w, h, b), "runs"), h.notes().toString());
    }
}
