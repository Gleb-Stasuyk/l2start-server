package org.l2start.bots.director;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;

class DirectorTest {
    static final Pos TOWN = new Pos(0, 0, 0);
    static final Pos GK = new Pos(500, 0, 0);
    static final Pos FIELD = new Pos(30_000, 0, 0);

    static WorldCatalog catalog() {
        final WorldCatalog.Town t = new WorldCatalog.Town("town", "Town", TOWN, 2000, 20, 40, new int[] { -2000, 2000, 2000, -2000 }, new int[] { -2000, -2000, 2000, 2000 },
            List.of(new Assignment.Anchor("gk", GK), new Assignment.Anchor("restart", new Pos(-500, 0, 0)), new Assignment.Anchor("shop", new Pos(0, 800, 0))));
        final List<Pos> spots = new ArrayList<>();
        for (int i = -3; i <= 3; i++) {
            spots.add(new Pos(FIELD.x() + (i * 1000), FIELD.y() + 500, 0));
        }
        final WorldCatalog.Ground g = new WorldCatalog.Ground("field", "town", FIELD, 3000, 30, 34, 40, 0, false, spots);
        return new WorldCatalog(List.of(t), List.of(g));
    }

    /** A tiny world: bots appear where told, do not move, orders are kept. */
    static final class Sim {
        final Director d;
        final Map<Integer, Director.BotNow> bots = new HashMap<>();
        final Map<Integer, Integer> pool = new HashMap<>(); // botId -> level of personas outside
        final List<Director.Command> all = new ArrayList<>();
        int nextId = 1000;
        long now = 1_000_000_000L;
        int created;
        /** Bots do what they were told in one step (4d): a scroll lands in town, runners reach the exit, spot logouts vanish. */
        boolean obey;
        final Map<Integer, Integer> levels = new HashMap<>();
        final Map<Integer, Long> lastLogout = new HashMap<>();
        final List<Integer> loggedOutOnField = new ArrayList<>();

        Sim(DirectorConfig c) {
            d = new Director(catalog(), c, new Random(42));
        }

        List<Director.Command> tick(int hour, List<Director.Live> live) {
            final List<Director.Command> out = d.tick(now, hour, live, new ArrayList<>(bots.values()), (lo, hi, t) -> {
                final List<Director.Candidate> l = new ArrayList<>();
                pool.forEach((id, lvl) -> {
                    if ((lvl >= lo) && (lvl <= hi)) {
                        l.add(new Director.Candidate(id, lvl, obey ? lastLogout.getOrDefault(id, 0L) : 0));
                    }
                });
                return l;
            }, p -> p);
            for (Director.Command c : out) {
                switch (c) {
                    case Director.Command.Materialize m -> {
                        final int id;
                        if (m.botId() == 0) {
                            id = nextId++;
                            created++;
                        } else {
                            id = m.botId();
                            assertNotNull(pool.remove(id), "materialized a persona that is not in the pool");
                        }
                        Pos at = m.at();
                        if (obey && (m.assignment().kind() == Assignment.Kind.HUNT)) {
                            // walks in to hunt in sight of the player, but not on top of him
                            at = new Pos(m.assignment().center().x() + 1500 + ((id % 5) * 250), m.assignment().center().y() + 500, 0);
                        }
                        bots.put(id, new Director.BotNow(id, at, m.assignment(), false, id, m.group()));
                        levels.put(id, m.level());
                    }
                    case Director.Command.Assign a -> {
                        final Director.BotNow b = bots.get(a.botId());
                        Pos to = b.pos();
                        if (obey) {
                            switch (a.assignment().kind()) {
                                case LEAVE_SOE -> to = TOWN;
                                case LEAVE_RUN, LEAVE_GK, PASS -> to = a.assignment().exit() != null ? a.assignment().exit() : to;
                                case LEAVE_SPOT -> {
                                    bots.remove(a.botId());
                                    pool.put(a.botId(), levels.getOrDefault(a.botId(), 30));
                                    lastLogout.put(a.botId(), now);
                                    loggedOutOnField.add(a.botId());
                                    continue;
                                }
                                default -> {
                                }
                            }
                        }
                        bots.put(a.botId(), new Director.BotNow(b.botId(), to, a.assignment(), false, b.seed(), b.group()));
                    }
                    case Director.Command.Logout o -> {
                        final Director.BotNow b = bots.remove(o.botId());
                        assertNotNull(b);
                        pool.put(o.botId(), obey ? levels.getOrDefault(o.botId(), 30) : 30);
                        lastLogout.put(o.botId(), now);
                        if (b.assignment().center().distance2d(FIELD) < 100) {
                            loggedOutOnField.add(o.botId());
                        }
                    }
                }
            }
            all.addAll(out);
            return out;
        }

