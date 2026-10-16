package org.l2start.bots.clan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Loads {@link ClanTemplate} list from BotClans.xml. */
public final class ClanTemplates {
    private ClanTemplates() {
    }

    public static List<ClanTemplate> load(Path xml) throws Exception {
        if (!Files.isRegularFile(xml)) {
            throw new IllegalArgumentException("no BotClans.xml at " + xml);
        }
        final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile());
        doc.getDocumentElement().normalize();
        final List<ClanTemplate> out = new ArrayList<>();
        final NodeList nodes = doc.getElementsByTagName("clan");
        for (int i = 0; i < nodes.getLength(); i++) {
            final Node n = nodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            final Element e = (Element) n;
            final String id = e.getAttribute("id");
            final String name = e.getAttribute("name");
            final int level = Integer.parseInt(e.getAttribute("level"));
            final long seed = e.hasAttribute("crestSeed") ? Long.parseLong(e.getAttribute("crestSeed")) : id.hashCode();
            out.add(new ClanTemplate(id, name, level, seed));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("BotClans.xml has no <clan> rows");
        }
        return List.copyOf(out);
    }

    /** Deterministic 16×12-ish crest payload for {@code CrestTable.createCrest} (format not validated by stock). */
    public static byte[] crestBytes(long seed) {
        final byte[] data = new byte[256];
        long s = seed == 0 ? 1 : seed;
        for (int i = 0; i < data.length; i++) {
            s = (s * 6364136223846793005L) + 1;
            data[i] = (byte) (s >>> 56);
        }
        // DDS magic so tools can recognise a crest blob
        data[0] = 'D';
        data[1] = 'D';
        data[2] = 'S';
        data[3] = ' ';
        return data;
    }
}
