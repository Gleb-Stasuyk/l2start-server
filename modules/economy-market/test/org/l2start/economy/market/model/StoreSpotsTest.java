package org.l2start.economy.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class StoreSpotsTest {
    static final Path SPOTS = Path.of("modules/economy-market/data/store-spots.xml");

    @Test
    void giranHasItsTwentyOnePlacesAndOnlyGiranIsOn() throws Exception {
        final StoreSpots s = StoreSpots.load(SPOTS);
        assertEquals(21, s.spots("Giran").size());
        assertEquals("Xevoq", s.spots("giran").get(0).legacy());
        final TownQuota q = TownQuota.parse("Giran:20,Dion:0,Aden:0");
        assertEquals(20, s.active("Giran", q, x -> true).size());
        assertEquals(0, s.active("Dion", q, x -> true).size(), "other towns are off");
        assertEquals(0, s.active("Aden", TownQuota.parse("Aden:5"), x -> true).size(), "no places listed yet");
        assertEquals("giran-02", s.active("Giran", q, x -> !x.id().equals("giran-01")).get(0).id(), "an unusable place is skipped");
    }

    @Test
    void placesAreApartAndOutOfTheNoStoreZones() throws Exception {
        final List<StoreSpots.Spot> spots = StoreSpots.load(SPOTS).spots("Giran");
        for (int a = 0; a < spots.size(); a++) {
            for (int b = a + 1; b < spots.size(); b++) {
                final double d = Math.hypot(spots.get(a).x() - spots.get(b).x(), spots.get(a).y() - spots.get(b).y());
                assertTrue(d >= 60, spots.get(a).id() + " and " + spots.get(b).id() + " overlap");
            }
        }
        final List<int[]> zones = noStoreCuboids();
        assertTrue(zones.size() > 10);
        for (StoreSpots.Spot s : spots) {
            for (int[] z : zones) {
                final boolean inside = (s.x() >= z[0]) && (s.x() <= z[1]) && (s.y() >= z[2]) && (s.y() <= z[3]) && (s.z() >= z[4]) && (s.z() <= z[5]);
                assertTrue(!inside, s.id() + " is in a NoStoreZone");
            }
        }
    }

    /** Stock zones/custom_no_store.xml cuboids: minX, maxX, minY, maxY, minZ, maxZ (min and max may come swapped). */
    static List<int[]> noStoreCuboids() throws Exception {
        final List<int[]> out = new ArrayList<>();
        final NodeList zones = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("core/dist/game/data/zones/custom_no_store.xml").toFile()).getElementsByTagName("zone");
        for (int k = 0; k < zones.getLength(); k++) {
            final Element z = (Element) zones.item(k);
            if (!"Cuboid".equals(z.getAttribute("shape"))) {
                continue;
            }
            final NodeList n = z.getElementsByTagName("node");
            final int x1 = Integer.parseInt(((Element) n.item(0)).getAttribute("X"));
            final int y1 = Integer.parseInt(((Element) n.item(0)).getAttribute("Y"));
            final int x2 = Integer.parseInt(((Element) n.item(1)).getAttribute("X"));
            final int y2 = Integer.parseInt(((Element) n.item(1)).getAttribute("Y"));
            final int z1 = Integer.parseInt(z.getAttribute("minZ"));
            final int z2 = Integer.parseInt(z.getAttribute("maxZ"));
            out.add(new int[] { Math.min(x1, x2), Math.max(x1, x2), Math.min(y1, y2), Math.max(y1, y2), Math.min(z1, z2), Math.max(z1, z2) });
        }
        return out;
    }
}
