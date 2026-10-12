package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.l2start.economy.market.model.TradeMeet.Kind;
import org.l2start.economy.market.model.TradeMeet.Line;
import org.l2start.economy.market.model.TradeMeet.Spot;

class TradeMeetTest {
    @Test
    void theBotSellsOnlyForExactlyTheAdena() {
        assertTrue(TradeMeet.matches(true, 1463, 1000, 7, List.of(new Line(57, 7000))));
        assertFalse(TradeMeet.matches(true, 1463, 1000, 7, List.of(new Line(57, 6999))), "less");
        assertFalse(TradeMeet.matches(true, 1463, 1000, 7, List.of(new Line(57, 7001))), "more is not the deal either");
        assertFalse(TradeMeet.matches(true, 1463, 1000, 7, List.of(new Line(57, 7000), new Line(1864, 1))), "something else in the window");
        assertFalse(TradeMeet.matches(true, 1463, 1000, 7, List.of(new Line(1463, 1000))), "the item itself is no payment");
        assertFalse(TradeMeet.matches(true, 1463, 1000, 7, List.of()), "empty window");
    }

    @Test
    void theBotBuysOnlyExactlyTheItemAndCount() {
        assertTrue(TradeMeet.matches(false, 1864, 20, 300, List.of(new Line(1864, 20))));
        assertTrue(TradeMeet.matches(false, 129, 2, 5000, List.of(new Line(129, 1), new Line(129, 1))), "two single pieces");
        assertFalse(TradeMeet.matches(false, 1864, 20, 300, List.of(new Line(1864, 19))));
        assertFalse(TradeMeet.matches(false, 1864, 20, 300, List.of(new Line(1865, 20))), "another item");
        assertFalse(TradeMeet.matches(false, 1864, 20, 300, List.of(new Line(1864, 20), new Line(57, 1))), "plus adena");
        assertFalse(TradeMeet.matches(false, 1864, 0, 300, List.of(new Line(1864, 0))), "no deal");
    }

    @Test
    void theBotPutsItsSide() {
        assertEquals(new Line(1463, 1000), TradeMeet.botSide(true, 1463, 1000, 7));
        assertEquals(new Line(57, 6000), TradeMeet.botSide(false, 1864, 20, 300));
    }

    @Test
    void meetsAtTheNearestOfAKindInThePlayersTown() {
        final List<Spot> c = List.of(new Spot(Kind.GATEKEEPER, 1, 0, 0, 0, 900), new Spot(Kind.GATEKEEPER, 2, 0, 0, 0, 400), new Spot(Kind.WAREHOUSE, 3, 0, 0, 0, 1200),
            new Spot(Kind.PRIEST, 4, 0, 0, 0, 2000), new Spot(Kind.WAREHOUSE, 5, 0, 0, 0, 7000));
        final Random rnd = new Random(1);
        final Set<Integer> seen = new HashSet<>();
        for (int k = 0; k < 200; k++) {
            seen.add(TradeMeet.pick(c, rnd).npcObjectId());
        }
        assertEquals(Set.of(2, 3, 4), seen, "nearest gatekeeper, warehouse and priest; the far warehouse is another town");
        assertNull(TradeMeet.pick(List.of(), rnd));
    }

    @Test
    void placeTextsInBothLanguages() {
        assertEquals("у ГК (Gatekeeper) в Giran", TradeTalk.place(Kind.GATEKEEPER, "Giran", true));
        assertEquals("at the Priest of Dawn in Giran", TradeTalk.place(Kind.PRIEST, "Giran", false));
        assertTrue(TradeTalk.meet(true, "Soulshot: D-grade", 1000, 7, Kind.WAREHOUSE, "Dion", "Arin", "RU", new Random(1)).contains("ровно 7000 адены"));
    }
}
