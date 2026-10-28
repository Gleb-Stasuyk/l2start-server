package org.l2start.bots.core;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Pos;
import org.l2start.bots.director.Director;
import org.l2start.bots.director.DirectorConfig;
import org.l2start.bots.director.GearTable;
import org.l2start.bots.director.WorldCatalog;
import org.l2start.platform.api.ModuleContext;

/**
 * The presence director in the game (Presence = director): every DirectorTickMs a snapshot of live players and bots
 * goes to the pure {@link Director}; its commands are executed here. Bringing a bot in (create persona, load,
 * level/class/skills/gear, enter) and taking it out (clean loot, save, leave) run on stock pool threads.
 */
public final class DirectorRunner {
    private final BotsCoreModule module;
    private final ModuleContext ctx;
    private final Director director;
    private final Materializer materializer;
    private final Set<Integer> busy = ConcurrentHashMap.newKeySet();
    private volatile boolean paused;
    /** Server/module stop: nobody comes in any more (a materialization in flight logs straight out). */
    private volatile boolean stopping;
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private volatile List<Pos> anchors = List.of();
    private volatile long tickNanos;
    /** Phase 7e: provisional clan leaders to pull in so stock Clan+crest appear. */
    private final Map<Integer, ForceEnter> forceEnter = new ConcurrentHashMap<>();

    private record ForceEnter(int x, int y, int z, int level, long untilMs) {
    }

    DirectorRunner(BotsCoreModule module, WorldCatalog catalog, GearTable gear, DirectorConfig cfg) {
        this.module = module;
        this.ctx = module.ctx();
        this.director = new Director(catalog, cfg, new Random());
        this.materializer = new Materializer(gear);
        // phase 5d: a market module (economy-market) lets hunters pay for shots and potions; looked up at every arrival
        this.materializer.setSupplies(() -> module.ctx().services().find(org.l2start.bots.trade.Supplies.class).orElse(null));
        this.materializer.setPkLedger(module.pkLedger());
    }

    Materializer materializer() {
        return materializer;
    }

    public Director director() {
        return director;
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean p) {
        paused = p;
    }

    /** Test players (smoke and load tests): the director treats them as live players. */
    public List<Pos> anchors() {
        return anchors;
    }

    void setAnchors(List<Pos> a) {
        anchors = List.copyOf(a);
    }

    void setConfig(DirectorConfig c) {
        director.setConfig(c);
    }

    void setGradeLag(int min, int max) {
        materializer.setGradeLag(min, max);
    }

    public long lastTickMicros() {
        return tickNanos / 1000;
    }

    void start(long tickMs) {
        ctx.scheduler().fixedRate("director", Duration.ofMillis(tickMs), Duration.ofMillis(tickMs), this::tick);
    }

    /** Real players with a client (not bots, not offline traders) plus the test anchors. */
    List<Director.Live> live() {
        final List<Director.Live> l = new ArrayList<>();
        for (Player p : World.getPlayers()) {
            if ((p.isOnlineInt() == 1) && !module.registry().isBot(p)) {
                l.add(new Director.Live(p.getObjectId(), new Pos(p.getX(), p.getY(), p.getZ()), p.getLevel()));
            }
        }
        int id = -1;
        for (Pos a : anchors) {
            l.add(new Director.Live(id--, a));
        }
        return l;
    }

    /** Phase 7e: clan member already visible — bring the paper leader nearby to create the stock clan. */
    void requestClanLeaderEnter(int leaderBotId, int nearX, int nearY, int nearZ, int preferLevel) {
        if ((leaderBotId <= 0) || stopping) {
            return;
        }
        final BotRecord r = byId(leaderBotId);
        if ((r == null) || r.inWorld() || (r.status() != BotRecord.Status.ACTIVE)) {
            return;
        }
        final int lvl = Math.max(10, Math.max(preferLevel, r.paperLevel(System.currentTimeMillis())));
        forceEnter.put(leaderBotId, new ForceEnter(nearX + 80, nearY + 60, nearZ, lvl, System.currentTimeMillis() + 180_000L));
    }

