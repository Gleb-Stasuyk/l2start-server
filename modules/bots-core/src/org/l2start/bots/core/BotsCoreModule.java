package org.l2start.bots.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerChat;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2start.bots.admin.AdminBots;
import org.l2start.bots.persona.ClassPicker;
import org.l2start.bots.persona.NameGenerator;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * bots-core (phase 3a): bot accounts and characters, login/logout of bots into the world, bot presence
 * (hooks P3-BOT-CHARINFO / P3-BOT-WHISPER), death -> back to town, //bots admin commands.
 * Brains, schedule and LOD come in the next steps of phase 3.
 */
public final class BotsCoreModule implements GameModule {
    private ModuleContext ctx;
    private final BotRegistry registry = new BotRegistry();
    private BotRepository repo;
    private BotFactory factory;
    private BotWorld world;

    @Override
    public void onEnable(ModuleContext context) throws Exception {
        ctx = context;
        repo = new BotRepository(ctx.db());
        final long seed = ctx.config().getLong("Seed");
        final Random rnd = seed == 0 ? new Random() : new Random(seed);
        factory = new BotFactory(repo, registry, new NameGenerator(rnd), new ClassPicker(rnd), ctx.log());
        world = new BotWorld(repo, ctx.log());
        applyConfig();

        ctx.botPresence(registry::isBot);
        ctx.events().<OnPlayerChat> onGlobal(EventType.ON_PLAYER_CHAT, this::onChat);
        ctx.events().<OnCreatureDeath> onPlayers(EventType.ON_CREATURE_DEATH, this::onDeath);
        ctx.handlers().admin(new AdminBots(this));
        // a module that is disabled or degraded must not leave its bots standing in the world
        ctx.cleanup("log out all bots", () -> logoutAll("module stopped"));
    }

    @Override
    public void onServerReady(ModuleContext context) throws Exception {
        int retired = 0;
        for (BotRecord r : repo.loadAll()) {
            registry.add(r);
            if (r.status() == BotRecord.Status.RETIRED) {
                retired++;
            }
        }
        final int rebanned = repo.enforceBans();
        if (rebanned > 0) {
            ctx.log().warn(rebanned + " bot account(s) were not banned in the login server: banned again");
        }
        final int reset = repo.enforcePlayerAccess();
        if (reset > 0) {
            ctx.log().warn(reset + " bot character(s) had an access level: reset to 0");
        }
        final boolean geo = BotWorld.geodataLoaded();
        ctx.log().info("ready: " + registry.size() + " bots known (" + retired + " retired), geodata in starting villages: " + (geo ? "yes" : "NO") + ", pathfinding " + GeoEngineConfig.PATHFINDING
            + ", RequireGeodata=" + ctx.config().getBoolean("RequireGeodata"));
        if (!geo && ctx.config().getBoolean("RequireGeodata")) {
            ctx.log().warn("no geodata: bots cannot enter the world (RequireGeodata=true). See docs/GEODATA.md");
        }
        ctx.scheduler().fixedRate("teleports", Duration.ofMillis(500), Duration.ofMillis(500), () -> {
            final int n = world.completeTeleports(registry.all());
            if (n > 0) {
                ctx.metrics().counter("teleports").add(n);
            }
        });
        final int autoCreate = ctx.config().getInt("AutoCreate");
        final int autoSpawn = ctx.config().getInt("AutoSpawn");
        if ((autoCreate > 0) || (autoSpawn > 0)) {
            ctx.scheduler().schedule("autostart", Duration.ofSeconds(1), () -> {
                final List<String> errors = new ArrayList<>();
                final int created = create(Math.max(0, autoCreate - active().size()), -1, errors);
                final int spawned = spawn(autoSpawn, null, errors);
                ctx.log().info("autostart: created " + created + ", in world " + spawned + (errors.isEmpty() ? "" : ", problems: " + errors.subList(0, Math.min(5, errors.size()))));
            });
        }
    }

    @Override
    public void onConfigReload(ModuleContext context) {
        applyConfig();
    }

