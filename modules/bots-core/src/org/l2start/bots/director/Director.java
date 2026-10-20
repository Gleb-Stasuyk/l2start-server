package org.l2start.bots.director;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;

/**
 * The presence director (plan section 20.3): bots exist only where live players can see them. Every tick it looks at
 * the live players, groups them into clusters, finds the places around them (town, hunting grounds), computes how
 * many bots each place should show (time of day, monsters, live players already there) and orders bots in and out so
 * that nobody sees them appear or vanish. Pure logic: no Mobius classes, time and randomness are injected; the
 * game side ({@code DirectorRunner}) executes the {@link Command}s.
 */
public final class Director {
    /** A live (human) player. */
    public record Live(int id, Pos pos) {
    }

    /** A bot in the world right now. */
    /** @param group the pair/trio it came in with (phase 4g), 0 = alone */
    public record BotNow(int botId, Pos pos, Assignment assignment, boolean dead, long seed, int group) {
        public BotNow(int botId, Pos pos, Assignment assignment, boolean dead, long seed) {
            this(botId, pos, assignment, dead, seed, 0);
        }
    }

    /** A persona that could enter: not in the world, its level now (paper or real, the higher). */
    /** @param race 0 human, 1 elf, 2 dark elf, 3 orc, 4 dwarf, -1 unknown */
    /** @param activity 0..1: how much it is "their time" now (chronotype, local time of the persona; phase 4e) */
    /** @param clanId phase 7d bot clan (0 = none; provisional or stock) */
    /** @param clanStockPending phase 7e: provisional clan leader — prefer spawn so stock Clan+crest appear */
    public record Candidate(int botId, int level, long lastLogout, int race, double activity, int clanId, boolean clanStockPending) {
        public Candidate(int botId, int level, long lastLogout, int race, double activity, int clanId) {
            this(botId, level, lastLogout, race, activity, clanId, false);
        }

        public Candidate(int botId, int level, long lastLogout, int race, double activity) {
            this(botId, level, lastLogout, race, activity, 0, false);
        }

        public Candidate(int botId, int level, long lastLogout, int race) {
            this(botId, level, lastLogout, race, 1, 0, false);
        }

        public Candidate(int botId, int level, long lastLogout) {
            this(botId, level, lastLogout, -1, 1, 0, false);
        }
    }

    /** Starting villages are of their race (Talking Island humans, Elven Village elves, ...); -1 = any race. */
    public static int raceOf(String townId) {
        return switch (townId) {
            case "talking_island" -> 0;
            case "elven_village" -> 1;
            case "dark_elven_village" -> 2;
            case "orc_village" -> 3;
            case "dwarven_village" -> 4;
            default -> -1;
        };
    }

    /** Personas not in the world whose level is in the band (and that did not leave just now). */
    public interface Pool {
        List<Candidate> available(int minLevel, int maxLevel, long now);
    }

    /** Geodata: a walkable point near {@code approx} (with the right height), or null. */
    public interface Terrain {
        Pos ground(Pos approx);

        /** Line of sight by geodata (walls of a dungeon); without geodata: true (no hiding behind walls). */
        default boolean canSee(Pos from, Pos to) {
            return true;
        }
    }

    public sealed interface Command {
        /** Bring this persona (botId 0 = create a new one) at {@code level} into the world at {@code at} with this order. */
        /** @param race the race of a new persona (botId 0), -1 = any */
        /** @param group bots of one group come in together and form a party (phase 4g), 0 = alone */
        record Materialize(int botId, int level, Pos at, Assignment assignment, int race, int group) implements Command {
            public Materialize(int botId, int level, Pos at, Assignment assignment, int race) {
                this(botId, level, at, assignment, race, 0);
            }
        }

        /** A new order for a bot in the world (leave). */
        record Assign(int botId, Assignment assignment) implements Command {
        }

        /** Log out now (nobody can see it). */
        record Logout(int botId, String why) implements Command {
        }
    }

    /** One place in the last tick (for //bots director and the log). */
    public record PlaceState(String id, boolean town, int players, int target, int present, int pending) {
    }

    /**
     * How hunters end a hunt (phase 4d; bots-core keys RestockShare, LogoutStyle, RestockAway).
     * @param restockShare share of ends that are "went to town and back" (scroll of escape or running off, back on the
     *     same ground from its edge after {@code awayMinMs..awayMaxMs})
     * @param soe weight of "scroll of escape and log out in town"
     * @param spot weight of "log out right here, sitting" (only with no live player within {@link #SPOT_LOGOUT_CLEAR})
     * @param gk weight of "walk off to the gatekeeper" (out of sight)
     */
    public record Life(double restockShare, double soe, double spot, double gk, long awayMinMs, long awayMaxMs) {
        public static Life defaults() {
            return new Life(0.4, 5, 3, 2, 4 * 60_000L, 12 * 60_000L);
        }
    }

    /** A player closer than this: no logging out on the spot (it would vanish in front of them). */
    static final int SPOT_LOGOUT_CLEAR = 1200;

    private record Comeback(String place, long due) {
    }

    /** A bot leaving a dungeon farther than this from every player and out of their line of sight is gone from view. */
    static final int INDOOR_OUT_OF_SIGHT = 600;

    private static final class Track {
        long seenSince;
        long unseenSince;
        long since;
        boolean lingered;
    }

    private record Pending(String place, long seq, long since) {
    }

    private record Demand(String id, boolean town, WorldCatalog.Town townRef, WorldCatalog.Ground groundRef, int players, double nearest) {
    }

    private final WorldCatalog catalog;
    private volatile DirectorConfig cfg;
    private final Random rnd;
    private final Map<Integer, Track> tracks = new HashMap<>();
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final Map<String, Long> nextSpawn = new HashMap<>();
    private final Map<String, Long> nextLeave = new HashMap<>();
    private final Map<Integer, Comeback> comebacks = new HashMap<>();
    private volatile Life life = Life.defaults();
    private final Deque<String> decisions = new ArrayDeque<>();
    private long seq;
    private int newKey;
    private volatile List<PlaceState> places = List.of();
    private volatile int clusters;
    private volatile int livePlayers;
    // counters (since start)
    private long materialized;
    private long created;
    private long leaves;
    private long lingerLeaves;
    private long unseenLogouts;
    private long noSpot;
    private long noPersona;
    private long transits;
    private long passers;
    private long restocks;
    private long comebacksDone;
    private long soeLeaves;
    private long spotLogouts;
    private long errandsTrips;
    // right now (last tick)
    private volatile int roadNow;
    private volatile int transitNow;
    private volatile int leavingNow;
    private volatile int indoorNow;