    private void tick() {
        if (paused) {
            return;
        }
        final long start = System.nanoTime();
        final long now = System.currentTimeMillis();
        flushForceEnter(now);
        final List<Director.Live> live = live();
        final List<Director.BotNow> bots = new ArrayList<>();
        for (BotRecord r : module.registry().all()) {
            final Player p = r.player();
            if ((p != null) && !busy.contains(r.botId()) && !r.pooled() // store traders / olympiad nobles belong to other modules
                && !withPlayer(p) // phase 6b: in a real player's party it is his companion, the director leaves it be
                && !module.isAcademyRecruiter(r.botId()) // phase 7f: recruiters stay at GK
                && !module.isRbPinned(r.botId())) { // RB party: answered a player's call, it is his until it leaves
                bots.add(new Director.BotNow(r.botId(), new Pos(p.getX(), p.getY(), p.getZ()), r.assignment(), p.isDead(), r.persona() == null ? r.charId() : r.persona().seed(), r.group(), p.isInCombat()));
            }
        }
        if (live.isEmpty() && bots.isEmpty() && forceEnter.isEmpty()) {
            tickNanos = System.nanoTime() - start;
            return; // nobody to show anything to: zero cost
        }
        director.setDensity(module.brains().overload().density());
        director.setCanCreate(module.inWorldActive() < ctx.config().getInt("MaxBots")); // MaxBots = bots in the world
        final boolean needGeo = ctx.config().getBoolean("RequireGeodata");
        final List<Director.Command> cmds = director.tick(now, LocalTime.now().getHour(), live, bots, this::available, new Director.Terrain() {
            @Override
            public Pos ground(Pos approx) {
                return DirectorRunner.ground(approx, needGeo);
            }

            @Override
            public boolean canSee(Pos from, Pos to) {
                final GeoEngine geo = GeoEngine.getInstance();
                return !geo.hasGeo(from.x(), from.y()) || geo.canSeeTarget(from.x(), from.y(), from.z() + 40, to.x(), to.y(), to.z() + 40);
            }

            @Override
            public List<Director.MobSpot> monstersNear(Pos at, int radius) {
                return DirectorRunner.monstersNear(at, radius);
            }
        });
        for (Director.Command c : cmds) {
            switch (c) {
                case Director.Command.Materialize m -> materialize(m);
                case Director.Command.Assign a -> {
                    final BotRecord r = byId(a.botId());
                    if (r != null) {
                        r.setAssignment(a.assignment());
                        module.brains().wake(r);
                    }
                }
                case Director.Command.Logout o -> requestLogout(byId(o.botId()), o.why());
            }
        }
        tickNanos = System.nanoTime() - start;
    }

    /**
     * Phase 6b/7e: pinned companion — in a party with a live player, or in a clan that has a live online member
     * (never sent away or logged out by the director).
     */
    boolean withPlayer(Player p) {
        final org.l2jmobius.gameserver.entity.groups.Party pt = p.getParty();
        if (pt != null) {
            for (Player m : pt.getMembers()) {
                if ((m != null) && (m != p) && !module.registry().isBot(m) && m.isOnline()) {
                    return true;
                }
            }
        }
        final org.l2jmobius.gameserver.entity.clan.Clan clan = p.getClan();
        if (clan != null) {
            for (org.l2jmobius.gameserver.entity.clan.ClanMember m : clan.getMembers()) {
                final Player o = m == null ? null : m.getPlayer();
                if ((o != null) && (o != p) && !module.registry().isBot(o) && o.isOnline()) {
                    return true;
                }
            }
            // also pin when marked in module player-clan table (offline live leader)
            final BotRecord br = module.registry().byCharId(p.getObjectId());
            if ((br != null) && (module.clans() != null) && module.clans().inPlayerClan(br.botId())) {
                return true;
            }
        }
        return false;
    }

    private BotRecord byId(int botId) {
        for (BotRecord r : module.registry().all()) {
            if (r.botId() == botId) {
                return r;
            }
        }
        return null;
    }

    /** Race of a starting class (0 human, 1 elf, 2 dark elf, 3 orc, 4 dwarf), -1 = not a starting class. */
    static int race(int startClass) {
        return org.l2start.bots.director.StarterZones.raceOfStartClass(startClass);
    }

    /**
     * A starting class of that race (fighter or mystic), -1 = any. KT12 F2: about 40% of the time pick the
     * fighter/mystic line that can become BD/SWS/PP/SE/BP so support bots show up around the player.
     */
    static int startClass(int race) {
        final java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        final boolean preferBuffer = rnd.nextDouble() < org.l2start.bots.persona.SupportRoles.FUTURE_BIAS;
        return switch (race) {
            case 0 -> preferBuffer || rnd.nextBoolean() ? 10 : 0; // Human Mystic → Bishop/Prophet
            case 1 -> preferBuffer ? (rnd.nextBoolean() ? 18 : 25) : (rnd.nextBoolean() ? 25 : 18); // SWS / Elder
            case 2 -> preferBuffer ? (rnd.nextBoolean() ? 31 : 38) : (rnd.nextBoolean() ? 38 : 31); // BD / SE
            case 3 -> rnd.nextBoolean() ? 49 : 44;
            case 4 -> 53;
            default -> -1;
        };
    }

