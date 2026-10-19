package org.l2start.bots.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Pos;

/** S4: //bots spots finds the piles (bots within 50 of each other). */
class PilesTest {
    @Test
    void schoolPilesAreFoundBiggestFirst() {
        final List<Pos> pts = List.of(new Pos(-90954, 248118, 0), new Pos(-90954, 248118, 0), new Pos(-90918, 248070, 0), new Pos(-90920, 248072, 0),
            new Pos(-90920, 248075, 0), new Pos(-80000, 240000, 0), new Pos(-80300, 240000, 0));
        final List<List<Integer>> piles = Piles.of(pts, 50);
        assertEquals(2, piles.size(), piles.toString());
        assertEquals(List.of(2, 3, 4), piles.get(0));
        assertEquals(List.of(0, 1), piles.get(1));
        assertTrue(Piles.of(List.of(new Pos(0, 0, 0), new Pos(200, 0, 0)), 50).isEmpty());
    }
}