    public Director(WorldCatalog catalog, DirectorConfig cfg, Random rnd) {
        this.catalog = catalog;
        this.cfg = cfg;
        this.rnd = rnd;
    }

    public void setConfig(DirectorConfig c) {
        cfg = c;
    }

    public void setLife(Life l) {
        life = l;
    }

    /** Phase 4g: share of hunters that come as a pair or trio (bots-core PartyShare). */
    private volatile double partyShare = 0.12;
    /** Phase 7d: clan hunters take mixed (other clan / clanless) mates with this chance. */
    private volatile double clanMixedPartyChance = 0.25;
    private int groupSeq;
    private long parties;
    private long partyLeaves;
    /** Extra Materialize commands of the partners of the last {@link #spawn}. */
    private final List<Command> partners = new ArrayList<>();
    /** How many more bots the place being spawned may take (the partners must fit). */
    private int spawnRoom;

    public void setPartyShare(double share) {
        partyShare = Math.max(0, Math.min(1, share));
    }

    public double partyShare() {
        return partyShare;
    }

    public void setClanMixedPartyChance(double chance) {
        clanMixedPartyChance = Math.max(0, Math.min(1, chance));
    }

    /** The starting zones: only bots of their race live there (docs/STARTER_ZONES_PLAN.md S5); null = no zones (tests). */
    private volatile StarterZones starterZones;

    public void setStarterZones(StarterZones z) {
        starterZones = z;
    }

    /** A passer-by through a starting zone is of its race; elsewhere any (-1). */
    int passRace(Pos player, Pos from, Pos to) {
        final StarterZones z = starterZones;
        if (z == null) {
            return -1;
        }
        for (Pos p : new Pos[] { player, from, to }) {
            final int r = z.raceAt(p);
            if (r >= 0) {
                return r;
            }
        }
        return -1;
    }

    /** Town activity bots in a starting village at most (bots-core DirectorStarterTownMax); companions and hunters aside. */
    private volatile int starterTownMax = 8;

    public void setStarterTownMax(int max) {
        starterTownMax = Math.max(0, max);
    }
    
    /** Visible presence guarantee: field min-max (1-3 bots actively farming near hunting players). */
    private volatile int presenceFieldMin = 0;
    private volatile int presenceFieldMax = 0;
    
    /** Visible presence guarantee: town ranges by category (traders excluded). */
    private volatile int presenceTownStarterMin = 0;
    private volatile int presenceTownStarterMax = 0;
    private volatile int presenceTownSmallMin = 0;
    private volatile int presenceTownSmallMax = 0;
    private volatile int presenceTownMediumMin = 0;
    private volatile int presenceTownMediumMax = 0;
    private volatile int presenceTownLargeMin = 0;
    private volatile int presenceTownLargeMax = 0;
    
    public void setPresenceField(int min, int max) {
        presenceFieldMin = Math.max(0, min);
        presenceFieldMax = Math.max(presenceFieldMin, max);
    }
    
    public void setPresenceTown(String category, int min, int max) {
        final int minClamped = Math.max(0, min);
        final int maxClamped = Math.max(minClamped, max);
        switch (category.toLowerCase()) {
            case "starter" -> { presenceTownStarterMin = minClamped; presenceTownStarterMax = maxClamped; }
            case "small" -> { presenceTownSmallMin = minClamped; presenceTownSmallMax = maxClamped; }
            case "medium" -> { presenceTownMediumMin = minClamped; presenceTownMediumMax = maxClamped; }
            case "large" -> { presenceTownLargeMin = minClamped; presenceTownLargeMax = maxClamped; }
        }
    }

    public double clanMixedPartyChance() {
        return clanMixedPartyChance;
    }

    public Life life() {
        return life;
    }

    private volatile double density = 1;

    /** Overload guard: 0.7 = 30 % fewer bots in towns and on grounds (who is there already leaves as usual). */
    public void setDensity(double d) {
        density = Math.max(0.1, Math.min(1, d));
    }

    public double density() {
        return density;
    }

    public DirectorConfig config() {
        return cfg;
    }

    public WorldCatalog catalog() {
        return catalog;
    }

    // ------------------------------------------------------------------ tick

