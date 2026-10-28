package org.l2start.bots.core;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2start.bots.api.ActionResult;
import org.l2start.bots.api.Actuators;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Brain;
import org.l2start.bots.api.BrainContext;
import org.l2start.bots.api.BrainFactory;
import org.l2start.bots.api.BrainRegistry;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.WorldView;
import org.l2start.bots.persona.Persona;
import org.l2start.bots.persona.Traits;
import org.l2start.bots.runtime.BotClock;
import org.l2start.bots.runtime.BotSlot;
import org.l2start.bots.runtime.Lod;
import org.l2start.bots.runtime.TickHandler;
import org.l2start.bots.runtime.Ticker;
import org.l2start.platform.api.ModuleContext;

/**
 * The bot runtime in the game (step 3c): one {@link BotSlot} per bot in the world, ticked by the shards of a
 * {@link Ticker} (periodic tasks on the stock thread pool), brains from the factories registered through the
 * {@link BrainRegistry} service, events from stock listeners into the bot mailboxes, LOD ACTIVE/PRESENT by the
 * distance to live players.
 */
public final class BrainHost implements BrainRegistry, TickHandler, MobiusActuators.Sink {
    /** Host data of one bot (the slot's owner). */
    final class Mind {
        final BotRecord r;
        final BotSlot slot;
        final MobiusActuators act;
        final Context ctx;
        volatile BrainFactory factory;
        long lodCheckedAt;
        double nearestLive = Double.POSITIVE_INFINITY;
        // idle watch (docs/STARTER_ZONES_PLAN.md S3)
        final org.l2start.bots.runtime.IdleWatch idle = new org.l2start.bots.runtime.IdleWatch();
        volatile org.l2start.bots.api.Activity activity;
        Pos teleportPending;
        long warnedAt;
        long separatedAt;

        Mind(BotRecord r, long first) {
            this.r = r;
            slot = new BotSlot(r.botId(), r.name(), this, first);
            act = new MobiusActuators(r, world, BrainHost.this);
            ctx = new Context(this);
        }
    }

    /** {@link BrainContext} of one bot; the world view is replaced every tick. */
    final class Context implements BrainContext {
        private final Mind m;
        private final Random rnd;
        private final Map<String, Object> blackboard = new HashMap<>();
        private BotInfo info;
        private WorldView view;

        Context(Mind m) {
            this.m = m;
            final Persona p = m.r.persona();
            rnd = new Random((p == null ? m.r.charId() : p.seed()) ^ System.nanoTime());
            info = BrainHost.info(m.r);
        }

        @Override public BotInfo info() { return info; }
        @Override public WorldView world() { return view; }
        @Override public Actuators act() { return m.act; }
        @Override public Random rnd() { return rnd; }
        @Override public long now() { return clock.now(); }
        @Override public Map<String, Object> blackboard() { return blackboard; }
        @Override public org.l2start.bots.api.Assignment assignment() { return m.r.assignment(); }

        @Override
        public long reactionDelay() {
            return Math.max(200, Math.min(1200, Math.round(info.reactionMs() * (0.7 + (rnd.nextDouble() * 0.6)))));
        }

        @Override
        public void note(String text) {
            m.slot.log().add(clock.now(), text);
        }

        private org.l2start.bots.api.Chat chat;

        @Override
        public org.l2start.bots.api.Memory memory() {
            final MemoryStore ms = memories;
            return ms == null ? NO_MEMORY : ms.of(m.r.botId());
        }

        @Override
        public org.l2start.bots.api.Chat chat() {
            final org.l2start.bots.chat.TemplateBrain t = templates;
            if (t == null) {
                return org.l2start.bots.api.Chat.SILENT;
            }
            if (chat == null) {
                chat = new org.l2start.bots.chat.TemplateChat(t, info, rnd);
            }
            return chat;
        }

        @Override
        public org.l2start.bots.chat.SocialBoard social() {
            final org.l2start.bots.chat.SocialBoard b = social;
            return b == null ? org.l2start.bots.chat.SocialBoard.NONE : b;
        }

