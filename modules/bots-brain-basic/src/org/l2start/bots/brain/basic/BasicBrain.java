package org.l2start.bots.brain.basic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.l2start.bots.api.ActionResult;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.Actuators;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.Brain;
import org.l2start.bots.api.BrainContext;
import org.l2start.bots.api.DropView;
import org.l2start.bots.api.NpcView;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SelfView;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.api.WorldView;

/**
 * The minimal brain of phase 3 (plan section 10): TOWN_IDLE / LEVEL / REST / DEAD. Proves the loop "stroll in town ->
 * walk to a hunting ground -> kill monsters of about its level -> rest -> die -> back in town -> again", not combat
 * realism. Warriors hit, mystics use their first offensive magic skill and the staff when MP is low. No potions,
 * soulshots or buffs (phase 4). Uses only org.l2start.bots.api: tested offline on FakeWorld.
 * <p>
 * Step 3e: when the presence director gives an order ({@link BrainContext#assignment()}), the bot follows it instead
 * (plan section 20.5): TOWN = errands between gatekeeper, shops, warehouse and trainers, standing in groups, sitting;
 * HUNT = hunt on that ground, move between its spawns, rest; LEAVE_RUN / LEAVE_GK = drop everything and run off
 * (out of sight) or to the gatekeeper and "teleport" (log out there). Never a monster a real player fights or stands
 * next to. Without an order (Presence = manual) it lives as in step 3d.
 */
public final class BasicBrain implements Brain {
    public enum Phase {
        TOWN_IDLE, LEVEL, REST, DEAD
    }

    static final int LEG = 1500;
    static final int SEARCH = 1400;
    static final int LOOT_RANGE = 500;
    static final long FIGHT_TIMEOUT = 60_000;
    static final long BLACKLIST_MS = 300_000;
    /** Farther than this from every starting village: no hunting (phase 3 area). */
    static final int FAR = 30_000;

    private final LevelZones zones;
    private final List<Village> villages;
    private final MobClaims claims;
    private final int botId;

    private Phase phase;
    private String detail = "";
    private boolean started;
    private Village village;
    private long idleUntil;
    private long nextIdleAction;
    private LevelZone zone;
    private int fightId;
    private String fightName = "";
    private long fightSince;
    private double fightHp = 101;
    private long fightHpAt;
    private long lootUntil;
    private int lootTaken;
    private Pos lastPos;
    private long lastMovedAt;
    private int stuck;
    private int detourSide;
    private boolean far;
    private double restTo;
    private final Map<Integer, Long> blacklist = new HashMap<>();
    /** (skill, monster type) pairs the game refused (e.g. undead-only Might of Heaven on a living monster), skipped until then. */
    static final long BAD_SKILL_MS = 10 * 60_000L;
    private final Map<Long, Long> badSkills = new HashMap<>();
    /** Monsters this close to a real player are theirs (presence rule 6). */
    static final int PLAYER_MOB_RADIUS = 700;
    // presence director orders
    private long orderSeq = -1;
    /** Monster levels it picks: [lvl - mobBelow, lvl + mobAbove] (director bots: a bit below their level). */
    private int mobBelow = 3;
    private int mobAbove = 2;
    private Pos errand;
    private String errandWhat = "";
    private long arrivedAt;
    private long leaveAt;

    public BasicBrain(int botId, LevelZones zones, List<Village> villages, MobClaims claims) {
        this.botId = botId;
        this.zones = zones;
        this.villages = villages;
        this.claims = claims;
    }

    @Override
    public String state() {
        return (phase == null ? "INIT" : phase.name()) + (detail.isEmpty() ? "" : "/" + detail);
    }

    public Phase phase() {
        return phase;
    }

    public LevelZone zone() {
        return zone;
    }

    @Override
    public boolean inCombat() {
        return fightId != 0;
    }