    public synchronized List<Command> tick(long now, int hour, List<Live> live, List<BotNow> bots, Pool pool, Terrain terrain) {
        final DirectorConfig c = cfg;
        final List<Command> out = new ArrayList<>();
        livePlayers = live.size();
        // 1. pending materializations: done (in the world) or given up
        final Set<Integer> inWorld = new HashSet<>();
        final Set<Long> arrived = new HashSet<>();
        for (BotNow b : bots) {
            inWorld.add(b.botId());
            if (b.assignment() != null) {
                arrived.add(b.assignment().seq());
            }
        }
        pending.entrySet().removeIf(e -> inWorld.contains(e.getKey()) || arrived.contains(e.getValue().seq()) || ((now - e.getValue().since()) > c.pendingTimeoutMs()));
        tracks.keySet().retainAll(inWorld);

        // 2. who sees whom: unseen bots leave, leaving bots vanish once unseen, lingering bots get a leave order
        final Map<String, List<BotNow>> byPlace = new HashMap<>();
        final List<BotNow> lingering = new ArrayList<>();
        int road = 0;
        int transit = 0;
        int leavingCount = 0;
        int indoors = 0;
        for (BotNow b : bots) {
            final Assignment k = b.assignment();
            if (k != null) {
                switch (k.kind()) {
                    case PASS -> road++;
                    case TRANSIT -> transit++;
                    case LEAVE_RUN, LEAVE_GK, LEAVE_SOE, LEAVE_SPOT, ERRANDS -> leavingCount++;
                    default -> {
                        final WorldCatalog.Ground g = catalog.ground(k.place());
                        if ((g != null) && g.indoor()) {
                            indoors++;
                        }
                    }
                }
            }
        }
        roadNow = road;
        transitNow = transit;
        leavingNow = leavingCount;
        indoorNow = indoors;
        for (BotNow b : bots) {
            final Track t = tracks.computeIfAbsent(b.botId(), k -> {
                final Track n = new Track();
                n.since = now;
                return n;
            });
            final double d = nearest(live, b.pos());
            final Assignment a0 = b.assignment();
            final WorldCatalog.Ground g0 = (a0 == null) || !a0.passing() ? null : catalog.ground(a0.place());
            // leaving a dungeon: round a corner or onto another floor is out of sight, not only past the radius
            final boolean hiddenIndoors = (g0 != null) && g0.indoor() && (d > INDOOR_OUT_OF_SIGHT) && !inSight(c, live, b.pos(), terrain);
            if ((d > c.unseenRadius()) || hiddenIndoors) {
                if (t.unseenSince == 0) {
                    t.unseenSince = now;
                }
                t.seenSince = 0;
            } else {
                t.unseenSince = 0;
                if ((d <= c.seenRadius()) && (t.seenSince == 0)) {
                    t.seenSince = now;
                }
            }
            final Assignment a = b.assignment();
            final boolean leaving = (a != null) && a.passing();
            if ((t.unseenSince != 0) && ((leaving && ((now - t.unseenSince) >= 3000)) || ((now - t.unseenSince) >= c.unseenLogoutMs()))) {
                out.add(new Command.Logout(b.botId(), leaving ? "left unseen" : "nobody around"));
                unseenLogouts++;
                tracks.remove(b.botId());
                continue;
            }
            if ((a != null) && (a.kind() == Assignment.Kind.LEAVE_SOE) && !b.dead() && (t.unseenSince == 0)) {
                // read a scroll of escape and landed in a town in front of a player: errands there, then the gatekeeper
                final WorldCatalog.Town town = catalog.townAt(b.pos(), c.townMargin());
                if ((town != null) && (a.center().distance2d(b.pos()) > (a.radius() + 3000))) {
                    Pos gk = null;
                    for (Pos g : town.anchors("gk")) {
                        if ((gk == null) || (g.distance2d(b.pos()) < gk.distance2d(b.pos()))) {
                            gk = g;
                        }
                    }
                    if (gk != null) {
                        errandsTrips++;
                        note(now, "bot " + b.botId() + " back in " + town.id() + " from a hunt: errands, then the gatekeeper");
                        out.add(new Command.Assign(b.botId(), new Assignment(++seq, Assignment.Kind.ERRANDS, town.id(), town.center(), town.radius(), town.minLevel(),
                            town.maxLevel(), List.of(), town.anchors(), gk)));
                    }
                }
                continue;
            }
            if ((a == null) || leaving || b.dead()) {
                continue;
            }
            if (a.center().distance2d(b.pos()) > (a.radius() + 6000)) {
                // died and went to town, or wandered off: not part of its place any more, it leaves quietly
                if (!t.lingered) {
                    final Command l = leave(c, now, b, live, terrain, "away from " + a.place());
                    if (l != null) {
                        out.add(l);
                        t.lingered = true;
                    }
                }
                continue;
            }
            byPlace.computeIfAbsent(a.place(), k -> new ArrayList<>()).add(b);
            if (!t.lingered && (t.seenSince != 0) && ((now - t.seenSince) > linger(c, b.seed()))) {
                lingering.add(b);
            }
        }

        // 3. clusters of live players and the places around them
        final List<List<Live>> cl = clusters(live, c.clusterLink());
        clusters = cl.size();
        cl.sort((a, b) -> b.size() - a.size());
        final double curve = c.curve(hour);
        final List<PlaceState> states = new ArrayList<>();
        final Set<String> claimed = new HashSet<>();
        int total = bots.size() + pending.size();
        boolean createdThisTick = false;
        for (List<Live> cluster : cl) {
            final Map<String, Demand> demand = new LinkedHashMap<>();
            for (Live p : cluster) {
                final WorldCatalog.Town t = catalog.townAt(p.pos(), c.townMargin());
                if (t != null) {
                    demand.merge(t.id(), new Demand(t.id(), true, t, null, 1, 0), (x, y) -> new Demand(x.id(), true, t, null, x.players() + 1, 0));
                }
            }
            for (Live p : cluster) {
                for (WorldCatalog.Ground g : catalog.groundsNear(p.pos(), c.groundReach())) {
                    final double edge = Math.max(0, g.center().distance2d(p.pos()) - g.radius());
                    final int inside = g.center().distance2d(p.pos()) <= g.radius() ? 1 : 0;
                    demand.merge(g.id(), new Demand(g.id(), false, null, g, inside, edge),
                        (x, y) -> new Demand(x.id(), false, null, g, x.players() + y.players(), Math.min(x.nearest(), y.nearest())));
                }
            }
            demand.keySet().removeAll(claimed);
            claimed.addAll(demand.keySet());
            // targets (TownWanderersMultiplier scales TOWN + hunt + PASS frequency)
            final double wander = c.wanderMult();
            final Map<String, Integer> target = new LinkedHashMap<>();
            int townSum = 0;
            for (Demand d : demand.values()) {
                if (d.town()) {
                    int t = (int) Math.round(townTarget(c, d.id(), hour, curve) * density * wander);
                    if (raceOf(d.id()) >= 0) {
                        t = Math.min(t, starterTownMax);
                    }
                    target.put(d.id(), t);
                    townSum += t;
                }
            }
            final List<Demand> grounds = new ArrayList<>();
            for (Demand d : demand.values()) {
                if (!d.town()) {
                    grounds.add(d);
                }
            }
            grounds.sort((a, b) -> Double.compare(a.nearest(), b.nearest()));
            final int clusterCap = (int) Math.round(c.maxPerCluster() * wander);
            final int huntBase = (int) Math.round((c.huntPerCluster() + (c.huntPerExtraPlayer() * (cluster.size() - 1))) * wander);
            int huntCap = (int) Math.round(Math.min(huntBase, Math.max(0, clusterCap - townSum)) * density);
            for (Demand d : grounds) {
                int t = (int) Math.round(groundTarget(c, d.groundRef(), curve) * density * wander) - d.players();
                t = Math.max(0, Math.min(t, huntCap));
                huntCap -= t;
                target.put(d.id(), t);
            }
            // materialize / leave per place
            for (Demand d : demand.values()) {
                final int want = target.getOrDefault(d.id(), 0);
                final List<BotNow> here = byPlace.getOrDefault(d.id(), Collections.emptyList());
                int pend = 0;
                for (Pending p : pending.values()) {
                    if (p.place().equals(d.id())) {
                        pend++;
                    }
                }
                final int have = here.size() + pend;
                if ((have < want) && (now >= nextSpawn.getOrDefault(d.id(), 0L)) && (total < c.maxInWorld())) {
                    spawnRoom = Math.min(want - have, c.maxInWorld() - total);
                    partners.clear();
                    final Command m = spawn(c, now, d, cluster, live, bots, pool, terrain, createdThisTick);
                    if (m != null) {
                        out.add(m);
                        total++;
                        out.addAll(partners);
                        total += partners.size();
                        if (((Command.Materialize) m).botId() == 0) {
                            createdThisTick = true;
                        }
                    }
                    nextSpawn.put(d.id(), now + c.spawnIntervalMinMs() + (long) (rnd.nextDouble() * Math.max(0, c.spawnIntervalMaxMs() - c.spawnIntervalMinMs())));
                } else if ((here.size() > want) && (now >= nextLeave.getOrDefault(d.id(), 0L))) {
                    final BotNow b = oldest(here);
                    final Command l = endOfStay(c, now, b, live, terrain, "more than " + want + " at " + d.id());
                    if (l != null) {
                        out.add(l);
                        nextLeave.put(d.id(), now + c.leaveIntervalMs());
                    }
                }
                states.add(new PlaceState(d.id(), d.town(), d.players(), want, here.size(), pend));
            }
            // gatekeeper transit in the towns of this cluster; a passer-by on the road where there is no place at all
            for (Demand d : demand.values()) {
                if (!d.town() || (c.transitMinMs() <= 0) || (total >= c.maxInWorld())) {
                    continue;
                }
                final String key = "transit:" + d.id();
                final Long next = nextSpawn.get(key);
                if (next == null) {
                    nextSpawn.put(key, now + (transitInterval(c, d.id(), curve, rnd) / 2));
                } else if (now >= next) {
                    nextSpawn.put(key, now + transitInterval(c, d.id(), curve, rnd));
                    final Command.Materialize m = transit(c, now, d.townRef(), live, pool, terrain, createdThisTick);
                    if (m != null) {
                        out.add(m);
                        total++;
                        createdThisTick |= m.botId() == 0;
                    }
                }
            }
            if (demand.isEmpty() && (c.passMinMs() > 0) && (total < c.maxInWorld())) {
                final String key = "road:" + cluster.get(0).id();
                final Long next = nextSpawn.get(key);
                if (next == null) {
                    nextSpawn.put(key, now + (passInterval(c, curve, rnd) / 2));
                } else if (now >= next) {
                    nextSpawn.put(key, now + passInterval(c, curve, rnd));
                    final Command.Materialize m = passer(c, now, cluster, live, pool, terrain, createdThisTick);
                    if (m != null) {
                        out.add(m);
                        total++;
                        createdThisTick |= m.botId() == 0;
                    }
                }
            }
        }
        // 4. bots that stood in front of a player for too long leave (one per place per interval)
        for (BotNow b : lingering) {
            final String place = b.assignment().place();
            if (now < nextLeave.getOrDefault(place, 0L)) {
                continue;
            }
            final Command l = endOfStay(c, now, b, live, terrain, "hunt over");
            if (l != null) {
                out.add(l);
                lingerLeaves++;
                tracks.get(b.botId()).lingered = true;
                nextLeave.put(place, now + c.leaveIntervalMs());
            }
        }
        
        // 5. Visible presence guarantee: ensure N non-trader bots are in sight of every player
        // Field (hunting zones): 1-3 bots actively farming within visibility
        // Towns: per-town ranges, traders excluded
        final int presenceTopups = ensurePresence(c, now, hour, live, bots, pool, terrain, out, total, createdThisTick);
        total += presenceTopups;
        
        together(out, bots, live);
        places = List.copyOf(states);
        return out;
    }