    @Override
    public void onShutdown(ModuleContext context) {
        logoutAll("server shutdown");
    }

    private void applyConfig() {
        world.setRequireGeodata(ctx.config().getBoolean("RequireGeodata"));
    }

    // ------------------------------------------------------------------ events

    private void onChat(OnPlayerChat e) {
        final Player target = e.getTarget();
        if ((e.getChatType() != ChatType.WHISPER) || (target == null)) {
            return;
        }
        final BotRecord r = registry.byCharId(target.getObjectId());
        if (r != null) {
            r.whisper(e.getPlayer().getName(), e.getText());
            ctx.metrics().counter("whispersReceived").inc();
        }
    }

    private void onDeath(OnCreatureDeath e) {
        final Creature dead = e.getTarget();
        if ((dead == null) || !dead.isPlayer()) {
            return;
        }
        final BotRecord r = registry.byCharId(dead.getObjectId());
        if ((r == null) || !r.inWorld()) {
            return;
        }
        r.died();
        ctx.metrics().counter("deaths").inc();
        final long min = ctx.config().getDuration("DeathReturnMin").toMillis();
        final long max = Math.max(min, ctx.config().getDuration("DeathReturnMax").toMillis());
        final long delay = min + (max > min ? ThreadLocalRandom.current().nextLong(max - min + 1) : 0);
        ctx.scheduler().schedule("return " + r.name(), Duration.ofMillis(delay), () -> world.returnToTown(r));
    }

    // ------------------------------------------------------------------ operations (admin commands, autostart)

    public BotRegistry registry() {
        return registry;
    }

    public BotWorld world() {
        return world;
    }

    public ModuleContext ctx() {
        return ctx;
    }

    public List<BotRecord> active() {
        final List<BotRecord> l = new ArrayList<>();
        for (BotRecord r : registry.sorted()) {
            if (r.status() == BotRecord.Status.ACTIVE) {
                l.add(r);
            }
        }
        return l;
    }

    /** @return how many were created */
    public int create(int n, int classId, List<String> errors) {
        final int max = ctx.config().getInt("MaxBots");
        int done = 0;
        for (int i = 0; i < n; i++) {
            if (active().size() >= max) {
                errors.add("MaxBots=" + max + " reached");
                break;
            }
            try {
                final BotRecord r = factory.create(classId);
                ctx.log().info("created " + r.name() + " (class " + r.classId() + ", account " + r.accountName() + ")");
                ctx.metrics().counter("created").inc();
                done++;
            } catch (Exception e) {
                ctx.log().warn("cannot create a bot", e);
                errors.add(String.valueOf(e.getMessage()));
                break;
            }
        }
        return done;
    }

    /** Log in up to n bots that are not in the world (oldest first). */
    public int spawn(int n, org.l2jmobius.gameserver.entity.Location near, List<String> errors) {
        int done = 0;
        for (BotRecord r : active()) {
            if (done >= n) {
                break;
            }
            if (r.inWorld()) {
                continue;
            }
            if (spawnOne(r, near, errors)) {
                done++;
            } else if (errors.size() >= 3) {
                break;
            }
        }
        return done;
    }

    public boolean spawnOne(BotRecord r, org.l2jmobius.gameserver.entity.Location near, List<String> errors) {
        final int max = ctx.config().getInt("MaxInWorld");
        if (registry.inWorld() >= max) {
            errors.add("MaxInWorld=" + max + " reached");
            return false;
        }
        final String why = world.enter(r, near);
        if (why != null) {
            errors.add(why);
            return false;
        }
        ctx.metrics().counter("logins").inc();
        return true;
    }

    public boolean despawn(BotRecord r) {
        final boolean done = world.leave(r);
        if (done) {
            ctx.metrics().counter("logouts").inc();
        }
        return done;
    }

    public int logoutAll(String why) {
        int n = 0;
        for (BotRecord r : registry.sorted()) {
            if (r.inWorld() && world.leave(r)) {
                n++;
            }
        }
        if (n > 0) {
            ctx.log().info("logged out " + n + " bots (" + why + ")");
        }
        return n;
    }
}
