package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.PlaceNames;
import org.l2start.bots.api.Pos;
import org.l2start.bots.runtime.IdleWatch;

/** Hunt pass: ad-hoc grounds from the real monsters, barren places, the scroll escape, named grounds in world.xml. */
class HuntPassTest {
    static final Pos ORK = new Pos(101363, 200590, -3720);

    @Test
    void adHocGroundFollowsTheRealMonstersNotThePlayersLevel() {
        final List<Director.MobSpot> mobs = new ArrayList<>();
        mobs.add(new Director.MobSpot(new Pos(101500, 200700, -3720), 45)); // his own, right by him
        for (int i = 0; i < 10; i++) {
            mobs.add(new Director.MobSpot(new Pos(102500 + (i * 450), 201500, -3700), 38 + (i % 3)));
        }
        final WorldCatalog.Ground g = Director.adHocGround("adhoc_1", "heine", ORK, 4500, mobs, List.of());
        assertNotNull(g);
        assertEquals(38, g.minLevel());
        assertEquals(40, g.maxLevel());
        assertTrue(g.spots().stream().allMatch(s -> s.distance2d(ORK) > Director.PLAYER_MOBS));
        assertTrue(g.spots().size() >= 3);
    }

    @Test
    void noMonstersNoAdHocGround() {
        assertNull(Director.adHocGround("adhoc_1", "heine", ORK, 4500, List.of(), List.of()));
        assertNull(Director.adHocGround("adhoc_1", "heine", ORK, 4500, List.of(new Director.MobSpot(new Pos(101400, 200600, -3720), 45)), List.of()),
            "only the player's own monsters: nothing for a bot");
    }

    @Test
    void barrenPlaceIsSkippedForTenMinutes() {
        final Director d = new Director(new WorldCatalog(List.of(), List.of()), DirectorConfig.defaults(), new java.util.Random(1));
        d.markBarren("alligatorbeach_5", 1000);
        assertTrue(d.isBarren("alligatorbeach_5", 1000 + 60_000));
        assertFalse(d.isBarren("alligatorbeach_5", 1000 + Director.BARREN_MS));
        assertFalse(d.isBarren("other", 1000));
    }

    @Test
    void seenIdleBotEscapesByScrollAfterAWaitNotAtOnce() {
        assertFalse(IdleWatch.escapeDue(0, 100_000));
        assertFalse(IdleWatch.escapeDue(10_000, 10_000 + IdleWatch.SEEN_WAIT_MS - 1));
        assertTrue(IdleWatch.escapeDue(10_000, 10_000 + IdleWatch.SEEN_WAIT_MS));
        final Pos at = new Pos(0, 0, 0);
        assertFalse(IdleWatch.escaped(at, new Pos(100, 0, 0), 0, 5_000), "still casting");
        assertTrue(IdleWatch.escaped(at, new Pos(40_000, 0, 0), 0, 21_000), "scroll took it to town");
        assertTrue(IdleWatch.escaped(at, at, 0, IdleWatch.ESCAPE_MS), "time is up");
    }

    @Test
    void worldXmlHasAlligatorBeachByOrkAndNamesEveryGround() throws Exception {
        final WorldCatalog c = WorldCatalog.load(CatalogDataTest.DATA.resolve("world.xml"));
        assertTrue(c.grounds().size() >= 1200, "grounds: " + c.grounds().size());
        assertTrue(c.grounds().stream().anyMatch(g -> g.id().startsWith("alligatorbeach") && (g.center().distance2d(new Pos(105113, 202808, 0)) < 2000)));
        final String xml = java.nio.file.Files.readString(CatalogDataTest.DATA.resolve("world.xml"));
        final Matcher m = Pattern.compile("<ground id=\"([^\"]+)\"( name=\"([^\"]*)\" nameEn=\"([^\"]*)\")?").matcher(xml);
        int n = 0;
        while (m.find()) {
            n++;
            assertNotNull(m.group(2), "ground " + m.group(1) + " has no display name");
            assertFalse(m.group(3).isBlank() || m.group(3).contains("_") || m.group(4).contains("_"), m.group(1) + ": " + m.group(3) + " / " + m.group(4));
            assertFalse(PlaceNames.say(m.group(1), "ru").contains("_"));
            assertFalse(PlaceNames.say(m.group(1), "en").contains("_"));
        }
        assertEquals(c.grounds().size(), n);
        for (WorldCatalog.Town t : c.towns()) {
            assertFalse(PlaceNames.say(t.id(), "ru").contains("_"), t.id());
        }
        assertTrue(PlaceNames.grounds().stream().noneMatch(g -> c.towns().stream().anyMatch(t -> t.id().equals(g.id()))), "towns are never party destinations");
    }
}
