package org.l2start.modules.townbuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TownBufferTest {
    @Test
    void pricesAndTitles() {
        assertEquals(20_000, Towns.price(48));
        assertEquals(62_000, Towns.price(55));
        assertEquals(116_000, Towns.price(64));
        assertEquals(176_000, Towns.price(74));
        assertEquals(200_000, Towns.price(78));
        for (Towns.Town t : Towns.ALL) {
            assertTrue(Towns.title(t.level(), t.price()).length() <= 16, t.name());
        }
        assertEquals("Баф 55 ур - 62k", Towns.title(55, 62_000));
        assertEquals("Баф 64 ур - 116k", Towns.title(64, 116_000));
    }

    @Test
    void onlyAdenaAtLeastThePrice() {
        assertEquals(Deal.Verdict.OK, Deal.check(List.of(new long[] { 57, 62_000 }), 0, 62_000));
        assertEquals(Deal.Verdict.OK, Deal.check(List.of(new long[] { 57, 70_000 }), 0, 62_000)); // overpay kept, like x100
        assertEquals(Deal.Verdict.NOT_ENOUGH, Deal.check(List.of(new long[] { 57, 61_999 }), 0, 62_000));
        assertEquals(Deal.Verdict.NOT_ENOUGH, Deal.check(List.of(), 0, 62_000));
        assertEquals(Deal.Verdict.WRONG, Deal.check(List.of(new long[] { 57, 62_000 }, new long[] { 1835, 1 }), 0, 62_000));
        assertEquals(Deal.Verdict.WRONG, Deal.check(List.of(new long[] { 57, 62_000 }), 1, 62_000)); // the bot gives nothing
    }

    @Test
    void mageAndWarriorSetsOfTheOldServer() {
        final Map<Integer, Integer> lv = new LinkedHashMap<>();
        for (int id : Towns.BUFFS) {
            lv.put(id, 2);
        }
        lv.remove(1388); // Greater Might not learned yet at this level
        final List<int[]> w = Deal.plan(lv, false);
        final List<int[]> m = Deal.plan(lv, true);
        assertEquals(1204, w.get(0)[0]); // Wind Walk first, old order
        assertTrue(w.stream().anyMatch(a -> a[0] == 1068) && w.stream().noneMatch(a -> a[0] == 1085));
        assertTrue(m.stream().anyMatch(a -> a[0] == 1085) && m.stream().noneMatch(a -> (a[0] == 1068) || (a[0] == 1086)));
        assertTrue(w.stream().noneMatch(a -> a[0] == 1388));
        assertEquals(18 - 3 - 1, w.size());
        assertEquals(18 - 6, m.size());
        assertFalse(Towns.forClass(1389, false));
    }

    /** KT12 F6: Berserker Spirit (1062) is shared and present in both paid sets. */
    @Test
    void berserkerSpiritInMageAndWarriorSets() {
        assertTrue(Towns.forClass(1062, true));
        assertTrue(Towns.forClass(1062, false));
        final Map<Integer, Integer> lv = new LinkedHashMap<>();
        for (int id : Towns.BUFFS) {
            lv.put(id, 2);
        }
        final List<int[]> w = Deal.plan(lv, false);
        final List<int[]> m = Deal.plan(lv, true);
        assertTrue(w.stream().anyMatch(a -> a[0] == 1062), "warrior set missing Berserker Spirit");
        assertTrue(m.stream().anyMatch(a -> a[0] == 1062), "mage set missing Berserker Spirit");
        for (Towns.Town t : Towns.ALL) {
            assertTrue(Towns.BUFFS.length > 0);
            assertTrue(java.util.Arrays.stream(Towns.BUFFS).anyMatch(id -> id == 1062), t.name() + " town uses shared BUFFS with 1062");
        }
    }

    @Test
    void highestLearnedLevelOnly() {
        final List<BuffLevels.Learn> tree = List.of(new BuffLevels.Learn(1040, 1, 20), new BuffLevels.Learn(1040, 2, 40), new BuffLevels.Learn(1040, 3, 52), new BuffLevels.Learn(1086, 1, 44),
            new BuffLevels.Learn(1086, 2, 56), new BuffLevels.Learn(9999, 5, 1));
        final Map<Integer, Integer> at48 = BuffLevels.at(tree, Towns.BUFFS, 48);
        assertEquals(2, at48.get(1040));
        assertEquals(1, at48.get(1086));
        assertFalse(at48.containsKey(9999));
        assertEquals(3, BuffLevels.at(tree, Towns.BUFFS, 78).get(1040));
    }

    /** Prophet-like tree: PP 40/52/62/76 give growing buff sets (WW always; Bers from 52). */
    @Test
    void prophetSetsGrowWithLevel() {
        final List<BuffLevels.Learn> tree = List.of(
            new BuffLevels.Learn(1204, 1, 20), new BuffLevels.Learn(1204, 2, 30),
            new BuffLevels.Learn(1068, 3, 40), new BuffLevels.Learn(1035, 2, 40), new BuffLevels.Learn(1045, 2, 40),
            new BuffLevels.Learn(1040, 3, 44), new BuffLevels.Learn(1086, 1, 44), new BuffLevels.Learn(1077, 2, 44),
            new BuffLevels.Learn(1085, 3, 48),
            new BuffLevels.Learn(1062, 2, 52), new BuffLevels.Learn(1086, 2, 52),
            new BuffLevels.Learn(1388, 1, 58), new BuffLevels.Learn(1389, 1, 58),
            new BuffLevels.Learn(1388, 2, 66), new BuffLevels.Learn(1389, 2, 66),
            new BuffLevels.Learn(1388, 3, 74), new BuffLevels.Learn(1389, 3, 74));
        final Map<Integer, Integer> at40 = BuffLevels.at(tree, Towns.BUFFS, 40);
        final Map<Integer, Integer> at52 = BuffLevels.at(tree, Towns.BUFFS, 52);
        final Map<Integer, Integer> at62 = BuffLevels.at(tree, Towns.BUFFS, 62);
        final Map<Integer, Integer> at76 = BuffLevels.at(tree, Towns.BUFFS, 76);
        assertTrue(at40.containsKey(1204));
        assertFalse(at40.containsKey(1062));
        assertTrue(at52.containsKey(1062));
        assertTrue(at52.size() > at40.size());
        assertTrue(at62.containsKey(1388));
        assertTrue(at62.size() >= at52.size());
        assertEquals(3, at76.get(1388));
        assertTrue(at76.size() >= at62.size());
        // each higher band is a proper super-set of ids
        assertTrue(at52.keySet().containsAll(at40.keySet()));
        assertTrue(at62.keySet().containsAll(at52.keySet()));
        assertTrue(at76.keySet().containsAll(at62.keySet()));
    }
}
