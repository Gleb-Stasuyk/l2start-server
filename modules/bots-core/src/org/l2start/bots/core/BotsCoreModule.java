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
import org.l2start.bots.persona.NameDictionary;
import org.l2start.bots.persona.NameGenerator;
import org.l2start.bots.persona.Persona;
import org.l2start.bots.persona.PersonaGenerator;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * bots-core: bot accounts and characters (3a), personas and state restored after a restart (3b), login/logout of
 * bots into the world, bot presence (hooks P3-BOT-CHARINFO / P3-BOT-WHISPER), death -> back to town, //bots.
 * Brains, schedule and LOD come in the next steps of phase 3.
 */
public final class BotsCoreModule implements GameModule {
    private ModuleContext ctx;
    private final BotRegistry registry = new BotRegistry();
    private BotRepository repo;
    private BotFactory factory;
    private BotWorld world;
    private Random seeds;
    private volatile PersonaGenerator personas;

    @Override
    public void onEnable(ModuleContext context) throws Exception {
        ctx = context;
        repo = new BotRepository(ctx.db());
        final long seed = ctx.config().getLong("Seed");
        seeds = seed == 0 ? new Random() : new Random(seed);
        personas = buildPersonas(); // bad dictionary or weights: the module fails to enable with a clear message
        factory = new BotFactory(repo, registry, () -> personas, () -> seeds.nextLong());
        world = new BotWorld(repo, ctx.log());
        applyConfig();

        ctx.botPresence(registry::isBot);
        ctx.events().<OnPlayerChat> onGlobal(EventType.ON_PLAYER_CHAT, this::onChat);
        ctx.events().<OnCreatureDeath> onPlayers(EventType.ON_CREATURE_DEATH, this::onDeath);
        ctx.handlers().admin(new AdminBots(this));
        // a module that is disabled or degraded must not leave its bots standing in the world
        ctx.cleanup("log out all bots", () -> logoutAll("module stopped", true));
    }

    private PersonaGenerator buildPersonas() throws Exception {
        final NameDictionary dict = NameDictionary.load(ctx.resources().path("data/names"));
        final ClassPicker classes = new ClassPicker(ctx.config().getList("ClassWeights"), ctx.config().getList("FaceWeights"), ctx.config().getList("HairColorWeights"),
            ctx.config().getList("HairStyleWeightsMale"), ctx.config().getList("HairStyleWeightsFemale"));
        return new PersonaGenerator(dict, NameGenerator.parseStyles(ctx.config().getList("NameStyles")), classes,
            PersonaGenerator.parseTimezones(ctx.config().getList("Timezones")), PersonaGenerator.parseChronotypes(ctx.config().getList("Chronotypes")));
    }