        @Override
        public org.l2start.bots.chat.PvpBoard pvp() {
            return BrainHost.this.pvp();
        }
    }

    /** Phase 6a: memories of real players (null = no database: nothing is remembered). */
    private volatile MemoryStore memories;

    void setMemories(MemoryStore m) {
        memories = m;
    }

    MemoryStore memories() {
        return memories;
    }

    /** Phase 6a: the phrase banks (null = none: bots are silent). */
    private volatile org.l2start.bots.chat.TemplateBrain templates;

    void setTemplates(org.l2start.bots.chat.TemplateBrain t) {
        templates = t;
    }

    /** Phase 6d: world LFP shout/reply limits (null = none). */
    private volatile org.l2start.bots.chat.SocialBoard social;

    void setSocial(org.l2start.bots.chat.SocialBoard board) {
        social = board;
    }

    /** Phase 6g: world social board for {@code //bots social} (null before start). */
    public org.l2start.bots.chat.SocialBoard social() {
        return social;
    }

    /** Phase 7b: hourly PvP counters for {@code //bots pvp}. */
    private volatile org.l2start.bots.chat.PvpBoard pvp = new org.l2start.bots.chat.PvpBoard();

    public org.l2start.bots.chat.PvpBoard pvp() {
        return pvp == null ? org.l2start.bots.chat.PvpBoard.NONE : pvp;
    }

    private final ModuleContext ctx;
    private final BotRegistry registry;
    private final BotWorld world;
    private final Consumer<BotRecord> logout;
    private final BotClock clock = BotClock.SYSTEM;
    private final Map<String, BrainFactory> factories = new ConcurrentHashMap<>();
    private final Map<Integer, Mind> minds = new ConcurrentHashMap<>();
    private volatile java.util.function.Supplier<java.util.List<Pos>> anchors = java.util.List::of;
    private volatile org.l2start.bots.director.GkGraph gkGraph;
    private volatile Ticker ticker;
    private final org.l2start.bots.runtime.Overload overload = new org.l2start.bots.runtime.Overload();
    private volatile String brainId = "basic";
    private volatile int viewRadius = 1500;
    private volatile int promoteRadius = 3000;
    private volatile int demoteRadius = 4500;
    private volatile long demoteDelay = 45_000;
    private long lastLogTicks;
    private long lastLogBusy;
    private long lastLogAt;
    private long lastLogDeaths;

    BrainHost(ModuleContext ctx, BotRegistry registry, BotWorld world, Consumer<BotRecord> logout) {
        this.ctx = ctx;
        this.registry = registry;
        this.world = world;
        this.logout = logout;
    }

    static BotInfo info(BotRecord r) {
        final Persona p = r.persona();
        final Traits t = p == null ? new Traits(0.5, 0.5, 0.5, 0.5, 0.5, 0.5) : p.traits();
        return new BotInfo(r.botId(), r.charId(), r.name(), r.classId(), (p != null) && p.female(), p == null ? "RU" : String.valueOf(p.lang()), t.aggression(), t.sociability(),
            t.greed(), t.patience(), t.skill(), t.chattiness(), t.reactionMs(), new Pos(r.homeX(), r.homeY(), r.homeZ()), p == null ? r.charId() : p.seed(), (p != null) && p.pk());
    }

    /** Reads the runtime keys; the shard count and wave settings take effect on the next start. */
    void applyConfig() {
        brainId = ctx.config().getString("Brain").trim().toLowerCase(Locale.ROOT);
        viewRadius = ctx.config().getInt("ViewRadius");
        promoteRadius = ctx.config().getInt("PromoteRadius");
        demoteRadius = Math.max(promoteRadius, ctx.config().getInt("DemoteRadius"));
        demoteDelay = ctx.config().getDuration("DemoteDelay").toMillis();
        if (overload.force(org.l2start.bots.runtime.Overload.parse(ctx.config().getString("Overload")))) {
            ctx.log().info("overload: " + overload.describe());
        }
        for (Mind m : minds.values()) {
            ensureBrain(m);
        }
    }

