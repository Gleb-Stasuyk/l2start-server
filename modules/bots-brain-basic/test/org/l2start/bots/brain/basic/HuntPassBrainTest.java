package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.PlaceNames;
import org.l2start.bots.api.Pos;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** Hunt pass: a barren hunt gives up, bots walk out of water and never into it, party searches name real grounds. */
class HuntPassBrainTest {
    static final long MIN = 60_000;

    @Test
    void huntWithNothingToFightReportsEmptyAfterMinutes() {
        final FakeWorld w = new FakeWorld(3, BasicBrainTest.TOWN, false); // no monsters at all
        w.setLevel(5);
        w.teleport(new Pos(0, 4000, 0));
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, BasicBrainTest.info(false), w, 3);
        h.setAssignment(PresenceBrainTest.hunt(1));
        h.run(2 * MIN);
        assertFalse(b.huntEmpty(), "2 min is not barren yet");
        h.run(3 * MIN);
        assertTrue(b.huntEmpty(), "5 min without a target: barren " + h.notes());
    }

    @Test
    void huntWithMonstersIsNeverEmpty() {
        final SurvivalBrainTest.Run r = SurvivalBrainTest.hunt(SurvivalBrainTest.ground(4, false, 5, 8), false, 4, 10 * MIN);
        assertTrue(r.w().kills > 0);
        assertFalse(r.b().huntEmpty());
    }

    @Test
    void botInWaterWalksOutAndTownWanderNeverGoesIn() {
        final FakeWorld w = new FakeWorld(7, BasicBrainTest.TOWN, false);
        w.setLevel(5);
        w.addWater(800, 0, 400); // a canal by the town, like Heine's
        w.teleport(new Pos(800, 0, 0));
        assertTrue(w.inWater(w.pos()));
        final BasicBrain b = new BasicBrain(1, BasicBrainTest.zones(), BasicBrainTest.VILLAGES, new MobClaims());
        final BrainHarness h = new BrainHarness(b, BasicBrainTest.info(false), w, 7);
        h.setAssignment(PresenceBrainTest.town(1));
        h.run(MIN);
        assertFalse(w.inWater(w.pos()), "walked out: " + w.pos());
        int wet = 0;
        for (int i = 0; i < 60; i++) {
            h.run(10_000);
            if (w.inWater(w.pos())) {
                wet++;
            }
        }
        assertTrue(wet == 0, "stood in the water " + wet + " times");
    }

    @Test
    void partySearchNamesAFittingGroundNeverATownOrAnId() {
        PlaceNames.register(java.util.Map.of("fieldofwhispers_1", new String[] { "Поле Шепота", "Field of Whispers" }, "town_of_goddard",
            new String[] { "Town of Goddard", "Town of Goddard" }),
            List.of(new PlaceNames.Ground("fieldofwhispers_1", "Поле Шепота", "Field of Whispers", new Pos(1000, 1000, 0), 40, 46)));
        final FakeWorld w = new FakeWorld(8, BasicBrainTest.TOWN, false);
        w.setLevel(45);
        final Assignment inTown = new Assignment(1, Assignment.Kind.TOWN, "town_of_goddard", new Pos(0, 0, 0), 500, 40, 50, List.of(), List.of(), null);
        final String s = BasicBrain.lfpSpot(inTown, w.self(), "ru", new Random(1));
        assertTrue(s.equals("Поле Шепота"), s);
        assertTrue(BasicBrain.lfpSpot(inTown, w.self(), "en", new Random(1)).equals("Field of Whispers"));
        w.setLevel(20);
        assertTrue(BasicBrain.lfpSpot(inTown, w.self(), "ru", new Random(1)).isEmpty(), "nothing fits level 20: no place named");
        assertFalse(PlaceNames.say("near_heine_3", "ru").contains("_"));
    }
}
