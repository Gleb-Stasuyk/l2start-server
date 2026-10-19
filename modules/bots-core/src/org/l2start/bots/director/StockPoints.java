package org.l2start.bots.director;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
public record StockPoints(List<Pos> creation, List<Pos> respawn, Map<Pos, Integer> starts) {

    public StockPoints(List<Pos> creation, List<Pos> respawn) {
        this(creation, respawn, Map.of());
    }

    /** @param data the datapack {@code data} folder */
    public static StockPoints load(Path data) throws Exception {
        final Set<Pos> creation = new LinkedHashSet<>();
        final Set<Pos> respawn = new LinkedHashSet<>();
        final Map<Pos, Integer> starts = new LinkedHashMap<>();
        for (Path f : xml(data.resolve("stats/players/templates"))) {
            final Document d = parse(f);
            final NodeList ids = d.getElementsByTagName("classId");
            final int race = ids.getLength() == 0 ? -1 : StarterZones.raceOfStartClass(Integer.parseInt(ids.item(0).getTextContent().trim()));
            final NodeList cps = d.getElementsByTagName("creationPoints");
            for (int i = 0; i < cps.getLength(); i++) {
                final NodeList nodes = ((Element) cps.item(i)).getElementsByTagName("node");
                for (int k = 0; k < nodes.getLength(); k++) {
                    final Element n = (Element) nodes.item(k);
                    final Pos p = new Pos(num(n, "x"), num(n, "y"), num(n, "z"));
                    creation.add(p);
                    if (race >= 0) {
                        starts.put(p, race); // a starting class: where new characters of that race appear
                    }
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
        return new StockPoints(List.copyOf(creation), List.copyOf(respawn), Map.copyOf(starts));
    }

    /** Gremlin (stock npc 18342, level 1) spawn territories of the datapack: their centres (S6: newbie companions hunt there). */
    public static final int GREMLIN = 18342;

    public static List<Pos> gremlins(Path data) throws Exception {
        final List<Pos> out = new ArrayList<>();
        for (Path f : xml(data.resolve("spawns"))) {
            if (!Files.readString(f).contains("\"" + GREMLIN + "\"")) {
                continue;
            }
            final NodeList spawns = parse(f).getElementsByTagName("spawn");
            for (int i = 0; i < spawns.getLength(); i++) {
                final Element s = (Element) spawns.item(i);
                boolean gremlin = false;
                final NodeList npcs = s.getElementsByTagName("npc");
                for (int k = 0; k < npcs.getLength(); k++) {
                    gremlin |= String.valueOf(GREMLIN).equals(((Element) npcs.item(k)).getAttribute("id"));
                }
                final NodeList terr = s.getElementsByTagName("territory");
                if (!gremlin || (terr.getLength() == 0)) {
                    continue;
                }
                final Element t = (Element) terr.item(0);
                final NodeList nodes = t.getElementsByTagName("node");
                long sx = 0;
                long sy = 0;
                for (int k = 0; k < nodes.getLength(); k++) {
                    sx += num((Element) nodes.item(k), "x");
                    sy += num((Element) nodes.item(k), "y");
                }
                if (nodes.getLength() > 0) {
                    final int z = (num(t, "minZ") + num(t, "maxZ")) / 2;
                    out.add(new Pos((int) (sx / nodes.getLength()), (int) (sy / nodes.getLength()), z));
                }
            }
        }
        return out;
    }

    /** The nearest of {@code spots} to {@code p} within {@code max}, or null. */
    public static Pos nearest(List<Pos> spots, Pos p, int max) {
        Pos best = null;
        for (Pos s : spots) {
            if ((s.distance2d(p) <= max) && ((best == null) || (s.distance2d(p) < best.distance2d(p)))) {
                best = s;
            }
        }
        return best;
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