    // ------------------------------------------------------------------ visible presence guarantee
    
    private long presenceFieldTopups;
    private long presenceTownTopups;
    private long presenceLeaves;
    
    /** Ensure visible presence: field (1-3 bots farming nearby) and town (per-town ranges, traders excluded). */
    private int ensurePresence(DirectorConfig c, long now, int hour, List<Live> live, Collection<BotNow> bots, Pool pool, Terrain terrain,
        List<Command> out, int currentTotal, boolean createdThisTick) {
        // Implementation will be added
        return 0;
    }

    /** A pair or trio leaves together: the order one of them got goes to the others too (phase 4g). */
    private void together(List<Command> out, List<BotNow> bots, List<Live> live) {
        final Map<Integer, BotNow> byId = new HashMap<>();
        for (BotNow b : bots) {
            byId.put(b.botId(), b);
        }
        final Set<Integer> ordered = new HashSet<>();
        for (Command x : out) {
            if (x instanceof Command.Assign a) {
                ordered.add(a.botId());
            } else if (x instanceof Command.Logout l) {
                ordered.add(l.botId());
            }
        }
        final List<Command> more = new ArrayList<>();
        for (Command x : out) {
            if (!(x instanceof Command.Assign a) || !a.assignment().leaving()) {
                continue;
            }
            final BotNow b = byId.get(a.botId());
            if ((b == null) || (b.group() == 0)) {
                continue;
            }
            for (BotNow o : bots) {
                if ((o.group() != b.group()) || (o.botId() == b.botId()) || o.dead() || ordered.contains(o.botId()) || (o.assignment() == null) || o.assignment().leaving()) {
                    continue;
                }
                Assignment.Kind k = a.assignment().kind();
                if ((k == Assignment.Kind.LEAVE_SPOT) && (nearest(live, o.pos()) < SPOT_LOGOUT_CLEAR)) {
                    k = Assignment.Kind.LEAVE_SOE; // that one has somebody next to it
                }
                final Pos to = k == Assignment.Kind.LEAVE_SOE ? null : k == Assignment.Kind.LEAVE_SPOT ? o.pos() : a.assignment().exit();
                more.add(new Command.Assign(o.botId(), o.assignment().leave(++seq, k, to)));
                ordered.add(o.botId());
                partyLeaves++;
            }
        }
        out.addAll(more);
    }

    // ------------------------------------------------------------------ density

    static int townTarget(DirectorConfig c, String place, int hour, double curve) {
        final double lo;
        final double hi;
        if (curve < 0.5) {
            final double k = curve / 0.5;
            lo = lerp(c.townNight()[0], c.townDay()[0], k);
            hi = lerp(c.townNight()[1], c.townDay()[1], k);
        } else {
            final double k = Math.min(1, (curve - 0.5) / 0.5);
            lo = lerp(c.townDay()[0], c.townEvening()[0], k);
            hi = lerp(c.townDay()[1], c.townEvening()[1], k);
        }
        final int a = (int) Math.round(lo);
        final int b = Math.max(a, (int) Math.round(hi));
        return a + Math.floorMod((place.hashCode() * 31) + hour, (b - a) + 1); // stable for the place and hour
    }

