package org.l2start.economy.market.model;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Item ids sold by stock NPCs with title containing "Grocer" (buylist). Bot shops / WTS / WTB / trade skip these;
 * service traders (4UDO) are exempt.
 */
public final class GroceryItems {
    private final Set<Integer> ids;

    private GroceryItems(Set<Integer> ids) {
        this.ids = Collections.unmodifiableSet(ids);
    }

    public static GroceryItems empty() {
        return new GroceryItems(Set.of());
    }

    public boolean contains(int itemId) {
        return ids.contains(itemId);
    }

    public Set<Integer> ids() {
        return ids;
    }

    public int size() {
        return ids.size();
    }

    /**
     * Pure: given NPC id → title and buylist file NPC→item ids, collect grocery item ids.
     */
    public static GroceryItems of(Map<Integer, String> npcTitles, Map<Integer, Set<Integer>> npcItems) {
        final Set<Integer> out = new HashSet<>();
        if ((npcTitles == null) || (npcItems == null)) {
            return new GroceryItems(out);
        }
        for (Map.Entry<Integer, String> e : npcTitles.entrySet()) {
            final String t = e.getValue();
            if ((t == null) || !t.toLowerCase(java.util.Locale.ROOT).contains("grocer")) {
                continue;
            }
            final Set<Integer> items = npcItems.get(e.getKey());
            if (items != null) {
                out.addAll(items);
            }
        }
        return new GroceryItems(out);
    }

    /** Load a shipped list (one item id per line; {@code #} comments). Prefer this in Docker where stock data paths differ. */
    public static GroceryItems loadList(Path file) throws Exception {
        final Set<Integer> out = new HashSet<>();
        if ((file == null) || !Files.isRegularFile(file)) {
            return new GroceryItems(out);
        }
        for (String line : Files.readAllLines(file)) {
            final String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            out.add(Integer.parseInt(t));
        }
        return new GroceryItems(out);
    }

    /** Load from stock {@code stats/npcs} titles + {@code buylists/*.xml}. */
    public static GroceryItems load(Path dataRoot) throws Exception {
        final Path npcs = dataRoot.resolve("stats").resolve("npcs");
        final Path buys = dataRoot.resolve("buylists");
        final Map<Integer, String> titles = new HashMap<>();
        if (Files.isDirectory(npcs)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(npcs, "*.xml")) {
                for (Path f : ds) {
                    try (InputStream in = Files.newInputStream(f)) {
                        final NodeList list = db().parse(in).getElementsByTagName("npc");
                        for (int i = 0; i < list.getLength(); i++) {
                            final Element e = (Element) list.item(i);
                            final String id = e.getAttribute("id");
                            if (!id.isEmpty()) {
                                titles.put(Integer.parseInt(id), e.getAttribute("title"));
                            }
                        }
                    }
                }
            }
        }
        final Map<Integer, Set<Integer>> byNpc = new HashMap<>();
        if (Files.isDirectory(buys)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(buys, "*.xml")) {
                for (Path f : ds) {
                    try (InputStream in = Files.newInputStream(f)) {
                        final Element root = db().parse(in).getDocumentElement();
                        final Set<Integer> sellers = new HashSet<>();
                        final NodeList npcNodes = root.getElementsByTagName("npc");
                        for (int i = 0; i < npcNodes.getLength(); i++) {
                            final String t = npcNodes.item(i).getTextContent();
                            if ((t != null) && t.trim().matches("\\d+")) {
                                sellers.add(Integer.parseInt(t.trim()));
                            }
                        }
                        final Set<Integer> items = new HashSet<>();
                        final NodeList itemNodes = root.getElementsByTagName("item");
                        for (int i = 0; i < itemNodes.getLength(); i++) {
                            final Element it = (Element) itemNodes.item(i);
                            final String id = it.getAttribute("id");
                            if (!id.isEmpty()) {
                                items.add(Integer.parseInt(id));
                            }
                        }
                        for (Integer s : sellers) {
                            byNpc.computeIfAbsent(s, k -> new HashSet<>()).addAll(items);
                        }
                    }
                }
            }
        }
        return of(titles, byNpc);
    }

    private static javax.xml.parsers.DocumentBuilder db() throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder();
    }
}