        void run(int hour, List<Director.Live> live, long forMs) {
            final long end = now + forMs;
            while (now < end) {
                tick(hour, live);
                now += 2000;
            }
        }

        long count(Assignment.Kind kind) {
            return bots.values().stream().filter(b -> b.assignment().kind() == kind).count();
        }
    }

    static List<Director.Live> at(Pos... p) {
        final List<Director.Live> l = new ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            l.add(new Director.Live(i + 1, p[i]));
        }
        return l;
    }

    @Test
    void nothingWithoutPlayers() {
        final Sim s = new Sim(DirectorConfig.defaults());
        s.run(20, List.of(), 600_000);
        assertTrue(s.all.isEmpty());
    }

    @Test
    void eveningTownFillsToTargetAtGatekeeperOrRestart() {
        final Sim s = new Sim(DirectorConfig.defaults());
        final Pos player = new Pos(0, -1500, 0);
        s.run(20, at(player), 10 * 60_000);
        final long town = s.count(Assignment.Kind.TOWN);
        assertTrue((town >= 10) && (town <= 20), "town bots in the evening: " + town);
        for (Director.Command c : s.all) {
            if (c instanceof Director.Command.Materialize m) {
                final double near = Math.min(m.at().distance2d(GK), m.at().distance2d(new Pos(-500, 0, 0)));
                assertTrue(near <= 260, "appears at the gatekeeper or restart point: " + m.at());
                assertTrue(m.at().distance2d(player) >= 300, "not under the player's nose");
                assertTrue((m.assignment().kind() == Assignment.Kind.TOWN) || (m.assignment().kind() == Assignment.Kind.TRANSIT), "kind " + m.assignment().kind());
            }
        }
        assertTrue(s.created > 0);
    }

    @Test
    void overloadGuardWantsThirtyPercentFewer() {
        final Sim s = new Sim(DirectorConfig.defaults());
        s.d.setDensity(0.7);
        s.run(20, at(new Pos(0, -1500, 0)), 10 * 60_000);
        final long town = s.count(Assignment.Kind.TOWN);
        assertTrue((town >= 7) && (town <= 14), "town bots in the evening at 70 %: " + town);
        assertTrue(s.d.statsLine().contains("density=0.7"), s.d.statsLine());
    }

    @Test
    void nightTownHasFewer() {
        final Sim s = new Sim(DirectorConfig.defaults());
        s.run(4, at(new Pos(0, -1500, 0)), 10 * 60_000);
        final long town = s.count(Assignment.Kind.TOWN);
        assertTrue((town >= 2) && (town <= 4), "town bots at night: " + town);
    }

    @Test
    void huntersAppearOutOfSightOnTheGround() {
        final DirectorConfig c = DirectorConfig.defaults();
        final Sim s = new Sim(c);
        final Pos player = FIELD;
        s.run(14, at(player), 5 * 60_000);
        final long hunt = s.count(Assignment.Kind.HUNT);
        assertTrue((hunt >= 1) && (hunt <= c.groundMax()), "hunters: " + hunt);
        for (Director.Command cmd : s.all) {
            if (cmd instanceof Director.Command.Materialize m) {
                assertTrue(m.at().distance2d(player) >= c.hiddenMin(), "appeared in sight: " + m.at());
                assertTrue((m.level() >= 34) && (m.level() <= 38), "level a bit above the ground (30-34): " + m.level());
            }
        }
    }

    @Test
    void aggressiveGroundsGetHigherHunters() {
        final WorldCatalog.Ground calm = new WorldCatalog.Ground("a", "t", FIELD, 3000, 36, 40, 40, 0, false, List.of());
        final WorldCatalog.Ground half = new WorldCatalog.Ground("b", "t", FIELD, 3000, 36, 40, 40, 0.5, false, List.of());
        final WorldCatalog.Ground aggro = new WorldCatalog.Ground("c", "t", FIELD, 3000, 36, 40, 40, 1.0, false, List.of());
        assertArrayEquals(new int[] { 40, 44 }, Director.huntLevels(calm));
        assertArrayEquals(new int[] { 42, 46 }, Director.huntLevels(half));
        assertArrayEquals(new int[] { 44, 48 }, Director.huntLevels(aggro));
    }

    @Test
    void poolPersonasAreUsedBeforeNewOnes() {
        final Sim s = new Sim(DirectorConfig.defaults());
        for (int i = 1; i <= 30; i++) {
            s.pool.put(i, 35);
        }
        s.run(14, at(FIELD), 3 * 60_000);
        assertEquals(0, s.created);
        assertFalse(s.bots.isEmpty());
    }

    @Test
    void unseenBotsLogOut() {
        final Sim s = new Sim(DirectorConfig.defaults());
        s.run(20, at(new Pos(0, -1500, 0)), 5 * 60_000);
        assertFalse(s.bots.isEmpty());
        // the player goes far away: every bot is unseen and logs out after DirectorUnseenLogout
        s.run(20, at(new Pos(200_000, 200_000, 0)), 3 * 60_000);
        // (only passers-by of the player's new road may be around)
        assertTrue(s.bots.values().stream().allMatch(b -> b.assignment().kind() == Assignment.Kind.PASS), "left: " + s.bots.size());
    }

    @Test
    void botsSeenTooLongLeave() {
        final Sim s = new Sim(DirectorConfig.defaults());
        s.run(14, at(new Pos(0, -1500, 0)), 50 * 60_000);
        assertTrue(s.all.stream().anyMatch(c -> (c instanceof Director.Command.Assign a) && a.assignment().leaving()), "nobody lingered out");
        assertTrue(s.d.statsLine().contains("lingerLeaves="));
    }

    static DirectorConfig linger(long minMs, long maxMs) {
        final DirectorConfig d = DirectorConfig.defaults();
        return new DirectorConfig(d.clusterLink(), d.groundReach(), d.townMargin(), d.hiddenMin(), d.hiddenMax(), d.seenRadius(), d.unseenRadius(),
            d.unseenLogoutMs(), minMs, maxMs, d.townNight(), d.townDay(), d.townEvening(), d.groundMin(), d.groundMax(), d.indoorFactor(),
            d.huntPerCluster(), d.huntPerExtraPlayer(), d.maxPerCluster(), d.maxInWorld(), d.spawnIntervalMinMs(), d.spawnIntervalMaxMs(), d.leaveIntervalMs(), d.returnCooldownMs(),
            d.pendingTimeoutMs(), d.onlineCurve(), d.transitMinMs(), d.transitMaxMs(), d.transitStarterFactor(), d.passMinMs(), d.passMaxMs(), d.townWanderersMultiplier());
    }

    static long stat(Director d, String key) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile(" " + key + "=(\\d+)").matcher(d.statsLine());
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    @Test
    void huntSessionIsTwentyToNinetyMinutes() {
        assertEquals(20 * 60_000L, DirectorConfig.defaults().lingerMinMs());
        assertEquals(90 * 60_000L, DirectorConfig.defaults().lingerMaxMs());
    }

    @Test
    void restockersGoToTownAndComeBackToTheSameGround() {
        final Sim s = new Sim(linger(60_000, 120_000));
        s.obey = true;
        s.d.setLife(new Director.Life(1.0, 1, 0, 0, 60_000, 120_000));
        s.run(14, at(FIELD), 25 * 60_000);
        assertTrue(stat(s.d, "restocks") > 0, s.d.statsLine());
        assertTrue(stat(s.d, "comebacks") > 0, "nobody came back: " + s.d.statsLine());
        // the same persona appears again on the field after it left it
        boolean again = false;
        final java.util.Set<Integer> left = new java.util.HashSet<>();
        for (Director.Command c : s.all) {
            if ((c instanceof Director.Command.Assign a) && a.assignment().leaving()) {
                left.add(a.botId());
            } else if ((c instanceof Director.Command.Materialize m) && left.contains(m.botId()) && "field".equals(m.assignment().place())) {
                again = true;
            }
        }
        assertTrue(again, "no persona came back to the field");
        assertTrue(s.all.stream().anyMatch(c -> (c instanceof Director.Command.Assign a) && (a.assignment().kind() == Assignment.Kind.LEAVE_SOE)), "no scroll of escape");
    }

    @Test
    void logoutStylesFollowTheWeights() {
        final Sim s = new Sim(linger(60_000, 120_000));
        s.obey = true;
        s.d.setLife(new Director.Life(0, 0, 1, 0, 60_000, 120_000));
        s.run(14, at(FIELD), 15 * 60_000);
        assertTrue(s.all.stream().anyMatch(c -> (c instanceof Director.Command.Assign a) && (a.assignment().kind() == Assignment.Kind.LEAVE_SPOT)), "no spot logout");
        assertTrue(s.all.stream().noneMatch(c -> (c instanceof Director.Command.Assign a) && (a.assignment().kind() == Assignment.Kind.LEAVE_SOE)), "scroll with weight 0");
        assertEquals(0, stat(s.d, "restocks"));
    }

    @Test
    void neverLogsOutOnTheSpotInFrontOfAPlayer() {
        final Director d = new Director(catalog(), DirectorConfig.defaults(), new Random(1));
        d.setLife(new Director.Life(0, 0, 1, 0, 60_000, 120_000));
        final Pos bot = new Pos(FIELD.x() + 500, FIELD.y(), 0);
        final Assignment hunt = new Assignment(1, Assignment.Kind.HUNT, "field", FIELD, 3000, 30, 34, List.of(), List.of(), null);
        final Director.BotNow b = new Director.BotNow(77, bot, hunt, false, 77);
        final Director.Command near = d.endOfStay(DirectorConfig.defaults(), 1_000_000L, b, at(new Pos(bot.x() + 600, bot.y(), 0)), p -> p, "test");
        assertTrue((near instanceof Director.Command.Assign a) && (a.assignment().kind() != Assignment.Kind.LEAVE_SPOT), "vanished in front of a player: " + near);
        final Director.Command far = d.endOfStay(DirectorConfig.defaults(), 1_000_000L, b, at(new Pos(bot.x() + 2500, bot.y(), 0)), p -> p, "test");
        assertTrue((far instanceof Director.Command.Assign a) && (a.assignment().kind() == Assignment.Kind.LEAVE_SPOT), "no spot logout: " + far);
    }

    @Test
    void scrollLandingInFrontOfAPlayerGoesOnErrands() {
        final Sim s = new Sim(linger(60_000, 120_000));
        s.obey = true;
        s.d.setLife(new Director.Life(0, 1, 0, 0, 60_000, 120_000));
        s.run(14, at(FIELD, new Pos(0, -1500, 0)), 15 * 60_000);
        final List<Assignment> errands = s.all.stream().filter(c -> (c instanceof Director.Command.Assign a) && (a.assignment().kind() == Assignment.Kind.ERRANDS))
            .map(c -> ((Director.Command.Assign) c).assignment()).toList();
        assertFalse(errands.isEmpty(), "no errands: " + s.d.statsLine());
        assertTrue(errands.stream().allMatch(a -> "town".equals(a.place()) && GK.equals(a.exit())), errands.toString());
    }

    @Test
    void pairsAndTriosComeTogetherAndLeaveTogether() {
        final Sim s = new Sim(linger(3 * 60_000, 4 * 60_000));
        s.obey = true;
        s.d.setPartyShare(1.0);
        s.d.setLife(new Director.Life(0, 1, 0, 0, 60_000, 120_000));
        for (int i = 1; i <= 30; i++) {
            s.pool.put(i, 32 + (i % 3));
        }
        s.run(14, at(FIELD), 20 * 60_000);
        final Map<Integer, List<Director.Command.Materialize>> groups = new HashMap<>();
        for (Director.Command c : s.all) {
            if ((c instanceof Director.Command.Materialize m) && (m.group() != 0)) {
                groups.computeIfAbsent(m.group(), k -> new ArrayList<>()).add(m);
            }
        }
        assertFalse(groups.isEmpty(), "no party: " + s.d.statsLine());
        for (List<Director.Command.Materialize> g : groups.values()) {
            assertTrue((g.size() >= 2) && (g.size() <= 3), "group of " + g.size());
            for (Director.Command.Materialize m : g) {
                assertEquals(g.get(0).assignment().place(), m.assignment().place());
                assertTrue(m.at().distance2d(g.get(0).at()) < 300, "came from different places");
                assertTrue(Math.abs(m.level() - g.get(0).level()) <= 5, "levels too far apart");
            }
        }
        // whoever of a group got a leave order, the others got the same one in that tick
        assertTrue(stat(s.d, "partyLeaves") > 0, s.d.statsLine());
        assertEquals(0, stat(s.d, "restocks"), "a party does not restock one by one");
    }

    @Test
    void noPartiesWhenSwitchedOff() {
        final Sim s = new Sim(DirectorConfig.defaults());
        s.d.setPartyShare(0);
        for (int i = 1; i <= 30; i++) {
            s.pool.put(i, 32);
        }
        s.run(14, at(FIELD), 10 * 60_000);
        assertTrue(s.all.stream().noneMatch(c -> (c instanceof Director.Command.Materialize m) && (m.group() != 0)));
    }

    @Test
    void deadBotsWaitByTheirPatience() {
        final Random r = new Random(3);
        for (int i = 0; i < 200; i++) {
            final long quick = DeathWait.ms(0.1, 5000, 40_000, r);
            assertTrue((quick >= 2000) && (quick < 8000), "impatient: " + quick);
            final long res = DeathWait.ms(0.9, 5000, 40_000, r);
            assertTrue((res >= 60_000) && (res < 180_000), "waits for a res: " + res);
            final long mid = DeathWait.ms(0.5, 5000, 40_000, r);
            assertTrue((mid >= 5000) && (mid <= 40_000), "usual: " + mid);
        }
    }

    @Test
    void globalBudgetIsKept() {
        final DirectorConfig d = DirectorConfig.defaults();
        final DirectorConfig c = new DirectorConfig(d.clusterLink(), d.groundReach(), d.townMargin(), d.hiddenMin(), d.hiddenMax(), d.seenRadius(), d.unseenRadius(),
            d.unseenLogoutMs(), d.lingerMinMs(), d.lingerMaxMs(), d.townNight(), d.townDay(), d.townEvening(), d.groundMin(), d.groundMax(), d.indoorFactor(),
            d.huntPerCluster(), d.huntPerExtraPlayer(), d.maxPerCluster(), 3, d.spawnIntervalMinMs(), d.spawnIntervalMaxMs(), d.leaveIntervalMs(), d.returnCooldownMs(),
            d.pendingTimeoutMs(), d.onlineCurve(), d.transitMinMs(), d.transitMaxMs(), d.transitStarterFactor(), d.passMinMs(), d.passMaxMs(), d.townWanderersMultiplier());
        final Sim s = new Sim(c);
        s.run(20, at(new Pos(0, -1500, 0), FIELD), 10 * 60_000);
        assertTrue(s.bots.size() <= 3, "budget: " + s.bots.size());
    }

    @Test
    void townWanderersMultiplierDoublesTownAndPassPace() {
        final DirectorConfig base = withWander(1.0);
        final DirectorConfig double_ = withWander(2.0);
        final int hour = 14;
        final double curve = base.curve(hour);
        final int t1 = (int) Math.round(Director.townTarget(base, "giran", hour, curve) * base.wanderMult());
        final int t2 = (int) Math.round(Director.townTarget(double_, "giran", hour, curve) * double_.wanderMult());
        assertEquals(t1 * 2, t2);
        final Random rnd = new Random(1);
        final long p1 = Director.passInterval(base, 0.5, rnd);
        final Random rnd2 = new Random(1);
        final long p2 = Director.passInterval(double_, 0.5, rnd2);
        assertEquals(p1 / 2, p2);
        assertEquals(1.0, DirectorConfig.defaults().townWanderersMultiplier(), "unit baseline");
    }

    static DirectorConfig withWander(double m) {
        final DirectorConfig d = DirectorConfig.defaults();
        return new DirectorConfig(d.clusterLink(), d.groundReach(), d.townMargin(), d.hiddenMin(), d.hiddenMax(), d.seenRadius(), d.unseenRadius(),
            d.unseenLogoutMs(), d.lingerMinMs(), d.lingerMaxMs(), d.townNight(), d.townDay(), d.townEvening(), d.groundMin(), d.groundMax(), d.indoorFactor(),
            d.huntPerCluster(), d.huntPerExtraPlayer(), d.maxPerCluster(), d.maxInWorld(), d.spawnIntervalMinMs(), d.spawnIntervalMaxMs(), d.leaveIntervalMs(),
            d.returnCooldownMs(), d.pendingTimeoutMs(), d.onlineCurve(), d.transitMinMs(), d.transitMaxMs(), d.transitStarterFactor(), d.passMinMs(), d.passMaxMs(), m);
    }

    @Test
    void clustersJoinNearPlayers() {
        assertEquals(1, Director.clusters(at(new Pos(0, 0, 0), new Pos(5000, 0, 0), new Pos(10_000, 0, 0)), 6000).size());
        assertEquals(2, Director.clusters(at(new Pos(0, 0, 0), new Pos(20_000, 0, 0)), 6000).size());
    }

    @Test
    void densityTargets() {
        final DirectorConfig c = DirectorConfig.defaults();
        for (int h = 0; h < 24; h++) {
            final int t = Director.townTarget(c, "giran", h, c.curve(h));
            assertTrue((t >= 2) && (t <= 20), h + ": " + t);
        }
        final int evening = Director.townTarget(c, "giran", 20, c.curve(20));
        assertTrue(evening >= 10, "evening " + evening);
        final WorldCatalog.Ground big = new WorldCatalog.Ground("g", "t", FIELD, 3000, 30, 34, 80, 0, false, List.of());
        final WorldCatalog.Ground cave = new WorldCatalog.Ground("c", "t", FIELD, 3000, 30, 34, 80, 0, true, List.of());
        assertEquals(5, Director.groundTarget(c, big, 1));
        assertTrue(Director.groundTarget(c, cave, 1) < 5);
    }

    static long materialized(Sim s, Assignment.Kind kind) {
        return s.all.stream().filter(c -> (c instanceof Director.Command.Materialize m) && (m.assignment().kind() == kind)).count();
    }

    @Test
    void gatekeeperTransitIsFrequentInTheEvening() {
        final Sim s = new Sim(DirectorConfig.defaults());
        s.run(20, at(new Pos(0, -1500, 0)), 10 * 60_000);
        final long transit = materialized(s, Assignment.Kind.TRANSIT);
        assertTrue((transit >= 12) && (transit <= 35), "transits in 10 evening minutes: " + transit);
        for (Director.Command c : s.all) {
            if ((c instanceof Director.Command.Materialize m) && (m.assignment().kind() == Assignment.Kind.TRANSIT)) {
                assertTrue(m.at().distance2d(GK) <= 260, "transit at the gatekeeper: " + m.at());
                assertFalse(m.assignment().leaving());
                assertTrue(m.assignment().passing());
            }
        }
        final Sim night = new Sim(DirectorConfig.defaults());
        night.run(4, at(new Pos(0, -1500, 0)), 10 * 60_000);
        assertTrue(materialized(night, Assignment.Kind.TRANSIT) < (transit / 2), "night transit: " + materialized(night, Assignment.Kind.TRANSIT));
    }

    @Test
    void transitIsRareInStartingVillages() {
        final DirectorConfig c = DirectorConfig.defaults();
        final Random r = new Random(1);
        for (int i = 0; i < 100; i++) {
            final long giran = Director.transitInterval(c, "giran", 1, r);
            assertTrue((giran >= 20_000) && (giran <= 40_000), "giran evening " + giran);
            final long day = Director.transitInterval(c, "giran", 0.5, r);
            assertTrue((day >= 40_000) && (day <= 80_000), "giran day " + day);
            assertTrue(Director.transitInterval(c, "talking_island", 1, r) >= 80_000);
        }
    }

    @Test
    void passersByRunPastAPlayerOnTheRoad() {
        final DirectorConfig c = DirectorConfig.defaults();
        final Sim s = new Sim(c);
        final Pos player = new Pos(100_000, 0, 0);
        s.run(20, at(player), 30 * 60_000);
        final long pass = materialized(s, Assignment.Kind.PASS);
        assertTrue((pass >= 10) && (pass <= 35), "passers-by in 30 evening minutes: " + pass);
        for (Director.Command cmd : s.all) {
            if (cmd instanceof Director.Command.Materialize m) {
                assertEquals(Assignment.Kind.PASS, m.assignment().kind());
                assertTrue(m.at().distance2d(player) >= c.hiddenMin(), "appeared in sight");
                final Pos to = m.assignment().exit();
                final double dot = ((m.at().x() - player.x()) * (double) (to.x() - player.x())) + ((m.at().y() - player.y()) * (double) (to.y() - player.y()));
                assertTrue(dot < 0, "runs past the player to the other side");
                assertTrue(to.distance2d(player) > c.unseenRadius(), "vanishes out of sight");
            }
        }
    }

    @Test
    void inADungeonWallsHide() {
        final DirectorConfig c = DirectorConfig.defaults();
        final Director d = new Director(catalog(), c, new Random(3));
        final Pos player = new Pos(30_000, 0, 0);
        final Pos spot = new Pos(31_600, 0, 0);
        final WorldCatalog.Ground cave = new WorldCatalog.Ground("cave", "town", new Pos(31_000, 0, 0), 800, 30, 34, 40, 0, true, List.of(spot));
        final Director.Terrain walls = new Director.Terrain() {
            @Override
            public Pos ground(Pos approx) {
                return approx;
            }

            @Override
            public boolean canSee(Pos from, Pos to) {
                return false;
            }
        };
        assertEquals(spot, d.hiddenPoint(c, cave, at(player), walls));
        final Pos open = d.hiddenPoint(c, cave, at(player), p -> p);
        assertTrue((open == null) || (open.distance2d(player) >= c.hiddenMin()), "in sight without walls: " + open);
    }

    @Test
    void aBotLeavingADungeonVanishesRoundACorner() {
        final DirectorConfig c = DirectorConfig.defaults();
        final WorldCatalog.Ground cave = new WorldCatalog.Ground("cave", "town", new Pos(31_000, 0, 0), 800, 30, 34, 40, 0, true, List.of());
        final WorldCatalog.Ground field = new WorldCatalog.Ground("open", "town", new Pos(31_000, 0, 0), 800, 30, 34, 40, 0, false, List.of());
        final Pos player = new Pos(30_000, 0, 0);
        final Pos bot = new Pos(30_900, 0, 0); // well inside the unseen radius
        final Director.Terrain walls = new Director.Terrain() {
            @Override
            public Pos ground(Pos approx) {
                return approx;
            }

            @Override
            public boolean canSee(Pos from, Pos to) {
                return false;
            }
        };
        for (WorldCatalog.Ground g : List.of(cave, field)) {
            for (boolean wall : new boolean[] { true, false }) {
                final Director d = new Director(new WorldCatalog(List.of(catalog().town("town")), List.of(g)), c, new Random(4));
                final Assignment hunt = new Assignment(1, Assignment.Kind.HUNT, g.id(), g.center(), g.radius(), 30, 34, List.of(), List.of(), null);
                final Assignment leave = hunt.leave(2, Assignment.Kind.LEAVE_RUN, new Pos(33_000, 0, 0));
                final List<Director.BotNow> bots = List.of(new Director.BotNow(7, bot, leave, false, 1));
                boolean gone = false;
                for (long now = 1000; now <= 10_000; now += 2000) {
                    for (Director.Command cmd : d.tick(now, 20, at(player), bots, (lo, hi, t) -> List.of(), wall ? walls : p -> p)) {
                        gone |= (cmd instanceof Director.Command.Logout l) && (l.botId() == 7);
                    }
                }
                assertEquals(g.indoor() && wall, gone, g.id() + (wall ? " behind a wall" : " in sight"));
                assertTrue(d.kindLines().get(3).startsWith("Leaving: "), d.kindLines().toString());
            }
        }
    }

    @Test
    void unreachableTownNpcsAreLeftOut() {
        final List<String> dropped = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final WorldCatalog c = catalog().withReachableAnchors((from, to) -> to.equals(new Pos(0, 800, 0)) ? null : to, dropped, warnings);
        assertEquals(List.of("town shop 0,800,0"), dropped);
        assertTrue(warnings.isEmpty());
        final WorldCatalog.Town t = c.town("town");
        assertEquals(2, t.anchors().size());
        assertTrue(t.anchors("shop").isEmpty());
        assertEquals(1, t.anchors("gk").size());
        assertEquals(1, t.anchors("restart").size(), "restart points stay");
        assertNotNull(c.ground("field"));
    }

    @Test
    void aTownNeverLosesAllItsGatekeepers() {
        final List<String> dropped = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final WorldCatalog c = catalog().withReachableAnchors((from, to) -> null, dropped, warnings);
        assertEquals(List.of("town shop 0,800,0"), dropped);
        assertEquals(1, c.town("town").anchors("gk").size());
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void anchorsThatCannotBeCheckedStay() {
        final List<String> dropped = new ArrayList<>();
        assertEquals(3, catalog().withReachableAnchors((from, to) -> WorldCatalog.UNKNOWN, dropped, new ArrayList<>()).town("town").anchors().size());
        assertTrue(dropped.isEmpty());
    }

    @Test
    void farNpcsAreReachedHopByHop() {
        // a chain of shops 2000 apart from the restart point: each one only from the one before
        final List<Assignment.Anchor> anchors = new ArrayList<>(List.of(new Assignment.Anchor("restart", new Pos(0, 0, 0))));
        for (int i = 1; i <= 4; i++) {
            anchors.add(new Assignment.Anchor("shop", new Pos(i * 2000, 0, 0)));
        }
        anchors.add(new Assignment.Anchor("gk", new Pos(9000, 9000, 0))); // far from everything: cannot be judged, stays
        final WorldCatalog.Town t = new WorldCatalog.Town("long", "Long", TOWN, 12_000, 1, 10, new int[] { -1, 1, 1, -1 }, new int[] { -1, -1, 1, 1 }, anchors);
        final List<String> dropped = new ArrayList<>();
        final WorldCatalog c = new WorldCatalog(List.of(t), List.of()).withReachableAnchors((from, to) -> from.distance2d(to) <= 2100 ? to : null, dropped,
            new ArrayList<>());
        assertTrue(dropped.isEmpty(), dropped.toString());
        assertEquals(6, c.town("long").anchors().size());
    }

    @Test
    void startingVillagesGetTheirOwnRace() {
        assertEquals(0, Director.raceOf("talking_island"));
        assertEquals(1, Director.raceOf("elven_village"));
        assertEquals(2, Director.raceOf("dark_elven_village"));
        assertEquals(3, Director.raceOf("orc_village"));
        assertEquals(4, Director.raceOf("dwarven_village"));
        assertEquals(-1, Director.raceOf("giran"));
        final WorldCatalog.Town elf = new WorldCatalog.Town("elven_village", "Elven Village", TOWN, 2000, 1, 10, new int[] { -2000, 2000, 2000, -2000 },
            new int[] { -2000, -2000, 2000, 2000 }, List.of(new Assignment.Anchor("gk", GK), new Assignment.Anchor("restart", new Pos(-500, 0, 0))));
        final Director d = new Director(new WorldCatalog(List.of(elf), List.of()), DirectorConfig.defaults(), new Random(5));
        final List<Director.Candidate> pool = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            pool.add(new Director.Candidate(i, 5, 0, i <= 10 ? 0 : 1)); // 1-10 humans, 11-20 elves
        }
        long now = 1_000_000_000L;
        int seen = 0;
        for (int t = 0; t < 120; t++, now += 2000) {
            for (Director.Command c : d.tick(now, 20, at(new Pos(0, -1500, 0)), List.of(), (lo, hi, x) -> pool, p -> p)) {
                if (c instanceof Director.Command.Materialize m) {
                    seen++;
                    assertEquals(1, m.race(), "race of a bot in the elven village");
                    assertTrue((m.botId() == 0) || (m.botId() > 10), "a human persona in the elven village: " + m.botId());
                }
            }
        }
        assertTrue(seen > 0);
    }

    @Test
    void personasInTheirHoursComeFarMoreOften() {
        final Director d = new Director(catalog(), DirectorConfig.defaults(), new Random(7));
        final List<Director.Candidate> pool = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            pool.add(new Director.Candidate(i, 25, 0, -1, i <= 10 ? 1.0 : 0.05)); // 1-10: their evening, 11-20: asleep
        }
        long now = 1_000_000_000L;
        int theirs = 0;
        int others = 0;
        final java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int t = 0; t < 400; t++, now += 2000) {
            final List<Director.Candidate> left = new ArrayList<>(pool);
            left.removeIf(c -> used.contains(c.botId()));
            for (Director.Command c : d.tick(now, 20, at(new Pos(0, -1500, 0)), List.of(), (lo, hi, x) -> left, p -> p)) {
                if ((c instanceof Director.Command.Materialize m) && (m.botId() != 0)) {
                    if (m.botId() <= 10) {
                        theirs++;
                    } else {
                        others++;
                    }
                }
            }
        }
        assertTrue(theirs > (others * 3), "their hours " + theirs + ", asleep " + others);
    }
}