    static int groundTarget(DirectorConfig c, WorldCatalog.Ground g, double curve) {
        double t = Math.max(c.groundMin(), Math.min(c.groundMax(), Math.round(g.monsters() / 8.0)));
        t *= 0.6 + (0.4 * Math.min(1, curve / 0.5));
        if (g.indoor()) {
            t *= c.indoorFactor();
        }
        t *= 1 - (0.5 * Math.max(0, Math.min(1, g.aggressive()))); // aggressive grounds: fewer (bots have no potions yet)
        return Math.max(1, (int) Math.round(t));
    }

    private static double lerp(double a, double b, double k) {
        return a + ((b - a) * k);
    }

    static long linger(DirectorConfig c, long seed) {
        final long span = Math.max(1, c.lingerMaxMs() - c.lingerMinMs());
        return c.lingerMinMs() + Math.floorMod(seed * 0x2545F4914F6CDD1DL, span);
    }

    // ------------------------------------------------------------------ in

    /**
     * Levels of hunters for a ground: a bit above the monsters (0-4), like players without top gear, soulshots and buffs
     * (phase 4) hunt; on aggressive grounds up to 4 more (all monsters aggressive = +4), because there several monsters
     * attack at once and bots have no potions yet.
     */
    static int[] huntLevels(WorldCatalog.Ground g) {
        final int extra = (int) Math.round(4 * Math.max(0, Math.min(1, g.aggressive())));
        final int lo = Math.max(1, Math.min(PaperLevel.MAX, g.maxLevel() + extra));
        final int hi = Math.min(PaperLevel.MAX, g.maxLevel() + 4 + extra);
        return new int[] { lo, hi };
    }

    private Command spawn(DirectorConfig c, long now, Demand d, List<Live> cluster, List<Live> live, List<BotNow> bots, Pool pool, Terrain terrain, boolean createdThisTick) {
        final int lo;
        final int hi;
        if (d.town()) {
            lo = d.townRef().minLevel();
            hi = d.townRef().maxLevel();
        } else {
            final int[] band = huntLevels(d.groundRef());
            lo = band[0];
            hi = band[1];
        }
        final Pos at = d.town() ? townPoint(d.townRef(), live, botPositions(bots), terrain) : hiddenPoint(c, d.groundRef(), live, terrain);
        if (at == null) {
            noSpot++;
            return null;
        }
        final List<Candidate> cands = new ArrayList<>(pool.available(lo, hi, now));
        // starting village or one of its grounds up to level 20: only its race (S5); elsewhere any
        final int race = d.town() ? raceOf(d.townRef().id()) : StarterZones.raceOfGround(d.groundRef());
        Candidate back = null;
        if (!d.town()) {
            // back from town (restock): the same persona on the same ground, from its edge, before anybody else
            comebacks.values().removeIf(x -> now > (x.due() + 30 * 60_000L));
            for (Candidate x : cands) {
                final Comeback cb = comebacks.get(x.botId());
                if ((cb != null) && cb.place().equals(d.id()) && (now >= cb.due()) && !pending.containsKey(x.botId()) && ((race < 0) || (x.race() == race))) {
                    back = x;
                    break;
                }
            }
        }
        cands.removeIf(x -> pending.containsKey(x.botId()) || ((now - x.lastLogout()) < c.returnCooldownMs()) || ((race >= 0) && (x.race() != race)));
        int botId = 0;
        int level;
        Candidate chosen = back;
        if (back != null) {
            comebacks.remove(back.botId());
            comebacksDone++;
            botId = back.botId();
            level = back.level();
        } else if (!cands.isEmpty()) {
            final Candidate pick = weighted(cands, now);
            chosen = pick;
            botId = pick.botId();
            level = pick.level();
        } else {
            if (createdThisTick) {
                noPersona++;
                return null; // one new persona per tick (it is a database insert)
            }
            level = lo + rnd.nextInt((hi - lo) + 1);
        }
        final Assignment a;
        if (d.town()) {
            final WorldCatalog.Town t = d.townRef();
            a = new Assignment(++seq, Assignment.Kind.TOWN, t.id(), t.center(), t.radius(), t.minLevel(), t.maxLevel(), List.of(), t.anchors(), null);
        } else {
            final WorldCatalog.Ground g = d.groundRef();
            a = new Assignment(++seq, Assignment.Kind.HUNT, g.id(), g.center(), g.radius(), g.minLevel(), g.maxLevel(), g.spots(), List.of(), null);
        }
        pending.put(botId == 0 ? --newKey : botId, new Pending(d.id(), a.seq(), now));
        materialized++;
        if (botId == 0) {
            created++;
        }
        note(now, (botId == 0 ? "new persona" : "bot " + botId) + " lv " + level + " -> " + a.kind() + " " + d.id() + " at " + at + (back != null ? " (back from town)" : ""));
        if (!d.town() && (botId != 0) && (back == null) && (spawnRoom >= 2) && (rnd.nextDouble() < partyShare)) {
            // a pair or a trio: personas of about the same level come in together from the same edge
            final int lvl = level;
            final int self = botId;
            final int anchorClan = chosen == null ? 0 : chosen.clanId();
            // phase 7d: clan bots usually party with clanmates; mixed with ClanMixedPartyChance
            final boolean mixed = (anchorClan != 0) && (rnd.nextDouble() < clanMixedPartyChance);
            List<Candidate> mates = new ArrayList<>(cands);
            mates.removeIf(x -> (x.botId() == self) || (Math.abs(x.level() - lvl) > 5));
            if ((anchorClan != 0) && !mixed) {
                final List<Candidate> same = new ArrayList<>();
                for (Candidate x : mates) {
                    if (x.clanId() == anchorClan) {
                        same.add(x);
                    }
                }
                mates = same;
            }
            final int want = Math.min(mates.size(), ((spawnRoom >= 3) && (rnd.nextDouble() < 0.35)) ? 2 : 1);
            if (want > 0) {
                final int group = ++groupSeq;
                parties++;
                final StringBuilder names = new StringBuilder();
                for (int i = 0; i < want; i++) {
                    final Candidate mate = mates.remove(rnd.nextInt(mates.size()));
                    final Assignment ma = new Assignment(++seq, a.kind(), a.place(), a.center(), a.radius(), a.minLevel(), a.maxLevel(), a.spots(), a.anchors(), a.exit());
                    final Pos mat = terrain.ground(new Pos(at.x() + 60 + (i * 50), at.y() + 40 - (i * 80), at.z()));
                    pending.put(mate.botId(), new Pending(d.id(), ma.seq(), now));
                    materialized++;
                    partners.add(new Command.Materialize(mate.botId(), mate.level(), mat == null ? at : mat, ma, -1, group));
                    names.append(' ').append(mate.botId());
                }
                note(now, "bot " + botId + " comes with" + names + " as a party (" + (want + 1) + ")"
                    + (anchorClan != 0 ? (mixed ? " mixed-clan" : " clan") : ""));
                return new Command.Materialize(botId, level, at, a, race, group);
            }
        }
        return new Command.Materialize(botId, level, at, a, race);
    }

