package org.l2start.bots.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerChat;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLevelChanged;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2start.bots.admin.AdminBots;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.BrainRegistry;
import org.l2start.bots.api.Pos;
import org.l2start.bots.director.DirectorConfig;
import org.l2start.bots.director.GearTable;
import org.l2start.bots.director.WorldCatalog;
import org.l2start.bots.persona.ClassPicker;
import org.l2start.bots.persona.DigitNameTrim;
import org.l2start.bots.persona.NameDictionary;
import org.l2start.bots.persona.NameGenerator;
import org.l2start.bots.persona.Persona;
import org.l2start.bots.persona.PersonaGenerator;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * bots-core: bot accounts and characters (3a), personas and state restored after a restart (3b), login/logout of
 * bots into the world, bot presence (hooks P3-BOT-CHARINFO / P3-BOT-WHISPER), death -> back to town, //bots.
 * Step 3c: the bot runtime ({@link BrainHost}: shards, mailboxes, LOD ACTIVE/PRESENT) and the brain API
 * (org.l2start.bots.api, service {@link BrainRegistry}); brains themselves live in other modules (bots-brain-basic).
 * Step 3e: the presence director ({@link DirectorRunner}, Presence = director): bots exist only around real players.
 */
public final class BotsCoreModule implements GameModule {
    private ModuleContext ctx;
    private final BotRegistry registry = new BotRegistry();
    private BotRepository repo;
    private BotFactory factory;
    private BotWorld world;
    private BrainHost brains;
    private Random seeds;
    private volatile PersonaGenerator personas;
    private DirectorRunner director;
    /** The five starting zones (docs/STARTER_ZONES_PLAN.md), in both presence modes. */
    private volatile org.l2start.bots.director.StarterZones zones;
    private volatile org.l2start.bots.director.StockPoints stockPoints;
    private PkLedger pkLedger;
    private org.l2start.bots.clan.ClanService clans;
    private org.l2start.bots.clan.ClanHallBids clanHallBids;
    private org.l2start.bots.clan.ClanHallAssign clanHallAssign;
    private org.l2start.bots.clan.AcademyRecruiters academyRecruiters;
    /** Clan recruiters (main clan) at the gatekeepers of Dion, Giran, Aden and Goddard. */
    private org.l2start.bots.clan.AcademyRecruiters clanRecruiters;
    private org.l2start.bots.clan.AcademyRewards academyRewards;
    private volatile boolean clanInvitePlayers = true;
    private volatile boolean clanJoinPlayers = true;