    private void flushForceEnter(long now) {
        if (forceEnter.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<Integer, ForceEnter>> it = forceEnter.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Integer, ForceEnter> e = it.next();
            if (now > e.getValue().untilMs()) {
                it.remove();
                continue;
            }
            final BotRecord r = byId(e.getKey());
            if ((r == null) || (r.status() != BotRecord.Status.ACTIVE)) {
                it.remove();
                continue;
            }
            if (r.inWorld() || busy.contains(r.botId())) {
                it.remove();
                continue;
            }
            final ForceEnter f = e.getValue();
            it.remove();
            final Pos at = new Pos(f.x(), f.y(), f.z());
            final Assignment a = new Assignment(0, Assignment.Kind.HUNT, "clan-leader", at, 800, f.level(), f.level(), List.of(at), List.of(), null);
            ctx.log().info("bot clans: pulling leader " + r.name() + " lv " + f.level() + " to create stock clan");
            materialize(new Director.Command.Materialize(r.botId(), f.level(), at, a, -1));
        }
    }

    private List<Director.Candidate> available(int minLevel, int maxLevel, long now) {
        final List<Director.Candidate> l = new ArrayList<>();
        for (BotRecord r : module.registry().all()) {
            if ((r.status() != BotRecord.Status.ACTIVE) || r.inWorld() || busy.contains(r.botId())) {
                continue;
            }
            final int lvl = r.paperLevel(now);
            if ((lvl >= minLevel) && (lvl <= maxLevel)) {
                final int clanId = module.clans() == null ? 0 : module.clans().clanIdOf(r.botId());
                final boolean stockPending = (module.clans() != null) && module.clans().needsStockPromotion(r.botId());
                l.add(new Director.Candidate(r.botId(), lvl, r.lastLogout(), race(r.persona() == null ? r.classId() : r.persona().classId()),
                    activity(r.persona(), now), clanId, stockPending, (r.persona() != null) && r.persona().pk()));
            }
        }
        return l;
    }

    /** How much it is the persona's playing time now (its chronotype at its local time), 0..1; 1 without a persona. */
    static double activity(org.l2start.bots.persona.Persona p, long now) {
        if (p == null) {
            return 1;
        }
        java.time.ZoneId zone;
        try {
            zone = java.time.ZoneId.of(p.timezone());
        } catch (Exception e) {
            zone = java.time.ZoneId.systemDefault();
        }
        final java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(now).atZone(zone);
        return p.chronotype().activity(t.getHour(), t.getDayOfWeek());
    }

    /**
     * A walkable point with geodata near {@code approx}, at the right height (null = no geodata there). Without
     * RequireGeodata (smoke image: no geodata at all) the catalogue height is taken as it is.
     */
    static Pos ground(Pos approx, boolean requireGeo) {
        final GeoEngine geo = GeoEngine.getInstance();
        if (!geo.hasGeo(approx.x(), approx.y())) {
            return requireGeo ? null : approx;
        }
        final int z = geo.getHeight(approx.x(), approx.y(), approx.z());
        if (Math.abs(z - approx.z()) > 2000) {
            return null;
        }
        if (MobiusGeo.water(approx.x(), approx.y(), z)) {
            return null; // never a spot in the water (a bot would swim)
        }
        return new Pos(approx.x(), approx.y(), z);
    }

    /** The live ordinary monsters around a live player standing at {@code at} (the world is asked around him). */
    static List<Director.MobSpot> monstersNear(Pos at, int radius) {
        Player by = null;
        for (Player p : World.getPlayers()) {
            if ((p != null) && (Math.hypot(p.getX() - at.x(), p.getY() - at.y()) < 300)) {
                by = p;
                break;
            }
        }
        if (by == null) {
            return List.of();
        }
        final List<Director.MobSpot> out = new ArrayList<>();
        for (org.l2jmobius.gameserver.entity.actor.Npc n : World.getVisibleObjectsInRange(by, org.l2jmobius.gameserver.entity.actor.Npc.class, radius)) {
            if (n.isMonster() && !n.isDead() && !n.isRaid() && !n.isRaidMinion() && n.isAttackable() && !MobiusGeo.water(n.getX(), n.getY(), n.getZ())) {
                out.add(new Director.MobSpot(new Pos(n.getX(), n.getY(), n.getZ()), n.getLevel()));
            }
        }
        return out;
    }

