package org.l2start.modules.starterkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.l2start.platform.loader.DataType;
import org.l2start.platform.loader.ManifestReader;
import org.l2start.platform.loader.ModuleManifest;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Checks the starter-kit data against the stock datapack in core/ (tests run from the repository root). */
class StarterKitDataTest {
    private static final Path MODULE = Path.of("modules/starter-kit");
    private static final Path FILE = MODULE.resolve("data/initialEquipment/initialEquipment.xml");
    private static final Path STOCK = Path.of("core/dist/game/data/stats/players/initialEquipment.xml");
    private static final Path ITEMS = Path.of("core/dist/game/data/stats/items");
    private static final Path XSD = Path.of("core/dist/game/data/xsd/initialEquipment.xsd");

    /** item id -> bodypart ("" when the item has none) */
    private static final Map<Integer, String> STOCK_ITEMS = new HashMap<>();

    @BeforeAll
    static void loadItems() throws Exception {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(ITEMS, "*.xml")) {
            for (Path p : ds) {
                final NodeList items = parse(p).getElementsByTagName("item");
                for (int i = 0; i < items.getLength(); i++) {
                    final Element item = (Element) items.item(i);
                    String body = "";
                    final NodeList sets = item.getElementsByTagName("set");
                    for (int j = 0; j < sets.getLength(); j++) {
                        final Element set = (Element) sets.item(j);
                        if ("bodypart".equals(set.getAttribute("name"))) {
                            body = set.getAttribute("val");
                        }
                    }
                    STOCK_ITEMS.put(Integer.parseInt(item.getAttribute("id")), body);
                }
            }
        }
        assertTrue(STOCK_ITEMS.size() > 5000, "stock items found: " + STOCK_ITEMS.size());
    }

    private static Document parse(Path p) throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(p.toFile());
    }

    /** classId -> list of {itemId, count, equipped(0/1)} in file order */
    private static Map<Integer, List<int[]>> equipment(Path p) throws Exception {
        final Map<Integer, List<int[]>> out = new LinkedHashMap<>();
        final NodeList eq = parse(p).getElementsByTagName("equipment");
        for (int i = 0; i < eq.getLength(); i++) {
            final Element e = (Element) eq.item(i);
            final List<int[]> list = new ArrayList<>();
            final NodeList items = e.getElementsByTagName("item");
            for (int j = 0; j < items.getLength(); j++) {
                final Element it = (Element) items.item(j);
                list.add(new int[] { Integer.parseInt(it.getAttribute("id")), Integer.parseInt(it.getAttribute("count")), Boolean.parseBoolean(it.getAttribute("equipped")) ? 1 : 0 });
            }
            out.put(Integer.parseInt(e.getAttribute("classId")), list);
        }
        return out;
    }

    @Test
    void manifestDeclaresInitialEquipment() throws Exception {
        final ModuleManifest m = ManifestReader.read(MODULE);
        assertEquals("starter-kit", m.id());
        assertEquals("data/initialEquipment", m.data().get(DataType.INITIAL_EQUIPMENT));
    }

    @Test
    void validAgainstStockXsd() throws Exception {
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(XSD.toFile()).newValidator().validate(new StreamSource(new File(FILE.toString())));
    }

    @Test
    void coversExactlyTheNineStartingClasses() throws Exception {
        final Set<Integer> stock = new TreeSet<>(equipment(STOCK).keySet());
        assertEquals(Set.of(0, 10, 18, 25, 31, 38, 44, 49, 53), stock);
        assertEquals(stock, new TreeSet<>(equipment(FILE).keySet()));
    }

    @Test
    void everyItemExistsAndEquippedItemsHaveASlot() throws Exception {
        for (Map.Entry<Integer, List<int[]>> e : equipment(FILE).entrySet()) {
            for (int[] it : e.getValue()) {
                final String body = STOCK_ITEMS.get(it[0]);
                assertNotNull(body, "class " + e.getKey() + ": item " + it[0] + " does not exist in stock items");
                assertTrue(it[1] > 0, "class " + e.getKey() + ": item " + it[0] + " count");
                if (it[2] == 1) {
                    assertFalse(body.isEmpty() || "none".equals(body), "class " + e.getKey() + ": equipped item " + it[0] + " has no bodypart");
                }
            }
        }
    }

    @Test
    void contentAsOnGlebsBuild() throws Exception {
        final Map<Integer, List<int[]>> eq = equipment(FILE);
        for (int fighter : new int[] { 0, 18, 31, 44 }) {
            assertTrue(has(eq.get(fighter), 2, 1, true), "Long Sword for " + fighter);
            assertTrue(has(eq.get(fighter), 1835, 1000, false), "1000 Soulshot NG for " + fighter);
            assertTrue(has(eq.get(fighter), 114, 1, true), "Earring of Strength for " + fighter);
        }
        assertTrue(has(eq.get(53), 4221, 1, true), "dwarf: Ubiquitous Axe");
        assertTrue(has(eq.get(53), 1835, 1000, false), "dwarf: 1000 Soulshot NG");
        assertFalse(has(eq.get(53), 2, 1, true), "dwarf has no Long Sword");
        for (int mystic : new int[] { 10, 25, 38, 49 }) {
            assertTrue(has(eq.get(mystic), 177, 1, true), "Mage Staff for " + mystic);
            assertTrue(has(eq.get(mystic), 3947, 1000, false), "1000 Blessed Spiritshot NG for " + mystic);
            assertTrue(has(eq.get(mystic), 115, 1, true), "Earring of Wisdom for " + mystic);
        }
    }

    private static boolean has(List<int[]> list, int id, int count, boolean equipped) {
        return list.stream().anyMatch(i -> i[0] == id && i[1] == count && i[2] == (equipped ? 1 : 0));
    }
}