    /** Starts the shards (onServerReady). */
    void start() {
        final int configured = ctx.config().getInt("Shards");
        final int n = configured > 0 ? configured : Ticker.autoShards(Runtime.getRuntime().availableProcessors());
        final long wave = Math.max(20, ctx.config().getInt("WaveMs"));
        final long budget = Math.max(1, ctx.config().getInt("WaveBudgetMs"));
        final Ticker t = new Ticker(n, this, budget, System::nanoTime);
        for (Mind m : minds.values()) {
            t.add(m.slot);
        }
        ticker = t;
        for (int i = 0; i < n; i++) {
            final int shard = i;
            ctx.scheduler().fixedRate("shard " + i, Duration.ofMillis(wave + i), Duration.ofMillis(wave), () -> t.wave(shard, clock.now()));
        }
        final Duration every = ctx.config().getDuration("StatsLogInterval");
        if (!every.isZero()) {
            ctx.scheduler().fixedRate("stats", every, every, this::logStats);
        }
        ctx.scheduler().fixedRate("overload", Duration.ofSeconds(10), Duration.ofSeconds(10), () -> {
            if (overload.sample(clock.now(), t.waves(), t.overruns())) {
                ctx.metrics().counter("overloadSteps").inc();
                ctx.log().warn("overload: " + overload.describe() + (overload.density() < 1 ? ": the director wants 30% fewer bots" : overload.step() == org.l2start.bots.runtime.Overload.Step.SLOW_FAR ? ": far active bots think half as often" : ": back to normal"));
            }
        });
        lastLogAt = clock.now();
        ctx.log().info("runtime: " + n + " shards, wave " + wave + " ms, budget " + budget + " ms, brain '" + brainId + "'" + (factories.containsKey(brainId) ? "" : " (not registered yet)"));
    }

    // ------------------------------------------------------------------ BrainRegistry

    @Override
    public void register(BrainFactory factory) {
        factories.put(factory.id().toLowerCase(Locale.ROOT), factory);
        ctx.log().info("brain '" + factory.id() + "' registered" + (factory.id().equalsIgnoreCase(brainId) ? " (in use)" : " (not in use: Brain=" + brainId + ")"));
        for (Mind m : minds.values()) {
            ensureBrain(m);
        }
    }

    @Override
    public void unregister(String id) {
        final BrainFactory f = factories.remove(id.toLowerCase(Locale.ROOT));
        if (f == null) {
            return;
        }
        for (Mind m : minds.values()) {
            ensureBrain(m);
        }
        ctx.log().info("brain '" + id + "' unregistered");
    }

    private void ensureBrain(Mind m) {
        final BrainFactory f = factories.get(brainId);
        final Brain old = m.slot.brain();
        if ((f == null) && (old == null)) {
            return;
        }
        if ((f != null) && (old != null) && (f == m.factory)) {
            return;
        }
        if (old != null) {
            try {
                old.stop(m.ctx);
            } catch (RuntimeException e) {
                ctx.log().warn("brain stop of " + m.r.name() + " failed", e);
            }
        }
        m.ctx.blackboard().clear();
        m.factory = f;
        if (f == null) {
            m.slot.setBrain(null);
            return;
        }
        m.ctx.info = BrainHost.info(m.r);
        m.slot.setBrain(f.create(m.ctx.info));
        m.slot.wake(clock.now() + 1000);
    }

    // ------------------------------------------------------------------ bots entering / leaving

    void entered(BotRecord r) {
        final long first = clock.now() + ThreadLocalRandom.current().nextLong(1000, 4000); // not all at once after a restore
        final Mind m = new Mind(r, first);
        minds.put(r.charId(), m);
        r.setSlot(m.slot);
        ensureBrain(m);
        final Ticker t = ticker;
        if (t != null) {
            t.add(m.slot);
        }
    }

