package org.l2start.platform.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.l2start.platform.TestModules;

class IdScannerTest {
    @TempDir
    Path dir;

    @Test
    void npcIdsFromXml() throws Exception {
        TestModules.write(dir.resolve("npcs/a.xml"), "<list>\n<npc id=\"95000\" displayId=\"30115\" type=\"Folk\">\n<skill id=\"4045\"/></npc>\n<npc displayId=\"1\" id=\"95001\" /></list>");
        TestModules.write(dir.resolve("npcs/sub/b.xml"), "<list><npc id=\"95002\"/></list>");
        TestModules.write(dir.resolve("npcs/readme.txt"), "<npc id=\"1\"/>");
        final List<String> errors = new ArrayList<>();
        assertEquals(Set.of(95000, 95001, 95002), IdScanner.scan(DataType.NPCS, dir.resolve("npcs"), errors));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void duplicateNpcId() throws Exception {
        TestModules.write(dir.resolve("npcs/a.xml"), "<npc id=\"5\"/><npc id=\"5\"/>");
        final List<String> errors = new ArrayList<>();
        IdScanner.scan(DataType.NPCS, dir.resolve("npcs"), errors);
        assertEquals(1, errors.size());
    }

    @Test
    void multisellIdsFromFileNames() throws Exception {
        TestModules.write(dir.resolve("ms/95000.xml"), "<list/>");
        TestModules.write(dir.resolve("ms/0095001.xml"), "<list/>");
        TestModules.write(dir.resolve("ms/shop.xml"), "<list/>");
        final List<String> errors = new ArrayList<>();
        assertEquals(Set.of(95000, 95001), IdScanner.scan(DataType.MULTISELL, dir.resolve("ms"), errors));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("shop.xml"));
    }

    @Test
    void typesWithoutIds() throws Exception {
        TestModules.write(dir.resolve("sp/a.xml"), "<npc id=\"1\"/>");
        assertTrue(IdScanner.scan(DataType.SPAWNS, dir.resolve("sp"), new ArrayList<>()).isEmpty());
        assertTrue(IdScanner.scan(DataType.NPCS, dir.resolve("missing"), new ArrayList<>()).isEmpty());
    }
}