    @Override
    public void onEnable(ModuleContext context) throws Exception {
        ctx = context;
        repo = new BotRepository(ctx.db());
        pkLedger = new PkLedger(ctx.db());
        clans = new org.l2start.bots.clan.ClanService(ctx, registry, repo, ctx.resources().path("data/clans/BotClans.xml"));
        clanHallBids = new org.l2start.bots.clan.ClanHallBids(ctx, clans, registry);
        clanHallAssign = new org.l2start.bots.clan.ClanHallAssign(ctx, clans, registry);
        academyRewards = new org.l2start.bots.clan.AcademyRewards(ctx, clans, registry);
        final long seed = ctx.config().getLong("Seed");
        seeds = seed == 0 ? new Random() : new Random(seed);
        personas = buildPersonas(); // bad dictionary or weights: the module fails to enable with a clear message
        factory = new BotFactory(repo, registry, () -> personas, () -> seeds.nextLong());
        world = new BotWorld(repo, ctx.log());
        academyRecruiters = new org.l2start.bots.clan.AcademyRecruiters(ctx, clans, registry, world);
        academyRecruiters.setHalls(clanHallAssign);
        clanRecruiters = new org.l2start.bots.clan.AcademyRecruiters(ctx, clans, registry, world, org.l2start.bots.clan.AcademyRecruiters.Kind.CLAN);
        clanRecruiters.setHalls(clanHallAssign);
        academyRecruiters.setBusyLeader(id -> clanRecruiters.isRecruiter(id));
        clanRecruiters.setBusyLeader(id -> academyRecruiters.isRecruiter(id));
        brains = new BrainHost(ctx, registry, world, this::brainLogout);
        world.setListener(new BotWorld.Listener() {
            @Override
            public void entered(BotRecord r) {
                if (r.status() == BotRecord.Status.TRADER) {
                    return; // a trader sits in its store: no brain (phase 5)
                }
                if ((clans != null) && (r.player() != null)) {
                    clans.attachPlayer(r, r.player()); // phase 7d
                }
                brains.entered(r);
            }

            @Override
            public void left(BotRecord r) {
                brains.left(r);
            }

            @Override
            public void teleported(BotRecord r) {
                final org.l2jmobius.gameserver.entity.actor.Player p = r.player();
                if (p != null) {
                    brains.post(r, new BotEvent.Teleported(new Pos(p.getX(), p.getY(), p.getZ())));
                }
            }
        });
        zones = new org.l2start.bots.director.StarterZones(WorldCatalog.load(ctx.resources().path("data/world.xml")));
        try {
            stockPoints = org.l2start.bots.director.StockPoints.load(org.l2jmobius.gameserver.config.ServerConfig.DATAPACK_ROOT.toPath().resolve("data"));
        } catch (Exception e) {
            stockPoints = new org.l2start.bots.director.StockPoints(List.of(), List.of());
            ctx.log().warn("stock creation/respawn points not read: " + e.getMessage());
        }
        final List<Pos> nodes = new ArrayList<>(stockPoints.creation());
        nodes.addAll(stockPoints.respawn());
        zones.setAvoid(nodes);
        zones.setStarts(stockPoints.starts());
        world.setStarterZones(zones, terrain());
        brains.setStarterZones(zones);
        factory.setHomePicker(race -> zones.villagePoint(race, List.of(), 0, terrain(), new java.util.Random()));
        if ("director".equals(ctx.config().getString("Presence"))) {
            // bad catalogue or gear table: the module fails to enable with a clear message
            final List<String> unreachable = new ArrayList<>();
            final int[] checked = new int[3]; // routes asked, answered "reached", answered "cannot tell"
            final List<String> warnings = new ArrayList<>();
            final WorldCatalog catalog = WorldCatalog.load(ctx.resources().path("data/world.xml")).withReachableAnchors((from, to) -> {
                checked[0]++;
                final Pos got = AnchorReach.reach(from, to);
                if (got == WorldCatalog.UNKNOWN) {
                    checked[2]++;
                } else if (got != null) {
                    checked[1]++;
                }
                return got;
            }, unreachable, warnings);
            ctx.log().info("town NPCs: " + checked[0] + " short routes checked by geodata (" + checked[1] + " reached, " + checked[2] + " cannot tell), "
                + unreachable.size() + " NPCs unreachable");
            if (!unreachable.isEmpty()) {
                ctx.log().warn("town NPCs unreachable by geodata, left out of town errands: " + String.join("; ", unreachable));
            }
            for (String w : warnings) {
                ctx.log().warn("town NPCs: " + w);
            }
            final GearTable gear = GearTable.load(ctx.resources().path("data/gear.xml"));
            director = new DirectorRunner(this, catalog, gear, directorConfig());
            brains.setAnchors(director::anchors);
            final org.l2start.bots.director.GkGraph gk = org.l2start.bots.director.GkGraph.fromTeleporterData(catalog);
            brains.setGkGraph(gk);
            ctx.log().info("presence director: " + catalog.towns().size() + " towns, " + catalog.grounds().size() + " hunting grounds, " + gear.sets() + " armour sets, " + gear.weapons() + " weapons; gk graph " + gk.edges() + " hops");
            if (clans != null) {
                clans.setLeaderEnterHook(director::requestClanLeaderEnter);
            }
        }
        applyConfig();
        ctx.services().provide(BrainRegistry.class, brains);
        final org.l2start.bots.chat.Phrases phrases = org.l2start.bots.chat.Phrases.load(ctx.resources().path("data/chat/phrases.xml"));
        brains.setTemplates(new org.l2start.bots.chat.TemplateBrain(phrases, System::currentTimeMillis, new java.util.Random()));
        ctx.log().info("chat: " + phrases.intents().size() + " intents (phase 6 phrase banks)");
        final org.l2start.bots.chat.SocialBoard social = new org.l2start.bots.chat.SocialBoard(ctx.config().getInt("LfpShoutPerHour"));
        social.setWhispersPerHour(ctx.config().getInt("WhisperPerHour"));
        brains.setSocial(social);
        if (academyRecruiters != null) {
            academyRecruiters.setSocial(social);
        }
        ctx.log().info("social: LFP shouts " + social.shoutsPerHour() + "/hour, whisper answers " + social.whispersPerHour() + "/bot/hour (phase 6d/6g)");
        final MemoryStore memories = new MemoryStore(ctx.db());
        brains.setMemories(memories);
        ctx.scheduler().fixedRate("bot memory", Duration.ofMinutes(1), Duration.ofMinutes(1), () -> {
            try {
                memories.flush();
            } catch (Exception e) {
                ctx.log().warn("bot memory not saved: " + e.getMessage());
            }
        });
        // phase 5: store traders for economy-market (personas kept for a store place, no brain)
        ctx.services().provide(org.l2start.bots.trade.TraderPool.class, new TraderService(this, factory, repo, GearTable.load(ctx.resources().path("data/gear.xml"))));

        // phase 6f: party mate gifts shots/potions via P6-BOT-TRADE (bot puts nothing, confirms last)
        final PartyTrades partyTrades = new PartyTrades(ctx, registry, memories);
        // academy join reward by trade: the recruiter offers the adena, or takes the trade the player opens later
        final AcademyTrades academyTrades = new AcademyTrades(ctx);
        ctx.onBotTradeRequest((requester, bot) -> academyTrades.onTradeRequest(requester, bot) || partyTrades.onTradeRequest(requester, bot));
        ctx.scheduler().fixedRate("academy trades", Duration.ofMillis(400), Duration.ofMillis(400), academyTrades::tick);
        if (academyRewards != null) {
            academyRewards.setJoinPayer((p, clanId, amount, onPaid) -> {
                final int oid = academyRecruiters == null ? 0 : academyRecruiters.inviterOf(p.getObjectId());
                final Player bot = oid == 0 ? null : org.l2jmobius.gameserver.entity.World.getPlayer(oid);
                return academyTrades.offer(bot, p, amount, onPaid);
            });
            academyRewards.setGraduatePayer((p, clanId, amount, onPaid) -> {
                final org.l2start.bots.clan.AcademyRecruiters.Leader l = academyRecruiters == null ? null : academyRecruiters.leaderAtPlace(clanId);
                return (l != null) && academyTrades.offerAt(l.bot(), p, amount, l.place(), onPaid);
            });
        }
        ctx.scheduler().fixedRate("party trades", Duration.ofMillis(400), Duration.ofMillis(400), partyTrades::tick);

        ctx.botPresence(registry::isBot);
        ctx.events().<OnPlayerChat> onGlobal(EventType.ON_PLAYER_CHAT, this::onChat);
        ctx.onBotPartyRequest(new org.l2start.platform.api.BotPartyListener() { // phase 6a/6d, hook P6-BOT-PARTY
            @Override
            public boolean onPartyRequest(Player requester, Player bot) {
                return BotsCoreModule.this.onPartyRequest(requester, bot);
            }

            @Override
            public boolean onPartyBusy(Player requester, Player bot) {
                return BotsCoreModule.this.onPartyBusy(requester, bot);
            }
        });
        ctx.onBotDuelRequest(this::onDuelRequest); // phase 7b, hook P7-BOT-DUEL
        ctx.onBotPledgeRequest(this::onPledgeRequest); // phase 7e, hook P7-BOT-PLEDGE
        ctx.onBotAttackable(this::forcePkAttackable); // phase 7c, hook P7-ATTACKABLE
        brains.setOnJoinPlayerClan((r, p) -> {
            if (clans != null) {
                clans.leaveBotClanForPlayer(r, p);
            }
        });
        ctx.events().<OnCreatureDeath> onPlayers(EventType.ON_CREATURE_DEATH, this::onDeath);
        ctx.events().<OnCreatureDamageReceived> onPlayers(EventType.ON_CREATURE_DAMAGE_RECEIVED, this::onDamage);
        ctx.events().<OnPlayerLevelChanged> onPlayers(EventType.ON_PLAYER_LEVEL_CHANGED, this::onLevel);
        ctx.events().<OnAttackableKill> onMonsters(EventType.ON_ATTACKABLE_KILL, this::onKill);
        ctx.events().<org.l2jmobius.gameserver.mechanics.events.holders.actor.player.clan.OnPlayerClanJoin> onGlobal( // Clan notifies the global container only
            EventType.ON_PLAYER_CLAN_JOIN, e -> {
                if (academyRewards != null) {
                    academyRewards.onClanJoin(e);
                }
            });
        ctx.events().<org.l2jmobius.gameserver.mechanics.events.holders.actor.player.clan.OnPlayerClanLeft> onGlobal( // Clan notifies the global container only
            EventType.ON_PLAYER_CLAN_LEFT, e -> {
                if (academyRewards != null) {
                    academyRewards.onClanLeft(e);
                }
            });
        ctx.handlers().admin(new AdminBots(this));
        // a module that is disabled or degraded must not leave its bots standing in the world
        ctx.cleanup("log out all bots", () -> stopAll("module stopped"));
    }

    private PersonaGenerator buildPersonas() throws Exception {
        final NameDictionary dict = NameDictionary.load(ctx.resources().path("data/names"));
        final ClassPicker classes = new ClassPicker(ctx.config().getList("ClassWeights"), ctx.config().getList("FaceWeights"), ctx.config().getList("HairColorWeights"),
            ctx.config().getList("HairStyleWeightsMale"), ctx.config().getList("HairStyleWeightsFemale"));
        final PersonaGenerator g = new PersonaGenerator(dict, NameGenerator.parseStyles(ctx.config().getList("NameStyles")), classes,
            PersonaGenerator.parseTimezones(ctx.config().getList("Timezones")), PersonaGenerator.parseChronotypes(ctx.config().getList("Chronotypes")));
        g.setPkShare(pkShareOf(ctx.config().getString("PkBotShare")));
        g.setDigitShare(digitShareOf(ctx.config().getString("BotNameDigitShare")));
        return g;
    }

    static double digitShareOf(String raw) {
        try {
            return Math.max(0, Math.min(1, Double.parseDouble(raw == null ? "0.03" : raw.trim())));
        } catch (RuntimeException e) {
            return 0.03;
        }
    }

    /** Phase 7c: 1–3 % of new personas are PK ({@code PkBotShare}). */
    static double pkShareOf(String raw) {
        try {
            return Math.max(0, Math.min(0.03, Double.parseDouble(raw == null ? "0.02" : raw.trim())));
        } catch (RuntimeException e) {
            return 0.02;
        }
    }

    PkLedger pkLedger() {
        return pkLedger;
    }

    public org.l2start.bots.clan.ClanService clans() {
        return clans;
    }

    public org.l2start.bots.clan.ClanHallBids clanHallBids() {
        return clanHallBids;
    }

    public org.l2start.bots.clan.ClanHallAssign clanHallAssign() {
        return clanHallAssign;
    }

    public org.l2start.bots.clan.AcademyRecruiters academyRecruiters() {
        return academyRecruiters;
    }

