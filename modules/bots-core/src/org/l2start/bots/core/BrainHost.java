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
            t.greed(), t.patience(), t.skill(), t.chattiness(), t.reactionMs(), new Pos(r.homeX(), r.homeY(), r.homeZ()), p == null ? r.charId() : p.seed());
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
        final long d = Lod.delay(slot.lod().level(), brain.inCombat() || p.isInCombat(), wanted, m.ctx.rnd().nextDouble());
        return overload.delay(d, slot.lod().level() == Lod.Level.ACTIVE, m.nearestLive);
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
