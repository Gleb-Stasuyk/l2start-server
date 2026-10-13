package org.l2start.modules.townbuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TownBufferTest {
    @Test
    void pricesOfTheSpec() {
        assertEquals(20_000, Towns.price(48));
        assertEquals(62_000, Towns.price(55));
        assertEquals(116_000, Towns.price(64));
        assertEquals(176_000, Towns.price(74));
        assertEquals(200_000, Towns.price(78));
        assertEquals("Баф 62k", Towns.title(62_000));
    }

    @Test
    void npcTitlesAndSpawnsMatch() throws Exception {
        final String npcs = Files.readString(Path.of("modules/town-buffer/data/npcs/town_buffers.xml"));
        final String spawns = Files.readString(Path.of("modules/town-buffer/data/spawns/town_buffers.xml"));
        for (Towns.Town t : Towns.ALL) {
            assertTrue(npcs.contains("id=\"" + t.npcId() + "\"") && npcs.contains("name=\"" + t.name() + " Buffer\"") && npcs.contains("title=\"" + Towns.title(t.price()) + "\""), t.name());
            assertTrue(spawns.contains("npc id=\"" + t.npcId() + "\""), t.name());
            assertTrue((t.npcId() >= 95200) && (t.npcId() <= 95299));
        }
    }

    @Test
    void highestLearnedLevelOnly() {
        final List<BuffLevels.Learn> tree = List.of(new BuffLevels.Learn(1040, 1, 20), new BuffLevels.Learn(1040, 2, 40), new BuffLevels.Learn(1040, 3, 52), new BuffLevels.Learn(1086, 1, 44),
            new BuffLevels.Learn(1086, 2, 56), new BuffLevels.Learn(1355, 1, 78), new BuffLevels.Learn(9999, 5, 1));
        final Map<Integer, Integer> at48 = BuffLevels.at(tree, Towns.BUFFS, 48);
        assertEquals(2, at48.get(1040));
        assertEquals(1, at48.get(1086));
        assertFalse(at48.containsKey(1355));
        assertFalse(at48.containsKey(9999));
        final Map<Integer, Integer> at78 = BuffLevels.at(tree, Towns.BUFFS, 78);
        assertEquals(3, at78.get(1040));
        assertEquals(2, at78.get(1086));
        assertEquals(1, at78.get(1355));
    }
}