    void left(BotRecord r) {
        final Mind m = minds.remove(r.charId());
        r.setSlot(null);
        final MemoryStore ms = memories;
        if (ms != null) {
            ctx.scheduler().schedule("memory " + r.name(), java.time.Duration.ZERO, () -> {
                try {
                    ms.release(r.botId());
                } catch (Exception e) {
                    ctx.log().warn("bot memory of " + r.name() + " not saved: " + e.getMessage());
                }
            });
        }
        if (m == null) {
            return;
        }
        final Ticker t = ticker;
        if (t != null) {
            t.remove(m.slot);
        } else {
            m.slot.markRemoved();
        }
        final Brain b = m.slot.brain();
        if (b != null) {
            try {
                b.stop(m.ctx);
            } catch (RuntimeException e) {
                // the bot is leaving anyway
            }
        }
    }

    void setGkGraph(org.l2start.bots.director.GkGraph g) {
        gkGraph = g;
    }

    @Override
    public org.l2start.bots.director.GkGraph gkGraph() {
        return gkGraph;
    }

    void setAnchors(java.util.function.Supplier<java.util.List<Pos>> a) {
        anchors = a;
    }

    /** Tick the bot soon (a new director assignment). */
    void wake(BotRecord r) {
        final Mind m = r == null ? null : minds.get(r.charId());
        if (m != null) {
            m.slot.wake(clock.now() + m.ctx.reactionDelay());
        }
    }

    /** A line in the bot's own log (//bots info). */
    void noteFor(BotRecord r, String text) {
        final Mind m = r == null ? null : minds.get(r.charId());
        if (m != null) {
            m.slot.log().add(clock.now(), text);
        }
    }

    /** An event for a bot from a stock thread: into its mailbox, and tick it after its reaction time. */
    void post(BotRecord r, BotEvent e) {
        final Mind m = r == null ? null : minds.get(r.charId());
        if (m == null) {
            return;
        }
        m.slot.mailbox().offer(e);
        m.slot.wake(clock.now() + m.ctx.reactionDelay());
    }

    // ------------------------------------------------------------------ TickHandler

    @Override
    public long tick(BotSlot slot, long now) {
        final Mind m = (Mind) slot.owner();
        final Player p = m.r.player();
        if (p == null) {
            return 5000;
        }
        if ((now - m.lodCheckedAt) >= 2000) {
            m.lodCheckedAt = now;
            m.nearestLive = nearestLive(p);
            if (slot.lod().update(m.nearestLive, now, promoteRadius, demoteRadius, demoteDelay)) {
                ctx.metrics().counter("lodChanges").inc();
            }
        }
        final List<BotEvent> events = slot.mailbox().drain();
        final Brain brain = slot.brain();
        if (brain == null) {
            return 5000;
        }
        if (p.isTeleporting()) {
            for (BotEvent e : events) {
                slot.mailbox().offer(e); // keep them for the tick after the teleport
            }
            return 500;
        }
        m.ctx.view = new MobiusWorldView(p, registry, viewRadius, events);
        final long wanted;
        try {
            wanted = brain.tick(m.ctx);
        } finally {
            m.ctx.view = null;
        }
        m.r.setGoal(brain.state());
        watch(m, slot, p, brain, now);
        final long d = Lod.delay(slot.lod().level(), brain.inCombat() || p.isInCombat(), wanted, m.ctx.rnd().nextDouble());
        return overload.delay(d, slot.lod().level() == Lod.Level.ACTIVE, m.nearestLive);
    }

    private volatile org.l2start.bots.director.StarterZones zones;

    void setStarterZones(org.l2start.bots.director.StarterZones z) {
        zones = z;
    }

