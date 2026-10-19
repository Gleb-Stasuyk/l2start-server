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
        for (Towns.Spot t : Towns.ALL) {
            assertTrue(Towns.title(t).length() <= 16, t.id());
            assertTrue(t.id().length() <= 16, t.id());
        }
        assertEquals("Баф 55 ур - 62k", Towns.title(55, 62_000));
        final Towns.Spot se64 = Towns.ALL.stream().filter(s -> "GiranSE".equals(s.id())).findFirst().orElseThrow();
        assertEquals(20_000, se64.price());
        assertTrue(Towns.title(se64).startsWith("SE "));
        final Towns.Spot se78 = Towns.ALL.stream().filter(s -> "GoddardSE".equals(s.id())).findFirst().orElseThrow();
        assertEquals(40_000, se78.price());
    }

    @Test
    void onlyAdenaAtLeastThePrice() {
        // Exact price and overpay both OK (buffers keep the overpay); underpay / non-adena / bot giving items = fail.
        assertEquals(Deal.Verdict.OK, Deal.check(List.of(new long[] { 57, 62_000 }), 0, 62_000));
        assertEquals(Deal.Verdict.OK, Deal.check(List.of(new long[] { 57, 70_000 }), 0, 62_000));
        assertEquals(Deal.Verdict.OK, Deal.check(List.of(new long[] { 57, 20_000 }), 0, Towns.price(48)));
        assertEquals(Deal.Verdict.OK, Deal.check(List.of(new long[] { 57, 40_000 }), 0, 40_000)); // SE 78 title price
        assertEquals(Deal.Verdict.NOT_ENOUGH, Deal.check(List.of(new long[] { 57, 61_999 }), 0, 62_000));
        assertEquals(Deal.Verdict.NOT_ENOUGH, Deal.check(List.of(), 0, 62_000));
        assertEquals(Deal.Verdict.WRONG, Deal.check(List.of(new long[] { 57, 62_000 }, new long[] { 1835, 1 }), 0, 62_000));
        assertEquals(Deal.Verdict.WRONG, Deal.check(List.of(new long[] { 57, 62_000 }), 1, 62_000));
    }

    @Test
    void mageAndWarriorSetsOfTheOldServer() {
        final Map<Integer, Integer> lv = new LinkedHashMap<>();
        for (int id : Towns.PP_BUFFS) {
            lv.put(id, 2);
        }
        lv.remove(1388);
        final List<int[]> w = Deal.plan(lv, false);
        final List<int[]> m = Deal.plan(lv, true);
        assertEquals(1204, w.get(0)[0]);
        assertTrue(w.stream().anyMatch(a -> a[0] == 1068) && w.stream().noneMatch(a -> a[0] == 1085));
        assertTrue(m.stream().anyMatch(a -> a[0] == 1085) && m.stream().noneMatch(a -> (a[0] == 1068) || (a[0] == 1086)));
        assertTrue(w.stream().noneMatch(a -> a[0] == 1388));
        assertEquals(18 - 3 - 1, w.size());
        assertEquals(18 - 6, m.size());
        assertFalse(Towns.forClass(1389, false));
    }

    @Test
    void berserkerSpiritInMageAndWarriorSets() {
        assertTrue(Towns.forClass(1062, true));
        assertTrue(Towns.forClass(1062, false));
        assertTrue(Towns.isPartyMemberBuff(1062));
        final Map<Integer, Integer> lv = new LinkedHashMap<>();
        for (int id : Towns.PP_BUFFS) {
            lv.put(id, 2);
        }
        final List<int[]> w = Deal.plan(lv, false);
        final List<int[]> m = Deal.plan(lv, true);
        assertTrue(w.stream().anyMatch(a -> a[0] == 1062));
        assertTrue(m.stream().anyMatch(a -> a[0] == 1062));
    }

    @Test
    void seSetsByClassAndLevel() {
        final List<BuffLevels.Learn> tree = List.of(
            new BuffLevels.Learn(1059, 2, 44), new BuffLevels.Learn(1059, 3, 52),
            new BuffLevels.Learn(1268, 2, 44), new BuffLevels.Learn(1268, 3, 58), new BuffLevels.Learn(1268, 4, 72),
            new BuffLevels.Learn(1303, 1, 62), new BuffLevels.Learn(1303, 2, 70),
            new BuffLevels.Learn(1354, 1, 76));
        final Map<Integer, Integer> at64 = BuffLevels.at(tree, Towns.SE_BUFFS, 64);
        final Map<Integer, Integer> at78 = BuffLevels.at(tree, Towns.SE_BUFFS, 78);
        assertTrue(at64.containsKey(1059) && at64.containsKey(1303) && at64.containsKey(1268));
        assertFalse(at64.containsKey(1354), "Arcane Protection only from 76");
        assertTrue(at78.containsKey(1354));
        final List<int[]> mage64 = Deal.plan(at64, Towns.SE_BUFFS, Towns.Kind.SE, true);
        final List<int[]> war64 = Deal.plan(at64, Towns.SE_BUFFS, Towns.Kind.SE, false);
        assertTrue(mage64.stream().anyMatch(a -> a[0] == 1059) && mage64.stream().anyMatch(a -> a[0] == 1303));
        assertTrue(mage64.stream().noneMatch(a -> a[0] == 1268));
        assertTrue(war64.stream().anyMatch(a -> a[0] == 1268));
        assertTrue(war64.stream().noneMatch(a -> a[0] == 1059));
        final List<int[]> mage78 = Deal.plan(at78, Towns.SE_BUFFS, Towns.Kind.SE, true);
        assertTrue(mage78.stream().anyMatch(a -> a[0] == 1354));
        assertTrue(Deal.plan(at78, Towns.SE_BUFFS, Towns.Kind.SE, false).stream().anyMatch(a -> a[0] == 1354));
    }

    @Test
    void ppTownsHaveDistinctLevels() {
        final long distinct = Towns.ALL.stream().filter(s -> s.kind() == Towns.Kind.PP).map(Towns.Spot::level).distinct().count();
        assertEquals(5, distinct);
        assertEquals(7, Towns.ALL.size());
    }

    @Test
    void highestLearnedLevelOnly() {
        final List<BuffLevels.Learn> tree = List.of(new BuffLevels.Learn(1040, 1, 20), new BuffLevels.Learn(1040, 2, 40), new BuffLevels.Learn(1040, 3, 52), new BuffLevels.Learn(1086, 1, 44),
            new BuffLevels.Learn(1086, 2, 56), new BuffLevels.Learn(9999, 5, 1));
        final Map<Integer, Integer> at48 = BuffLevels.at(tree, Towns.PP_BUFFS, 48);
        assertEquals(2, at48.get(1040));
        assertEquals(1, at48.get(1086));
        assertFalse(at48.containsKey(9999));
        assertEquals(3, BuffLevels.at(tree, Towns.PP_BUFFS, 78).get(1040));
    }

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
        final Map<Integer, Integer> at40 = BuffLevels.at(tree, Towns.PP_BUFFS, 40);
        final Map<Integer, Integer> at52 = BuffLevels.at(tree, Towns.PP_BUFFS, 52);
        final Map<Integer, Integer> at62 = BuffLevels.at(tree, Towns.PP_BUFFS, 62);
        final Map<Integer, Integer> at76 = BuffLevels.at(tree, Towns.PP_BUFFS, 76);
        assertTrue(at40.containsKey(1204));
        assertFalse(at40.containsKey(1062));
        assertTrue(at52.containsKey(1062));
        assertTrue(at52.size() > at40.size());
        assertTrue(at62.containsKey(1388));
        assertTrue(at76.keySet().containsAll(at62.keySet()));
    }
}
