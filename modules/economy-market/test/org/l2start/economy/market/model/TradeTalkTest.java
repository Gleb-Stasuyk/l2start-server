package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class TradeTalkTest {
    static final MarketItems ITEMS;
    static {
        try {
            ITEMS = MarketItems.load(Path.of("modules/economy-market/data/market-items.xml"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void englishAndRussianAds() {
        final TradeTalk.Ad a = TradeTalk.parse("WTB ssd 5k @12", ITEMS.all(), 2);
        assertFalse(a.playerSells());
        assertEquals(1463, a.items().get(0).id());
        assertEquals(5000, a.count());
        assertEquals(12, a.price());
        final TradeTalk.Ad r = TradeTalk.parse("куплю ссд 2к по 11", ITEMS.all(), 2);
        assertFalse(r.playerSells());
        assertEquals(1463, r.items().get(0).id());
        assertEquals(2000, r.count());
        assertEquals(11, r.price());
        final TradeTalk.Ad s = TradeTalk.parse("продам Iron Ore 300", ITEMS.all(), 2);
        assertTrue(s.playerSells());
        assertEquals(1869, s.items().get(0).id());
        assertEquals(300, s.count());
        assertEquals(736, TradeTalk.parse("wtb soe 20", ITEMS.all(), 2).items().get(0).id(), "the shortest name wins");
        assertEquals(1539, TradeTalk.parse("WTS greater healing potion 100", ITEMS.all(), 1).items().get(0).id());
    }

    @Test
    void notAnAdOrAboveTheStage() {
        assertNull(TradeTalk.parse("hello giran", ITEMS.all(), 2));
        assertNull(TradeTalk.parse("wtb qwertyzxc", ITEMS.all(), 2));
        assertFalse(TradeTalk.parse("wtb ssc", ITEMS.all(), 1).items().stream().anyMatch(i -> i.id() == 1464), "C shots are stage 2");
        assertEquals(1464, TradeTalk.parse("wtb ssc", ITEMS.all(), 2).items().get(0).id());
    }

    @Test
    void yesNoAndTemplates() {
        assertTrue(TradeTalk.yes("ок"));
        assertTrue(TradeTalk.yes("давай, иду"));
        assertTrue(TradeTalk.yes("ok"));
        assertTrue(TradeTalk.yes("+"));
        assertFalse(TradeTalk.yes("нет, дорого"));
        assertTrue(TradeTalk.no("не, передумал"));
        assertFalse(TradeTalk.yes("что?"));
        final Random rnd = new Random(1);
        final String ru = TradeTalk.ad(true, "Soulshot: D-grade", 12, 5000, "RU", rnd);
        assertTrue(ru.contains("SSD"), ru);
        final String en = TradeTalk.offer(true, "Soulshot: D-grade", 2000, 12, "EN", rnd);
        assertTrue(en.contains("SSD") && en.contains("@12"), en);
        assertTrue(TradeTalk.offer(false, "Iron Ore", 300, 150, "RU", rnd).startsWith("возьму Iron Ore"));
        assertEquals("45k", TradeTalk.price(45_000));
    }

    @Test
    void limitsPerMinute() {
        final TradeTalk.Limit l = new TradeTalk.Limit(2);
        assertTrue(l.allow(0));
        assertTrue(l.allow(1000));
        assertFalse(l.allow(2000));
        assertTrue(l.allow(61_000));
    }

    @Test
    void whichOfTwo() {
        final List<MarketItem> two = List.of(ITEMS.get(1061), ITEMS.get(1539));
        assertEquals(1, TradeTalk.pick("greater", two));
        assertEquals(0, TradeTalk.pick("1", two));
    }
}