    /** Next transit at this town: evening peak min..max, rarer by the online curve, much rarer in the starting villages. */
    static long transitInterval(DirectorConfig c, String townId, double curve, Random rnd) {
        final long base = c.transitMinMs() + (long) (rnd.nextDouble() * Math.max(0, c.transitMaxMs() - c.transitMinMs()));
        double k = 1.0 / Math.max(0.2, curve);
        if (raceOf(townId) >= 0) {
            k *= Math.max(1, c.transitStarterFactor());
        }
        return (long) (base * k);
    }

    /** Next passer-by: min..max at a usual hour (curve 0.5), twice as often at the evening peak, rarer at night; ÷ TownWanderersMultiplier. */
    static long passInterval(DirectorConfig c, double curve, Random rnd) {
        final long base = c.passMinMs() + (long) (rnd.nextDouble() * Math.max(0, c.passMaxMs() - c.passMinMs()));
        return (long) ((base * (0.5 / Math.max(0.25, curve))) / c.wanderMult());
    }

    /** A persona for a short appearance (transit, passer-by): one of the pool, else a new one (at most one per tick). */
    private int[] persona(DirectorConfig c, long now, Pool pool, int lo, int hi, int race, boolean createdThisTick) {
        final List<Candidate> cands = new ArrayList<>(pool.available(lo, hi, now));
        cands.removeIf(x -> pending.containsKey(x.botId()) || ((now - x.lastLogout()) < c.returnCooldownMs()) || ((race >= 0) && (x.race() != race)));
        if (!cands.isEmpty()) {
            final Candidate pick = weighted(cands, now);
            return new int[] { pick.botId(), pick.level() };
        }
        if (createdThisTick) {
            noPersona++;
            return null;
        }
        created++;
        return new int[] { 0, lo + rnd.nextInt((hi - lo) + 1) };
    }

    private Command.Materialize transit(DirectorConfig c, long now, WorldCatalog.Town t, List<Live> live, Pool pool, Terrain terrain, boolean createdThisTick) {
        final List<Pos> gks = t.anchors("gk");
        if (gks.isEmpty()) {
            return null;
        }
        Pos at = null;
        Pos gk = null;
        for (int k = 0; (k < 6) && (at == null); k++) {
            gk = gks.get(rnd.nextInt(gks.size()));
            final double ang = rnd.nextDouble() * Math.PI * 2;
            final int r = 80 + rnd.nextInt(171);
            final Pos approx = new Pos(gk.x() + (int) (Math.cos(ang) * r), gk.y() + (int) (Math.sin(ang) * r), gk.z());
            if (nearest(live, approx) >= 300) {
                at = terrain.ground(approx);
            }
        }
        if (at == null) {
            noSpot++;
            return null;
        }
        final int race = raceOf(t.id());
        final int[] who = persona(c, now, pool, t.minLevel(), t.maxLevel(), race, createdThisTick);
        if (who == null) {
            return null;
        }
        final Assignment a = new Assignment(++seq, Assignment.Kind.TRANSIT, t.id(), at, 400, t.minLevel(), t.maxLevel(), List.of(), t.anchors(), gk);
        pending.put(who[0] == 0 ? --newKey : who[0], new Pending("transit:" + t.id(), a.seq(), now));
        transits++;
        note(now, (who[0] == 0 ? "new persona" : "bot " + who[0]) + " lv " + who[1] + " -> TRANSIT " + t.id() + " at " + at);
        return new Command.Materialize(who[0], who[1], at, a, race);
    }

    /** Somebody runs along the road past the player: appears out of sight on one side, runs past, vanishes on the other. */
    private Command.Materialize passer(DirectorConfig c, long now, List<Live> cluster, List<Live> live, Pool pool, Terrain terrain, boolean createdThisTick) {
        final Live p0 = cluster.get(rnd.nextInt(cluster.size()));
        WorldCatalog.Town near = null;
        for (WorldCatalog.Town t : catalog.towns()) {
            if ((near == null) || (t.center().distance2d(p0.pos()) < near.center().distance2d(p0.pos()))) {
                near = t;
            }
        }
        final int lo = near == null ? 1 : near.minLevel();
        final int hi = near == null ? 20 : Math.max(lo, near.maxLevel());
        for (int k = 0; k < 8; k++) {
            final double ang = rnd.nextDouble() * Math.PI * 2;
            final double r = c.hiddenMin() + (rnd.nextDouble() * 600);
            final Pos from0 = new Pos(p0.pos().x() + (int) (Math.cos(ang) * r), p0.pos().y() + (int) (Math.sin(ang) * r), p0.pos().z());
            final double back = ang + Math.PI + (Math.toRadians(10 + rnd.nextInt(25)) * (rnd.nextBoolean() ? 1 : -1));
            final double r2 = c.unseenRadius() + 800;
            final Pos to0 = new Pos(p0.pos().x() + (int) (Math.cos(back) * r2), p0.pos().y() + (int) (Math.sin(back) * r2), p0.pos().z());
            if (nearest(live, from0) < c.hiddenMin()) {
                continue;
            }
            final Pos from = terrain.ground(from0);
            final Pos to = terrain.ground(to0);
            if ((from == null) || (to == null) || (nearest(live, from) < c.hiddenMin())) {
                continue;
            }
            final int race = passRace(p0.pos(), from, to);
            final int[] who = persona(c, now, pool, lo, hi, race, createdThisTick);
            if (who == null) {
                return null;
            }
            final Assignment a = new Assignment(++seq, Assignment.Kind.PASS, "road", p0.pos(), 0, lo, hi, List.of(), List.of(), to);
            pending.put(who[0] == 0 ? --newKey : who[0], new Pending("road", a.seq(), now));
            passers++;
            note(now, (who[0] == 0 ? "new persona" : "bot " + who[0]) + " lv " + who[1] + " -> PASS by player " + p0.id() + " from " + from + " to " + to);
            return new Command.Materialize(who[0], who[1], from, a, race);
        }
        noSpot++;
        return null;
    }