    /**
     * Idle watch after every tick (docs/STARTER_ZONES_PLAN.md S3): piles are taken apart in any state; an idle bot takes up
     * another activity; the second time it is teleported to a fitting spot, but only when no live player sees it (until
     * then it runs and the teleport waits). Stores, buffers and recruiters at their posts are left alone.
     */
    private void watch(Mind m, BotSlot slot, Player p, Brain brain, long now) {
        if ((m.r.status() != BotRecord.Status.ACTIVE) || m.r.post() || p.isDead()) {
            return;
        }
        final Pos at = new Pos(p.getX(), p.getY(), p.getZ());
        final org.l2start.bots.api.Activity a = brain.activity();
        m.activity = a;
        separate(m, p, now);
        if (m.teleportPending != null) {
            final Boolean go = org.l2start.bots.runtime.IdleWatch.pendingTeleport(m.teleportPending, at, seenByLive(p));
            if (go == null) {
                m.teleportPending = null; // it ran off: no teleport needed
            } else if (go) {
                m.teleportPending = null;
                teleportToSpot(m, p, "idle again, now unseen");
                return;
            }
        }
        final org.l2start.bots.runtime.IdleWatch.Verdict v = m.idle.check(now, at, a);
        final org.l2start.bots.runtime.IdleWatch.Reaction what = org.l2start.bots.runtime.IdleWatch.react(v, (v == org.l2start.bots.runtime.IdleWatch.Verdict.IDLE_AGAIN) && seenByLive(p));
        switch (what) {
            case CHANGE -> {
                ctx.log().info("idle: " + m.r.name() + " " + m.idle.why() + " at " + at + " (" + (a == null ? "?" : a.kind()) + ", " + m.r.goal() + "): another activity");
                post(m.r, new BotEvent.Idle(m.idle.why(), false));
            }
            case TELEPORT -> teleportToSpot(m, p, "idle again (" + m.idle.why() + ")");
            case RUN_TELEPORT_LATER -> {
                ctx.log().info("idle: " + m.r.name() + " " + m.idle.why() + " again at " + at + ", seen by a live player: runs, the teleport waits");
                m.teleportPending = at;
                post(m.r, new BotEvent.Idle(m.idle.why(), true));
            }
            default -> {
            }
        }
        if ((slot.lod().level() == Lod.Level.ACTIVE) && (m.idle.stillFor(now) > org.l2start.bots.runtime.IdleWatch.WARN_STILL_MS)
            && !org.l2start.bots.runtime.IdleWatch.standsOnPurpose(a, now) && ((now - m.warnedAt) > 5 * 60_000L)) {
            m.warnedAt = now;
            ctx.log().warn("idle: ACTIVE " + m.r.name() + " has not moved more than 50 for " + (m.idle.stillFor(now) / 1000) + " s at " + at + " (" + (a == null ? "?" : a.kind()) + ", "
                + m.r.goal() + ")");
        }
    }

