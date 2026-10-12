package org.l2start.economy.market.model;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import javax.xml.parsers.DocumentBuilderFactory;

import org.l2start.economy.market.model.StoreSpots.Spot;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * data/market-areas.xml: where a town's store places are generated and its fixed service traders; and the plan of a
 * new place (kind, theme, look of its trader).
 */
public final class MarketAreas {
    /** A fixed service trader: sells {@code items} at the NPC price from a large stock. */
    public record Service(Spot spot, int[] items) {
    }

    /**
     * Where the dwarf crafters sit (decision of 12.10.2026: around the Adventure Guildsman): a ring {@code rMin..rMax}
     * around the NPC at x, y, outside the traders' polygon and not in the sector of {@code front} degrees in front of
     * the NPC (heading), so the way to it stays free.
     */
    public record Crafters(int npcId, int x, int y, int z, int heading, int rMin, int rMax, int front, int spacing) implements SpotGenerator.Shape {
        @Override
        public boolean inside(int px, int py) {
            final double d = Math.hypot(px - x, py - y);
            if ((d < rMin) || (d > rMax)) {
                return false;
            }
            final double face = (heading * 2 * Math.PI) / 65536;
            final double to = Math.atan2(py - y, px - x);
            double diff = Math.abs(to - face) % (2 * Math.PI);
            if (diff > Math.PI) {
                diff = (2 * Math.PI) - diff;
            }
            return Math.toDegrees(diff) > (front / 2.0);
        }
    }

    private final Map<String, SpotGenerator.Area> areas;
    private final Map<String, List<Service>> services;
    private final Map<String, SpotGenerator.Polygon> polygons;
    private final Map<String, Crafters> crafters;
    private final Map<String, Integer> resourceCrafters = new LinkedHashMap<>();
    /** Profile of the static crafters of intermediate resources (decision of 12.10.2026, 15:04). */
    public static final String RESOURCES = "resources";

    private MarketAreas(Map<String, SpotGenerator.Area> areas, Map<String, List<Service>> services, Map<String, SpotGenerator.Polygon> polygons, Map<String, Crafters> crafters) {
        this.areas = areas;
        this.services = services;
        this.polygons = polygons;
        this.crafters = crafters;
    }