    private Candidate weighted(List<Candidate> cands, long now) {
        double sum = 0;
        final double[] w = new double[cands.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = 1 + (Math.min(86_400_000L, Math.max(0, now - cands.get(i).lastLogout())) / 3_600_000.0); // long gone = more likely
            w[i] *= 0.1 + Math.max(0, Math.min(1, cands.get(i).activity())); // their hours (chronotype): far more likely
            if (cands.get(i).clanStockPending()) {
                w[i] *= 12; // phase 7e: get provisional leaders in so stock clans become visible
            }
            sum += w[i];
        }
        double r = rnd.nextDouble() * sum;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0) {
                return cands.get(i);
            }
        }
        return cands.get(cands.size() - 1);
    }

    /** A point of the ground nobody can see: a free spot first, else a point on the ring around the nearest player. */
    Pos hiddenPoint(DirectorConfig c, WorldCatalog.Ground g, List<Live> live, Terrain terrain) {
        final List<Pos> spots = new ArrayList<>(g.spots());
        Collections.shuffle(spots, rnd);
        // in a dungeon walls hide: a spot closer than usual will do when no player has a line of sight to it
        final double min = g.indoor() ? Math.min(1500, c.hiddenMin()) : c.hiddenMin();
        for (Pos s : spots) {
            final double d = nearest(live, s);
            if ((d >= min) && (d <= (c.hiddenMax() + 2500))) {
                final Pos p = terrain.ground(s);
                if ((p != null) && (nearest(live, p) >= min) && ((nearest(live, p) >= c.hiddenMin()) || !inSight(c, live, p, terrain))) {
                    return p;
                }
            }
        }
        final Live p0 = nearestLive(live, g.center());
        if (p0 == null) {
            return null;
        }
        final double base = Math.atan2(g.center().y() - p0.pos().y(), g.center().x() - p0.pos().x());
        for (int k = 0; k <= 12; k++) {
            final int step = (k + 1) / 2;
            final double a = base + Math.toRadians(20 * step * ((k % 2) == 0 ? 1 : -1));
            final double r = c.hiddenMin() + (rnd.nextDouble() * Math.max(0, c.hiddenMax() - c.hiddenMin()));
            final Pos approx = new Pos(p0.pos().x() + (int) (Math.cos(a) * r), p0.pos().y() + (int) (Math.sin(a) * r), g.center().z());
            if ((nearest(live, approx) < c.hiddenMin()) || (approx.distance2d(g.center()) > (g.radius() + c.hiddenMax()))) {
                continue;
            }
            final Pos p = terrain.ground(approx);
            if ((p != null) && (nearest(live, p) >= c.hiddenMin())) {
                return p;
            }
        }
        return null;
    }

    /** Some player within the unseen radius has a line of sight to {@code p}. */
    static boolean inSight(DirectorConfig c, List<Live> live, Pos p, Terrain terrain) {
        for (Live l : live) {
            if ((l.pos().distance2d(p) <= c.unseenRadius()) && terrain.canSee(l.pos(), p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Near the gatekeeper / shops / trainers (or restart in big towns), not under a player's nose or on another bot.
     * Starting villages never use {@code restart} anchors: those often are the stock create/SOE node piles
     * (Einhovant four points, Cedric, etc.). For towns, checks that the point is inside the town polygon.
     */
    Pos townPoint(WorldCatalog.Town t, List<Live> live, List<Pos> bots, Terrain terrain) {
        final boolean starter = raceOf(t.id()) >= 0;
        final List<Pos> spots = new ArrayList<>();
        for (Assignment.Anchor a : t.anchors()) {
            if (a.kind().equals("gk")) {
                spots.add(a.pos());
                spots.add(a.pos());
            } else if (!starter && a.kind().equals("restart")) {
                spots.add(a.pos());
            } else if (a.kind().equals("shop") || a.kind().equals("wh") || a.kind().equals("trainer")) {
                spots.add(a.pos());
            }
        }
        Collections.shuffle(spots, rnd);
        for (Pos s : spots) {
            final double ang = rnd.nextDouble() * Math.PI * 2;
            final int r = 120 + rnd.nextInt(281); // 120..400
            final Pos approx = new Pos(s.x() + (int) (Math.cos(ang) * r), s.y() + (int) (Math.sin(ang) * r), s.z());
            if (nearest(live, approx) < 300) {
                continue;
            }
            if (nearestPos(bots, approx) < 200) {
                continue;
            }
            final Pos p = terrain.ground(approx);
            // Check that town points are inside the town polygon
            if (p != null && t.contains(p.x(), p.y())) {
                return p;
            }
        }
        return null;
    }

    static List<Pos> botPositions(List<BotNow> bots) {
        final List<Pos> out = new ArrayList<>(bots.size());
        for (BotNow b : bots) {
            out.add(b.pos());
        }
        return out;
    }

    static double nearestPos(List<Pos> pts, Pos p) {
        double best = Double.POSITIVE_INFINITY;
        for (Pos x : pts) {
            best = Math.min(best, x.distance2d(p));
        }
        return best;
    }

    // ------------------------------------------------------------------ out

    /**
     * The end of a stay (phase 4d). A hunter: "went to town and back" (RestockShare: scroll of escape or running off,
     * the same persona comes back to the same ground from its edge later), else a human logout by LogoutStyle (scroll of
     * escape and logout in town, logout on the spot sitting when nobody is near, walking off to the gatekeeper). A town
     * bot leaves as before (gatekeeper or running off).
     */
    Command endOfStay(DirectorConfig c, long now, BotNow b, List<Live> live, Terrain terrain, String why) {
        final Assignment a = b.assignment();
        if (a.kind() != Assignment.Kind.HUNT) {
            return leave(c, now, b, live, terrain, why);
        }
        final Life l = life;
        final boolean restock = (b.group() == 0) && (rnd.nextDouble() < l.restockShare()); // a party just logs out together
        String how;
        if (restock) {
            how = rnd.nextDouble() < 0.6 ? "soe" : "gk";
        } else {
            final double sum = Math.max(1e-9, l.soe() + l.spot() + l.gk());
            final double x = rnd.nextDouble() * sum;
            how = x < l.soe() ? "soe" : x < (l.soe() + l.spot()) ? "spot" : "gk";
        }
        if (how.equals("spot") && (nearest(live, b.pos()) < SPOT_LOGOUT_CLEAR)) {
            how = "gk"; // somebody would see it vanish
        }
        final Command cmd;
        if (how.equals("soe")) {
            leaves++;
            soeLeaves++;
            note(now, "bot " + b.botId() + " reads a scroll of escape at " + a.place() + " (" + why + (restock ? ", back later" : ", logs out in town") + ")");
            cmd = new Command.Assign(b.botId(), a.leave(++seq, Assignment.Kind.LEAVE_SOE, null));
        } else if (how.equals("spot")) {
            leaves++;
            spotLogouts++;
            note(now, "bot " + b.botId() + " logs out on the spot at " + a.place() + " (" + why + ")");
            cmd = new Command.Assign(b.botId(), a.leave(++seq, Assignment.Kind.LEAVE_SPOT, b.pos()));
        } else {
            cmd = leave(c, now, b, live, terrain, why + (restock ? ", back later" : ""));
        }
        if ((cmd != null) && restock) {
            restocks++;
            final long span = Math.max(0, l.awayMaxMs() - l.awayMinMs());
            comebacks.put(b.botId(), new Comeback(a.place(), now + l.awayMinMs() + (long) (rnd.nextDouble() * span)));
        }
        return cmd;
    }

    private Command leave(DirectorConfig c, long now, BotNow b, List<Live> live, Terrain terrain, String why) {
        final Assignment a = b.assignment();
        if (a.kind() == Assignment.Kind.TOWN) {
            final WorldCatalog.Town t = catalog.town(a.place());
            if ((t != null) && rnd.nextBoolean()) {
                Pos gk = null;
                for (Pos p : t.anchors("gk")) {
                    if ((gk == null) || (p.distance2d(b.pos()) < gk.distance2d(b.pos()))) {
                        gk = p;
                    }
                }
                if (gk != null) {
                    leaves++;
                    note(now, "bot " + b.botId() + " leaves " + a.place() + " by the gatekeeper (" + why + ")");
                    return new Command.Assign(b.botId(), a.leave(++seq, Assignment.Kind.LEAVE_GK, gk));
                }
            }
        }
        final Pos exit = runOutPoint(c, b.pos(), live, terrain);
        if (exit == null) {
            return null;
        }
        leaves++;
        note(now, "bot " + b.botId() + " runs off from " + a.place() + " (" + why + ")");
        return new Command.Assign(b.botId(), a.leave(++seq, Assignment.Kind.LEAVE_RUN, exit));
    }

    /** A point out of everybody's sight, away from the nearest player. */
    Pos runOutPoint(DirectorConfig c, Pos from, List<Live> live, Terrain terrain) {
        final Live p0 = nearestLive(live, from);
        double base = p0 == null ? rnd.nextDouble() * Math.PI * 2 : Math.atan2(from.y() - p0.pos().y(), from.x() - p0.pos().x());
        if ((p0 != null) && (p0.pos().distance2d(from) < 1)) {
            base = rnd.nextDouble() * Math.PI * 2;
        }
        final Pos origin = p0 == null ? from : p0.pos();
        for (int deg : new int[] { 0, 30, -30, 60, -60, 90, -90, 135, -135 }) {
            final double a = base + Math.toRadians(deg);
            final double r = c.unseenRadius() + 800;
            final Pos approx = new Pos(origin.x() + (int) (Math.cos(a) * r), origin.y() + (int) (Math.sin(a) * r), from.z());
            if (nearest(live, approx) < (c.unseenRadius() + 200)) {
                continue;
            }
            final Pos p = terrain.ground(approx);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private BotNow oldest(List<BotNow> here) {
        BotNow best = here.get(0);
        for (BotNow b : here) {
            final Track tb = tracks.get(b.botId());
            final Track tbest = tracks.get(best.botId());
            if ((tb != null) && (tbest != null) && (tb.since < tbest.since)) {
                best = b;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ helpers

    static double nearest(List<Live> live, Pos p) {
        double best = Double.POSITIVE_INFINITY;
        for (Live l : live) {
            best = Math.min(best, l.pos().distance2d(p));
        }
        return best;
    }

    static Live nearestLive(List<Live> live, Pos p) {
        Live best = null;
        for (Live l : live) {
            if ((best == null) || (l.pos().distance2d(p) < best.pos().distance2d(p))) {
                best = l;
            }
        }
        return best;
    }

    static List<List<Live>> clusters(List<Live> live, int link) {
        final int n = live.size();
        final int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (live.get(i).pos().distance2d(live.get(j).pos()) <= link) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        final Map<Integer, List<Live>> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            m.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(live.get(i));
        }
        return new ArrayList<>(m.values());
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private void note(long now, String s) {
        decisions.addLast(s);
        while (decisions.size() > 10) {
            decisions.removeFirst();
        }
    }

    // ------------------------------------------------------------------ status

    public List<PlaceState> places() {
        return places;
    }

    public synchronized List<String> decisions() {
        return new ArrayList<>(decisions);
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    /** Separate lines for //bots director: road, transit, dungeons, leaving - now and since the start. */
    public List<String> kindLines() {
        return List.of("Road (passers-by): " + roadNow + " now, " + passers + " since start", "Transit (gatekeeper): " + transitNow + " now, " + transits + " since start",
            "Dungeons: " + indoorNow + " bots on indoor grounds now", "Leaving: " + leavingNow + " now, " + leaves + " leave orders since start",
            "Ends of hunts: " + soeLeaves + " scroll of escape, " + spotLogouts + " logout on the spot, " + restocks + " went to town (" + errandsTrips + " errands seen, "
                + comebacksDone + " came back, " + comebacks.size() + " expected); parties " + parties + " (share " + Math.round(partyShare * 100) + "%), left together " + partyLeaves);
    }

    public synchronized String statsLine() {
        int target = 0;
        for (PlaceState p : places) {
            target += p.target();
        }
        return "DIRECTOR players=" + livePlayers + " clusters=" + clusters + " places=" + places.size() + " target=" + target + " pending=" + pending.size()
            + " materialized=" + materialized + " created=" + created + " leaves=" + leaves + " lingerLeaves=" + lingerLeaves + " unseenLogouts=" + unseenLogouts
            + " noSpot=" + noSpot + " noPersona=" + noPersona + " transits=" + transits + " passers=" + passers + " restocks=" + restocks + " comebacks=" + comebacksDone + " parties=" + parties + " partyLeaves=" + partyLeaves + " presence=" + presenceFieldTopups + "/" + presenceTownTopups + " leaves=" + presenceLeaves + (density < 1 ? " density=" + density : "");
    }
}
