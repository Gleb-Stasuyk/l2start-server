package org.l2start.bots.brain.basic;

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
            }
            return 2000; // bots-core brings it back to town after 5-40 s
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
        if (!me.sitting()) {
            if (me.moving() || me.attacking() || me.casting()) {
                ctx.act().stop();
                return 1000;
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
        return switch (a.kind()) {
            case LEAVE_RUN, LEAVE_GK -> leaving(ctx, w, me, now, a);
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
        detail = (a.kind() == Assignment.Kind.LEAVE_GK ? "to gk " : "away ") + (int) me.pos().distance2d(exit);
        return walk(ctx, w, me, exit, now);
    }

    /** Share of town bots that appear at the gatekeeper only "in transit": stand 3-10 s and teleport on. */
    static final double TRANSIT_SHARE = 0.25;
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
            restTo = 80 + (ctx.info().patience() * 15);
            enter(ctx, Phase.REST, String.format(java.util.Locale.ROOT, "hp %.0f%%, mp %.0f%%", me.hpPct(), me.mpPct()));
            return rest(ctx, w, me, now, null);
        }
        final double toZone = me.pos().distance2d(a.center());
        if (toZone < (a.radius() + 1500)) {
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
        ctx.note("fight " + mob.name() + " lv " + mob.level() + (mob.targetId() == me.objectId() ? " (it attacked)" : ""));
        detail = "fight " + fightName;
        hit(ctx, me, mob);
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
        detail = "fight " + fightName;
        hit(ctx, me, mob);
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
    private void hit(BrainContext ctx, SelfView me, NpcView mob) {
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