    @Override
    public void stop(BrainContext ctx) {
        claims.releaseAll(botId);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public long tick(BrainContext ctx) {
        final WorldView w = ctx.world();
        final SelfView me = w.self();
        final long now = ctx.now();
        final Assignment order = ctx.assignment();
        if (!started && (order == null)) {
            start(ctx, me, now);
        }
        boolean revived = false;
        for (BotEvent e : w.events()) {
            switch (e) {
                case BotEvent.Died d -> {
                    enter(ctx, Phase.DEAD, "waiting");
                    endFight();
                }
                case BotEvent.Teleported t -> revived = true;
                case BotEvent.Attacked at -> {
                    if (at.byPlayer()) {
                        playerHits.put(at.attackerId(), now);
                    }
                }
                case BotEvent.LevelUp l -> {
                    final int n = ctx.act().learnSkills();
                    ctx.note("level " + l.newLevel() + (n > 0 ? ", learned " + n + " skill(s)" : ""));
                    if ((zone != null) && !zone.fits(l.newLevel())) {
                        zone = null; // outgrew it
                    }
                }
                default -> {
                }
            }
        }
        if (me.dead()) {
            if (phase != Phase.DEAD) {
                enter(ctx, Phase.DEAD, "waiting");
                endFight();
                pvpId = 0;
            }
            return 2000; // bots-core brings it back to town after 5-40 s
        }
        if (!me.teleporting()) {
            drink(ctx, w, me, now);
            final long fl = mobFlee(ctx, w, me, now);
            if (fl > 0) {
                return fl;
            }
        }
        if (order != null) {
            if (phase == Phase.DEAD) {
                enter(ctx, Phase.TOWN_IDLE, "revived"); // in town now: the director sends it away
            }
            if (me.teleporting()) {
                return 500;
            }
            return presence(ctx, w, me, now, order, attacker(w, me));
        }
        if (phase == Phase.DEAD || (revived && (fightId == 0) && (phase != Phase.TOWN_IDLE))) {
            // back on its feet in town: a short pause, then hunting again
            village = Village.nearest(villages, me.pos());
            idleUntil = now + 10_000 + ctx.rnd().nextInt(30_000);
            nextIdleAction = now + ctx.reactionDelay();
            zone = null;
            enter(ctx, Phase.TOWN_IDLE, "revived");
        }
        if (me.teleporting()) {
            return 500;
        }
        final long pv = pvp(ctx, w, me, now);
        if (pv > 0) {
            return pv;
        }
        final NpcView attacker = attacker(w, me);
        return switch (phase) {
            case TOWN_IDLE -> townIdle(ctx, w, me, now, attacker);
            case REST -> rest(ctx, w, me, now, attacker);
            default -> level(ctx, w, me, now, attacker);
        };
    }

    private void start(BrainContext ctx, SelfView me, long now) {
        started = true;
        // the village it is at now (a GM may have placed it elsewhere: //bots spawn here), else its home village
        final Pos home = ctx.info().home();
        village = Village.nearest(villages, me.pos());
        if ((village.center().distance2d(me.pos()) > FAR) && !((home.x() == 0) && (home.y() == 0))) {
            village = Village.nearest(villages, home);
        }
        far = village.center().distance2d(me.pos()) > FAR;
        ctx.act().learnSkills(); // SP collected before (or before this brain existed)
        final boolean inTown = me.pos().distance2d(village.center()) < (village.radius() + 1500);
        if (inTown && (ctx.rnd().nextDouble() < 0.3)) {
            idleUntil = now + 60_000 + ctx.rnd().nextInt(240_000); // 30 %: "afk" in town 1-5 min after login
            enter(ctx, Phase.TOWN_IDLE, "just logged in");
        } else if (inTown) {
            idleUntil = now + 5_000 + ctx.rnd().nextInt(25_000);
            enter(ctx, Phase.TOWN_IDLE, "");
        } else if (far) {
            // phase 3 bots live around the five starting villages; far away it only strolls where it is
            village = new Village("HERE", me.pos(), 400);
            idleUntil = Long.MAX_VALUE;
            enter(ctx, Phase.TOWN_IDLE, "far from the starting villages");
        } else {
            enter(ctx, Phase.LEVEL, "");
        }
        nextIdleAction = now + ctx.reactionDelay();
    }

    private void enter(BrainContext ctx, Phase p, String why) {
        if (phase != p) {
            ctx.note(p + (why.isEmpty() ? "" : " (" + why + ")"));
        }
        phase = p;
        detail = why;
    }

    // ------------------------------------------------------------------ a real player attacks

    /** Base chance to fight back a real player who attacks (config PvpFightBack of bots-brain-basic); else it flees. */
    static volatile double fightBackBase = 0.5;
    /** How far a bot follows its attacker: no chasing across the map. */
    static final int PVP_LEASH = 900;
    private int pvpId;
    private boolean pvpFight;
    private Pos pvpStart;
    private Pos pvpFlee;
    private long pvpSince;
    private long pvpQuietSince;
    private final Map<Integer, Long> pvpDone = new HashMap<>();
    /** Real players who hit this bot: id -> last hit (from the damage events). */
    private final Map<Integer, Long> playerHits = new HashMap<>();
    /** Fight or flight, kept per attacker while the bot is in the world (a person does not change its mind each hit). */
    private final Map<Integer, Boolean> pvpDecision = new HashMap<>();
    private int pvpRefused;
    private long pvpFleeMs;
    /** A hit by a player counts as "still fighting" this long. */
    static final long PVP_HIT_MEMORY = 10_000;
    /** After a fight the same player is left alone this long, unless it hits again. */
    static final long PVP_IGNORE_MS = 60_000;
    /** Below this HP a fight with a player turns into running away (and a scroll of escape). */
    static final double PVP_FLEE_HP = 25;

    /** A real player (not a bot) who attacks this bot: hit it lately, or has it targeted in a fight while flagged or with karma. */
    PlayerView playerAttacker(WorldView w, SelfView me, long now) {
        for (PlayerView p : w.players()) {
            if (p.bot() || (p.pos().distance2d(me.pos()) > 1200)) {
                continue;
            }
            if (hitByRecently(p.objectId(), now, PVP_HIT_MEMORY) || ((p.targetId() == me.objectId()) && p.inCombat() && (p.pvpFlag() || (p.karma() > 0)))) {
                return p;
            }
        }
        return null;
    }

    private boolean hitByRecently(int playerId, long now, long ms) {
        final Long t = playerHits.get(playerId);
        return (t != null) && ((now - t) <= ms);
    }

    /**
     * Chance to fight back: the base, more when the bot is stronger (level), more for aggressive personas, less for
     * patient ones and when already hurt. Base 0 = always flee, 1 = always fight.
     */
    static double fightBackChance(double base, int myLevel, int theirLevel, double aggression, double patience, double hpPct) {
        if (base <= 0) {
            return 0;
        }
        if (base >= 1) {
            return 1;
        }
        double c = base + Math.max(-0.35, Math.min(0.35, 0.05 * (myLevel - theirLevel))) + ((aggression - 0.5) * 0.4) - ((patience - 0.5) * 0.1);
        if (hpPct < 40) {
            c -= 0.25;
        }
        return Math.max(0.05, Math.min(0.95, c));
    }

    /**
     * Reaction to a real player's attack, as a player would: fight back (stock PvP: the bot gets the flag, karma rules
     * as for anybody) or run and use a scroll of escape. A fight goes on to the end: no sitting, no resting, no going
     * back to monsters while the attacker is near and still fighting; only low HP turns it into running away. No chasing:
     * it ends when the attacker is dead (the attack is refused), far, or has left the bot alone for a while.
     * @return the delay, or 0 when there is nothing of the kind going on
     */
    private long pvp(BrainContext ctx, WorldView w, SelfView me, long now) {
        if (pvpId == 0) {
            if (me.inPeaceZone()) {
                return 0;
            }
            final PlayerView p = playerAttacker(w, me, now);
            if (p == null) {
                return 0;
            }
            final long done = pvpDone.getOrDefault(p.objectId(), 0L);
            final Long hit = playerHits.get(p.objectId());
            if ((done > now) && ((hit == null) || (hit <= (done - PVP_IGNORE_MS)))) {
                return 0; // we just parted, and it has not hit us again since
            }
            pvpId = p.objectId();
            pvpStart = me.pos();
            pvpSince = now;
            pvpRefused = 0;
            final Boolean before = pvpDecision.get(p.objectId());
            final double chance = fightBackChance(fightBackBase, me.level(), p.level(), ctx.info().aggression(), ctx.info().patience(), me.hpPct());
            pvpFight = (me.hpPct() >= fleeHp) && (before != null ? before : ctx.rnd().nextDouble() < chance);
            pvpDecision.put(p.objectId(), pvpFight);
            endFight();
            standIfSitting(ctx, me);
            ctx.note((pvpFight ? "fights back " : "runs from ") + p.name() + " lv " + p.level() + String.format(java.util.Locale.ROOT, " (chance to fight %.2f)", chance));
            if (!pvpFight) {
                return startFlee(ctx, me, p, now);
            }
        }
        final PlayerView p = w.players().stream().filter(x -> x.objectId() == pvpId).findFirst().orElse(null);
        standIfSitting(ctx, me); // never sits while a player fights it
        if (!pvpFight) {
            // a few seconds of running, then a scroll of escape (logs out later in town, the director sends it away)
            detail = "runs from a player";
            if (((now - pvpSince) > pvpFleeMs) || (p == null) || (pvpFlee.distance2d(me.pos()) < 150)) {
                ctx.note("scroll of escape");
                ctx.act().returnToTown();
                pvpOver(now);
                return 3000;
            }
            if (!me.moving()) {
                ctx.act().moveTo(pvpFlee);
            }
            return 600;
        }
        if ((p != null) && (me.hpPct() < fleeHp)) {
            ctx.note("hurt badly in the fight with " + p.name() + ": runs");
            pvpFight = false;
            pvpDecision.put(p.objectId(), false);
            return startFlee(ctx, me, p, now);
        }
        final boolean engaged = (p != null) && (hitByRecently(p.objectId(), now, PVP_HIT_MEMORY) || ((p.targetId() == me.objectId()) && p.inCombat()));
        final String over;
        if (p == null) {
            over = "the player is gone";
        } else if (pvpRefused >= 4) {
            over = "the player is dead or out of reach";
        } else if (p.pos().distance2d(me.pos()) > 1500) {
            over = "the player ran away";
        } else if (!engaged) {
            over = "the player left it alone";
        } else if ((me.pos().distance2d(pvpStart) > PVP_LEASH) && !hitByRecently(p.objectId(), now, 4000)) {
            over = "no chasing";
        } else {
            over = null;
        }
        if (over != null) {
            ctx.note("the fight with a player is over: " + over);
            ctx.act().stop();
            pvpOver(now);
            return 1000;
        }
        detail = "fights " + p.name();
        if (me.mage() && (me.mpPct() >= 20) && !me.casting()) {
            final SkillView nuke = nuke(me, 0, badSkills, now);
            if ((nuke != null) && nuke.ready() && (nuke.mpCost() <= me.mp())) {
                final ActionResult r = ctx.act().cast(nuke.id(), p.objectId());
                if (r.ok()) {
                    pvpRefused = 0;
                    return 700;
                }
                if (r == ActionResult.INVALID) {
                    pvpRefused++;
                }
            }
        }
        if (!"ATTACK".equals(me.intention()) || (me.targetId() != p.objectId())) {
            final ActionResult r = ctx.act().attack(p.objectId());
            if (r == ActionResult.INVALID) {
                pvpRefused++;
            } else if (r.ok()) {
                pvpRefused = 0;
            }
        }
        return 500;
    }

    private long startFlee(BrainContext ctx, SelfView me, PlayerView p, long now) {
        pvpSince = now;
        pvpFleeMs = 3000 + ctx.rnd().nextInt(3000);
        final double dx = me.pos().x() - p.pos().x();
        final double dy = me.pos().y() - p.pos().y();
        final double len = Math.max(1, Math.hypot(dx, dy));
        final int from = (int) me.pos().distance2d(p.pos());
        final Pos safe = safePoint(me.pos(), p.pos(), from + 1200, 1200, ctx.world().geo(), threats(ctx.world(), 0), FLEE_FAN, false);
        pvpFlee = safe != null ? safe : new Pos(me.pos().x() + (int) ((dx / len) * 1200), me.pos().y() + (int) ((dy / len) * 1200), me.pos().z());
        endFight();
        ctx.act().moveTo(pvpFlee);
        return 600;
    }

    private void pvpOver(long now) {
        pvpDone.put(pvpId, now + PVP_IGNORE_MS);
        pvpDone.values().removeIf(t -> t < now);
        if (pvpDecision.size() > 50) {
            pvpDecision.clear();
        }
        pvpId = 0;
    }

    /** A living monster that is fighting this bot (self-defence comes before anything else). */
    private static NpcView attacker(WorldView w, SelfView me) {
        for (NpcView n : w.npcs()) {
            if (!n.dead() && n.monster() && (n.targetId() == me.objectId()) && n.attackable()) {
                return n;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ TOWN_IDLE

    private long townIdle(BrainContext ctx, WorldView w, SelfView me, long now, NpcView attacker) {
        final Actuators act = ctx.act();
        if ((attacker != null) && !me.inPeaceZone()) {
            standIfSitting(ctx, me);
            enter(ctx, Phase.LEVEL, "attacked");
            return startFight(ctx, me, attacker, now);
        }
        if (now >= idleUntil) {
            standIfSitting(ctx, me);
            enter(ctx, Phase.LEVEL, "");
            return ctx.reactionDelay();
        }
        if (now < nextIdleAction) {
            return Math.min(nextIdleAction, idleUntil) - now;
        }
        final double r = ctx.rnd().nextDouble();
        if (me.sitting()) {
            act.stand();
            detail = "stand";
            nextIdleAction = now + 20_000 + ctx.rnd().nextInt(60_000);
        } else if (r < 0.6) {
            final Optional<Pos> p = w.geo().randomReachablePoint(village.center(), village.radius(), ctx.rnd());
            if (p.isPresent() && (p.get().distance2d(me.pos()) < 3500) && act.moveTo(p.get()).ok()) {
                detail = "stroll";
                nextIdleAction = now + 15_000 + ctx.rnd().nextInt(30_000);
            } else {
                nextIdleAction = now + 5_000;
            }
        } else if (r < 0.85) {
            detail = "stand";
            nextIdleAction = now + 20_000 + ctx.rnd().nextInt(100_000);
        } else {
            if (act.sit().ok()) {
                detail = "sit";
            }
            nextIdleAction = now + 30_000 + ctx.rnd().nextInt(60_000);
        }
        return Math.max(1000, Math.min(nextIdleAction, idleUntil) - now);
    }

    // ------------------------------------------------------------------ REST

    private long rest(BrainContext ctx, WorldView w, SelfView me, long now, NpcView attacker) {
        if (attacker != null) {
            standIfSitting(ctx, me);
            enter(ctx, Phase.LEVEL, "attacked while resting");
            return startFight(ctx, me, attacker, now);
        }
        if ((me.hpPct() >= restTo) && (!me.mage() || (me.mpPct() >= restTo))) {
            standIfSitting(ctx, me);
            enter(ctx, Phase.LEVEL, "rested");
            return ctx.reactionDelay();
        }
        final NpcView aggro = aggroNear(w, me, AGGRO_NEAR);
        if (aggro != null) {
            standIfSitting(ctx, me);
            final long away = awayFromAggro(ctx, w, me, now);
            return away > 0 ? away : 1500;
        }
        if (!me.sitting()) {
            if (me.moving() || me.attacking() || me.casting()) {
                ctx.act().stop();
                return 1000;
            }
            if (stance(ctx, me, false, now)) {
                return 800; // stances drain MP: off before sitting down
            }
            ctx.act().sit();
            detail = "sit";
        }
        return 3000;
    }

    private boolean needsRest(SelfView me) {
        return (me.hpPct() < 45) || (me.mage() && (me.mpPct() < 30));
    }

    private void standIfSitting(BrainContext ctx, SelfView me) {
        if (me.sitting()) {
            ctx.act().stand();
        }
    }

    // ------------------------------------------------------------------ LEVEL

    private long level(BrainContext ctx, WorldView w, SelfView me, long now, NpcView attacker) {
        if (me.sitting() && (fightId == 0) && (attacker == null)) {
            ctx.act().stand();
            return 1500;
        }
        // 1. the current fight
        if (fightId != 0) {
            final long d = fight(ctx, w, me, now);
            if (d > 0) {
                return d;
            }
        }
        // 2. someone attacks me: defend
        if (attacker != null) {
            return startFight(ctx, me, attacker, now);
        }
        // 3. loot of the last kill
        if (now < lootUntil) {
            final long d = loot(ctx, w, me);
            if (d > 0) {
                return d;
            }
        }
        // 4. rest
        if (needsRest(me)) {
            restTo = 80 + (ctx.info().patience() * 15);
            enter(ctx, Phase.REST, String.format(java.util.Locale.ROOT, "hp %.0f%%, mp %.0f%%", me.hpPct(), me.mpPct()));
            return rest(ctx, w, me, now, null);
        }
        // 5. a hunting ground
        if ((zone == null) || !zone.fits(me.level())) {
            zone = zones.pick(me.level(), village.id(), me.pos(), ctx.rnd());
            if ((zone != null) && (zone.center().distance2d(me.pos()) > FAR)) {
                zone = null; // not walking across the world
            }
            if (zone == null) {
                idleUntil = now + 300_000;
                enter(ctx, Phase.TOWN_IDLE, "no hunting ground for level " + me.level());
                return 5000;
            }
            ctx.note("hunting ground " + zone.id() + " (lv " + zone.minLevel() + "-" + zone.maxLevel() + ")");
            stuck = 0;
        }
        final double toZone = me.pos().distance2d(zone.center());
        // 6. a monster nearby (also on the way, once close to the ground)
        if (toZone < (zone.radius() + 1500)) {
            final NpcView mob = chooseMob(ctx, w, me, now);
            if (mob != null) {
                return startFight(ctx, me, mob, now);
            }
        }
        // 7. walk: to the ground (legs), or around in it
        if (toZone > zone.radius()) {
            detail = "to " + zone.id() + " " + (int) toZone;
            return walk(ctx, w, me, zone.center(), now);
        }
        detail = "search " + zone.id();
        if (!me.moving() || checkStuck(me, now)) {
            final Optional<Pos> p = w.geo().randomReachablePoint(me.pos(), Math.min(zone.radius(), 1200), ctx.rnd());
            final Pos target = p.filter(x -> x.distance2d(zone.center()) <= zone.radius()).orElse(me.pos().toward(zone.center(), 800));
            ctx.act().moveTo(target);
        }
        return 2000 + ctx.rnd().nextInt(2000);
    }

    /**
     * Walk towards a far point in legs of about 1500 (stock pathfinding does the fine work). When the straight way is
     * blocked it follows the obstacle on one side (45/90/135 degrees) until the straight way is free; 6 failures ->
     * back to town by teleport (like a scroll of escape) and another hunting ground.
     */
    private long walk(BrainContext ctx, WorldView w, SelfView me, Pos dest, long now) {
        final boolean stuckNow = checkStuck(me, now);
        if (me.moving() && !stuckNow) {
            return 2000;
        }
        if (stuckNow) {
            stuck++;
            lastMovedAt = now;
        }
        if (stuck >= 6) {
            ctx.note("stuck on the way to " + (zone == null ? "?" : zone.id()) + ": back to town");
            stuck = 0;
            zone = null;
            detourSide = 0;
            ctx.act().returnToTown();
            return 3000;
        }
        for (int len : new int[] { LEG, 700 }) {
            final Pos leg = me.pos().toward(dest, len);
            if (w.geo().canMoveTo(me.pos(), leg) && ctx.act().moveTo(leg).ok()) {
                detourSide = 0;
                return 2000;
            }
        }
        if (detourSide == 0) {
            detourSide = ctx.rnd().nextBoolean() ? 1 : -1;
        }
        final double base = Math.atan2(dest.y() - me.pos().y(), dest.x() - me.pos().x());
        for (int deg : new int[] { 45, 90, 135 }) {
            final double a = base + Math.toRadians(deg * detourSide);
            final Pos p = new Pos(me.pos().x() + (int) (Math.cos(a) * 600), me.pos().y() + (int) (Math.sin(a) * 600), me.pos().z());
            if (w.geo().canMoveTo(me.pos(), p) && ctx.act().moveTo(new Pos(p.x(), p.y(), w.geo().height(p))).ok()) {
                return 2000;
            }
        }
        detourSide = -detourSide;
        stuck++;
        // with pathfinding the stock may still find a way where a straight line does not exist
        ctx.act().moveTo(me.pos().toward(dest, LEG));
        return 2000;
    }

    /** Not moved 40 units in 10 s while it should walk. */
    private boolean checkStuck(SelfView me, long now) {
        if ((lastPos == null) || (lastPos.distance2d(me.pos()) > 40)) {
            lastPos = me.pos();
            lastMovedAt = now;
            return false;
        }
        return (now - lastMovedAt) > 10_000;
    }

    private NpcView chooseMob(BrainContext ctx, WorldView w, SelfView me, long now) {
        blacklist.values().removeIf(t -> t < now);
        final int lvl = me.level();
        NpcView best = null;
        double bestScore = Double.MAX_VALUE;
        for (NpcView n : w.npcs()) {
            if (!n.monster() || n.dead() || !n.attackable() || (n.distance() > SEARCH)) {
                continue;
            }
            if ((n.level() < (lvl - mobBelow)) || (n.level() > (lvl + mobAbove))) {
                continue;
            }
            if (n.aggressive() && (lvl <= 5)) {
                continue;
            }
            if (n.engagedByOther() || ((n.targetId() != 0) && (n.targetId() != me.objectId()))) {
                continue; // somebody else's
            }
            if (blacklist.containsKey(n.objectId()) || claims.takenByOther(n.objectId(), botId, now)) {
                continue;
            }
            if (nearRealPlayer(w, n)) {
                continue; // a real player's monster: never steal it
            }
            if (!w.geo().canMoveTo(me.pos(), n.pos())) {
                continue;
            }
            final double score = n.distance() * (0.8 + (ctx.rnd().nextDouble() * 0.4)); // not always the very nearest
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    /** A real (not bot) player stands next to this monster, or the monster fights one. */
    static boolean nearRealPlayer(WorldView w, NpcView n) {
        for (PlayerView p : w.players()) {
            if (!p.bot() && ((p.pos().distance2d(n.pos()) <= PLAYER_MOB_RADIUS) || (p.targetId() == n.objectId()) || (n.targetId() == p.objectId()))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ presence director orders

    private long presence(BrainContext ctx, WorldView w, SelfView me, long now, Assignment a, NpcView attacker) {
        if (a.seq() != orderSeq) {
            orderSeq = a.seq();
            newOrder(ctx, me, now, a);
        }
        final long pv = pvp(ctx, w, me, now);
        if (pv > 0) {
            return pv;
        }
        return switch (a.kind()) {
            case LEAVE_RUN, LEAVE_GK, PASS -> leaving(ctx, w, me, now, a);
            case TRANSIT -> transit(ctx, me, now);
            case TOWN -> town(ctx, w, me, now, a, attacker);
            case HUNT -> hunt(ctx, w, me, now, a, attacker);
        };
    }

    private void newOrder(BrainContext ctx, SelfView me, long now, Assignment a) {
        ctx.note("order " + a);
        errand = null;
        arrivedAt = 0;
        stuck = 0;
        detourSide = 0;
        nextIdleAction = now + ctx.reactionDelay();
        if (a.leaving()) {
            endFight();
            ctx.act().stop();
            standIfSitting(ctx, me);
            enter(ctx, Phase.LEVEL, a.kind() == Assignment.Kind.LEAVE_GK ? "to the gatekeeper" : "running off");
            leaveAt = 0;
        } else if (a.kind() == Assignment.Kind.TRANSIT) {
            zone = null;
            endFight();
            standIfSitting(ctx, me);
            transitUntil = now + 3000 + ctx.rnd().nextInt(7000);
            enter(ctx, Phase.TOWN_IDLE, "transit at the gatekeeper of " + a.place());
        } else if (a.kind() == Assignment.Kind.HUNT) {
            mobBelow = 8;
            mobAbove = 1;
            zone = new LevelZone(a.place(), "", a.center(), a.radius(), a.minLevel(), a.maxLevel(), a.spots().size(), false, java.util.Set.of());
            enter(ctx, Phase.LEVEL, "hunt " + a.place());
        } else {
            zone = null;
            townStart = true;
            transitUntil = 0;
            badAnchors.clear();
            enter(ctx, Phase.TOWN_IDLE, "town " + a.place());
        }
    }

    /** Appeared at the gatekeeper "after a teleport": stands 3-10 s, then teleports on (logs out). */
    private long transit(BrainContext ctx, SelfView me, long now) {
        if (me.sitting()) {
            ctx.act().stand();
        }
        detail = "transit at the gatekeeper";
        if (now >= transitUntil) {
            ctx.note("teleported on (logout at the gatekeeper)");
            ctx.act().logout();
            return 5000;
        }
        return Math.max(500, Math.min(2000, transitUntil - now));
    }

    /** Run to the exit; at the gatekeeper "teleport" (log out) after a moment, else the director logs it out unseen. */
    private long leaving(BrainContext ctx, WorldView w, SelfView me, long now, Assignment a) {
        standIfSitting(ctx, me);
        final Pos exit = a.exit();
        if ((exit == null) || (me.pos().distance2d(exit) <= 150)) {
            if (arrivedAt == 0) {
                arrivedAt = now;
                final boolean gk = a.kind() == Assignment.Kind.LEAVE_GK;
                leaveAt = now + (gk ? 2000 + ctx.rnd().nextInt(5000) : 120_000);
                detail = gk ? "at the gatekeeper" : "out of sight";
            }
            if (now >= leaveAt) {
                ctx.note(a.kind() == Assignment.Kind.LEAVE_GK ? "teleported away (logout at the gatekeeper)" : "logout");
                ctx.act().logout();
                return 5000;
            }
            return 1000;
        }
        detail = (a.kind() == Assignment.Kind.LEAVE_GK ? "to gk " : a.kind() == Assignment.Kind.PASS ? "passing by " : "away ") + (int) me.pos().distance2d(exit);
        return walk(ctx, w, me, exit, now);
    }

    /** Transit at the gatekeeper is the director's now (order TRANSIT); town bots stay for their errands. */
    static final double TRANSIT_SHARE = 0;
    /** Chance that a bot that ran to the gatekeeper teleports away (logs out) there. */
    static final double GK_TELEPORT = 0.4;
    private static final String[] KINDS = { "gk", "shop", "wh", "trainer" };
    private static final double[] KIND_WEIGHTS = { 0.35, 0.30, 0.15, 0.20 };
    private boolean townStart;
    private long transitUntil;
    private Pos errandAnchor;
    private double errandBest;
    private long errandProgressAt;
    private final java.util.Set<Pos> badAnchors = new java.util.HashSet<>();

    /** Errands in town: purposeful runs between gatekeeper, shops, warehouse and trainers; groups; never sits. */
    private long town(BrainContext ctx, WorldView w, SelfView me, long now, Assignment a, NpcView attacker) {
        if (fightId != 0) {
            final long d = fight(ctx, w, me, now);
            if (d > 0) {
                return d;
            }
        }
        if ((attacker != null) && !me.inPeaceZone()) {
            standIfSitting(ctx, me);
            return startFight(ctx, me, attacker, now);
        }
        if (me.sitting()) { // nobody sits in town
            ctx.act().stand();
            detail = "stand";
            return 1500;
        }
        if (townStart) {
            townStart = false;
            final Assignment.Anchor gk = nearest(a, "gk", me.pos());
            if ((gk != null) && (gk.pos().distance2d(me.pos()) < 400) && (ctx.rnd().nextDouble() < TRANSIT_SHARE)) {
                transitUntil = now + 3000 + ctx.rnd().nextInt(7000);
            }
        }
        if (transitUntil > 0) {
            detail = "at the gatekeeper (teleports on)";
            if (now >= transitUntil) {
                transitUntil = 0;
                ctx.note("teleported on (logout at the gatekeeper)");
                ctx.act().logout();
                return 5000;
            }
            return 1000;
        }
        if (errand != null) {
            final double d = me.pos().distance2d(errand);
            if (d > 120) {
                if (d < (errandBest - 80)) {
                    errandBest = d;
                    errandProgressAt = now;
                }
                if ((now - errandProgressAt) > 8000) { // runs along a wall / cannot get there: another errand
                    ctx.note("cannot reach " + errandWhat + ": another errand");
                    if (errandAnchor != null) {
                        badAnchors.add(errandAnchor);
                    }
                    errand = null;
                    errandAnchor = null;
                    ctx.act().stop();
                    nextIdleAction = now + 500;
                    return 1000;
                }
                detail = "to " + errandWhat;
                return walk(ctx, w, me, errand, now);
            }
            errand = null;
            errandAnchor = null;
            detail = "at " + errandWhat;
            if ("gk".equals(errandWhat) && (ctx.rnd().nextDouble() < GK_TELEPORT)) {
                transitUntil = now + 2000 + ctx.rnd().nextInt(5000); // a moment at the gatekeeper, then teleports away
                return 1000;
            }
            nextIdleAction = now + 5_000 + ctx.rnd().nextInt(25_000);
        }
        if (now < nextIdleAction) {
            return Math.max(500, Math.min(5000, nextIdleAction - now));
        }
        final double r = ctx.rnd().nextDouble();
        if (r < 0.8) {
            final Assignment.Anchor p = pickAnchor(ctx, a, me.pos());
            if (p != null) {
                return startErrand(ctx, w, me, now, p.pos(), around(ctx, w, p.pos(), 60, 160), p.kind());
            }
        }
        if (r < 0.92) {
            final List<PlayerView> others = new java.util.ArrayList<>();
            for (PlayerView p : w.players()) {
                if (!p.inCombat() && (p.distance() < 1500) && (p.distance() > 100)) {
                    others.add(p);
                }
            }
            if (!others.isEmpty()) {
                final PlayerView p = others.get(ctx.rnd().nextInt(others.size()));
                return startErrand(ctx, w, me, now, null, around(ctx, w, p.pos(), 70, 140), "group");
            }
        }
        detail = "look around"; // stands a moment (no sitting in towns)
        nextIdleAction = now + 4_000 + ctx.rnd().nextInt(12_000);
        return 3000;
    }

    private long startErrand(BrainContext ctx, WorldView w, SelfView me, long now, Pos anchor, Pos to, String what) {
        errandAnchor = anchor;
        errand = to;
        errandWhat = what;
        errandBest = me.pos().distance2d(to);
        errandProgressAt = now;
        detail = "to " + what;
        return walk(ctx, w, me, to, now);
    }

    /** Another kind of NPC than the last one, weighted (gatekeeper most often), not one it could not reach. */
    private Assignment.Anchor pickAnchor(BrainContext ctx, Assignment a, Pos me) {
        for (int attempt = 0; attempt < 6; attempt++) {
            double x = ctx.rnd().nextDouble();
            String kind = KINDS[KINDS.length - 1];
            for (int i = 0; i < KINDS.length; i++) {
                x -= KIND_WEIGHTS[i];
                if (x < 0) {
                    kind = KINDS[i];
                    break;
                }
            }
            if (kind.equals(errandWhat) && (attempt < 4)) {
                continue;
            }
            final List<Assignment.Anchor> l = new java.util.ArrayList<>();
            for (Assignment.Anchor p : a.anchors()) {
                final double d = p.pos().distance2d(me);
                if (p.kind().equals(kind) && !badAnchors.contains(p.pos()) && (d < 4000) && (d > 200)) {
                    l.add(p);
                }
            }
            if (!l.isEmpty()) {
                return l.get(ctx.rnd().nextInt(l.size()));
            }
        }
        return null;
    }

    private static Assignment.Anchor nearest(Assignment a, String kind, Pos me) {
        Assignment.Anchor best = null;
        for (Assignment.Anchor p : a.anchors()) {
            if (p.kind().equals(kind) && ((best == null) || (p.pos().distance2d(me) < best.pos().distance2d(me)))) {
                best = p;
            }
        }
        return best;
    }
    /** A reachable point min..max from {@code c} (not on top of the NPC or player). */
    private static Pos around(BrainContext ctx, WorldView w, Pos c, int min, int max) {
        for (int i = 0; i < 6; i++) {
            final double ang = ctx.rnd().nextDouble() * Math.PI * 2;
            final int r = min + ctx.rnd().nextInt((max - min) + 1);
            final Pos p = new Pos(c.x() + (int) (Math.cos(ang) * r), c.y() + (int) (Math.sin(ang) * r), c.z());
            if (w.geo().canMoveTo(c, p)) {
                return new Pos(p.x(), p.y(), w.geo().height(p));
            }
        }
        return c;
    }

    /** Hunt on the ordered ground: fight, loot, rest, move between its spawns (never next to a real player). */
    private long hunt(BrainContext ctx, WorldView w, SelfView me, long now, Assignment a, NpcView attacker) {
        if (phase == Phase.REST) {
            return rest(ctx, w, me, now, attacker);
        }
        if (phase != Phase.LEVEL) {
            enter(ctx, Phase.LEVEL, "hunt " + a.place());
        }
        if (me.sitting() && (fightId == 0) && (attacker == null)) {
            ctx.act().stand();
            return 1500;
        }
        if (fightId != 0) {
            final long d = fight(ctx, w, me, now);
            if (d > 0) {
                return d;
            }
        }
        if (attacker != null) {
            return startFight(ctx, me, attacker, now);
        }
        if (now < lootUntil) {
            final long d = loot(ctx, w, me);
            if (d > 0) {
                return d;
            }
        }
        if (needsRest(me)) {
            final long away = awayFromAggro(ctx, w, me, now);
            if (away > 0) {
                return away;
            }
            restTo = 80 + (ctx.info().patience() * 15);
            enter(ctx, Phase.REST, String.format(java.util.Locale.ROOT, "hp %.0f%%, mp %.0f%%", me.hpPct(), me.mpPct()));
            return rest(ctx, w, me, now, null);
        }
        final double toZone = me.pos().distance2d(a.center());
        if (toZone < (a.radius() + 1500)) {
            final long buffed = selfBuff(ctx, me, now);
            if (buffed > 0) {
                return buffed;
            }
            final NpcView mob = chooseMob(ctx, w, me, now);
            if (mob != null) {
                errand = null;
                return startFight(ctx, me, mob, now);
            }
        }
        if (toZone > a.radius() && (errand == null)) {
            detail = "to " + a.place() + " " + (int) toZone;
            return walk(ctx, w, me, a.center(), now);
        }
        // between spawns: another spot of the ground nobody (real) stands at, or around here
        if ((errand != null) && (me.pos().distance2d(errand) > 200)) {
            if (stuck >= 3) {
                errand = null; // that spawn is hard to reach: search here
                stuck = 0;
            } else {
                return walk(ctx, w, me, errand, now);
            }
        }
        errand = null;
        detail = "search " + a.place();
        if (!me.moving() || checkStuck(me, now)) {
            if (!a.spots().isEmpty() && (ctx.rnd().nextDouble() < 0.35)) {
                final Pos spot = a.spots().get(ctx.rnd().nextInt(a.spots().size()));
                if (!realPlayerNear(w, spot, 1200) && (spot.distance2d(me.pos()) > 300)) {
                    errand = spot;
                    detail = "to another spawn";
                    return walk(ctx, w, me, spot, now);
                }
            }
            final Optional<Pos> p = w.geo().randomReachablePoint(me.pos(), Math.min(a.radius(), 1200), ctx.rnd());
            final Pos target = p.filter(x -> x.distance2d(a.center()) <= a.radius()).orElse(me.pos().toward(a.center(), 800));
            ctx.act().moveTo(target);
        }
        return 2000 + ctx.rnd().nextInt(2000);
    }

    private static boolean realPlayerNear(WorldView w, Pos p, int radius) {
        for (PlayerView o : w.players()) {
            if (!o.bot() && (o.pos().distance2d(p) <= radius)) {
                return true;
            }
        }
        return false;
    }

    private long startFight(BrainContext ctx, SelfView me, NpcView mob, long now) {
        if (!claims.claim(mob.objectId(), botId, now, FIGHT_TIMEOUT * 2) && (mob.targetId() != me.objectId())) {
            blacklist.put(mob.objectId(), now + 30_000);
            return ctx.reactionDelay();
        }
        fightId = mob.objectId();
        fightName = mob.name();
        fightSince = now;
        fightHp = mob.hpPct();
        fightHpAt = now;
        kiteOff = false;
        kiteFails = 0;
        kiteUntil = 0;
        kiteNext = 0;
        kiteBlocked = false;
        ctx.note("fight " + mob.name() + " lv " + mob.level() + (mob.targetId() == me.objectId() ? " (it attacked)" : ""));
        detail = "fight " + fightName;
        hit(ctx, ctx.world(), me, mob);
        return 400;
    }

    /** @return the delay, or 0 when the fight is over */
    private long fight(BrainContext ctx, WorldView w, SelfView me, long now) {
        final NpcView mob = w.npc(fightId);
        if ((mob == null) || mob.dead()) {
            claims.release(fightId, botId);
            fightId = 0;
            lootUntil = now + 8000;
            lootTaken = 0;
            detail = "loot";
            return 0;
        }
        if (mob.engagedByOther() && (mob.targetId() != me.objectId())) {
            ctx.note("left " + fightName + ": someone else's");
            abandon(now, 60_000);
            ctx.act().stop();
            return 0;
        }
        if (mob.hpPct() < fightHp - 0.5) {
            fightHp = mob.hpPct();
            fightHpAt = now;
        } else if (((now - fightHpAt) > 25_000) || ((now - fightSince) > (FIGHT_TIMEOUT * 3))) {
            ctx.note("gave up on " + fightName + ": no progress");
            abandon(now, BLACKLIST_MS);
            ctx.act().stop();
            return 0;
        }
        if (mob.distance() > 2400) {
            abandon(now, 60_000);
            return 0;
        }
        if (shouldFlee(w, me, mob, now)) {
            ctx.note(String.format(java.util.Locale.ROOT, "hurt badly by %s (hp %.0f%%, no potion at hand): runs", fightName, me.hpPct()));
            abandon(now, BLACKLIST_MS);
            return startMobFlee(ctx, me, mob, now);
        }
        detail = "fight " + fightName;
        hit(ctx, w, me, mob);
        return 400 + ctx.rnd().nextInt(300);
    }

    private void abandon(long now, long blacklistMs) {
        blacklist.put(fightId, now + blacklistMs);
        claims.release(fightId, botId);
        fightId = 0;
    }

    private void endFight() {
        if (fightId != 0) {
            claims.release(fightId, botId);
        }
        fightId = 0;
        lootUntil = 0;
    }

    /** One combat decision: mystics cast their nuke while MP lasts (>= 20 %), everyone else hits. */
    private void hit(BrainContext ctx, WorldView w, SelfView me, NpcView mob) {
        if (kite(ctx, w, me, mob, ctx.now())) {
            return;
        }
        if (stance(ctx, me, me.mpPct() >= 15, ctx.now())) {
            return; // the stance is switched (it targets the bot itself): the next tick attacks again
        }
        if (me.mage() && (me.mpPct() >= 20)) {
            final SkillView nuke = nuke(me, mob.npcId(), badSkills, ctx.now());
            if (nuke != null) {
                if (me.casting()) {
                    return;
                }
                if (nuke.ready() && (nuke.mpCost() <= me.mp())) {
                    final ActionResult r = ctx.act().cast(nuke.id(), mob.objectId());
                    if (r.ok() || (r == ActionResult.COOLDOWN)) {
                        return;
                    }
                    if (r == ActionResult.INVALID) {
                        badSkills.put(badKey(nuke.id(), mob.npcId()), ctx.now() + BAD_SKILL_MS); // next decision tries the next nuke
                        return;
                    }
                } else {
                    return; // wait for the reuse instead of running into melee
                }
            }
        }
        if (!"ATTACK".equals(me.intention()) || (me.targetId() != mob.objectId())) { // the stock AI keeps hitting by itself
            final ActionResult r = ctx.act().attack(mob.objectId());
            if (r == ActionResult.NOT_REACHABLE) {
                blacklist.put(mob.objectId(), ctx.now() + BLACKLIST_MS);
                claims.release(mob.objectId(), botId);
                fightId = 0;
            } else if (r == ActionResult.INVALID && !"ATTACK".equals(me.intention())) {
                blacklist.put(mob.objectId(), ctx.now() + 30_000);
                claims.release(mob.objectId(), botId);
                fightId = 0;
            }
        }
    }

    /** The first magic damage skill (Wind Strike for the human/elf/dark elf mystics). */
    static SkillView nuke(SelfView me) {
        return nuke(me, 0, Map.of(), 0);
    }

    static long badKey(int skillId, int npcId) {
        return ((long) skillId << 32) | (npcId & 0xFFFFFFFFL);
    }

    /** The first magic damage skill that the game has not refused lately on this monster type. */
    static SkillView nuke(SelfView me, int npcId, Map<Long, Long> bad, long now) {
        for (SkillView s : me.skills()) {
            if ((s.kind() == SkillView.Kind.NUKE) && (bad.getOrDefault(badKey(s.id(), npcId), 0L) <= now)) {
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ buffs (4b)

    /** Config SelfBuffs of bots-brain-basic. */
    static volatile boolean selfBuffs = true;
    /** Stances worth having on in a fight (Vicious Stance, Accuracy). Other toggles (Fake Death, Silent Move...) never. */
    static final java.util.Set<Integer> STANCES = java.util.Set.of(312, 256);
    /** A self buff with less than this many seconds left is cast again. */
    static final int BUFF_RENEW = 30;
    /** Skill id -> not before (ms): a buff just cast, refused or switched. */
    private final Map<Integer, Long> buffTried = new HashMap<>();
    private int buffs;

    /** How many self buffs this bot cast (tests, //bots info). */
    public int buffsCast() {
        return buffs;
    }

    /**
     * Out of a fight on the hunting ground: put on the own buffs that are missing or about to run out (an effect of the
     * same kind from somebody else counts), one per decision, MP permitting (mystics keep half for nukes). A skill the
     * game refuses is left alone for 10 minutes.
     * @return the delay, 0 = nothing to do
     */
    private long selfBuff(BrainContext ctx, SelfView me, long now) {
        if (!selfBuffs || me.casting() || me.sitting() || me.inPeaceZone() || (me.mpPct() < (me.mage() ? 50 : 30))) {
            return 0;
        }
        for (SkillView s : me.skills()) {
            if ((s.kind() != SkillView.Kind.BUFF) || (s.buffLeft() > BUFF_RENEW) || !s.ready() || (s.mpCost() > me.mp()) || (buffTried.getOrDefault(s.id(), 0L) > now)) {
                continue;
            }
            final ActionResult r = ctx.act().cast(s.id(), me.objectId());
            buffTried.put(s.id(), now + (r.ok() ? 20_000 : (r == ActionResult.COOLDOWN ? 5_000 : 600_000)));
            if (r.ok()) {
                buffs++;
                ctx.note("buff " + s.id());
                detail = "buff";
                return 1200 + ctx.rnd().nextInt(600);
            }
        }
        return 0;
    }

    /**
     * Switch the bot's stances on (in a fight, MP permitting) or off (before resting).
     * @return true when one was switched now
     */
    private boolean stance(BrainContext ctx, SelfView me, boolean on, long now) {
        if (!selfBuffs || me.casting()) {
            return false;
        }
        for (SkillView s : me.skills()) {
            if ((s.kind() != SkillView.Kind.TOGGLE) || !STANCES.contains(s.id()) || ((s.buffLeft() == -1) == on) || (buffTried.getOrDefault(s.id(), 0L) > now)) {
                continue;
            }
            if (on && ((me.mpPct() < 25) || (s.mpCost() > me.mp()))) {
                continue;
            }
            final ActionResult r = ctx.act().cast(s.id(), me.objectId());
            buffTried.put(s.id(), now + (r.ok() ? 3000 : 60_000));
            if (r.ok()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ survival (4a): potions, leaving a lost fight, kiting

    /** Config of bots-brain-basic (Potions, PotionHpPercent, FleeHpPercent, KiteDistance). */
    static volatile boolean potions = true;
    static volatile double potionLo = 40;
    static volatile double potionHi = 60;
    static volatile double fleeHp = 25;
    /** A melee monster closer than its attack range + this is hitting (or about to): step back. 0 = no kiting. */
    static volatile int kiteClose = 120;
    /** ... to this far from it (at most the cast range / bow range minus 50). */
    static volatile int kiteTo = 400;
    /** Aggressive monsters this close: no sitting down. */
    static final int AGGRO_NEAR = 450;
    /** One step back is at most this long (a short step keeps the bot on the ground it knows). */
    static final int KITE_STEP = 400;
    /** A spot (and the way to it) must stay this much outside the aggro range of every free aggressive monster. */
    static final int AGGRO_MARGIN = 80;
    /** Directions tried for stepping back, degrees from straight away from the monster (LW PhantomCasterPositioning fan). */
    static final int[] KITE_FAN = { 0, 30, -30, 60, -60, 90, -90 };
    /** Directions tried for running away (wider: anything but back past the monster). */
    static final int[] FLEE_FAN = { 0, 30, -30, 60, -60, 90, -90, 135, -135 };
    /** HP potions, best first (Greater Healing, Healing, Lesser Healing). */
    static final int[] HP_POTIONS = { 1539, 1061, 1060 };
    static final long POTION_REUSE = 10_000;

    private long nextPotion;
    private long kiteUntil;
    private long kiteNext;
    private int kiteFails;
    private boolean kiteOff;
    private boolean kiteBlocked;
    private int kites;
    private Pos mobFleeTo;
    private int mobFleeFrom;
    private long mobFleeSince;
    private long mobFleeMs;

    /** How many times this bot stepped back from a monster (tests, //bots info). */
    public int kites() {
        return kites;
    }

    /** Below this HP share the bot drinks (its own, between PotionHpPercent min and max by persona). */
    static double potionAt(long seed, double lo, double hi) {
        final double f = ((seed * 0x9E3779B97F4A7C15L) >>> 11) / (double) (1L << 53);
        return lo + ((hi - lo) * f);
    }

    /** In a fight (a monster or a player on it) and HP below its threshold: an HP potion, its reuse respected. */
    private void drink(BrainContext ctx, WorldView w, SelfView me, long now) {
        if (!potions || me.sitting() || (now < nextPotion) || (me.hpPct() >= potionAt(ctx.info().seed(), potionLo, potionHi))) {
            return;
        }
        if ((fightId == 0) && (pvpId == 0) && (attacker(w, me) == null)) {
            return;
        }
        for (int id : HP_POTIONS) {
            if (w.itemCount(id) > 0) {
                final ActionResult r = ctx.act().useItem(id);
                nextPotion = now + (r.ok() ? POTION_REUSE : 2000);
                if (r.ok()) {
                    ctx.note(String.format(java.util.Locale.ROOT, "potion %d (hp %.0f%%)", id, me.hpPct()));
                }
                return;
            }
        }
        nextPotion = now + 5000; // none left
    }

    private boolean potionSoon(WorldView w, long now) {
        if (!potions || (nextPotion - now) > 2000) {
            return false;
        }
        for (int id : HP_POTIONS) {
            if (w.itemCount(id) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Low HP, no potion at hand, and the fight is not almost won: leave it. */
    private boolean shouldFlee(WorldView w, SelfView me, NpcView mob, long now) {
        if ((me.hpPct() >= fleeHp) || potionSoon(w, now)) {
            return false;
        }
        return (onMe(w, me) >= 2) || (mob.hpPct() > 20) || (me.hpPct() < (fleeHp * 0.5));
    }

    private long startMobFlee(BrainContext ctx, SelfView me, NpcView mob, long now) {
        final WorldView w = ctx.world();
        final int from = (int) me.pos().distance2d(mob.pos());
        final Pos away = safePoint(me.pos(), mob.pos(), from + 1200, 1200, w.geo(), threats(w, mob.objectId()), FLEE_FAN, false);
        if (away == null) { // every way out leads into other aggressive monsters
            ctx.note("no safe way out from " + mob.name() + ": scroll of escape");
            endFight();
            standIfSitting(ctx, me);
            ctx.act().returnToTown();
            return 3000;
        }
        mobFleeTo = new Pos(away.x(), away.y(), me.pos().z());
        mobFleeFrom = mob.objectId();
        mobFleeSince = now;
        mobFleeMs = 3000 + ctx.rnd().nextInt(3000);
        standIfSitting(ctx, me);
        ctx.act().moveTo(mobFleeTo);
        detail = "runs from " + mob.name();
        return 600;
    }

    /** Running from a monster: a few seconds away; still chased -> scroll of escape, else rest (away from aggressive ones). */
    private long mobFlee(BrainContext ctx, WorldView w, SelfView me, long now) {
        if (mobFleeTo == null) {
            return 0;
        }
        if (me.dead()) {
            mobFleeTo = null;
            return 0;
        }
        final NpcView chaser = attacker(w, me);
        if (((now - mobFleeSince) < mobFleeMs) && (mobFleeTo.distance2d(me.pos()) > 150)) {
            if (!me.moving()) {
                ctx.act().moveTo(mobFleeTo);
            }
            return 600;
        }
        mobFleeTo = null;
        if ((chaser != null) && (chaser.distance() < 600)) {
            ctx.note("still chased by " + chaser.name() + ": scroll of escape");
            endFight();
            ctx.act().returnToTown();
            return 3000;
        }
        ctx.note("got away");
        return 0;
    }

    /** A living aggressive monster near that is not fighting anybody yet. */
    private static NpcView aggroNear(WorldView w, SelfView me, int radius) {
        for (NpcView n : w.npcs()) {
            if (!n.dead() && n.monster() && n.aggressive() && n.attackable() && (n.distance() <= radius) && (n.targetId() == 0)) {
                return n;
            }
        }
        return null;
    }

    /** Wants to rest but aggressive monsters are near: walk off a bit first (once in a while), never sit among them. */
    private long awayFromAggro(BrainContext ctx, WorldView w, SelfView me, long now) {
        final NpcView aggro = aggroNear(w, me, AGGRO_NEAR);
        if (aggro == null) {
            return 0;
        }
        detail = "rest away from " + aggro.name();
        if (!me.moving()) {
            final Pos p = safePoint(me.pos(), aggro.pos(), AGGRO_NEAR + 300, AGGRO_NEAR + 300, w.geo(), threats(w, aggro.objectId()), FLEE_FAN, false);
            if ((p == null) || !ctx.act().moveTo(p).ok()) {
                return 0; // nowhere to go: rest here after all
            }
        }
        return 1500;
    }

    /**
     * Archers and mages against a melee monster: when it comes into its reach (it swings or is about to), step back to the
     * working distance, then shoot or cast again while it walks up (LW CasterPositioning, VP mage: no melee). A monster
     * that keeps up (faster than the bot) twice in a row: no more kiting in this fight.
     * @return true while stepping back (no attack this tick)
     */
    private boolean kite(BrainContext ctx, WorldView w, SelfView me, NpcView mob, long now) {
        if ((kiteClose <= 0) || kiteOff || !mob.melee()) {
            return false;
        }
        final int range;
        if (me.bow()) {
            range = me.attackRange();
        } else if (me.mage() && (me.mpPct() >= 20)) {
            final SkillView nuke = nuke(me, mob.npcId(), badSkills, now);
            if (nuke == null) {
                return false;
            }
            range = nuke.castRange() > 0 ? nuke.castRange() : 600;
        } else {
            return false;
        }
        final double close = mob.attackRange() + kiteClose;
        if (onMe(w, me) >= 2) {
            return false; // several on it: stepping back only drags them along; fight here (or leave the fight)
        }
        if (kiteUntil > 0) {
            if (me.moving() && (now < kiteUntil)) {
                return true;
            }
            kiteUntil = 0;
            if (mob.distance() <= close) {
                if (++kiteFails >= 2) {
                    kiteOff = true;
                    ctx.note("kiting " + fightName + " does not work (it keeps up)");
                }
            } else {
                kiteFails = 0;
            }
            return false; // now shoot / cast
        }
        if ((mob.distance() > close) || (now < kiteNext) || me.casting() || me.attacking()) {
            return false;
        }
        final int to = Math.max(150, Math.min(kiteTo, range - 50));
        final Pos p = safePoint(me.pos(), mob.pos(), to, KITE_STEP, w.geo(), threats(w, mob.objectId()), KITE_FAN, true);
        if (p == null) {
            kiteNext = now + 2000; // no safe spot behind (wall, other aggressive monsters): fight in place, look again later
            if (!kiteBlocked) {
                kiteBlocked = true;
                ctx.note("no safe spot to step back from " + fightName + ": fights in place");
            }
            return false;
        }
        if (!ctx.act().moveTo(p).ok()) {
            kiteNext = now + 3000;
            if (++kiteFails >= 2) {
                kiteOff = true;
            }
            return false;
        }
        kites++;
        kiteUntil = now + 4000;
        kiteNext = now + 2000 + ctx.reactionDelay();
        detail = "kite " + fightName;
        return true;
    }

    /** {@link #safePoint} with no other monsters around and no step limit (tests). */
    static Pos kitePoint(Pos me, Pos mob, int dist, org.l2start.bots.api.Geo geo) {
        return safePoint(me, mob, dist, Integer.MAX_VALUE, geo, List.of(), KITE_FAN, true);
    }

    /** Monsters of this bot's fight on it (they target it). */
    static int onMe(WorldView w, SelfView me) {
        int n = 0;
        for (NpcView v : w.npcs()) {
            if (!v.dead() && v.monster() && (v.targetId() == me.objectId())) {
                n++;
            }
        }
        return n;
    }

    /** Free aggressive monsters around (not the one fought): walking into their aggro range pulls them. */
    static List<NpcView> threats(WorldView w, int exceptId) {
        final List<NpcView> l = new ArrayList<>();
        for (NpcView v : w.npcs()) {
            if ((v.objectId() != exceptId) && v.freeAggressive() && (v.aggroRange() > 0)) {
                l.add(v);
            }
        }
        return l;
    }

    /**
     * How far the walk from {@code a} to {@code b} stays outside the aggro range (+ margin) of the nearest threat; negative =
     * it passes (or ends) inside one. No threats: {@link Double#MAX_VALUE}.
     */
    static double clearance(Pos a, Pos b, List<NpcView> threats) {
        double best = Double.MAX_VALUE;
        for (NpcView t : threats) {
            best = Math.min(best, segmentDistance(t.pos(), a, b) - (t.aggroRange() + AGGRO_MARGIN));
        }
        return best;
    }

    /** 2D distance from {@code p} to the segment a-b. */
    static double segmentDistance(Pos p, Pos a, Pos b) {
        final double dx = b.x() - a.x();
        final double dy = b.y() - a.y();
        final double len2 = (dx * dx) + (dy * dy);
        double t = len2 <= 0 ? 0 : (((p.x() - a.x()) * dx) + ((p.y() - a.y()) * dy)) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(p.x() - (a.x() + (t * dx)), p.y() - (a.y() + (t * dy)));
    }

    /**
     * A spot to step back or run to, away from {@code from} (the monster or player): rays of the fan (degrees from straight
     * away), on each the farthest point up to {@code dist} from {@code from} and at most {@code step} from the bot that is
     * walkable (geodata), in sight of {@code from} when asked, and whose way there stays outside the aggro range of every
     * threat. Best score wins: room from the threats, then straight away, then length (LW PhantomCasterPositioning fan).
     * @return null when there is no such spot (then: no kiting, fight in place)
     */
    static Pos safePoint(Pos me, Pos from, int dist, int step, org.l2start.bots.api.Geo geo, List<NpcView> threats, int[] fan, boolean sight) {
        final double now = Math.max(1, me.distance2d(from));
        final double angle = Math.atan2(me.y() - from.y(), me.x() - from.x());
        final double maxR = Math.min(dist, now + Math.max(80, step));
        final double minR = Math.max(now + 80, 150);
        Pos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int offset : fan) {
            final double a = angle + Math.toRadians(offset);
            for (double r = maxR; r >= minR; r -= 80) {
                final Pos c = new Pos(from.x() + (int) (Math.cos(a) * r), from.y() + (int) (Math.sin(a) * r), me.z());
                if ((c.distance2d(me) <= 40) || !geo.canMoveTo(me, c) || (sight && !geo.canSee(c, from))) {
                    continue;
                }
                final double room = clearance(me, c, threats);
                if (room < 0) {
                    continue;
                }
                final double score = Math.min(room, 400) - (2 * Math.abs(offset)) + (0.2 * r);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
                break; // the farthest good point of this ray
            }
        }
        return best;
    }

    private long loot(BrainContext ctx, WorldView w, SelfView me) {
        if (lootTaken >= 3) {
            lootUntil = 0;
            return 0;
        }
        if ("PICK_UP".equals(me.intention())) {
            return 600;
        }
        for (DropView d : w.drops()) {
            if (d.mine() && (d.distance() <= LOOT_RANGE)) {
                if (ctx.act().pickUp(d.objectId()).ok()) {
                    lootTaken++;
                    detail = "loot";
                    return 700;
                }
            }
        }
        lootUntil = 0;
        return 0;
    }
}
