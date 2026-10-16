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
            final boolean large = e.hasAttribute("large") && Boolean.parseBoolean(e.getAttribute("large"));
            out.add(new ClanTemplate(id, name, level, seed, large));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("BotClans.xml has no <clan> rows");
        }
        return List.copyOf(out);
    }

    /**
     * Crest payload for {@code CrestTable.createCrest}: real DDS from {@code data/clans/crests/*.bmp}
     * ({@link ClanCrests}), or a synthetic DDS if that folder is empty.
     */
    public static byte[] crestBytes(long seed) {
        return ClanCrests.ddsForSeed(seed);
    }

    /** Deterministic fake DDS when no BMP crests are installed (tests / empty folder). */
    public static byte[] syntheticCrestBytes(long seed) {
        long s = seed == 0 ? 1 : seed;
        final int[] rgba = new int[ClanCrests.TEX_W * ClanCrests.TEX_H];
        for (int i = 0; i < rgba.length; i++) {
            s = (s * 6364136223846793005L) + 1;
            final int r = (int) ((s >>> 40) & 0xff);
            final int g = (int) ((s >>> 24) & 0xff);
            final int b = (int) ((s >>> 8) & 0xff);
            rgba[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        return ClanCrests.encodeDxt1Dds(rgba, ClanCrests.TEX_W, ClanCrests.TEX_H);
    }

    /** Level 2–7 for a small clan by target size (larger → higher). */
    public static int smallLevelForSize(int size, int min, int max) {
        if (max <= min) {
            return 4;
        }
        final double t = (size - min) / (double) (max - min);
        return 2 + (int) Math.round(t * 5); // 2..7
    }
}
