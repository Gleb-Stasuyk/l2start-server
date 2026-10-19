package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Activity;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Pos;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** S3 (docs/STARTER_ZONES_PLAN.md): a level 1 bot at Einhovant's School has a goal and takes up another one when idle. */
class StarterIdleBrainTest {
    static final Pos TI = new Pos(-84318, 244579, -3730);
    static final Pos SCHOOL = new Pos(-90954, 248118, -3570);
    static final long MIN = 60_000;

    static BrainHarness harness(FakeWorld w, boolean mage) throws Exception {
        final LevelZones zones = LevelZones.load(Path.of("modules/bots-brain-basic/data/levelzones.xml"));
        final BasicBrain b = new BasicBrain(1, zones, Village.STARTING, new MobClaims());
        final BotInfo info = new BotInfo(1, 100, "Bot", mage ? 10 : 0, false, "RU", 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 700, TI, 42);
        return new BrainHarness(b, info, w, 7);
    }

    static FakeWorld school(boolean mage) {
        final FakeWorld w = new FakeWorld(3, TI, mage);
        w.teleport(SCHOOL);
        w.setPeace(false);
        final java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < 10; i++) { // the gremlins of the school (stock Gremlins.xml)
            w.addMob(18342, 1, new Pos(-90610 + r.nextInt(600) - 300, 248293 + r.nextInt(600) - 300, -3548));
        }
        return w;
    }

    @Test
    void levelOneMageAtTheSchoolHuntsNotTownIdle() throws Exception {
        final FakeWorld w = school(true);
        final BrainHarness h = harness(w, true);
        h.run(3 * MIN);
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("hunting ground talking_island")), h.notes().toString());
        assertTrue(h.states().stream().anyMatch(s -> s.startsWith("LEVEL")), h.states().toString());
        assertTrue(w.kills > 0, "kills " + w.kills);
        final Activity a = h.brain().activity();
        assertNotNull(a);
        assertNotEquals(Activity.Kind.NONE, a.kind());
    }

    @Test
    void idleEventMakesItTakeUpAnotherActivity() throws Exception {
        final FakeWorld w = school(false);
        final BrainHarness h = harness(w, false);
        h.run(MIN);
        w.event(new BotEvent.Idle("not moving", false));
        h.run(MIN);
        assertTrue(h.notes().stream().anyMatch(n -> n.contains("idle (not moving)")), h.notes().toString());
        final int idleAt = indexOf(h, "idle (not moving)");
        assertTrue(h.notes().subList(idleAt, h.notes().size()).stream().anyMatch(n -> n.contains("hunting ground")), "picked a ground again: " + h.notes());
    }

    static int indexOf(BrainHarness h, String text) {
        for (int i = 0; i < h.notes().size(); i++) {
            if (h.notes().get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }
}