    private final java.util.concurrent.atomic.AtomicInteger maxBotsSkipped = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long maxBotsLogAt;

    /** Pure: no PK in towns (town stays, gatekeeper transits, errands): PK personas only on hunting grounds and roads. */
    static boolean pkMayGo(boolean pk, org.l2start.bots.api.Assignment.Kind kind) {
        return !pk || !((kind == org.l2start.bots.api.Assignment.Kind.TOWN) || (kind == org.l2start.bots.api.Assignment.Kind.TRANSIT)
            || (kind == org.l2start.bots.api.Assignment.Kind.ERRANDS));
    }

    private void materialize(Director.Command.Materialize m) {
        materialize(m, null, null);
    }

    /**
     * RB party (iteration 1): bring this persona (null = create one with {@code create}) in at {@code m.at()} with the
     * level, class, skills and gear of {@code m.level()}, like every director arrival; {@code arrived} runs once it is
     * in the world. The director does not command it (bots-core pins it to the player who called).
     */
    void materializeFor(Director.Command.Materialize m, java.util.concurrent.Callable<BotRecord> create, java.util.function.Consumer<BotRecord> arrived) {
        materialize(m, create, arrived);
    }

    private void materialize(Director.Command.Materialize m, java.util.concurrent.Callable<BotRecord> create, java.util.function.Consumer<BotRecord> arrived) {
        final BotRecord known = m.botId() == 0 ? null : byId(m.botId());
        if (stopping || ((known != null) && !busy.add(known.botId()))) {
            return;
        }
        inFlight.incrementAndGet();
        module.brains().async("materialize", () -> {
            BotRecord r = known;
            try {
                if (stopping) {
                    return;
                }
                if (r == null) {
                    if (module.inWorldActive() >= ctx.config().getInt("MaxBots")) {
                        final long t = System.currentTimeMillis();
                        maxBotsSkipped.incrementAndGet();
                        if (t >= maxBotsLogAt) { // at most once a minute
                            maxBotsLogAt = t + 60_000L;
                            ctx.log().info("director: MaxBots=" + ctx.config().getInt("MaxBots") + " bots in the world, " + maxBotsSkipped.getAndSet(0) + " new persona(s) not created (last for " + m.assignment().place() + ")");
                        }
                        return;
                    }
                    r = create != null ? create.call() : module.createOne(startClass(m.race()));
                    if (r == null) {
                        return;
                    }
                    busy.add(r.botId());
                    r.setProgress(m.level(), System.currentTimeMillis(), 1);
                }
                final BotRecord bot = r;
                if (!pkMayGo(bot.persona() != null && bot.persona().pk(), m.assignment().kind())) {
                    ctx.log().info("director: PK " + bot.name() + " is not sent to the town " + m.assignment().place() + " (no PK in towns)");
                    return;
                }
                final long now = System.currentTimeMillis();
                bot.setAssignment(m.assignment());
                bot.setGroup(m.group());
                final String[] what = new String[1];
                final String why = module.world().enterPrepared(bot, new Location(m.at().x(), m.at().y(), m.at().z()), p -> what[0] = materializer.prepare(p, bot, m.level()));
                if (why != null) {
                    bot.setAssignment(null);
                    ctx.metrics().counter("directorFailed").inc();
                    ctx.log().info("director: " + bot.name() + " could not enter: " + why);
                    return;
                }
                if (stopping) { // the server began to stop while it was coming in
                    module.despawn(bot);
                    bot.setAssignment(null);
                    return;
                }
                final Player p = bot.player();
                if ((p != null) && !p.isDead()) {
                    // presence top-ups and every other arrival: full HP/MP/CP on entering (never rest right after arrival)
                    p.setCurrentHpMp(p.getMaxHp(), p.getMaxMp());
                    p.setCurrentCp(p.getMaxCp());
                }
                if (p != null) {
                    what[0] = what[0] + ", " + Materializer.arrivalBuffs(p, ArrivalBuffs.shares(ctx.config().getString("ArrivalBuffs")), java.util.concurrent.ThreadLocalRandom.current());
                }
                if ((p != null) && (m.group() != 0)) {
                    final String with = joinParty(bot, p, m.group());
                    if (with != null) {
                        what[0] = what[0] + ", party with " + with;
                    }
                }
                final int real = p == null ? m.level() : p.getLevel();
                bot.setProgress(real, now, real);
                module.repo().saveProgress(bot.botId(), real, now);
                ctx.metrics().counter("materialized").inc();
                bot.setGoal(m.assignment().kind() + " " + m.assignment().place());
                module.brains().noteFor(bot, "director: " + m.assignment() + " (" + what[0] + ")");
                if (arrived != null) {
                    arrived.accept(bot);
                }
            } catch (Exception e) {
                ctx.log().warn("director: cannot bring " + (r == null ? "a new persona" : r.name()) + " in", e);
            } finally {
                if (r != null) {
                    busy.remove(r.botId());
                }
                inFlight.decrementAndGet();
            }
        });
    }

