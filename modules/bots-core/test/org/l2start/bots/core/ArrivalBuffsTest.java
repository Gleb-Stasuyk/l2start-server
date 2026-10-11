package org.l2start.bots.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Phase 4b: buffs some bots arrive with. */
class ArrivalBuffsTest {
    static Set<Integer> ids(List<ArrivalBuffs.Buff> l) {
        return l.stream().map(ArrivalBuffs.Buff::skillId).collect(Collectors.toSet());
    }

    @Test
    void newbieHelperBuffsFollowTheStockLevelRules() {
        final Random r = new Random(1);
        assertEquals(Set.of(), ids(ArrivalBuffs.pick(7, false, 1, 1, r)));
        assertEquals(Set.of(4322), ids(ArrivalBuffs.pick(10, false, 1, 1, r)));
        assertEquals(Set.of(4322, 4323, 4324, 4325, 4326, 4327), ids(ArrivalBuffs.pick(15, false, 1, 1, r)));
        assertEquals(Set.of(4322, 4323, 4328, 4329, 4330, 4331), ids(ArrivalBuffs.pick(15, true, 1, 1, r)));
        assertEquals(Set.of(4322, 4323, 4324), ids(ArrivalBuffs.pick(22, false, 1, 1, r)));
        assertEquals(Set.of(4322), ids(ArrivalBuffs.pick(24, false, 1, 1, r)));
        assertEquals(Set.of(), ids(ArrivalBuffs.pick(25, false, 1, 1, r)));
        // 26-39: no Newbie Helper, no buffer either
        for (int lvl = 26; lvl < 40; lvl++) {
            assertEquals(Set.of(), ids(ArrivalBuffs.pick(lvl, false, 1, 1, r)), "lv " + lvl);
        }
        assertEquals(Set.of(), ids(ArrivalBuffs.pick(15, false, 0, 1, r)));
    }

    @Test
    void fromLevelFortyAnIncompleteSetOfABuffer() {
        final Random r = new Random(2);
        for (int i = 0; i < 200; i++) {
            final boolean mystic = (i % 2) == 0;
            final List<ArrivalBuffs.Buff> l = ArrivalBuffs.pick(40 + (i % 36), mystic, 1, 1, r);
            assertTrue((l.size() >= 2) && (l.size() <= 5), "size " + l.size());
            assertEquals(l.size(), ids(l).size(), "twice the same buff");
            final Set<Integer> pool = Stream.of(mystic ? ArrivalBuffs.MYSTIC : ArrivalBuffs.FIGHTER).map(b -> b[0]).collect(Collectors.toSet());
            assertTrue(pool.containsAll(ids(l)), ids(l).toString());
            for (ArrivalBuffs.Buff b : l) {
                assertTrue((b.left() > 0.05) && (b.left() <= 1), "left " + b.left());
            }
        }
        assertEquals(Set.of(), ids(ArrivalBuffs.pick(60, false, 1, 0, r)));
    }

    @Test
    void sharesAreRoughlyTheConfiguredOnes() {
        final Random r = new Random(3);
        int newbie = 0;
        int party = 0;
        for (int i = 0; i < 4000; i++) {
            newbie += ArrivalBuffs.pick(15, false, 0.6, 0.25, r).isEmpty() ? 0 : 1;
            party += ArrivalBuffs.pick(55, false, 0.6, 0.25, r).isEmpty() ? 0 : 1;
        }
        assertTrue(Math.abs((newbie / 4000.0) - 0.6) < 0.04, "newbie " + newbie);
        assertTrue(Math.abs((party / 4000.0) - 0.25) < 0.04, "party " + party);
        assertArrayEquals(new double[] { 0.6, 0.25 }, ArrivalBuffs.shares(null));
        assertArrayEquals(new double[] { 0.3, 0.1 }, ArrivalBuffs.shares("0.3, 0.1"));
        assertArrayEquals(new double[] { 0, 0 }, ArrivalBuffs.shares("0,0"));
        assertArrayEquals(new double[] { 0.6, 0.25 }, ArrivalBuffs.shares("x"));
    }

    /** Every buff id and level exists in the CT0 skill data. */
    @Test
    void skillIdsAndLevelsExistInTheGameData() throws Exception {
        final Pattern p = Pattern.compile("<skill id=\"(\\d+)\" levels=\"(\\d+)\" name=\"([^\"]*)\"");
        final java.util.Map<Integer, Integer> levels = new java.util.HashMap<>();
        try (Stream<Path> files = Files.list(Path.of("core/dist/game/data/stats/skills"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".xml")).toList()) {
                final Matcher m = p.matcher(Files.readString(f));
                while (m.find()) {
                    levels.put(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                }
            }
        }
        final Set<int[]> all = new HashSet<>(List.of(ArrivalBuffs.FIGHTER));
        all.addAll(List.of(ArrivalBuffs.MYSTIC));
        for (int[] b : all) {
            assertTrue(levels.getOrDefault(b[0], 0) >= b[1], "skill " + b[0] + " lv " + b[1] + " not in CT0 (has " + levels.get(b[0]) + ")");
        }
        for (int id = 4322; id <= 4331; id++) {
            assertTrue(levels.containsKey(id), "newbie buff " + id);
        }
    }
}