    /**
     * //bots spots [radius] (docs/STARTER_ZONES_PLAN.md S4): every bot within {@code radius} of {@code center}: position,
     * activity, goal, time without moving; then the piles (bots within 50 of each other).
     */
    public List<String> spotsDump(Pos center, int radius) {
        final long now = clock.now();
        final List<BotRecord> near = new java.util.ArrayList<>();
        final List<Pos> pts = new java.util.ArrayList<>();
        for (BotRecord r : registry.sorted()) {
            final Player p = r.player();
            if ((p == null) || (Math.hypot(p.getX() - center.x(), p.getY() - center.y()) > radius)) {
                continue;
            }
            near.add(r);
            pts.add(new Pos(p.getX(), p.getY(), p.getZ()));
        }
        final List<String> out = new java.util.ArrayList<>();
        out.add("spots: " + near.size() + " bots within " + radius + " of " + center);
        for (int i = 0; i < near.size(); i++) {
            final BotRecord r = near.get(i);
            final Player p = r.player();
            final Mind m = minds.get(r.charId());
            final org.l2start.bots.api.Activity a = m == null ? null : m.activity;
            final org.l2start.bots.api.Assignment as = r.assignment();
            out.add(String.format(Locale.ROOT, "%s lv %d %s %s | %s | %s | still %ds", r.name(), p == null ? 0 : p.getLevel(), pts.get(i),
                r.status() == BotRecord.Status.TRADER ? "TRADER" : (a == null ? "?" : a.kind() + (a.wantsToMove() ? "+move" : "") + " " + ((now - a.since()) / 1000) + "s"),
                as == null ? "-" : as.kind() + " " + as.place(), r.goal(), m == null ? 0 : m.idle.stillFor(now) / 1000));
        }
        final List<List<Integer>> piles = org.l2start.bots.runtime.Piles.of(pts, 50);
        out.add("piles (within 50): " + piles.size());
        for (List<Integer> g : piles) {
            final StringBuilder sb = new StringBuilder("pile of " + g.size() + " at " + pts.get(g.get(0)) + ":");
            for (int i : g) {
                sb.append(' ').append(near.get(i).name());
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** Closer than 50 to another bot (its party / pair: 90): a short step away, in any state. */
    private void separate(Mind m, Player p, long now) {
        if (p.isMoving() || ((now - m.separatedAt) < 3000)) {
            return;
        }
        for (Player o : World.getVisibleObjectsInRange(p, Player.class, 100)) {
            final BotRecord or = registry.byCharId(o.getObjectId());
            if ((or == null) || o.isDead()) {
                continue;
            }
            final boolean mate = ((p.getParty() != null) && (p.getParty() == o.getParty())) || ((m.r.group() != 0) && (or.group() == m.r.group()));
            final double min = mate ? 90 : 50;
            final double d = p.calculateDistance2D(o);
            if (d >= min) {
                continue;
            }
            m.separatedAt = now;
            if (p.isSitting()) {
                p.standUp();
                return;
            }
            double ang = Math.atan2(p.getY() - o.getY(), p.getX() - o.getX());
            if (d < 1) {
                ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            }
            final double step = (min - d) + 40 + ThreadLocalRandom.current().nextInt(41);
            final int x = p.getX() + (int) (Math.cos(ang) * step);
            final int y = p.getY() + (int) (Math.sin(ang) * step);
            m.act.moveTo(new Pos(x, y, org.l2jmobius.gameserver.geoengine.GeoEngine.getInstance().getHeight(x, y, p.getZ())));
            return;
        }
    }

    /** A live player (or a director test anchor) within the demote radius sees the bot. */
    private boolean seenByLive(Player p) {
        for (Player o : World.getVisibleObjectsInRange(p, Player.class, demoteRadius)) {
            if (!registry.isBot(o) && (o.isOnlineInt() != 0) && org.l2jmobius.gameserver.geoengine.GeoEngine.getInstance().canSeeTarget(o, p)) {
                return true;
            }
        }
        for (Pos a : anchors.get()) {
            if (Math.hypot(a.x() - p.getX(), a.y() - p.getY()) <= demoteRadius) {
                return true;
            }
        }
        return false;
    }

    /** To the nearest spot that fits it: its director place, else a hunting ground of its level (own race in starting zones), else its village. */
    private void teleportToSpot(Mind m, Player p, String why) {
        final Pos at = new Pos(p.getX(), p.getY(), p.getZ());
        final Pos spot = fittingSpot(m.r, p, at);
        if (spot == null) {
            post(m.r, new BotEvent.Idle(why, true));
            return;
        }
        final org.l2start.bots.director.Scatter.Result s = WorldScatter.pick(spot, p);
        ctx.log().info("idle: " + m.r.name() + " " + why + " at " + at + ": teleported to " + s.pos() + (s.fallback() ? " (fallback)" : ""));
        if (p.isSitting()) {
            p.standUp();
        }
        p.abortAttack();
        p.abortCast();
        p.getAI().setIntentionActive();
        p.teleToLocation(WorldScatter.loc(s.pos()), false);
    }

    private Pos fittingSpot(BotRecord r, Player p, Pos at) {
        final org.l2start.bots.api.Assignment a = r.assignment();
        final org.l2start.bots.director.StarterZones z = zones;
        final int race = org.l2start.bots.director.StarterZones.raceOfStartClass(r.persona() == null ? r.classId() : r.persona().classId());
        if ((a != null) && (a.kind() == org.l2start.bots.api.Assignment.Kind.HUNT)) {
            return a.center();
        }
        if (z == null) {
            return a == null ? null : a.center();
        }
        if ((a != null) && ((a.kind() == org.l2start.bots.api.Assignment.Kind.TOWN) || (a.kind() == org.l2start.bots.api.Assignment.Kind.ERRANDS))) {
            final org.l2start.bots.director.WorldCatalog.Town t = z.catalog().town(a.place());
            if ((t != null) && (org.l2start.bots.director.StarterZones.VILLAGES.indexOf(t.id()) >= 0)) {
                return z.villagePoint(race, List.of(), 0, terrain(), ThreadLocalRandom.current());
            }
            return a.center();
        }
        org.l2start.bots.director.WorldCatalog.Ground best = null;
        for (org.l2start.bots.director.WorldCatalog.Ground g : z.catalog().grounds()) {
            final int gr = org.l2start.bots.director.StarterZones.raceOfGround(g);
            if (!g.fits(p.getLevel()) || ((gr >= 0) && (race >= 0) && (gr != race)) || g.indoor()) {
                continue;
            }
            if ((g.center().distance2d(at) < 30_000) && ((best == null) || (g.center().distance2d(at) < best.center().distance2d(at)))) {
                best = g;
            }
        }
        return best != null ? best.center() : z.villagePoint(race, List.of(), 0, terrain(), ThreadLocalRandom.current());
    }

    private org.l2start.bots.director.Director.Terrain terrain() {
        return q -> DirectorRunner.ground(q, world.requireGeodata());
    }

    private double nearestLive(Player p) {
        double best = Double.POSITIVE_INFINITY;
        for (Player o : World.getVisibleObjectsInRange(p, Player.class, demoteRadius)) {
            if (!registry.isBot(o) && (o.isOnlineInt() != 0)) {
                best = Math.min(best, p.calculateDistance2D(o));
            }
        }
        for (Pos a : anchors.get()) { // test players of the director (smoke, load test) count as live players
            best = Math.min(best, Math.hypot(a.x() - p.getX(), a.y() - p.getY()));
        }
        return best;
    }

    @Override
    public void failed(BotSlot slot, Throwable error, boolean tooMany) {
        final Mind m = (Mind) slot.owner();
        ctx.metrics().counter("brainErrors").inc();
        slot.log().add(clock.now(), "ERROR " + error + (tooMany ? " -> logout" : " -> SAFE_IDLE 60s"));
        if ((slot.totalErrors() <= 3) || tooMany) {
            ctx.log().warn("brain of " + m.r.name() + " failed (" + slot.totalErrors() + " so far)" + (tooMany ? ": 10 errors in 10 min, the bot leaves the world" : ": SAFE_IDLE for 60 s"), error);
        }
        try {
            m.act.stop();
        } catch (RuntimeException ignored) {
            // best effort
        }
        if (tooMany) {
            m.r.setTrouble("10 brain errors in 10 min: " + error);
            ctx.scheduler().schedule("logout " + m.r.name(), Duration.ZERO, () -> logout.accept(m.r));
        }
    }

    // ------------------------------------------------------------------ Sink

    @Override
    public void done(BotRecord r, String action, Object arg, ActionResult result) {
        final Mind m = minds.get(r.charId());
        ctx.metrics().counter("actions").inc();
        if (!result.ok()) {
            ctx.metrics().counter("actionsRejected").inc();
        } else if ("attack".equals(action) || "cast".equals(action)) {
            ctx.metrics().counter("attacks").inc();
        } else if ("moveTo".equals(action)) {
            ctx.metrics().counter("moves").inc();
        }
        if (m != null) {
            m.slot.log().add(clock.now(), action + "(" + arg + ") " + result);
        }
    }

    @Override
    public void logout(BotRecord r) {
        ctx.scheduler().schedule("logout " + r.name(), Duration.ZERO, () -> logout.accept(r));
    }

    @Override
    public void async(String name, Runnable task) {
        ctx.scheduler().schedule(name, Duration.ZERO, task);
    }

    @Override
    public void prepareJoinPlayerClan(BotRecord r, Player p) {
        final java.util.function.BiConsumer<BotRecord, Player> h = onJoinPlayerClan;
        if (h != null) {
            h.accept(r, p);
        }
    }

    private volatile java.util.function.BiConsumer<BotRecord, Player> onJoinPlayerClan;

    /** Phase 7e: leave bot-clan paper when accepting a live player's pledge. */
    public void setOnJoinPlayerClan(java.util.function.BiConsumer<BotRecord, Player> h) {
        onJoinPlayerClan = h;
    }

    @Override
    public long now() {
        return clock.now();
    }

    // ------------------------------------------------------------------ admin and stats

    public Ticker ticker() {
        return ticker;
    }

    public String brainId() {
        return brainId;
    }

    public boolean brainRegistered() {
        return factories.containsKey(brainId);
    }

    public BotSlot slot(BotRecord r) {
        final Mind m = minds.get(r.charId());
        return m == null ? null : m.slot;
    }

    public double nearestLive(BotRecord r) {
        final Mind m = minds.get(r.charId());
        return m == null ? Double.POSITIVE_INFINITY : m.nearestLive;
    }

    /** Overload guard (director density, far bots' tick). */
    public org.l2start.bots.runtime.Overload overload() {
        return overload;
    }

    public void setPaused(boolean p) {
        final Ticker t = ticker;
        if (t != null) {
            t.setPaused(p);
            if (p) {
                for (Mind m : minds.values()) {
                    m.act.stop();
                }
            }
        }
    }

    /** One line: BOTS world=.. ticking=.. active=.. present=.. ... */
    public String statsLine() {
        final Ticker t = ticker;
        if (t == null) {
            return "BOTS runtime not started";
        }
        int active = 0;
        int present = 0;
        int safeIdle = 0;
        int noBrain = 0;
        final long now = clock.now();
        for (BotSlot s : t.slots()) {
            if (s.lod().level() == Lod.Level.ACTIVE) {
                active++;
            } else {
                present++;
            }
            if (s.safeIdle(now)) {
                safeIdle++;
            }
            if (s.brain() == null) {
                noBrain++;
            }
        }
        final Map<String, Long> mm = ctx.metrics().snapshot();
        return String.format(Locale.ROOT, "BOTS world=%d ticking=%d active=%d present=%d brain=%s%s noBrain=%d ticks=%d p50=%.2fms p99=%.2fms overrun=%d overload=%s errors=%d safeIdle=%d paused=%s moves=%d attacks=%d kills=%d deaths=%d levelUps=%d rejected=%d",
            registry.inWorld(), t.size(), active, present, brainId, factories.containsKey(brainId) ? "" : "(missing)", noBrain, t.ticks(), t.percentileNanos(50) / 1e6, t.percentileNanos(99) / 1e6,
            t.overruns(), overload.step(), t.errors(), safeIdle, t.paused(), mm.getOrDefault("moves", 0L), mm.getOrDefault("attacks", 0L), mm.getOrDefault("kills", 0L), mm.getOrDefault("deaths", 0L),
            mm.getOrDefault("levelUps", 0L), mm.getOrDefault("actionsRejected", 0L));
    }

    private void logStats() {
        final Ticker t = ticker;
        if ((t == null) || (registry.inWorld() == 0)) {
            return;
        }
        final long now = clock.now();
        final long ticks = t.ticks();
        final long busy = t.busyNanos();
        final double secs = Math.max(1, now - lastLogAt) / 1000.0;
        final double cpu = ((busy - lastLogBusy) / 1e9) / secs * 100;
        int walking = 0; // bots in the world that walk instead of running (should be 0: they run like players)
        for (BotRecord r : registry.all()) {
            final Player p = r.player();
            if ((p != null) && !p.isDead() && !p.isRunning() && (r.status() != BotRecord.Status.TRADER)) { // traders sit in their stores
                walking++;
            }
        }
        final long deaths = ctx.metrics().snapshot().getOrDefault("deaths", 0L);
        final double deathsPerHour = ((deaths - lastLogDeaths) / secs) * 3600;
        ctx.log().info(statsLine() + String.format(Locale.ROOT, " ticks/s=%.1f shardCpu=%.2f%% walking=%d deaths/h=%.0f", (ticks - lastLogTicks) / secs, cpu, walking, deathsPerHour));
        lastLogDeaths = deaths;
        lastLogTicks = ticks;
        lastLogBusy = busy;
        lastLogAt = now;
    }
}
