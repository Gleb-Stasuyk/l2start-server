package org.l2start.bots.chat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Phase 6a: phrase banks RU/EN by intent (data/chat/phrases.xml, the "line -> answers" idea of Living Worlds
 * FakePlayerChatData with intent and language). Format:
 * <pre>
 * &lt;intent id="PARTY_HELLO"&gt;&lt;ru&gt;ку|привет&lt;/ru&gt;&lt;en&gt;hi|hey&lt;/en&gt;&lt;/intent&gt;
 * </pre>
 * Immutable, shared by all bots.
 */
public final class Phrases {
    public static final Phrases EMPTY = new Phrases(Map.of());

    private final Map<String, Map<String, List<String>>> banks;

    Phrases(Map<String, Map<String, List<String>>> banks) {
        this.banks = banks;
    }

    public static Phrases load(Path xml) throws Exception {
        try (InputStream in = Files.newInputStream(xml)) {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final Document d = f.newDocumentBuilder().parse(in);
            final Map<String, Map<String, List<String>>> m = new HashMap<>();
            final NodeList intents = d.getDocumentElement().getElementsByTagName("intent");
            for (int i = 0; i < intents.getLength(); i++) {
                final Element e = (Element) intents.item(i);
                final String id = e.getAttribute("id").trim();
                if (id.isEmpty() || m.containsKey(id)) {
                    throw new IllegalArgumentException("phrases: empty or duplicate intent id '" + id + "'");
                }
                final Map<String, List<String>> byLang = new HashMap<>();
                for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element l) {
                        final List<String> lines = new ArrayList<>();
                        for (String s : l.getTextContent().split("\\|")) {
                            final String t = s.strip();
                            if (!t.isEmpty()) {
                                if (t.length() > 90) {
                                    throw new IllegalArgumentException("phrases: line too long in " + id + ": " + t);
                                }
                                if (!lines.contains(t)) {
                                    lines.add(t);
                                }
                            }
                        }
                        byLang.put(l.getTagName().toUpperCase(), List.copyOf(lines));
                    }
                }
                m.put(id, Map.copyOf(byLang));
            }
            return new Phrases(Map.copyOf(m));
        }
    }

    public Set<String> intents() {
        return banks.keySet();
    }

    /** The lines of an intent in a language (RU, EN); empty when there are none. */
    public List<String> lines(String intent, String lang) {
        final Map<String, List<String>> b = banks.get(intent);
        return b == null ? List.of() : b.getOrDefault(lang, List.of());
    }

    /** RU or EN for a persona language: MIX writes Russian with English now and then (30 %). */
    public static String langOf(String personaLang, Random rnd) {
        if ("EN".equals(personaLang)) {
            return "EN";
        }
        if ("MIX".equals(personaLang)) {
            return rnd.nextDouble() < 0.3 ? "EN" : "RU";
        }
        return "RU";
    }

    /** {player} etc. filled in; unknown variables stay as they are. */
    public static String fill(String line, Map<String, String> vars) {
        String s = line;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }
}
