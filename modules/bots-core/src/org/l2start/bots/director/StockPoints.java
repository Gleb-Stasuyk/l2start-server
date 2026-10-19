package org.l2start.bots.director;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.l2start.bots.api.Pos;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The fixed points where the stock server puts characters (read from the datapack): creation points of the player
 * templates ({@code stats/players/templates}) and the respawn points of the map regions ({@code mapregion}, chaotic
 * ones aside). Bots used to pile up on them.
 */
public record StockPoints(List<Pos> creation, List<Pos> respawn) {

    /** @param data the datapack {@code data} folder */
    public static StockPoints load(Path data) throws Exception {
        final Set<Pos> creation = new LinkedHashSet<>();
        final Set<Pos> respawn = new LinkedHashSet<>();
        for (Path f : xml(data.resolve("stats/players/templates"))) {
            final Document d = parse(f);
            final NodeList cps = d.getElementsByTagName("creationPoints");
            for (int i = 0; i < cps.getLength(); i++) {
                final NodeList nodes = ((Element) cps.item(i)).getElementsByTagName("node");
                for (int k = 0; k < nodes.getLength(); k++) {
                    final Element n = (Element) nodes.item(k);
                    creation.add(new Pos(num(n, "x"), num(n, "y"), num(n, "z")));
                }
            }
        }
        for (Path f : xml(data.resolve("mapregion"))) {
            final NodeList rps = parse(f).getElementsByTagName("respawnPoint");
            for (int i = 0; i < rps.getLength(); i++) {
                final Element n = (Element) rps.item(i);
                if (!"true".equalsIgnoreCase(n.getAttribute("isChaotic"))) {
                    respawn.add(new Pos(num(n, "X"), num(n, "Y"), num(n, "Z")));
                }
            }
        }
        return new StockPoints(List.copyOf(creation), List.copyOf(respawn));
    }

    private static List<Path> xml(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return new ArrayList<>(s.filter(p -> p.toString().endsWith(".xml")).sorted().toList());
        }
    }

    private static Document parse(Path f) throws Exception {
        final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (InputStream in = Files.newInputStream(f)) {
            return dbf.newDocumentBuilder().parse(in);
        }
    }

    private static int num(Element e, String attr) {
        return Integer.parseInt(e.getAttribute(attr).trim());
    }
}