    @Override
    public void onServerReady(ModuleContext context) throws Exception {
        int retired = 0;
        for (BotRecord r : repo.loadAll((bad, e) -> ctx.log().warn("persona of " + bad + " is unreadable, a new one is made: " + e))) {
            registry.add(r);
            if (r.status() == BotRecord.Status.RETIRED) {
                retired++;
            }
        }
        final int adopted = adoptOldBots();
        final int rebanned = repo.enforceBans();
        if (rebanned > 0) {
            ctx.log().warn(rebanned + " bot account(s) were not banned in the login server: banned again");
        }
        final int reset = repo.enforcePlayerAccess();
        if (reset > 0) {
            ctx.log().warn(reset + " bot character(s) had an access level: reset to 0");
        }
        final boolean geo = BotWorld.geodataLoaded();
        ctx.log().info("ready: " + registry.size() + " bots known (" + retired + " retired, " + adopted + " given a persona), to restore " + toRestore().size() + ", geodata in starting villages: " + (geo ? "yes" : "NO") + ", pathfinding " + GeoEngineConfig.PATHFINDING
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
        ctx.scheduler().schedule("startup", Duration.ofSeconds(1), this::startup);
    }

    /** Bots that were in the world at the last stop come back first (same places), then the autostart numbers. */
    private void startup() {
        final List<String> errors = new ArrayList<>();
        if (ctx.config().getBoolean("RestoreOnStart")) {
            final List<BotRecord> back = toRestore();
            if (!back.isEmpty()) {
                int ok = 0;
                for (BotRecord r : back) {
                    if (spawnOne(r, null, errors)) {
                        ok++;
                    } else {
                        try {
                            repo.setInWorld(r.botId(), false); // not coming back (no geodata, limit): do not retry forever
                            r.setRestore(false);
                        } catch (Exception e) {
                            ctx.log().warn("cannot store state of " + r, e);
                        }
                    }
                }
                ctx.log().info("restore: " + ok + " of " + back.size() + " bots back in the world" + problems(errors));
                errors.clear();
            }
        }
        final int autoCreate = ctx.config().getInt("AutoCreate");
        final int autoSpawn = ctx.config().getInt("AutoSpawn");
        if ((autoCreate > 0) || (autoSpawn > 0)) {
            final int created = create(Math.max(0, autoCreate - active().size()), -1, errors);
            final int entered = spawn(Math.max(0, autoSpawn - registry.inWorld()), null, errors);
            ctx.log().info("autostart: created " + created + ", entered " + entered + ", in world " + registry.inWorld() + problems(errors));
        }
    }

    private static String problems(List<String> errors) {
        return errors.isEmpty() ? "" : ", problems: " + errors.subList(0, Math.min(5, errors.size()));
    }

    private List<BotRecord> toRestore() {
        final List<BotRecord> l = new ArrayList<>();
        for (BotRecord r : active()) {
            if (r.restore() && !r.inWorld()) {
                l.add(r);
            }
        }
        return l;
    }

    /** Bots created before step 3b have no persona/state rows: build them from the real character. */
    private int adoptOldBots() {
        int n = 0;
        for (BotRecord r : active()) {
            try {
                if (r.persona() == null) {
                    final int[] look = repo.look(r.charId()).orElse(null);
                    if (look == null) {
                        continue;
                    }
                    final Persona p = personas.adopt(seeds.nextLong(), r.name(), r.classId(), look[0] != 0, look[1], look[2], look[3]);
                    repo.savePersona(r.botId(), p);
                    r.setPersona(p);
                    n++;
                }
                if ((r.homeX() == 0) && (r.homeY() == 0)) {
                    final int[] pos = repo.position(r.charId()).orElse(new int[3]);
                    repo.ensureState(r.botId(), pos[0], pos[1], pos[2]);
                    r.setHome(pos[0], pos[1], pos[2]);
                }
            } catch (Exception e) {
                ctx.log().warn("cannot give " + r + " a persona", e);
            }
        }
        return n;
    }

    @Override
    public void onConfigReload(ModuleContext context) throws Exception {
        personas = buildPersonas(); // dictionaries and weights; existing personas do not change
        applyConfig();
    }

    @Override
    public void onShutdown(ModuleContext context) {
        logoutAll("server shutdown", true);
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
                final Persona p = r.persona();
                ctx.log().info("created " + r.name() + " (class " + r.classId() + ", " + p.nameStyle() + ", " + (p.female() ? "female" : "male") + ", " + p.chronotype() + ", " + p.timezone() + ", account " + r.accountName() + ")");
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

    /** Log in up to n bots that are not in the world (random ones, like different people logging in). */
    public int spawn(int n, org.l2jmobius.gameserver.entity.Location near, List<String> errors) {
        int done = 0;
        final List<BotRecord> free = active();
        java.util.Collections.shuffle(free, seeds);
        for (BotRecord r : free) {
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

    /** Log out; it does not come back after a restart. */
    public boolean despawn(BotRecord r) {
        final boolean done = world.leave(r, false);
        if (done) {
            ctx.metrics().counter("logouts").inc();
        }
        return done;
    }

    /** @param comeBack true = these bots return on the next start (server or module stop), false = //bots despawn all */
    public int logoutAll(String why, boolean comeBack) {
        int n = 0;
        for (BotRecord r : registry.sorted()) {
            if (r.inWorld() && world.leave(r, comeBack)) {
                n++;
            }
        }
        if (n > 0) {
            ctx.log().info("logged out " + n + " bots (" + why + ")");
        }
        return n;
    }
}
