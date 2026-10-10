package org.l2start.bots.director;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
    public record BotNow(int botId, Pos pos, Assignment assignment, boolean dead, long seed) {
    }

    /** A persona that could enter: not in the world, its level now (paper or real, the higher). */
    public record Candidate(int botId, int level, long lastLogout) {
    }

    /** Personas not in the world whose level is in the band (and that did not leave just now). */
    public interface Pool {
        List<Candidate> available(int minLevel, int maxLevel, long now);
    }

    /** Geodata: a walkable point near {@code approx} (with the right height), or null. */
    public interface Terrain {
        Pos ground(Pos approx);
    }

    public sealed interface Command {
        /** Bring this persona (botId 0 = create a new one) at {@code level} into the world at {@code at} with this order. */
        record Materialize(int botId, int level, Pos at, Assignment assignment) implements Command {
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

    public Director(WorldCatalog catalog, DirectorConfig cfg, Random rnd) {
        this.catalog = catalog;
        this.cfg = cfg;
        this.rnd = rnd;
    }

    public void setConfig(DirectorConfig c) {
        cfg = c;
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
        for (BotNow b : bots) {
            final Track t = tracks.computeIfAbsent(b.botId(), k -> {
                final Track n = new Track();
                n.since = now;
                return n;
            });
            final double d = nearest(live, b.pos());
            if (d > c.unseenRadius()) {
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
            final boolean leaving = (a != null) && a.leaving();
            if ((t.unseenSince != 0) && ((leaving && ((now - t.unseenSince) >= 3000)) || ((now - t.unseenSince) >= c.unseenLogoutMs()))) {
                out.add(new Command.Logout(b.botId(), leaving ? "left unseen" : "nobody around"));
                unseenLogouts++;
                tracks.remove(b.botId());
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
            // targets
            final Map<String, Integer> target = new LinkedHashMap<>();
            int townSum = 0;
            for (Demand d : demand.values()) {
                if (d.town()) {
                    final int t = townTarget(c, d.id(), hour, curve);
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
            int huntCap = Math.min(c.huntPerCluster() + (c.huntPerExtraPlayer() * (cluster.size() - 1)), Math.max(0, c.maxPerCluster() - townSum));
            for (Demand d : grounds) {
                int t = groundTarget(c, d.groundRef(), curve) - d.players();
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
                    final Command m = spawn(c, now, d, cluster, live, pool, terrain, createdThisTick);
                    if (m != null) {
                        out.add(m);
                        total++;
                        if (((Command.Materialize) m).botId() == 0) {
                            createdThisTick = true;
                        }
                    }
                    nextSpawn.put(d.id(), now + c.spawnIntervalMinMs() + (long) (rnd.nextDouble() * Math.max(0, c.spawnIntervalMaxMs() - c.spawnIntervalMinMs())));
                } else if ((here.size() > want) && (now >= nextLeave.getOrDefault(d.id(), 0L))) {
                    final BotNow b = oldest(here);
                    final Command l = leave(c, now, b, live, terrain, "more than " + want + " at " + d.id());
                    if (l != null) {
                        out.add(l);
                        nextLeave.put(d.id(), now + c.leaveIntervalMs());
                    }
                }
                states.add(new PlaceState(d.id(), d.town(), d.players(), want, here.size(), pend));
            }
        }
        // 4. bots that stood in front of a player for too long leave (one per place per interval)
        for (BotNow b : lingering) {
            final String place = b.assignment().place();
            if (now < nextLeave.getOrDefault(place, 0L)) {
                continue;
            }
            final Command l = leave(c, now, b, live, terrain, "seen too long");
            if (l != null) {
                out.add(l);
                lingerLeaves++;
                tracks.get(b.botId()).lingered = true;
                nextLeave.put(place, now + c.leaveIntervalMs());
            }
        }
        places = List.copyOf(states);
        return out;
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

    private Command spawn(DirectorConfig c, long now, Demand d, List<Live> cluster, List<Live> live, Pool pool, Terrain terrain, boolean createdThisTick) {
        final int lo;
        final int hi;
        if (d.town()) {
            lo = d.townRef().minLevel();
            hi = d.townRef().maxLevel();
        } else {
            // a bit above the monsters, like players without top gear, soulshots and buffs (phase 4) hunt
            lo = Math.max(1, d.groundRef().maxLevel());
            hi = Math.min(PaperLevel.MAX, d.groundRef().maxLevel() + 4);
        }
        final Pos at = d.town() ? townPoint(d.townRef(), live, terrain) : hiddenPoint(c, d.groundRef(), live, terrain);
        if (at == null) {
            noSpot++;
            return null;
        }
        final List<Candidate> cands = new ArrayList<>(pool.available(lo, hi, now));
        cands.removeIf(x -> pending.containsKey(x.botId()) || ((now - x.lastLogout()) < c.returnCooldownMs()));
        int botId = 0;
        int level;
        if (!cands.isEmpty()) {
            final Candidate pick = weighted(cands, now);
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
        note(now, (botId == 0 ? "new persona" : "bot " + botId) + " lv " + level + " -> " + a.kind() + " " + d.id() + " at " + at);
        return new Command.Materialize(botId, level, at, a);
    }

    private Candidate weighted(List<Candidate> cands, long now) {
        double sum = 0;
        final double[] w = new double[cands.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = 1 + (Math.min(86_400_000L, Math.max(0, now - cands.get(i).lastLogout())) / 3_600_000.0); // long gone = more likely
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
        for (Pos s : spots) {
            final double d = nearest(live, s);
            if ((d >= c.hiddenMin()) && (d <= (c.hiddenMax() + 2500))) {
                final Pos p = terrain.ground(s);
                if ((p != null) && (nearest(live, p) >= c.hiddenMin())) {
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

    /** At the gatekeeper (as after a teleport) or a restart point (as after a scroll of escape), not under a player's nose. */
    Pos townPoint(WorldCatalog.Town t, List<Live> live, Terrain terrain) {
        final List<Pos> spots = new ArrayList<>();
        for (Assignment.Anchor a : t.anchors()) {
            if (a.kind().equals("gk")) {
                spots.add(a.pos());
                spots.add(a.pos());
            } else if (a.kind().equals("restart")) {
                spots.add(a.pos());
            }
        }
        Collections.shuffle(spots, rnd);
        for (Pos s : spots) {
            final double ang = rnd.nextDouble() * Math.PI * 2;
            final int r = 80 + rnd.nextInt(171);
            final Pos approx = new Pos(s.x() + (int) (Math.cos(ang) * r), s.y() + (int) (Math.sin(ang) * r), s.z());
            if (nearest(live, approx) < 300) {
                continue;
            }
            final Pos p = terrain.ground(approx);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ out

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

    public synchronized String statsLine() {
        int target = 0;
        for (PlaceState p : places) {
            target += p.target();
        }
        return "DIRECTOR players=" + livePlayers + " clusters=" + clusters + " places=" + places.size() + " target=" + target + " pending=" + pending.size()
            + " materialized=" + materialized + " created=" + created + " leaves=" + leaves + " lingerLeaves=" + lingerLeaves + " unseenLogouts=" + unseenLogouts
            + " noSpot=" + noSpot + " noPersona=" + noPersona;
    }
}
