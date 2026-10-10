package org.l2start.bots.core;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
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
    private volatile List<Pos> anchors = List.of();
    private volatile long tickNanos;

    DirectorRunner(BotsCoreModule module, WorldCatalog catalog, GearTable gear, DirectorConfig cfg) {
        this.module = module;
        this.ctx = module.ctx();
        this.director = new Director(catalog, cfg, new Random());
        this.materializer = new Materializer(gear);
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
                l.add(new Director.Live(p.getObjectId(), new Pos(p.getX(), p.getY(), p.getZ())));
            }
        }
        int id = -1;
        for (Pos a : anchors) {
            l.add(new Director.Live(id--, a));
        }
        return l;
    }

    private void tick() {
        if (paused) {
            return;
        }
        final long start = System.nanoTime();
        final long now = System.currentTimeMillis();
        final List<Director.Live> live = live();
        final List<Director.BotNow> bots = new ArrayList<>();
        for (BotRecord r : module.registry().all()) {
            final Player p = r.player();
            if ((p != null) && !busy.contains(r.botId())) {
                bots.add(new Director.BotNow(r.botId(), new Pos(p.getX(), p.getY(), p.getZ()), r.assignment(), p.isDead(), r.persona() == null ? r.charId() : r.persona().seed()));
            }
        }
        if (live.isEmpty() && bots.isEmpty()) {
            tickNanos = System.nanoTime() - start;
            return; // nobody to show anything to: zero cost
        }
        final boolean needGeo = ctx.config().getBoolean("RequireGeodata");
        final List<Director.Command> cmds = director.tick(now, LocalTime.now().getHour(), live, bots, this::available, p -> ground(p, needGeo));
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

    private BotRecord byId(int botId) {
        for (BotRecord r : module.registry().all()) {
            if (r.botId() == botId) {
                return r;
            }
        }
        return null;
    }

    private List<Director.Candidate> available(int minLevel, int maxLevel, long now) {
        final List<Director.Candidate> l = new ArrayList<>();
        for (BotRecord r : module.registry().all()) {
            if ((r.status() != BotRecord.Status.ACTIVE) || r.inWorld() || busy.contains(r.botId())) {
                continue;
            }
            final int lvl = r.paperLevel(now);
            if ((lvl >= minLevel) && (lvl <= maxLevel)) {
                l.add(new Director.Candidate(r.botId(), lvl, r.lastLogout()));
            }
        }
        return l;
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
        return new Pos(approx.x(), approx.y(), z);
    }

    private void materialize(Director.Command.Materialize m) {
        final BotRecord known = m.botId() == 0 ? null : byId(m.botId());
        if ((known != null) && !busy.add(known.botId())) {
            return;
        }
        module.brains().async("materialize", () -> {
            BotRecord r = known;
            try {
                if (r == null) {
                    if (module.active().size() >= ctx.config().getInt("MaxBots")) {
                        return;
                    }
                    r = module.createOne(-1);
                    if (r == null) {
                        return;
                    }
                    busy.add(r.botId());
                    r.setProgress(m.level(), System.currentTimeMillis(), 1);
                }
                final BotRecord bot = r;
                final long now = System.currentTimeMillis();
                bot.setAssignment(m.assignment());
                final String[] what = new String[1];
                final String why = module.world().enterPrepared(bot, new Location(m.at().x(), m.at().y(), m.at().z()), p -> what[0] = materializer.prepare(p, bot, m.level()));
                if (why != null) {
                    bot.setAssignment(null);
                    ctx.metrics().counter("directorFailed").inc();
                    ctx.log().info("director: " + bot.name() + " could not enter: " + why);
                    return;
                }
                final Player p = bot.player();
                final int real = p == null ? m.level() : p.getLevel();
                bot.setProgress(real, now, real);
                module.repo().saveProgress(bot.botId(), real, now);
                ctx.metrics().counter("materialized").inc();
                bot.setGoal(m.assignment().kind() + " " + m.assignment().place());
                module.brains().noteFor(bot, "director: " + m.assignment() + " (" + what[0] + ")");
            } catch (Exception e) {
                ctx.log().warn("director: cannot bring " + (r == null ? "a new persona" : r.name()) + " in", e);
            } finally {
                if (r != null) {
                    busy.remove(r.botId());
                }
            }
        });
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
                    materializer.cleanup(p);
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
            if (p == null) {
                continue;
            }
            try {
                materializer.cleanup(p);
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
