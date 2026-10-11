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
import java.util.function.Predicate;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Bot store places per town (data/store-spots.xml, phase 5b). Immutable. */
public final class StoreSpots {
    /**
     * One place. {@code kind} SELL / BUY / PACKAGE / CRAFT and {@code profile} (assortment theme) are hints for the trader
     * who takes it; {@code legacy} names the former FakePlayer trader of the place (name, race, sex, class).
     */
    public record Spot(String town, String id, int x, int y, int z, int heading, String kind, String profile, String legacy, String race, String sex, int classId) {
    }

    private final Map<String, List<Spot>> byTown;

    private StoreSpots(Map<String, List<Spot>> byTown) {
        this.byTown = byTown;
    }

    public static StoreSpots load(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in);
        }
    }

    public static StoreSpots parse(InputStream in) throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final NodeList towns = f.newDocumentBuilder().parse(in).getElementsByTagName("town");
        final Map<String, List<Spot>> m = new LinkedHashMap<>();
        for (int t = 0; t < towns.getLength(); t++) {
            final Element town = (Element) towns.item(t);
            final String name = town.getAttribute("name");
            final List<Spot> list = new ArrayList<>();
            final NodeList spots = town.getElementsByTagName("spot");
            for (int k = 0; k < spots.getLength(); k++) {
                final Element e = (Element) spots.item(k);
                list.add(new Spot(name, e.getAttribute("id"), Integer.parseInt(e.getAttribute("x")), Integer.parseInt(e.getAttribute("y")), Integer.parseInt(e.getAttribute("z")),
                    Integer.parseInt(e.getAttribute("heading")), e.getAttribute("kind"), e.getAttribute("profile"), e.getAttribute("legacy"), e.getAttribute("race"), e.getAttribute("sex"),
                    e.getAttribute("classId").isEmpty() ? -1 : Integer.parseInt(e.getAttribute("classId"))));
            }
            m.computeIfAbsent(key(name), x -> new ArrayList<>()).addAll(list);
        }
        m.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return new StoreSpots(Collections.unmodifiableMap(m));
    }

    public List<Spot> spots(String town) {
        return byTown.getOrDefault(key(town), List.of());
    }

    public int towns() {
        return byTown.size();
    }

    /**
     * The places a town uses: the first {@code quota.stores(town)} of its places (file order, so the same places every
     * time) that {@code usable} accepts (in the game: not in a NoStoreZone). A town with quota 0 has none.
     */
    public List<Spot> active(String town, TownQuota quota, Predicate<Spot> usable) {
        final int n = quota.stores(town);
        final List<Spot> out = new ArrayList<>();
        for (Spot s : spots(town)) {
            if (out.size() >= n) {
                break;
            }
            if (usable.test(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static String key(String town) {
        return town.trim().toLowerCase(Locale.ROOT);
    }
}
