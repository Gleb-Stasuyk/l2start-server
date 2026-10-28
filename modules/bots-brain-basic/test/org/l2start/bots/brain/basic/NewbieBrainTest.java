package org.l2start.bots.brain.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.testkit.BrainHarness;
import org.l2start.bots.testkit.FakeWorld;

/** S6 (docs/STARTER_ZONES_PLAN.md): a newbie companion hunts the gremlins next to the new player, 10-15 kills, then goes home. */
class NewbieBrainTest {
    static final Pos GREMLINS = new Pos(-90610, 248293, -3548);
    static final Pos HOME = new Pos(-84500, 244300, -3730);
    static final long MIN = 60_000;

    @Test
    void huntsTheGremlinsOfItsPlayerThenRunsHome() {
        final FakeWorld w = new FakeWorld(5, HOME, false);
        w.teleport(new Pos(-93500, 248300, -3548)); // out of sight of the player
        w.setPeace(false);
        final java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < 10; i++) {
            w.addMob(18342, 1, new Pos(GREMLINS.x() + r.nextInt(600) - 300, GREMLINS.y() + r.nextInt(600) - 300, GREMLINS.z()));
        }
        w.addPlayer(new PlayerView(9001, "NewGuy", 1, 10, new Pos(GREMLINS.x() + 150, GREMLINS.y(), GREMLINS.z()), 3000, 0, false, false, 0, false));
        final BasicBrain b = new BasicBrain(1, new LevelZones(List.of()), Village.STARTING, new MobClaims());
        final BrainHarness h = new BrainHarness(b, new BotInfo(1, 100, "Companion", 0, false, "RU", 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 700, HOME, 42), w, 5);
        final Assignment a = new Assignment(11, Assignment.Kind.NEWBIE, "newbie:talking_island:9001", GREMLINS, 8000, 1, 3, List.of(GREMLINS), List.of(), HOME);
        h.setAssignment(a);
        h.run(40 * MIN);
        final int want = Assignment.newbieKills(11, 1);
        assertEquals(want, b.newbieKills(), "kills before home " + h.notes());
        assertEquals(want, w.kills, "no more kills after its target");
        assertTrue(w.pos().distance2d(HOME) < 200, "went home: " + w.pos());
        assertTrue(h.states().stream().anyMatch(s -> s.contains("newbie: back to the village")), h.states().toString());
    }
}
