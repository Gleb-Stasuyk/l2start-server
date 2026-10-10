package org.l2start.bots.brain.basic;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.l2start.bots.api.Pos;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** data/levelzones.xml: hunting grounds near the starting villages (generated from stock spawns by tools/gen-levelzones.py). */
public final class LevelZones {
    private final List<LevelZone> zones;

    public LevelZones(List<LevelZone> zones) {
        this.zones = List.copyOf(zones);
    }

    public static LevelZones load(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            final NodeList nodes = f.newDocumentBuilder().parse(in).getDocumentElement().getElementsByTagName("zone");
            final List<LevelZone> l = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                final Element e = (Element) nodes.item(i);
                final Set<Integer> ids = new HashSet<>();
                for (String s : e.getAttribute("npcIds").split(",")) {
                    if (!s.isBlank()) {
                        ids.add(Integer.parseInt(s.trim()));
                    }
                }
                final LevelZone z = new LevelZone(e.getAttribute("id"), e.getAttribute("village"), new Pos(num(e, "x"), num(e, "y"), num(e, "z")), num(e, "radius"), num(e, "minLevel"),
                    num(e, "maxLevel"), num(e, "monsters"), Boolean.parseBoolean(e.getAttribute("aggressive")), Set.copyOf(ids));
                if ((z.minLevel() < 1) || (z.maxLevel() < z.minLevel()) || (z.radius() <= 0)) {
                    throw new IllegalArgumentException("bad zone " + z.id() + " in " + file);
                }
                l.add(z);
            }
            if (l.isEmpty()) {
                throw new IllegalArgumentException("no zones in " + file);
            }
            return new LevelZones(l);
        }
    }

    private static int num(Element e, String attr) {
        return Integer.parseInt(e.getAttribute(attr).trim());
    }

    public List<LevelZone> all() {
        return zones;
    }

    /**
     * A hunting ground for this level: zones of the bot's village that fit the level, best first (monster levels close
     * to the bot's, not too far away); one of the best three is taken at random so bots spread out. Falls back to the
     * nearest fitting zone of any village.
     */
    public LevelZone pick(int level, String village, Pos from, Random rnd) {
        List<LevelZone> fit = zones.stream().filter(z -> z.fits(level) && z.village().equals(village)).toList();
        if (fit.isEmpty()) {
            fit = zones.stream().filter(z -> z.fits(level)).sorted(Comparator.comparingDouble(z -> z.center().distance2d(from))).limit(3).toList();
        }
        if (fit.isEmpty()) {
            return null;
        }
        final List<LevelZone> best = new ArrayList<>(fit);
        best.sort(Comparator.comparingDouble(z -> score(z, level, from)));
        final double limit = score(best.get(0), level, from) + 0.75; // only about as good as the best
        int n = 1;
        while ((n < Math.min(3, best.size())) && (score(best.get(n), level, from) <= limit)) {
            n++;
        }
        return best.get(rnd.nextInt(n));
    }

    /** Lower is better: level fit first, distance second (8000 units weigh like one level). */
    static double score(LevelZone z, int level, Pos from) {
        final double target = level - 0.5; // slightly weaker monsters: x1 starter bots without potions
        return Math.abs(Math.min(z.maxLevel(), Math.max(z.minLevel(), target)) - target) + (Math.abs(z.mid() - target) * 0.3) + (z.center().distance2d(from) / 8000.0);
    }
}