    public org.l2start.bots.clan.AcademyRewards academyRewards() {
        return academyRewards;
    }

    /** Phase 7f: director must not logout academy recruiters while on duty. */
    public boolean isAcademyRecruiter(int botId) {
        return ((academyRecruiters != null) && academyRecruiters.isRecruiter(botId)) || ((clanRecruiters != null) && clanRecruiters.isRecruiter(botId));
    }

    /**
     * Phase 7c, hook P7-ATTACKABLE: a PK-persona bot may open on a white player/bot outside mutual PvP (stock would
     * refuse). Peace and under-20 stay blocked here too.
     */
    private boolean forcePkAttackable(Player target, org.l2jmobius.gameserver.entity.actor.Creature attacker) {
        final Player atk = attacker == null ? null : attacker.asPlayer();
        if ((atk == null) || !registry.isBot(atk) || (target == null) || !target.isPlayer()) {
            return false;
        }
        final BotRecord r = registry.byCharId(atk.getObjectId());
        if ((r == null) || (r.persona() == null) || !r.persona().pk()) {
            return false;
        }
        if (atk.isInsideZone(org.l2jmobius.gameserver.entity.zone.ZoneId.PEACE) || target.isInsideZone(org.l2jmobius.gameserver.entity.zone.ZoneId.PEACE)
            || atk.isInsideZone(org.l2jmobius.gameserver.entity.zone.ZoneId.NO_PVP) || target.isInsideZone(org.l2jmobius.gameserver.entity.zone.ZoneId.NO_PVP)) {
            return false;
        }
        if (target.getLevel() < 20) {
            return false;
        }
        if (registry.isBot(target) && (target.getObjectId() == atk.getObjectId())) {
            return false;
        }
        return true;
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
        final int renamed = trimDigitNames();
        if (renamed > 0) {
            ctx.log().info("renamed " + renamed + " bots with digit nicknames (BotNameDigitShare)");
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
        ctx.log().info("ready: " + registry.size() + " bots known (" + retired + " retired, " + adopted + " given a persona), to restore " + toRestore().size() + ", geodata in starting villages: " + (geo ? "yes" : "NO") + ", pathfinding " + GeoEngineConfig.PATHFINDING
            + ", RequireGeodata=" + ctx.config().getBoolean("RequireGeodata"));
        if (!geo && ctx.config().getBoolean("RequireGeodata")) {
            ctx.log().warn("no geodata: bots cannot enter the world (RequireGeodata=true). See docs/GEODATA.md");
        }
        relocateStarters();
        ctx.scheduler().fixedRate("teleports", Duration.ofMillis(500), Duration.ofMillis(500), () -> {
            final int n = world.completeTeleports(registry.all());
            if (n > 0) {
                ctx.metrics().counter("teleports").add(n);
            }
        });
        brains.start();
        if (clans != null) {
            clans.load();
            clans.ensureFilled();
            ctx.scheduler().fixedRate("bot clans", Duration.ofMinutes(2), Duration.ofMinutes(2), () -> {
                try {
                    clans.ensureFilled();
                } catch (Exception e) {
                    ctx.log().warn("bot clans refill: " + e.getMessage());
                }
            });
        }
        if (clanHallBids != null) {
            if (academyRecruiters != null) {
                clanHallBids.setSkipClans(academyRecruiters::recruiterClanIds);
            }
            ctx.scheduler().fixedRate("clan hall bids", Duration.ofMinutes(5), Duration.ofMinutes(5), () -> {
                try {
                    clanHallBids.tick();
                } catch (Exception e) {
                    ctx.log().warn("clan hall bids: " + e.getMessage());
                }
            });
        }
        if (clanHallAssign != null) {
            // initialDelay seconds (not 6h): fix unpaid halls soon after restart
            ctx.scheduler().fixedRate("clan hall cwh top-up", Duration.ofSeconds(30), Duration.ofHours(1), () -> {
                try {
                    clanHallAssign.maintainAllOwned();
                } catch (Exception e) {
                    ctx.log().warn("clan hall cwh top-up: " + e.getMessage());
                }
            });
        }
        if (clanRecruiters != null) {
            if (director != null) {
                clanRecruiters.setForceEnter(director::requestClanLeaderEnter);
            }
            ctx.scheduler().fixedRate("clan recruiters presence", Duration.ofSeconds(25), Duration.ofSeconds(10), () -> {
                try {
                    clanRecruiters.presence();
                } catch (Exception e) {
                    ctx.log().warn("clan recruiters presence: " + e.getMessage());
                }
            });
            ctx.scheduler().fixedRate("clan recruiters tick", Duration.ofSeconds(17), Duration.ofSeconds(5), () -> {
                try {
                    clanRecruiters.tick();
                } catch (Exception e) {
                    ctx.log().warn("clan recruiters: " + e.getMessage());
                }
            });
        }
        if (academyRecruiters != null) {
            if (director != null) {
                academyRecruiters.setForceEnter(director::requestClanLeaderEnter);
            }
            ctx.scheduler().fixedRate("academy recruiters presence", Duration.ofSeconds(20), Duration.ofSeconds(10), () -> {
                try {
                    academyRecruiters.presence();
                } catch (Exception e) {
                    ctx.log().warn("academy recruiters presence: " + e.getMessage());
                }
            });
            ctx.scheduler().fixedRate("academy recruiters tick", Duration.ofSeconds(15), Duration.ofSeconds(5), () -> {
                try {
                    academyRecruiters.tick();
                } catch (Exception e) {
                    ctx.log().warn("academy recruiters: " + e.getMessage());
                }
            });
        }
        if (director != null) {
            int cleared = 0;
            try {
                cleared = repo.clearInWorld(); // director mode: nobody comes back by itself, the director decides
            } catch (Exception e) {
                ctx.log().warn("cannot clear in-world marks", e);
            }
            for (BotRecord r : registry.all()) {
                r.setRestore(false);
            }
            director.start(ctx.config().getInt("DirectorTickMs"));
            final Duration every = ctx.config().getDuration("StatsLogInterval");
            if (!every.isZero()) {
                ctx.scheduler().fixedRate("director stats", every, every, () -> {
                    if ((director.director().pendingCount() > 0) || (registry.inWorld() > 0) || !director.live().isEmpty()) {
                        ctx.log().info(director.statsLine());
                        // Separate per-minute presence summary
                        final org.l2start.bots.director.Director.PresenceSummary ps = director.director().presenceSummary();
                        if (ps != null) {
                            ctx.log().info(ps.line());
                        }
                    }
                    final int traders = world.drainTraderEnters();
                    if (traders > 0) {
                        ctx.log().info("enter: " + traders + " store traders at their store places");
                    }
                });
            }
            ctx.log().info("presence: director (" + cleared + " old in-world marks cleared, anchors " + director.anchors().size() + ")");
            ctx.scheduler().fixedRate("pool warm-up", Duration.ofSeconds(30), Duration.ofSeconds(5), this::warmUp);
            final int autoCreate = ctx.config().getInt("AutoCreate");
            if (autoCreate > 0) {
                ctx.scheduler().schedule("startup", Duration.ofSeconds(1), () -> {
                    final List<String> errors = new ArrayList<>();
                    final int created = create(Math.max(0, autoCreate - active().size()), -1, errors);
                    ctx.log().info("autostart: created " + created + problems(errors));
                });
            }
        } else {
            ctx.scheduler().schedule("startup", Duration.ofSeconds(1), this::startup);
        }
    }

    org.l2start.bots.director.StarterZones zones() {
        return zones;
    }

    /** Ground with geodata (RequireGeodata: no point without geodata), for village points. */
    org.l2start.bots.director.Director.Terrain terrain() {
        return p -> DirectorRunner.ground(p, world.requireGeodata());
    }

    /**
     * Start-up relocation (docs/STARTER_ZONES_PLAN.md S2, S5): bots out of the world that logged out on a stock creation or
     * respawn node, or live in the starting zone of another race, get a spread position and home (stored).
     */
    private void relocateStarters() {
        try {
            final List<org.l2start.bots.director.StartMigration.Row> rows = new ArrayList<>();
            for (org.l2start.bots.director.StartMigration.Row r : repo.relocationRows()) {
                final BotRecord b = registry.byBotId(r.botId());
                if ((b == null) || !b.inWorld()) {
                    rows.add(r);
                }
            }
            final List<org.l2start.bots.director.StartMigration.Move> moves = org.l2start.bots.director.StartMigration.plan(rows, stockPoints.creation(), stockPoints.respawn(), zones,
                org.l2start.bots.director.StartMigration.placer(zones, terrain(), WorldScatter.GROUND, new Random()));
            int creates = 0;
            int respawns = 0;
            int foreign = 0;
            for (org.l2start.bots.director.StartMigration.Move m : moves) {
                repo.relocate(m.row().botId(), m.row().charId(), m.loc(), m.home());
                final BotRecord b = registry.byBotId(m.row().botId());
                if (b != null) {
                    b.setHome(m.home().x(), m.home().y(), m.home().z());
                }
                creates += m.why().contains("create node") ? 1 : 0;
                respawns += m.why().contains("respawn node") ? 1 : 0;
                foreign += m.why().contains("foreign") ? 1 : 0;
            }
            ctx.log().info("relocate: " + moves.size() + " of " + rows.size() + " bots moved (" + creates + " at create nodes, " + respawns + " at respawn nodes, " + foreign
                + " in a foreign starting zone); stock points: " + stockPoints.creation().size() + " creation, " + stockPoints.respawn().size() + " respawn");
        } catch (Exception e) {
            ctx.log().warn("relocate: " + e.getMessage(), e);
        }
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

    /** Offline bots with digit nicks beyond BotNameDigitShare get a new name (not in party/clan with a live player). */
    private int trimDigitNames() {
        if (personas == null) {
            return 0;
        }
        final double share = personas.digitShare();
        final List<BotRecord> all = new ArrayList<>();
        final List<Integer> digitIds = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (BotRecord r : active()) {
            all.add(r);
            names.add(r.name());
            if (NameGenerator.hasDigit(r.name()) && !digitNameProtected(r)) {
                digitIds.add(r.botId());
            }
        }
        final int keep = DigitNameTrim.keepDigitCount(all.size(), digitIds.size(), share);
        final List<Integer> rename = DigitNameTrim.toRename(digitIds, keep);
        if (rename.isEmpty()) {
            return 0;
        }
        final java.util.Set<String> taken = DigitNameTrim.takenLower(names);
        final NameGenerator gen = new NameGenerator(new java.util.Random(42), personas.dictionary(), personas.styles(), share);
        int n = 0;
        for (Integer botId : rename) {
            final BotRecord r = registry.byBotId(botId);
            if ((r == null) || r.inWorld() || digitNameProtected(r)) {
                continue;
            }
            try {
                final boolean female = (r.persona() != null) && r.persona().female();
                final String neu = DigitNameTrim.newNameWithoutDigits(gen, s -> taken.contains(s.toLowerCase(java.util.Locale.ROOT)) || org.l2start.bots.core.BotFactory.nameTaken(s), female, new java.util.Random(r.botId()));
                final String old = r.name();
                final org.l2jmobius.gameserver.data.sql.CharInfoTable charNames = org.l2jmobius.gameserver.data.sql.CharInfoTable.getInstance();
                charNames.removeName(r.charId());
                repo.rename(r.botId(), r.charId(), neu);
                charNames.getIdByName(neu);
                registry.rename(r, old, neu);
                if (r.persona() != null) {
                    final Persona p = r.persona().withName(neu);
                    repo.savePersona(r.botId(), p);
                    r.setPersona(p);
                }
                taken.add(neu.toLowerCase(java.util.Locale.ROOT));
                n++;
            } catch (Exception e) {
                ctx.log().warn("digit rename " + r + ": " + e.getMessage());
            }
        }
        return n;
    }

    private boolean digitNameProtected(BotRecord r) {
        final Player p = r.player();
        if (p == null) {
            return false; // offline — ok to rename unless elsewhere protected
        }
        if ((p.getParty() != null) && partyHasLive(p.getParty())) {
            return true;
        }
        if ((p.getClan() != null) && clanHasLive(p.getClan())) {
            return true;
        }
        return false;
    }

    private boolean partyHasLive(org.l2jmobius.gameserver.entity.groups.Party party) {
        for (Player m : party.getMembers()) {
            if ((m != null) && !registry.isBot(m) && m.isOnline()) {
                return true;
            }
        }
        return false;
    }

    private boolean clanHasLive(org.l2jmobius.gameserver.entity.clan.Clan clan) {
        for (var m : clan.getMembers()) {
            final Player p = m == null ? null : m.getPlayer();
            if ((p != null) && !registry.isBot(p) && p.isOnline()) {
                return true;
            }
        }
        return false;
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
        stopAll("server shutdown");
        final MemoryStore ms = brains == null ? null : brains.memories();
        if (ms != null) {
            try {
                ms.flush();
            } catch (Exception e) {
                ctx.log().warn("bot memory not saved: " + e.getMessage());
            }
        }
    }

    /** Manual mode: bots come back after the restart. Director mode: loot vanishes, levels saved, nobody comes back. */
    private void stopAll(String why) {
        if (director != null) {
            director.stop(10_000);
            director.cleanupAll();
            logoutAll(why, false);
            try {
                repo.clearInWorld(); // nobody comes back by itself in director mode, whatever was in flight
            } catch (Exception e) {
                ctx.log().warn("cannot clear in-world marks", e);
            }
            try {
                final int wiped = repo.wipeInventoryLoot();
                if (wiped > 0) {
                    ctx.log().info("director: wiped " + wiped + " leftover bag loot row(s) after logout");
                }
            } catch (Exception e) {
                ctx.log().warn("cannot wipe leftover bot bag loot", e);
            }
        } else {
            logoutAll(why, true);
        }
    }

    private void applyConfig() {
        world.setRequireGeodata(ctx.config().getBoolean("RequireGeodata"));
        org.l2start.bots.director.PaperLevel.setGlobalPace(Double.parseDouble(ctx.config().getString("PaperLevelPace").trim()));
        brains.applyConfig();
        org.l2start.bots.director.GearTable.setEnchantMax(ctx.config().getInt("EnchantMax"));
        final org.l2start.bots.chat.SocialBoard social = brains.social();
        if (social != null) {
            social.setShoutsPerHour(ctx.config().getInt("LfpShoutPerHour"));
            social.setWhispersPerHour(ctx.config().getInt("WhisperPerHour"));
        }
        if (director != null) {
            final int[] lag = ints(ctx.config().getList("GradeLag"), 2, "GradeLag");
            director.setGradeLag(lag[0], lag[1]);
            director.setConfig(directorConfig());
            director.director().setLife(life());
            director.director().setStarterTownMax(ctx.config().getInt("DirectorStarterTownMax"));
            director.director().setPresenceField(ctx.config().getInt("PresenceFieldMin"), ctx.config().getInt("PresenceFieldMax"));
            director.director().setPresenceTown("starter", ctx.config().getInt("PresenceTownStarterMin"), ctx.config().getInt("PresenceTownStarterMax"));
            director.director().setPresenceTown("small", ctx.config().getInt("PresenceTownSmallMin"), ctx.config().getInt("PresenceTownSmallMax"));
            director.director().setPresenceTown("medium", ctx.config().getInt("PresenceTownMediumMin"), ctx.config().getInt("PresenceTownMediumMax"));
            director.director().setPresenceTown("large", ctx.config().getInt("PresenceTownLargeMin"), ctx.config().getInt("PresenceTownLargeMax"));
            director.director().setStarterZones(zones);
            try {
                director.director().setPartyShare(Double.parseDouble(ctx.config().getString("PartyShare").trim()));
            } catch (NumberFormatException e) {
                ctx.log().warn("bad PartyShare: " + ctx.config().getString("PartyShare"));
            }
            try {
                director.director().setClanMixedPartyChance(Double.parseDouble(ctx.config().getString("ClanMixedPartyChance").trim()));
            } catch (NumberFormatException e) {
                ctx.log().warn("bad ClanMixedPartyChance: " + ctx.config().getString("ClanMixedPartyChance"));
            }
            director.setAnchors(anchors(ctx.config().getList("Anchors")));
        }
        if (personas != null) {
            personas.setPkShare(pkShareOf(ctx.config().getString("PkBotShare")));
            personas.setDigitShare(digitShareOf(ctx.config().getString("BotNameDigitShare")));
        }
        if (clans != null) {
            long adenaLimit = 50_000_000L;
            try {
                adenaLimit = Long.parseLong(ctx.config().getString("ClanAdenaSpendLimit").trim().replace("_", ""));
            } catch (RuntimeException e) {
                ctx.log().warn("bad ClanAdenaSpendLimit");
            }
            clans.configure(ctx.config().getInt("BotClans"), ctx.config().getInt("ClanMembersMin"), ctx.config().getInt("ClanMembersMax"),
                parseChance(ctx.config().getString("ClanMixedPartyChance"), 0.25),
                parseChance(ctx.config().getString("ClanBotShare"), 0.70),
                ctx.config().getInt("BigBotClans"),
                adenaLimit);
        }
        clanInvitePlayers = ctx.config().getBoolean("ClanInvitePlayers");
        clanJoinPlayers = ctx.config().getBoolean("ClanJoinPlayers");
        if (clanHallBids != null) {
            long chBudget = 200_000_000L;
            try {
                chBudget = Long.parseLong(ctx.config().getString("ClanHallBidBudget").trim().replace("_", ""));
            } catch (RuntimeException e) {
                ctx.log().warn("bad ClanHallBidBudget");
            }
            clanHallBids.configure(ctx.config().getBoolean("ClanHallBids"),
                parseChance(ctx.config().getString("ClanHallBidShare"), 0.55), chBudget);
        }
        if (academyRecruiters != null) {
            academyRecruiters.configure(ctx.config().getBoolean("AcademyRecruiters"));
            final int[] shout = org.l2start.bots.clan.AcademyRecruiters.parseIntervalSec(ctx.config().getString("AcademyShoutIntervalSec"));
            academyRecruiters.setShoutIntervalSec(shout[0], shout[1]);
        }
        if (clanRecruiters != null) {
            clanRecruiters.configure(ctx.config().getBoolean("ClanRecruiters"));
            final int[] shout = org.l2start.bots.clan.AcademyRecruiters.parseIntervalSec(ctx.config().getString("AcademyShoutIntervalSec"));
            clanRecruiters.setShoutIntervalSec(shout[0], shout[1]);
        }
        if (academyRewards != null) {
            long join = 50_000L;
            long grad = 150_000L;
            try {
                join = Long.parseLong(ctx.config().getString("AcademyJoinReward").trim().replace("_", ""));
            } catch (RuntimeException e) {
                ctx.log().warn("bad AcademyJoinReward");
            }
            try {
                grad = Long.parseLong(ctx.config().getString("AcademyGraduateReward").trim().replace("_", ""));
            } catch (RuntimeException e) {
                ctx.log().warn("bad AcademyGraduateReward");
            }
            academyRewards.configure(join, grad);
            if (academyRecruiters != null) {
                academyRecruiters.setRewards((int) Math.min(Integer.MAX_VALUE, join), (int) Math.min(Integer.MAX_VALUE, grad));
            }
        }
    }

    static double parseChance(String raw, double def) {
        try {
            return Math.max(0, Math.min(1, Double.parseDouble(raw == null ? "" : raw.trim())));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private DirectorConfig directorConfig() {
        final DirectorConfig d = DirectorConfig.defaults();
        final int[] hunt = ints(ctx.config().getList("DirectorHuntPerCluster"), 2, "DirectorHuntPerCluster");
        final int[] ground = ints(ctx.config().getList("DirectorGround"), 2, "DirectorGround");
        final int[] hidden = ints(ctx.config().getList("DirectorHidden"), 2, "DirectorHidden");
        final List<String> linger = ctx.config().getList("DirectorLinger");
        if (linger.size() != 2) {
            throw new IllegalArgumentException("DirectorLinger: min,max expected");
        }
        final List<String> curveList = ctx.config().getList("OnlineCurve");
        if (curveList.size() != 24) {
            throw new IllegalArgumentException("OnlineCurve: 24 values expected, got " + curveList.size());
        }
        final double[] curve = new double[24];
        for (int i = 0; i < 24; i++) {
            curve[i] = Math.max(0, Math.min(1, Double.parseDouble(curveList.get(i))));
        }
        final int unseen = ctx.config().getInt("DirectorUnseenRadius");
        final List<String> transit = ctx.config().getList("DirectorTransit");
        final List<String> pass = ctx.config().getList("DirectorPass");
        if ((transit.size() != 2) || (pass.size() != 2)) {
            throw new IllegalArgumentException("DirectorTransit / DirectorPass: min,max expected");
        }
        final double wander = Math.max(0.1, Double.parseDouble(ctx.config().getString("TownWanderersMultiplier").trim()));
        return new DirectorConfig(d.clusterLink(), d.groundReach(), d.townMargin(), hidden[0], Math.max(hidden[0], hidden[1]), Math.min(d.seenRadius(), unseen - 500), unseen,
            ctx.config().getDuration("DirectorUnseenLogout").toMillis(), millis(linger.get(0)),
            millis(linger.get(1)), ints(ctx.config().getList("DirectorTownNight"), 2, "DirectorTownNight"),
            ints(ctx.config().getList("DirectorTownDay"), 2, "DirectorTownDay"), ints(ctx.config().getList("DirectorTownEvening"), 2, "DirectorTownEvening"), ground[0],
            Math.max(ground[0], ground[1]), d.indoorFactor(), hunt[0], hunt[1], ctx.config().getInt("DirectorMaxPerCluster"), ctx.config().getInt("DirectorMaxInWorld"),
            d.spawnIntervalMinMs(), d.spawnIntervalMaxMs(), d.leaveIntervalMs(), ctx.config().getDuration("DirectorReturnCooldown").toMillis(), d.pendingTimeoutMs(), curve,
            millis(transit.get(0)), millis(transit.get(1)), Double.parseDouble(ctx.config().getString("DirectorTransitStarterFactor").trim()), millis(pass.get(0)),
            millis(pass.get(1)), wander);
    }

    private org.l2start.bots.director.Director.Life life() {
        double soe = 0;
        double spot = 0;
        double gk = 0;
        for (String e : ctx.config().getList("LogoutStyle")) {
            final String[] kv = e.trim().split(":");
            if (kv.length != 2) {
                throw new IllegalArgumentException("LogoutStyle: soe:N,spot:N,gk:N expected, got " + e);
            }
            final double w = Math.max(0, Double.parseDouble(kv[1].trim()));
            switch (kv[0].trim().toLowerCase(java.util.Locale.ROOT)) {
                case "soe" -> soe = w;
                case "spot" -> spot = w;
                case "gk" -> gk = w;
                default -> throw new IllegalArgumentException("LogoutStyle: unknown style " + kv[0]);
            }
        }
        if ((soe + spot + gk) <= 0) {
            gk = 1;
        }
        final List<String> away = ctx.config().getList("RestockAway");
        if (away.size() != 2) {
            throw new IllegalArgumentException("RestockAway: min,max expected");
        }
        final double share = Math.max(0, Math.min(1, Double.parseDouble(ctx.config().getString("RestockShare").trim())));
        final long min = millis(away.get(0));
        return new org.l2start.bots.director.Director.Life(share, soe, spot, gk, min, Math.max(min, millis(away.get(1))));
    }

    /** "90s", "20m", "2h". */
    static long millis(String t) {
        final String v = t.trim().toLowerCase(java.util.Locale.ROOT);
        final long n = Long.parseLong(v.substring(0, v.length() - 1).trim());
        return switch (v.charAt(v.length() - 1)) {
            case 's' -> n * 1000;
            case 'm' -> n * 60_000;
            case 'h' -> n * 3_600_000;
            default -> throw new IllegalArgumentException("duration like 20m expected, got " + t);
        };
    }

    private static int[] ints(List<String> l, int n, String key) {
        if (l.size() != n) {
            throw new IllegalArgumentException(key + ": " + n + " numbers expected");
        }
        final int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = Integer.parseInt(l.get(i).trim());
        }
        return a;
    }

    /** "x;y;z" points. */
    static List<Pos> anchors(List<String> l) {
        final List<Pos> out = new ArrayList<>();
        for (String s : l) {
            final String[] p = s.trim().split(";");
            if (p.length != 3) {
                throw new IllegalArgumentException("Anchors: x;y;z expected, got " + s);
            }
            out.add(new Pos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())));
        }
        return out;
    }

    // ------------------------------------------------------------------ events

    /** Chat a bot may read besides whispers (phase 6a): white chat within hearing, shout and trade around. */
    static final int HEAR_ALL = 1250;
    static final int HEAR_SHOUT = 12_000;

    private void onChat(OnPlayerChat e) {
        final Player from = e.getPlayer();
        if ((from == null) || registry.isBot(from)) {
            return;
        }
        final ChatType type = e.getChatType();
        if (type == ChatType.WHISPER) {
            final Player target = e.getTarget();
            final BotRecord r = target == null ? null : registry.byCharId(target.getObjectId());
            if (r != null) {
                r.whisper(from.getName(), e.getText());
                ctx.metrics().counter("whispersReceived").inc();
                if ((academyRecruiters != null) && academyRecruiters.onWhisper(from, r, e.getText())) {
                    return;
                }
                if ((clanRecruiters != null) && clanRecruiters.onWhisper(from, r, e.getText())) {
                    return;
                }
                brains.post(r, new BotEvent.Whisper(from.getName(), e.getText()));
            }
            return;
        }
        if (type == ChatType.CLAN) {
            onClanChat(from, e.getText());
            return;
        }
        final String channel = switch (type) {
            case PARTY -> "PARTY";
            case GENERAL -> "ALL";
            case SHOUT -> "SHOUT";
            case TRADE -> "TRADE";
            default -> null;
        };
        if (channel == null) {
            return;
        }
        final BotEvent.Chat ev = new BotEvent.Chat(channel, from.getObjectId(), from.getName(), e.getText());
        if (type == ChatType.PARTY) {
            if (from.getParty() != null) {
                for (Player m : from.getParty().getMembers()) {
                    final BotRecord r = m == null ? null : registry.byCharId(m.getObjectId());
                    if ((r != null) && r.inWorld()) {
                        brains.post(r, ev);
                    }
                }
            }
            return;
        }
        final int radius = type == ChatType.GENERAL ? HEAR_ALL : HEAR_SHOUT;
        for (Player o : org.l2jmobius.gameserver.entity.World.getVisibleObjectsInRange(from, Player.class, radius)) {
            final BotRecord r = registry.byCharId(o.getObjectId());
            if ((r != null) && r.inWorld()) {
                brains.post(r, ev);
            }
        }
    }

    /** Phase 7b, hook P7-BOT-DUEL: a real player challenged a bot; its brain answers. */
    private boolean onDuelRequest(Player requester, Player bot, boolean partyDuel) {
        final BotRecord r = registry.byCharId(bot.getObjectId());
        if ((r == null) || !r.inWorld() || registry.isBot(requester)) {
            return false;
        }
        brains.post(r, new BotEvent.DuelInvite(requester.getObjectId(), requester.getName(), requester.getLevel(), partyDuel));
        return true;
    }

    /** Phase 7e: clan chat from a clan leader — rally pulls non-trader clan bots. */
    private void onClanChat(Player from, String text) {
        if ((from.getClan() == null) || !from.isClanLeader() || !org.l2start.bots.clan.ClanRallyParse.isRally(text)) {
            return;
        }
        final String kind = org.l2start.bots.clan.ClanRallyParse.kind(text);
        final BotEvent.ClanRally rally = new BotEvent.ClanRally(from.getObjectId(), from.getName(), from.getX(), from.getY(), from.getZ(), kind);
        for (var m : from.getClan().getMembers()) {
            final Player o = m == null ? null : m.getPlayer();
            final BotRecord r = o == null ? null : registry.byCharId(o.getObjectId());
            if ((r == null) || !r.inWorld() || (r.status() == BotRecord.Status.TRADER) || (o == from)) {
                continue;
            }
            brains.post(r, rally);
        }
    }

    /** Phase 7e, hook P7-BOT-PLEDGE: a real player invited a bot into a clan / academy. */
    private boolean onPledgeRequest(Player requester, Player bot, int pledgeType) {
        final BotRecord r = registry.byCharId(bot.getObjectId());
        if ((r == null) || !r.inWorld() || registry.isBot(requester)) {
            return false;
        }
        if (org.l2jmobius.gameserver.entity.actor.holders.player.BlockList.isBlocked(requester, bot)) {
            return true;
        }
        final boolean canLeave = (r.status() != BotRecord.Status.TRADER) && ((clans == null) || !clans.isLeader(r.botId()));
        brains.post(r, new BotEvent.PledgeInvite(requester.getObjectId(), requester.getName(), requester.getLevel(), pledgeType, canLeave,
            clanJoinPlayers));
        return true;
    }

    /** Phase 6a, hook P6-BOT-PARTY: a real player invited a bot; its brain answers (or lets it expire). */
    private boolean onPartyRequest(Player requester, Player bot) {
        final BotRecord r = registry.byCharId(bot.getObjectId());
        if ((r == null) || !r.inWorld() || registry.isBot(requester)) {
            return false;
        }
        // phase 6g: /block or /blockall — ignore the invite (no answer dialog for the bot)
        if (org.l2jmobius.gameserver.entity.actor.holders.player.BlockList.isBlocked(requester, bot)) {
            return true;
        }
        ctx.metrics().counter("partyInvites").inc();
        final int members = requester.getParty() == null ? 0 : requester.getParty().getMemberCount();
        int bots = 0;
        if (requester.getParty() != null) {
            for (Player m : requester.getParty().getMembers()) {
                if (registry.isBot(m)) {
                    bots++;
                }
            }
        }
        brains.post(r, new BotEvent.PartyInvite(requester.getObjectId(), requester.getName(), requester.getLevel(), requester.getPlayerClass().getId(), members,
            bots >= ctx.config().getInt("PartyBotsMax")));
        return true;
    }

    /**
     * Phase 6d, hook P6-BOT-PARTY busy: player invited a bot that is already in a bot-only party. The invited bot
     * whispers; the party leader invites the player into that party.
     */
    private boolean onPartyBusy(Player requester, Player bot) {
        final BotRecord r = registry.byCharId(bot.getObjectId());
        if ((r == null) || !r.inWorld() || registry.isBot(requester)) {
            return false;
        }
        final org.l2jmobius.gameserver.entity.groups.Party party = bot.getParty();
        if (party == null) {
            return false;
        }
        for (Player m : party.getMembers()) {
            if (!registry.isBot(m)) {
                return false; // mixed party: stock "member of another party"
            }
        }
        final boolean isLeader = party.isLeader(bot);
        final int fromId = requester.getObjectId();
        final String from = requester.getName();
        final int fromLevel = requester.getLevel();
        final int fromClass = requester.getPlayerClass().getId();
        brains.post(r, new BotEvent.PartyRedirect(fromId, from, fromLevel, fromClass, true, isLeader));
        if (!isLeader) {
            final Player leader = party.getLeader();
            final BotRecord lr = leader == null ? null : registry.byCharId(leader.getObjectId());
            if ((lr != null) && lr.inWorld()) {
                brains.post(lr, new BotEvent.PartyRedirect(fromId, from, fromLevel, fromClass, false, true));
            }
        }
        ctx.metrics().counter("partyRedirects").inc();
        return true;
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
        if ((r.persona() != null) && r.persona().pk() && (pkLedger != null) && dead.isPlayer()) {
            try {
                pkLedger.sync(r.botId(), dead.asPlayer()); // stock karma drop removed object ids from the bag
            } catch (Exception ex) {
                ctx.log().warn("pk ledger sync after death of " + r.name() + ": " + ex.getMessage());
            }
            brains.pvp().notePkDeath(r.botId(), System.currentTimeMillis());
        }
        if (director != null) {
            logDeath(r, e.getAttacker());
        }
        // PK kill: attacker is a PK bot that killed a player
        final Creature killer = e.getAttacker();
        if ((killer != null) && killer.isPlayer()) {
            final BotRecord pk = registry.byCharId(killer.getObjectId());
            if ((pk != null) && (pk.persona() != null) && pk.persona().pk()) {
                brains.pvp().notePkKill(pk.botId(), System.currentTimeMillis());
            }
        }
        brains.post(r, new BotEvent.Died(e.getAttacker() == null ? 0 : e.getAttacker().getObjectId()));
        scheduleReturn(r);
    }

    private long deathLogWindow;
    private int deathLogs;

    /** Director mode: why bots die (at most 10 lines a minute): level, order, killer, the last actions of the brain. */
    private synchronized void logDeath(BotRecord r, Creature killer) {
        final long now = System.currentTimeMillis();
        if ((now - deathLogWindow) > 60_000) {
            deathLogWindow = now;
            deathLogs = 0;
        }
        if (deathLogs++ >= 10) {
            return;
        }
        final Player p = r.player();
        final StringBuilder sb = new StringBuilder("died: ").append(r.name()).append(" lv ").append(p == null ? 0 : p.getLevel()).append(" class ").append(p == null ? 0 : p.getPlayerClass().getId())
            .append(", order ").append(r.assignment()).append(", killer ").append(killer == null ? "-" : killer.getName() + " lv " + killer.getLevel())
            .append(p == null ? "" : " at " + p.getX() + "," + p.getY() + "," + p.getZ()).append(", last:");
        final org.l2start.bots.runtime.BotSlot slot = brains.slot(r);
        if (slot != null) {
            final List<org.l2start.bots.runtime.ActionLog.Entry> l = slot.log().entries();
            for (int i = Math.min(6, l.size()) - 1; i >= 0; i--) { // newest first in the log: oldest of the 6 first here
                sb.append(" | ").append(l.get(i).text());
            }
        }
        ctx.log().info(sb.toString());
    }

    /**
     * Lies dead a while by its patience (impatient 2-8 s, patient 1-3 min "waiting for a res", else
     * DeathReturnMin..Max), with a partner of its pair/trio alive near at least 1-2 min; accepts a resurrection
     * (4g) and otherwise presses "To village".
     */
    private void scheduleReturn(BotRecord r) {
        final long min = ctx.config().getDuration("DeathReturnMin").toMillis();
        final long max = Math.max(min, ctx.config().getDuration("DeathReturnMax").toMillis());
        final double patience = (r.persona() == null) || (r.persona().traits() == null) ? 0.5 : r.persona().traits().patience();
        long delay = org.l2start.bots.director.DeathWait.ms(patience, min, max, new java.util.Random(ThreadLocalRandom.current().nextLong()));
        final org.l2jmobius.gameserver.entity.actor.Player p = r.player();
        if ((p != null) && (p.isInsideZone(org.l2jmobius.gameserver.entity.zone.ZoneId.TOWN) || p.isInsideZone(org.l2jmobius.gameserver.entity.zone.ZoneId.PEACE))) {
            delay = 1500 + ThreadLocalRandom.current().nextLong(1500); // no corpses lying in towns
        } else if ((p != null) && (director != null) && director.withPlayer(p)) {
            delay = Math.max(delay, 120_000 + ThreadLocalRandom.current().nextLong(60_000)); // phase 6c: its player may raise it
        } else if ((p != null) && partnerNear(r, p)) {
            delay = Math.max(delay, 60_000 + ThreadLocalRandom.current().nextLong(60_000)); // its healer will raise it
        }
        final long returnAt = System.currentTimeMillis() + delay;
        deadWatch(r, returnAt);
    }

    /** Every 2 s while dead: takes a resurrection offered, or goes to town when the time is up. */
    private void deadWatch(BotRecord r, long returnAt) {
        final long wait = Math.max(200, Math.min(2000, returnAt - System.currentTimeMillis()));
        ctx.scheduler().schedule("dead " + r.name(), Duration.ofMillis(wait), () -> {
            final Player p = r.player();
            if ((p == null) || !p.isDead() || !r.inWorld()) {
                return;
            }
            if (p.isReviveRequested() && !p.isRevivingPet()) {
                p.reviveAnswer(1);
                ctx.metrics().counter("resurrected").inc();
                return;
            }
            if (System.currentTimeMillis() >= returnAt) {
                world.returnToTown(r);
                return;
            }
            deadWatch(r, returnAt);
        });
    }

    /** A live member of its pair/trio within 2000. */
    private boolean partnerNear(BotRecord r, Player p) {
        if (r.group() == 0) {
            return false;
        }
        for (BotRecord o : registry.all()) {
            final Player q = o.player();
            if ((o != r) && (o.group() == r.group()) && (q != null) && !q.isDead() && (q.calculateDistance2D(p) < 2000)) {
                return true;
            }
        }
        return false;
    }

    private void onDamage(OnCreatureDamageReceived e) {
        final Creature target = e.getTarget();
        if ((target == null) || !target.isPlayer()) {
            return;
        }
        final BotRecord r = registry.byCharId(target.getObjectId());
        if ((r != null) && r.inWorld() && (e.getAttacker() != null)) {
            brains.post(r, new BotEvent.Attacked(e.getAttacker().getObjectId(), e.getAttacker().isPlayable(), e.getDamage()));
        }
    }

    private void onLevel(OnPlayerLevelChanged e) {
        final BotRecord r = registry.byCharId(e.getPlayer().getObjectId());
        if ((r != null) && r.inWorld() && (e.getNewLevel() > e.getOldLevel())) {
            ctx.metrics().counter("levelUps").inc();
            brains.post(r, new BotEvent.LevelUp(e.getOldLevel(), e.getNewLevel()));
        }
    }

    private void onKill(OnAttackableKill e) {
        final BotRecord r = e.getAttacker() == null ? null : registry.byCharId(e.getAttacker().getObjectId());
        if ((r != null) && r.inWorld() && (e.getTarget() != null)) {
            ctx.metrics().counter("kills").inc();
            brains.post(r, new BotEvent.Killed(e.getTarget().getObjectId(), e.getTarget().getId()));
        }
    }

    // ------------------------------------------------------------------ operations (admin commands, autostart)

    public BotRegistry registry() {
        return registry;
    }

    public BotWorld world() {
        return world;
    }

    /**
     * Phase 7c admin: mark persona as PK, give entry karma, spawn / move next to the GM for a drop test.
     * @return short status for the GM
     */
    public String makePkNear(String name, Player gm) throws Exception {
        final BotRecord r = registry.byName(name);
        if (r == null) {
            return "No bot named " + name;
        }
        if (r.status() == BotRecord.Status.TRADER) {
            return name + " is a trader, not a PK";
        }
        org.l2start.bots.persona.Persona p = r.persona();
        if (p == null) {
            return name + " has no persona";
        }
        if (!p.pk()) {
            p = p.withPk(true);
            r.setPersona(p);
            repo.savePersona(r.botId(), p);
        }
        final Location near = new Location(gm.getX() + 80, gm.getY() + 80, gm.getZ());
        if (r.inWorld()) {
            final Player bot = r.player();
            if (bot != null) {
                if (bot.getKarma() <= 0) {
                    bot.setKarma(Materializer.PK_ENTRY_KARMA);
                }
                bot.teleToLocation(near, true);
            }
            return name + " is PK (karma " + (bot == null ? 0 : bot.getKarma()) + "), moved here";
        }
        final Materializer mat;
        if (director != null) {
            mat = director.materializer();
        } else {
            mat = new Materializer(GearTable.load(ctx.resources().path("data/gear.xml")));
            mat.setPkLedger(pkLedger);
            mat.setSupplies(() -> ctx.services().find(org.l2start.bots.trade.Supplies.class).orElse(null));
        }
        final int level = Math.max(20, gm.getLevel());
        final String why = world.enterPrepared(r, near, player -> mat.prepare(player, r, level));
        if (why != null) {
            return "spawn failed: " + why;
        }
        ctx.metrics().counter("logins").inc();
        final Player bot = r.player();
        return name + " is PK, spawned here (karma " + (bot == null ? 0 : bot.getKarma()) + ")";
    }

    /** Phase 6a: what a bot remembers of real players, for //bots info. */
    public record MemoryView(java.util.List<String> lines) {
    }

    public MemoryView memoryOf(BotRecord r) {
        final MemoryStore ms = brains == null ? null : brains.memories();
        final java.util.List<String> out = new java.util.ArrayList<>();
        if ((ms != null) && r.inWorld()) {
            for (org.l2start.bots.api.Memory.Of o : ms.summary(r.botId())) {
                final org.l2jmobius.gameserver.entity.actor.Player who = org.l2jmobius.gameserver.entity.World.getPlayer(o.charId());
                out.add((who == null ? "#" + o.charId() : who.getName()) + ": affinity " + o.affinity() + ", party " + o.partyMinutes() + " min, met " + o.met()
                    + (o.tags().isEmpty() ? "" : ", " + MemoryStore.tagText(o.tags())));
            }
        }
        return new MemoryView(out);
    }

    /** Phase 6g: {@code //bots social} — bots that remember someone as ANNOYED (at most 20 lines). */
    public java.util.List<String> annoyedSummary() {
        final MemoryStore ms = brains == null ? null : brains.memories();
        final java.util.List<String> out = new java.util.ArrayList<>();
        if (ms == null) {
            return out;
        }
        for (BotRecord r : registry.sorted()) {
            for (org.l2start.bots.api.Memory.Of o : ms.summary(r.botId())) {
                if (!o.has(org.l2start.bots.api.Memory.Tag.ANNOYED)) {
                    continue;
                }
                final org.l2jmobius.gameserver.entity.actor.Player who = org.l2jmobius.gameserver.entity.World.getPlayer(o.charId());
                out.add(r.name() + " ← " + (who == null ? "#" + o.charId() : who.getName()) + " (aff " + o.affinity() + ")");
                if (out.size() >= 20) {
                    return out;
                }
            }
        }
        return out;
    }

    public BrainHost brains() {
        return brains;
    }

    public ModuleContext ctx() {
        return ctx;
    }

    BotRepository repo() {
        return repo;
    }

    /** null in manual presence mode. */
    public DirectorRunner director() {
        return director;
    }

    /** Personas kept ready (offline, ACTIVE) per race at starting levels; everything else is created on demand. */
    static final int STARTER_MAX_LEVEL = 20;

    /** Pure: the race (0..4) whose starter reserve is lowest and below {@code reserve}, -1 = all full. */
    static int reserveGap(int[] ready, int reserve) {
        int best = -1;
        for (int race = 0; race < ready.length; race++) {
            if ((ready[race] < reserve) && ((best < 0) || (ready[race] < ready[best]))) {
                best = race;
            }
        }
        return best;
    }

    /** ACTIVE bots in the world now: what MaxBots caps (traders and offline personas do not count). */
    public int inWorldActive() {
        int n = 0;
        for (BotRecord r : registry.all()) {
            if ((r.status() == BotRecord.Status.ACTIVE) && r.inWorld()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Pool warm-up (director): keeps a small reserve of offline personas of every race at starting levels
     * (DirectorPoolReserve each, levels 1..20), at most DirectorPoolWarmup a minute. The database grows on demand:
     * the director creates a persona of the right race and level when none fits.
     */
    private void warmUp() {
        final int perMin = ctx.config().getInt("DirectorPoolWarmup");
        final int reserve = ctx.config().getInt("DirectorPoolReserve");
        if ((perMin <= 0) || (reserve <= 0) || (director == null) || director.stopping()) {
            return;
        }
        if ((perMin < 12) && (ThreadLocalRandom.current().nextInt(12) >= perMin)) {
            return;
        }
        final long now = System.currentTimeMillis();
        final int[] ready = new int[5];
        for (BotRecord r : registry.all()) {
            if ((r.status() == BotRecord.Status.ACTIVE) && !r.inWorld() && (r.paperLevel(now) <= STARTER_MAX_LEVEL)) {
                final int race = DirectorRunner.race(r.persona() == null ? r.classId() : r.persona().classId());
                if ((race >= 0) && (race < ready.length)) {
                    ready[race]++;
                }
            }
        }
        final int race = reserveGap(ready, reserve);
        if (race < 0) {
            return;
        }
        try {
            final BotRecord r = factory.create(DirectorRunner.startClass(race)); // quietly: no log line for each
            ctx.metrics().counter("created").inc();
            final int level = 1 + ThreadLocalRandom.current().nextInt(STARTER_MAX_LEVEL);
            r.setProgress(level, now, 1);
            repo.saveProgress(r.botId(), level, now);
        } catch (Exception e) {
            ctx.log().warn("pool warm-up: cannot create a persona", e);
        }
    }

    BotRecord createOne(int classId) throws Exception {
        final BotRecord r = factory.create(classId);
        final Persona p = r.persona();
        ctx.log().info("created " + r.name() + " (class " + r.classId() + ", " + p.nameStyle() + ", " + (p.female() ? "female" : "male") + ", " + p.chronotype() + ", " + p.timezone() + ", account " + r.accountName() + ")");
        ctx.metrics().counter("created").inc();
        return r;
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
        int done = 0;
        for (int i = 0; i < n; i++) { // MaxBots caps bots in the world, not characters in the database
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
        final org.l2jmobius.gameserver.entity.actor.Player p = r.player();
        if ((p != null) && p.isDead()) {
            scheduleReturn(r); // logged out dead (stock keeps it dead on login): back to town like after a death
        }
        return true;
    }

    /** A brain asked to log out: in director mode through the director (loot vanishes, level saved). */
    private void brainLogout(BotRecord r) {
        final DirectorRunner d = director;
        if (d != null) {
            d.requestLogout(r, "brain");
        } else {
            despawn(r);
        }
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
