package org.l2start.bots.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Phase 4c: facing of a monster (Backstab only from behind). */
class NpcViewTest {
    static NpcView facing(int heading) {
        return new NpcView(5, 9, "Mob", 10, new Pos(0, 0, 0), 0, 100, false, true, false, true, 0, false, 40, false, 0, heading, false, false);
    }

    @Test
    void behindIsTheSideItDoesNotLookAt() {
        final NpcView east = facing(0); // looks east (+x)
        assertFalse(east.behind(new Pos(100, 0, 0), 120), "in front");
        assertTrue(east.behind(new Pos(-100, 0, 0), 120), "straight behind");
        assertTrue(east.behind(new Pos(-100, 50, 0), 120), "behind, a bit to the side");
        assertFalse(east.behind(new Pos(0, 100, 0), 120), "at its side");
        final NpcView north = facing(16384); // a quarter turn: +y
        assertTrue(north.behind(new Pos(0, -100, 0), 120));
        assertFalse(north.behind(new Pos(0, 100, 0), 120));
        assertFalse(east.behind(new Pos(0, 0, 0), 120), "on the same spot");
    }

    @Test
    void behindPointIsOppositeToTheLook() {
        assertEquals(new Pos(-60, 0, 0), facing(0).behindPoint(60));
        assertEquals(new Pos(0, -60, 0), facing(16384).behindPoint(60));
        assertTrue(facing(12000).behind(facing(12000).behindPoint(60), 170));
    }

    @Test
    void olderConstructorsSeeNothingNew() {
        final NpcView v = new NpcView(5, 9, "Mob", 10, new Pos(0, 0, 0), 0, 100, false, true, true, true, 0, false);
        assertEquals(0, v.heading());
        assertFalse(v.undead());
        assertFalse(v.spoiled());
        assertEquals(NpcView.AGGRO, v.aggroRange());
    }
}