    private final Object partyLock = new Object();

    /** Phase 4g: the second and third of a group join the party of the first one in (a real party: shared XP, party window). */
    private String joinParty(BotRecord bot, Player p, int group) {
        synchronized (partyLock) {
            for (BotRecord o : module.registry().all()) {
                final Player q = o.player();
                if ((o == bot) || (o.group() != group) || (q == null) || !q.isOnline() || q.isDead()) {
                    continue;
                }
                org.l2jmobius.gameserver.entity.groups.Party party = q.getParty();
                if (party == null) {
                    party = new org.l2jmobius.gameserver.entity.groups.Party(q, org.l2jmobius.gameserver.entity.groups.PartyDistributionType.FINDERS_KEEPERS);
                    q.setParty(party);
                } else if (party.getMemberCount() >= 9) {
                    return null;
                }
                if (p.getParty() == null) {
                    p.joinParty(party);
                }
                ctx.metrics().counter("partyJoins").inc();
                return q.getName();
            }
            return null;
        }
    }

    /**
     * Before the server stops: no new bot comes in, and the ones coming in right now finish (or log straight out)
     * before everybody is logged out, so no bot stays marked "in the world" (smoke 8).
     */
    void stop(long waitMs) {
        stopping = true;
        paused = true;
        final long until = System.currentTimeMillis() + waitMs;
        while ((inFlight.get() > 0) && (System.currentTimeMillis() < until)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public boolean stopping() {
        return stopping;
    }

    /** Coming in or going out right now. */
    boolean isBusy(int botId) {
        return busy.contains(botId);
    }

    /** Take a bot out (director decision, or its brain left by the gatekeeper). */
    public void requestLogout(BotRecord r, String why) {
        if ((r != null) && r.inWorld() && busy.add(r.botId())) {
            module.brains().async("leave " + r.name(), () -> logout(r, why));
        }
    }

    private void logout(BotRecord r, String why) {
        try {
            final Player p = r.player();
            if (p != null) {
                try {
                    materializer.cleanup(p, (r.persona() != null) && r.persona().pk());
                } catch (Exception e) {
                    ctx.log().warn("director: cleanup of " + r.name() + " failed", e);
                }
                final long now = System.currentTimeMillis();
                final int real = p.getLevel();
                r.setProgress(real, now, real);
                module.repo().saveProgress(r.botId(), real, now);
            }
            module.despawn(r);
            r.setAssignment(null);
            ctx.metrics().counter("directorLogouts").inc();
        } catch (Exception e) {
            ctx.log().warn("director: cannot log out " + r.name() + " (" + why + ")", e);
        } finally {
            busy.remove(r.botId());
        }
    }

    /** Logs everything a director bot holds before the server stops (loot vanishes, levels saved). */
    void cleanupAll() {
        for (BotRecord r : module.registry().all()) {
            final Player p = r.player();
            if ((p == null) || r.pooled()) {
                continue;
            }
            try {
                materializer.cleanup(p, (r.persona() != null) && r.persona().pk());
                final long now = System.currentTimeMillis();
                r.setProgress(p.getLevel(), now, p.getLevel());
                module.repo().saveProgress(r.botId(), p.getLevel(), now);
            } catch (Exception e) {
                ctx.log().warn("director: cleanup of " + r.name() + " failed", e);
            }
        }
    }

    public String statsLine() {
        return director.statsLine() + " inWorld=" + module.registry().inWorld() + " busy=" + busy.size() + " tick=" + lastTickMicros() + "us" + (paused ? " PAUSED" : "");
    }
}