    public static MarketAreas load(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in);
        }
    }

    public static MarketAreas parse(InputStream in) throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final NodeList towns = f.newDocumentBuilder().parse(in).getElementsByTagName("town");
        final Map<String, SpotGenerator.Area> a = new LinkedHashMap<>();
        final Map<String, List<Service>> s = new LinkedHashMap<>();
        final Map<String, SpotGenerator.Polygon> polys = new LinkedHashMap<>();
        final Map<String, Crafters> crafts = new LinkedHashMap<>();
        final Map<String, Integer> res = new LinkedHashMap<>();
        for (int t = 0; t < towns.getLength(); t++) {
            final Element e = (Element) towns.item(t);
            final String name = e.getAttribute("name");
            if (e.hasAttribute("polygon")) {
                // the polygon gives the box and the centre the places are reached from
                final SpotGenerator.Polygon poly = SpotGenerator.Polygon.parse(e.getAttribute("polygon"));
                polys.put(key(name), poly);
                a.put(key(name), new SpotGenerator.Area(name, poly.cx(), poly.cy(), i(e, "z"), poly.minX(), poly.maxX(), poly.minY(), poly.maxY(), i(e, "dz"), i(e, "spacing"), i(e, "npcGap"), i(e, "doorGap"),
                    i(e, "reach")));
            } else {
                a.put(key(name), new SpotGenerator.Area(name, i(e, "cx"), i(e, "cy"), i(e, "z"), i(e, "minX"), i(e, "maxX"), i(e, "minY"), i(e, "maxY"), i(e, "dz"), i(e, "spacing"), i(e, "npcGap"), i(e, "doorGap"),
                    i(e, "reach")));
            }
            final NodeList cr = e.getElementsByTagName("crafters");
            if (cr.getLength() > 0) {
                final Element c = (Element) cr.item(0);
                crafts.put(key(name), new Crafters(i(c, "npc"), i(c, "x"), i(c, "y"), i(c, "z"), i(c, "heading"), i(c, "rMin"), i(c, "rMax"), i(c, "front"), i(c, "spacing")));
                if (c.hasAttribute("resources")) {
                    res.put(key(name), i(c, "resources"));
                }
            }
            final List<Service> list = new ArrayList<>();
            final NodeList sv = e.getElementsByTagName("service");
            for (int k = 0; k < sv.getLength(); k++) {
                final Element x = (Element) sv.item(k);
                final String[] ids = x.getAttribute("items").split(",");
                final int[] items = new int[ids.length];
                for (int j = 0; j < ids.length; j++) {
                    items[j] = Integer.parseInt(ids[j].trim());
                }
                list.add(new Service(new Spot(name, x.getAttribute("id"), i(x, "x"), i(x, "y"), i(x, "z"), i(x, "heading"), "SERVICE", "service", x.getAttribute("legacy"), x.getAttribute("race"),
                    x.getAttribute("sex"), i(x, "classId")), items));
            }
            s.put(key(name), Collections.unmodifiableList(list));
        }
        final MarketAreas out = new MarketAreas(Collections.unmodifiableMap(a), Collections.unmodifiableMap(s), Collections.unmodifiableMap(polys), Collections.unmodifiableMap(crafts));
        out.resourceCrafters.putAll(res);
        return out;
    }

    private static int i(Element e, String attr) {
        return Integer.parseInt(e.getAttribute(attr).trim());
    }

    static String key(String town) {
        return town.trim().toLowerCase(Locale.ROOT);
    }

    public SpotGenerator.Area area(String town) {
        return areas.get(key(town));
    }

    public List<Service> services(String town) {
        return services.getOrDefault(key(town), List.of());
    }

    /** The polygon the store places of a town stay in, or null (the area's box). */
    public SpotGenerator.Polygon polygon(String town) {
        return polygons.get(key(town));
    }

    /** Static crafters of intermediate resources in the crafters' ring of a town ({@code <crafters resources="2">}). */
    public int resourceCrafters(String town) {
        return (crafters(town) == null) ? 0 : resourceCrafters.getOrDefault(key(town), 0);
    }

    /** Where the crafters of a town sit, or null (among the other places). */
    public Crafters crafters(String town) {
        return crafters.get(key(town));
    }

    /**
     * A kept place still fits the layout of data/market-areas.xml: a crafter in its ring (when the town has one), any
     * other place inside the polygon (when the town has one). A place that does not fit means the layout changed and
     * the places of the town are generated again.
     */
    public boolean fits(Spot spot) {
        final Crafters c = crafters(spot.town());
        final SpotGenerator.Polygon poly = polygon(spot.town());
        if ("CRAFT".equals(spot.kind()) && (c != null)) {
            return c.inside(spot.x(), spot.y()) && ((poly == null) || !poly.inside(spot.x(), spot.y()));
        }
        return (poly == null) || poly.inside(spot.x(), spot.y());
    }

    /** The ring of the crafters without the traders' polygon. */
    public SpotGenerator.Shape crafterShape(String town) {
        final Crafters c = crafters(town);
        final SpotGenerator.Polygon poly = polygon(town);
        return (x, y) -> (c != null) && c.inside(x, y) && ((poly == null) || !poly.inside(x, y));
    }

    // ---- the plan of new places

    /** Themes of new places and their weights (sell themes more often than buy ones). */
    static final String[][] THEMES = { { "SELL", "weapons", "7" }, { "SELL", "armor", "7" }, { "SELL", "jewels", "3" }, { "SELL", "materials", "7" }, { "SELL", "recipes", "3" }, { "SELL", "scrolls", "2" },
        { "SELL", "misc", "2" }, { "SELL", "shots", "3" }, { "SELL", "potions", "3" }, { "BUY", "buy-materials", "4" }, { "BUY", "buy-crystals", "2" }, { "BUY", "buy-parts", "3" },
        { "BUY", "buy-scrolls", "2" }, { "BUY", "buy-dyes", "2" }, { "BUY", "buy-potions", "2" }, { "BUY", "buy-books", "2" }, { "BUY", "buy-misc", "2" } };
    /** 2nd professions a trader may show (all races; crafters are Warsmiths). */
    static final int[] PROFESSIONS = { 2, 3, 5, 6, 8, 9, 12, 13, 14, 16, 17, 20, 21, 23, 24, 27, 28, 30, 33, 34, 36, 37, 40, 41, 43, 46, 48, 51, 52, 55, 57 };
    public static final int WARSMITH = 57;
    /** A random 2nd profession of the trader looks (shoppers, 5e). */
    public static int randomProfession(Random rnd) {
        return PROFESSIONS[rnd.nextInt(PROFESSIONS.length)];
    }

    /** Every 8th new place without a former trader's look is a crafter. */
    static final int CRAFT_EVERY = 8;

    /**
     * Kind, theme and look of new places. Place {@code index} (0-based in the town) below {@code legacy.size()} takes
     * the former trader of that index (name, race, sex, class, kind, theme); the others get a theme by weight or are
     * crafters (dwarf Warsmiths, workshop), with a random profession and sex and a generated name.
     */
    public static List<Spot> plan(String town, List<SpotGenerator.Point> points, int firstIndex, List<Spot> legacy, Random rnd) {
        final List<Spot> out = new ArrayList<>();
        int total = 0;
        for (String[] t : THEMES) {
            total += Integer.parseInt(t[2]);
        }
        for (int k = 0; k < points.size(); k++) {
            final int index = firstIndex + k;
            final SpotGenerator.Point p = points.get(k);
            final String id = key(town) + "-" + String.format(Locale.ROOT, "%02d", index + 1);
            if (index < legacy.size()) {
                final Spot l = legacy.get(index);
                out.add(new Spot(town, id, p.x(), p.y(), p.z(), p.heading(), l.kind(), l.profile(), l.legacy(), l.race(), l.sex(), l.classId()));
                continue;
            }
            final String sex = rnd.nextBoolean() ? "FEMALE" : "MALE";
            if (((index - legacy.size()) % CRAFT_EVERY) == 3) {
                out.add(new Spot(town, id, p.x(), p.y(), p.z(), p.heading(), "CRAFT", "craft", "", "DWARF", sex, WARSMITH));
                continue;
            }
            int r = rnd.nextInt(total);
            String[] pick = THEMES[0];
            for (String[] t : THEMES) {
                r -= Integer.parseInt(t[2]);
                if (r < 0) {
                    pick = t;
                    break;
                }
            }
            out.add(new Spot(town, id, p.x(), p.y(), p.z(), p.heading(), pick[0], pick[1], "", "", sex, PROFESSIONS[rnd.nextInt(PROFESSIONS.length)]));
        }
        return out;
    }
}
