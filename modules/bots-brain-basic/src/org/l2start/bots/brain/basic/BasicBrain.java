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
import org.l2start.bots.api.PartyMember;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SelfView;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.api.WorldView;
import org.l2start.bots.chat.ClassLabels;
import org.l2start.bots.chat.LfpParse;
import org.l2start.bots.chat.SocialBoard;
import org.l2start.bots.chat.WhisperParse;

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
        ctxNow = now;
        socialEvents(ctx, w, me, now);
        flushPendingWhisper(ctx, w, me, now);
        answerInvite(ctx, me, now);
        flushSpeech(ctx, now);
        flushInvite(ctx, now);
        maybeShoutLfp(ctx, w, me, now);
        evaluatePvp(ctx, w, me, now); // phase 7b: decide and enact (fight / flee / call-help)
        answerDuel(ctx, me, now);
        answerPledge(ctx, me, now);
        maybeClanRally(ctx, w, me, now);
        final long d = tickMain(ctx, w, me, now);
        final long due = socialDue();
        return due > 0 ? Math.max(100, Math.min(d, due - now)) : d;
    }

    private long tickMain(BrainContext ctx, WorldView w, SelfView me, long now) {
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
                    if (me.hasPet()) {
                        ctx.act().unsummon(); // nobody to command it: it never stays behind alone
                    }
                    notePvpKiller(ctx, w, d.killerId()); // phase 7a grudge
                }
                case BotEvent.Teleported t -> revived = true;
                case BotEvent.Attacked at -> {
                    if (at.byPlayer()) {
                        playerHits.put(at.attackerId(), now);
                        notePvpAttacker(ctx, w, at.attackerId()); // phase 7a ATTACKED_ME
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
        if (me.teleporting()) {
            return 500;
        }
        // phase 7b: PvP before companion / director so protect of a live party mate still runs
        final long pv = pvp(ctx, w, me, now);
        if (pv > 0) {
            return pv;
        }
        if ((ptMateId != 0) || (mate(w) != null)) {
            // phase 6c: in a real player's party it plays with him (the director leaves it be meanwhile)
            final long c = companion(ctx, w, me, now, attacker(w, me));
            if (c > 0) {
                return c;
            }
        }
        if (order != null) {
            if (phase == Phase.DEAD) {
                enter(ctx, Phase.TOWN_IDLE, "revived"); // in town now: the director sends it away
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
    /** Phase 7a: initiative aggressiveness toward live players. */
    static volatile PvpDecision.Mode pvpMode = PvpDecision.Mode.NORMAL;
    static volatile long pvpPlayerCooldownMs = 12 * 60_000L;
    static volatile boolean pvpProtectParty = true;
    static volatile boolean pvpProtectClan = true;
    static volatile boolean pvpRevenge = true;
    static volatile long pvpRevengeTimeoutMs = 30 * 60_000L;
    /** Last phase-7 PvP verdict (shown in //bots info; 7b enacts it). */
    private PvpDecision.Verdict lastPvpVerdict;
    private String lastPvpTarget = "";
    private String lastPvpWhy = "";
    /** Live player id → last time we decided FIGHT as initiative (cooldown). */
    private final Map<Integer, Long> pvpInitiativeAt = new HashMap<>();
    /** Live player id → last time we counted a cooldown/zone block for //bots pvp. */
    private final Map<Integer, Long> pvpBlockNoted = new HashMap<>();
    /** Phase 7b: wait this long after the offence before revenge (not instant). */
    static volatile long pvpRevengeDelayMs = 2 * 60_000L;
    private long pvpHelpAt;
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

    /** Phase 7a: record who hit us (stock {@code byPlayer} = live player). */
    private void notePvpAttacker(BrainContext ctx, WorldView w, int attackerId) {
        final PlayerView p = playerById(w, attackerId);
        if ((p != null) && p.bot()) {
            return;
        }
        ctx.memory().note(attackerId, -8, org.l2start.bots.api.Memory.Tag.ATTACKED_ME, true);
    }

    /** Phase 7a: record who killed us (skip bot killers). */
    private void notePvpKiller(BrainContext ctx, WorldView w, int killerId) {
        if (killerId == 0) {
            return;
        }
        final PlayerView p = playerById(w, killerId);
        if ((p != null) && p.bot()) {
            return;
        }
        ctx.memory().note(killerId, -25, org.l2start.bots.api.Memory.Tag.KILLED_ME, true);
    }

    private static PlayerView playerById(WorldView w, int id) {
        for (PlayerView p : w.players()) {
            if (p.objectId() == id) {
                return p;
            }
        }
        return null;
    }

    /**
     * Phase 7b: pick a nearby candidate, decide fight/flee/help/none, show in details, and start the action. Expires
     * old grudges. Records initiative cooldown when the first FIGHT on a live player is taken.
     */
    private void evaluatePvp(BrainContext ctx, WorldView w, SelfView me, long now) {
        expireGrudges(ctx, now);
        if (me.dead() || me.teleporting()) {
            return;
        }
        PlayerView best = null;
        PvpDecision.Verdict bestV = null;
        boolean bestInit = false;
        for (PlayerView p : w.players()) {
            if (p.objectId() == me.objectId() || p.distance() > 1200) {
                continue;
            }
            final boolean hit = hitByRecently(p.objectId(), now, PVP_HIT_MEMORY);
            final org.l2start.bots.api.Memory.Of mem = ctx.memory().of(p.objectId());
            final boolean revenge = PvpDecision.revengeReady(mem, now, pvpRevengeDelayMs, pvpRevengeTimeoutMs);
            final boolean allyHit = allyUnderAttackBy(w, me, p);
            final boolean initiative = !hit && !revenge && !allyHit;
            final boolean cd = !p.bot() && ((now - pvpInitiativeAt.getOrDefault(p.objectId(), 0L)) < pvpPlayerCooldownMs);
            final int enemies = countHostilesNear(w, me, p, 700);
            final int allies = countAlliesNear(w, me, 700);
            final PvpDecision.Situation sit = new PvpDecision.Situation(me.inPvpZone(), p.inPvpZone(), initiative, hit, revenge, allyHit, !p.bot(),
                pvpProtectParty, pvpProtectClan, pvpRevenge, pvpMode, me.level(), p.level(), me.mage(), isMageClass(p.classId()), enemies, allies,
                me.hpPct(), me.mpPct(), ctx.info().aggression(), cd, p.level() < PvpDecision.MIN_TARGET_LEVEL);
            PvpDecision.Verdict v = PvpDecision.decide(sit);
            // phase 7c: PK persona opens chaotic fights (peace / <20 / off / cooldown already in decide)
            if ((v.intent() == PvpDecision.Intent.NONE) && initiative && ctx.info().pk() && me.inPvpZone() && p.inPvpZone()
                && (me.hpPct() >= 40) && !(p.level() < PvpDecision.MIN_TARGET_LEVEL)
                && !( !p.bot() && (pvpMode == PvpDecision.Mode.OFF) )
                && !( !p.bot() && cd )) {
                v = new PvpDecision.Verdict(PvpDecision.Intent.FIGHT, "pk");
            }
            // phase 7c: high-aggression bots hunt reds (players or bots)
            if ((v.intent() == PvpDecision.Intent.NONE) && !ctx.info().pk() && p.pvpMarked() && (ctx.info().aggression() >= 0.65)
                && me.inPvpZone() && p.inPvpZone() && (me.hpPct() >= 40)) {
                v = new PvpDecision.Verdict(PvpDecision.Intent.FIGHT, "pk-hunt");
            }
            if (v.intent() == PvpDecision.Intent.NONE) {
                notePvpBlock(ctx, p, initiative, v.why(), now);
                continue;
            }
            if ((bestV == null) || rank(v.intent()) > rank(bestV.intent())) {
                best = p;
                bestV = v;
                bestInit = initiative && (v.intent() == PvpDecision.Intent.FIGHT) && !p.bot()
                    && !"pk-hunt".equals(v.why()); // hunter CD separate? reuse initiative CD for live reds
            }
        }
        if (bestV != null) {
            lastPvpVerdict = bestV;
            lastPvpTarget = best.name();
            lastPvpWhy = bestV.why();
            if (bestInit) {
                pvpInitiativeAt.put(best.objectId(), now);
            }
            enactPvp(ctx, w, me, best, bestV, now);
        } else if ((lastPvpVerdict != null) && (lastPvpVerdict.intent() != PvpDecision.Intent.NONE)) {
            lastPvpVerdict = PvpDecision.Verdict.none("idle");
            lastPvpTarget = "";
            lastPvpWhy = "";
        }
    }

    /** Count cooldown / peace-zone initiative blocks once per player per cooldown window. */
    private void notePvpBlock(BrainContext ctx, PlayerView p, boolean initiative, String why, long now) {
        if (!initiative || p.bot()) {
            return;
        }
        final org.l2start.bots.chat.PvpBoard.Stat s = switch (why) {
            case "cooldown" -> org.l2start.bots.chat.PvpBoard.Stat.LIMIT;
            case "peace" -> org.l2start.bots.chat.PvpBoard.Stat.ZONE;
            default -> null;
        };
        if (s == null) {
            return;
        }
        final long gap = Math.max(60_000L, pvpPlayerCooldownMs);
        if ((now - pvpBlockNoted.getOrDefault(p.objectId(), 0L)) < gap) {
            return;
        }
        pvpBlockNoted.put(p.objectId(), now);
        ctx.pvp().note(s, now);
    }

    /** Phase 7b: turn a verdict into attack / flee / party call (uses the existing PvP FSM). */
    private void enactPvp(BrainContext ctx, WorldView w, SelfView me, PlayerView p, PvpDecision.Verdict v, long now) {
        switch (v.intent()) {
            case FIGHT -> beginPvpEngage(ctx, me, p, now, true, v.why());
            case FLEE -> beginPvpEngage(ctx, me, p, now, false, v.why());
            case CALL_HELP -> {
                if ((now - pvpHelpAt) > 15_000L) {
                    pvpHelpAt = now;
                    final String lang = ctx.info().lang();
                    final boolean ru = (lang != null) && lang.toUpperCase(java.util.Locale.ROOT).startsWith("RU");
                    ctx.act().sayParty(ru ? ("На помощь! " + p.name()) : ("Help! " + p.name()));
                    ctx.pvp().note(org.l2start.bots.chat.PvpBoard.Stat.CALL_HELP, now);
                    ctx.note("calls help vs " + p.name());
                }
                if (me.hpPct() >= fleeHp) {
                    beginPvpEngage(ctx, me, p, now, true, v.why());
                }
            }
            default -> {
            }
        }
        // revenge sometimes with party: shout once when starting revenge
        if ("revenge".equals(v.why()) && (v.intent() == PvpDecision.Intent.FIGHT) && !w.party().isEmpty()
            && (ctx.rnd().nextDouble() < 0.35) && ((now - pvpHelpAt) > 15_000L)) {
            pvpHelpAt = now;
            ctx.act().sayParty(p.name());
            ctx.pvp().note(org.l2start.bots.chat.PvpBoard.Stat.CALL_HELP, now);
        }
    }

    private void beginPvpEngage(BrainContext ctx, SelfView me, PlayerView p, long now, boolean fight, String why) {
        if ((pvpId == p.objectId()) && (pvpFight == fight)) {
            return; // already in that mood toward them
        }
        if ((pvpId != 0) && (pvpId != p.objectId()) && pvpFight && !"protect".equals(why) && !"revenge".equals(why) && !"pk-hunt".equals(why)
            && !"pk".equals(why)) {
            return; // finish the current fight unless protect/revenge/pk overrides
        }
        final boolean fresh = pvpId != p.objectId();
        pvpId = p.objectId();
        pvpFight = fight;
        pvpDecision.put(p.objectId(), fight);
        pvpStart = me.pos();
        pvpSince = now;
        pvpRefused = 0;
        endFight();
        standIfSitting(ctx, me);
        if (fresh) {
            notePvpStat(ctx, why, fight, now);
            ctx.note((fight ? "PvP " : "flees PvP ") + why + " → " + p.name() + " lv " + p.level());
        }
        if (!fight) {
            startFlee(ctx, me, p, now);
        }
    }

    private void notePvpStat(BrainContext ctx, String why, boolean fight, long now) {
        if (!fight) {
            ctx.pvp().note(org.l2start.bots.chat.PvpBoard.Stat.FLEE, now);
            return;
        }
        final org.l2start.bots.chat.PvpBoard.Stat s = switch (why) {
            case "protect", "attacked" -> org.l2start.bots.chat.PvpBoard.Stat.DEFEND;
            case "revenge" -> org.l2start.bots.chat.PvpBoard.Stat.REVENGE;
            case "pk-hunt" -> org.l2start.bots.chat.PvpBoard.Stat.PK_HUNT;
            default -> org.l2start.bots.chat.PvpBoard.Stat.ATTACK;
        };
        ctx.pvp().note(s, now);
    }

    private void expireGrudges(BrainContext ctx, long now) {
        if (pvpRevengeTimeoutMs <= 0) {
            return;
        }
        // MapMemory exposes all() only on the concrete type; ask about known hitters
        for (Integer id : new java.util.ArrayList<>(playerHits.keySet())) {
            final org.l2start.bots.api.Memory.Of o = ctx.memory().of(id);
            if ((o.has(org.l2start.bots.api.Memory.Tag.KILLED_ME) || o.has(org.l2start.bots.api.Memory.Tag.ATTACKED_ME))
                && !PvpDecision.grudgeActive(o, now, pvpRevengeTimeoutMs)) {
                ctx.memory().forget(id, org.l2start.bots.api.Memory.Tag.KILLED_ME);
                ctx.memory().forget(id, org.l2start.bots.api.Memory.Tag.ATTACKED_ME);
            }
        }
        for (Integer id : new java.util.ArrayList<>(pvpInitiativeAt.keySet())) {
            final org.l2start.bots.api.Memory.Of o = ctx.memory().of(id);
            if ((o.has(org.l2start.bots.api.Memory.Tag.KILLED_ME) || o.has(org.l2start.bots.api.Memory.Tag.ATTACKED_ME))
                && !PvpDecision.grudgeActive(o, now, pvpRevengeTimeoutMs)) {
                ctx.memory().forget(id, org.l2start.bots.api.Memory.Tag.KILLED_ME);
                ctx.memory().forget(id, org.l2start.bots.api.Memory.Tag.ATTACKED_ME);
            }
        }
    }

    private static int rank(PvpDecision.Intent i) {
        return switch (i) {
            case FLEE -> 3;
            case CALL_HELP -> 2;
            case FIGHT -> 1;
            default -> 0;
        };
    }

    private boolean allyUnderAttackBy(WorldView w, SelfView me, PlayerView foe) {
        if (!pvpProtectParty && !pvpProtectClan) {
            return false;
        }
        if (pvpProtectParty) {
            for (org.l2start.bots.api.PartyMember m : w.party()) {
                if (m.objectId() == me.objectId()) {
                    continue;
                }
                if (foe.targetId() == m.objectId() && foe.inCombat()) {
                    return true;
                }
            }
        }
        // phase 7d: defend clan mates under attack
        if (pvpProtectClan && (me.clanId() != 0) && foe.inCombat()) {
            for (PlayerView ally : w.players()) {
                if ((ally.objectId() != me.objectId()) && (ally.clanId() == me.clanId()) && (foe.targetId() == ally.objectId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countHostilesNear(WorldView w, SelfView me, PlayerView focus, int radius) {
        int n = 0;
        for (PlayerView p : w.players()) {
            if (p.objectId() == me.objectId()) {
                continue;
            }
            if (p.pos().distance2d(me.pos()) > radius) {
                continue;
            }
            if (p.objectId() == focus.objectId() || (p.inCombat() && (p.targetId() == me.objectId() || p.pvpMarked()))) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    private static int countAlliesNear(WorldView w, SelfView me, int radius) {
        int n = 1; // self counts: 1v1 is even numbers, not "outnumbered by the target"
        for (org.l2start.bots.api.PartyMember m : w.party()) {
            if (m.objectId() != me.objectId() && m.pos().distance2d(me.pos()) <= radius && !m.dead()) {
                n++;
            }
        }
        if (me.clanId() != 0) {
            for (PlayerView p : w.players()) {
                if ((p.objectId() != me.objectId()) && (p.clanId() == me.clanId()) && (p.pos().distance2d(me.pos()) <= radius)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean isMageClass(int classId) {
        // Interlude magic lines (human/elf/de mystic starts and evolved): rough for 7a weights
        return classId == 10 || classId == 11 || classId == 12 || classId == 13 || classId == 14 || classId == 15 || classId == 16 || classId == 17
            || classId == 25 || classId == 26 || classId == 27 || classId == 28 || classId == 29 || classId == 30
            || classId == 38 || classId == 39 || classId == 40 || classId == 41 || classId == 42 || classId == 43;
    }

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
            if (((now - pvpSince) > pvpFleeMs) || (p == null) || (pvpFlee != null && pvpFlee.distance2d(me.pos()) < 150)) {
                ctx.note("scroll of escape");
                ctx.act().returnToTown();
                pvpOver(ctx, now);
                return 3000;
            }
            if (!me.moving() && (pvpFlee != null)) {
                ctx.act().moveTo(pvpFlee);
            }
            return 600;
        }
        if ((p != null) && (me.hpPct() < fleeHp)) {
            ctx.note("hurt badly in the fight with " + p.name() + ": runs");
            pvpFight = false;
            pvpDecision.put(p.objectId(), false);
            ctx.pvp().note(org.l2start.bots.chat.PvpBoard.Stat.FLEE, now);
            return startFlee(ctx, me, p, now);
        }
        // initiative / revenge / protect: stay engaged while the foe is near (not only when they hit us)
        final boolean planned = "initiative".equals(lastPvpWhy) || "revenge".equals(lastPvpWhy) || "protect".equals(lastPvpWhy)
            || "need help".equals(lastPvpWhy) || "outnumbered".equals(lastPvpWhy) || "pk".equals(lastPvpWhy) || "pk-hunt".equals(lastPvpWhy);
        final boolean engaged = (p != null) && (hitByRecently(p.objectId(), now, PVP_HIT_MEMORY)
            || ((p.targetId() == me.objectId()) && p.inCombat())
            || (planned && (p.pos().distance2d(me.pos()) < 1200)));
        final String over;
        if (p == null) {
            over = "the player is gone";
        } else if (pvpRefused >= 4) {
            over = "the player is dead or out of reach";
        } else if (p.pos().distance2d(me.pos()) > 1500) {
            over = "the player ran away";
        } else if (!engaged) {
            over = "the player left it alone";
        } else if ((me.pos().distance2d(pvpStart) > PVP_LEASH) && !hitByRecently(p.objectId(), now, 4000) && !planned) {
            over = "no chasing";
        } else {
            over = null;
        }
        if (over != null) {
            ctx.note("the fight with a player is over: " + over);
            ctx.act().stop();
            pvpOver(ctx, now);
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
        if (dash(ctx, me)) {
            return 700;
        }
        ctx.act().moveTo(pvpFlee);
        return 600;
    }

    private void pvpOver(BrainContext ctx, long now) {
        if ((pvpId != 0) && "revenge".equals(lastPvpWhy)) {
            ctx.memory().forget(pvpId, org.l2start.bots.api.Memory.Tag.KILLED_ME);
            ctx.memory().forget(pvpId, org.l2start.bots.api.Memory.Tag.ATTACKED_ME);
        }
        pvpDone.put(pvpId, now + PVP_IGNORE_MS);
        pvpDone.values().removeIf(t -> t < now);
        if (pvpDecision.size() > 50) {
            pvpDecision.clear();
        }
        pvpId = 0;
        lastPvpWhy = "";
    }

    /** A living monster that is fighting this bot (self-defence comes before anything else). */
    private static NpcView attacker(WorldView w, SelfView me) {
        NpcView onPet = null;
        for (NpcView n : w.npcs()) {
            if (!n.dead() && n.monster() && n.attackable()) {
                if (n.targetId() == me.objectId()) {
                    return n;
                }
                if (me.hasPet() && (n.targetId() == me.petId()) && (onPet == null)) {
                    onPet = n; // its own summon is attacked: defend it
                }
            }
        }
        return onPet;
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
        // 3. loot of the last kill (Sweeper first: the body does not wait)
        final long swept = sweep(ctx, w, me, now);
        if (swept > 0) {
            return swept;
        }
        if (now < lootUntil) {
            final long d = loot(ctx, w, me);
            if (d > 0) {
                return d;
            }
        }
        // 4. heal itself, the summon; rest
        final long cared = care(ctx, w, me, now, (zone != null) && (zone.center().distance2d(me.pos()) < (zone.radius() + 1500)));
        if (cared > 0) {
            return cared;
        }
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
            if (n.champion() && (n.level() > (lvl - 5))) {
                continue; // EXTRAS_PLAN §1: x6 HP, a solo bot only takes champions well below its level
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
            case LEAVE_SOE -> escapeHome(ctx, w, me, now, a, attacker);
            case LEAVE_SPOT -> logoutHere(ctx, w, me, now, attacker);
            case ERRANDS -> errands(ctx, w, me, now, a, attacker);
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
        afkUntil = 0;
        afkAt = 0;
        soeAt = 0;
        spotLogoutAt = 0;
        if ((a.kind() == Assignment.Kind.LEAVE_SOE) || (a.kind() == Assignment.Kind.LEAVE_SPOT)) {
            // the hunt is over: the monster it fights now is finished first (nobody reads a scroll mid-fight)
            enter(ctx, Phase.LEVEL, a.kind() == Assignment.Kind.LEAVE_SOE ? "scroll of escape" : "logout here");
        } else if (a.kind() == Assignment.Kind.ERRANDS) {
            zone = null;
            endFight();
            standIfSitting(ctx, me);
            errandsLeft = 2 + ctx.rnd().nextInt(2);
            transitUntil = 0;
            badAnchors.clear();
            enter(ctx, Phase.TOWN_IDLE, "errands in " + a.place());
        } else if (a.leaving()) {
            if (!partyPlay || ctx.world().party().isEmpty()) {
                endFight();
                ctx.act().stop();
            }
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
            if (ctx.rnd().nextDouble() < afkShare) { // "went away from the keyboard" once in this hunt, 5-40 min in
                afkAt = now + 5 * 60_000L + ctx.rnd().nextInt(35 * 60_000);
            }
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
        if (partyPlay && !w.party().isEmpty()) {
            // a party does not run off while monsters are on one of them: they are finished first, together
            final long d = defendParty(ctx, w, me, now);
            if (d > 0) {
                return d;
            }
        }
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
            if (!"group".equals(errandWhat) && (ctx.rnd().nextDouble() < (afkShare / 2))) {
                afkAt = now; // stands at the NPC for a while (away from the keyboard)
            }
        }
        final long afk = afk(ctx, me, now);
        if (afk > 0) {
            return afk;
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

    // ------------------------------------------------------------------ life between hunts (phase 4d)

    /** Scroll of Escape. */
    static final int SOE = 736;
    /** Its cast takes 20 s; past this it tries again (or a plain return). */
    static final long SOE_WAIT = 30_000;
    /** Share of hunts with one AFK of 1-10 min (config AfkShare); half of it per NPC visit in town. */
    static volatile double afkShare = 0.15;
    private long afkAt;
    private long afkUntil;
    private long soeAt;
    private long spotLogoutAt;
    private int errandsLeft;

    /** Away from the keyboard: stands still 1-10 min (never sits, no fights unless something attacks it). */
    private long afk(BrainContext ctx, SelfView me, long now) {
        if ((afkAt > 0) && (now >= afkAt) && (fightId == 0)) {
            afkAt = 0;
            afkUntil = now + 60_000 + ctx.rnd().nextInt(9 * 60_000);
            ctx.act().stop();
            ctx.note("afk for " + ((afkUntil - now) / 60_000) + " min");
        }
        if (afkUntil > now) {
            if (me.sitting()) {
                ctx.act().stand();
            }
            detail = "afk";
            return Math.min(5000, afkUntil - now);
        }
        afkUntil = 0;
        return 0;
    }

    /** The hunt is over: the fight on hand first, then a scroll of escape where it stands (20 s cast). */
    private long escapeHome(BrainContext ctx, WorldView w, SelfView me, long now, Assignment a, NpcView attacker) {
        if (me.pos().distance2d(a.center()) > (a.radius() + 3000)) {
            standIfSitting(ctx, me);
            detail = "in town after the scroll"; // the director logs it out unseen or sends it on errands
            return 2000;
        }
        if (partyPlay && !w.party().isEmpty() && (soeAt == 0)) {
            final long d = defendParty(ctx, w, me, now); // nobody reads a scroll while a partner is beaten
            if (d > 0) {
                return d;
            }
        }
        if (fightId != 0) {
            final long d = fight(ctx, w, me, now);
            if (d > 0) {
                return d;
            }
        }
        if (attacker != null) {
            soeAt = 0;
            return startFight(ctx, me, attacker, now);
        }
        if ((soeAt > 0) && ((now - soeAt) < SOE_WAIT)) {
            detail = "scroll of escape";
            return 1000;
        }
        standIfSitting(ctx, me);
        soeAt = now;
        if ((w.itemCount(SOE) > 0) && (ctx.act().useItem(SOE) == ActionResult.OK)) {
            ctx.note("reads a scroll of escape");
            detail = "scroll of escape";
            return 2000;
        }
        ctx.note("no scroll of escape: back to town");
        ctx.act().returnToTown();
        return 3000;
    }

    /** Logs out right here, sitting (like a restart): the fight on hand first; nobody is near (the director checked). */
    private long logoutHere(BrainContext ctx, WorldView w, SelfView me, long now, NpcView attacker) {
        if (partyPlay && !w.party().isEmpty()) {
            final long d = defendParty(ctx, w, me, now);
            if (d > 0) {
                spotLogoutAt = 0;
                return d;
            }
        }
        if (fightId != 0) {
            final long d = fight(ctx, w, me, now);
            if (d > 0) {
                return d;
            }
        }
        if (attacker != null) {
            spotLogoutAt = 0;
            standIfSitting(ctx, me);
            return startFight(ctx, me, attacker, now);
        }
        if (!me.sitting()) {
            ctx.act().stop();
            ctx.act().sit();
            if (spotLogoutAt == 0) {
                spotLogoutAt = now + 5_000 + ctx.rnd().nextInt(15_000);
            }
            detail = "sits down to log out";
            return 1500;
        }
        if (spotLogoutAt == 0) {
            spotLogoutAt = now + 5_000 + ctx.rnd().nextInt(15_000);
        }
        if (now >= spotLogoutAt) {
            ctx.note("logout on the spot");
            ctx.act().logout();
            return 5000;
        }
        detail = "logging out";
        return Math.min(2000, spotLogoutAt - now);
    }

    private static final String[] ERRAND_KINDS = { "shop", "wh", "trainer" };

    /** Back in town from a hunt: 2-3 of shop, warehouse, trainer (reachable ones), now and then AFK, then the gatekeeper. */
    private long errands(BrainContext ctx, WorldView w, SelfView me, long now, Assignment a, NpcView attacker) {
        if (me.sitting()) {
            ctx.act().stand();
            return 1500;
        }
        final long afk = afk(ctx, me, now);
        if (afk > 0) {
            return afk;
        }
        if (errand != null) {
            final double d = me.pos().distance2d(errand);
            if (d > 120) {
                if (d < (errandBest - 80)) {
                    errandBest = d;
                    errandProgressAt = now;
                }
                if ((now - errandProgressAt) > 8000) {
                    ctx.note("cannot reach " + errandWhat + ": next one");
                    if (errandAnchor != null) {
                        badAnchors.add(errandAnchor);
                    }
                    if (!"gk".equals(errandWhat)) {
                        errandsLeft = Math.max(0, errandsLeft - 1);
                    }
                    errand = null;
                    errandAnchor = null;
                    ctx.act().stop();
                    return 1000;
                }
                detail = "to " + errandWhat;
                return walk(ctx, w, me, errand, now);
            }
            final String at = errandWhat;
            errand = null;
            errandAnchor = null;
            detail = "at " + at;
            if ("gk".equals(at)) {
                transitUntil = now + 2000 + ctx.rnd().nextInt(5000);
            } else {
                errandsLeft--;
                nextIdleAction = now + 5_000 + ctx.rnd().nextInt(25_000);
                if (ctx.rnd().nextDouble() < afkShare) {
                    afkAt = now + 2000;
                }
            }
        }
        if (transitUntil > 0) {
            detail = "at the gatekeeper (teleports)";
            if (now >= transitUntil) {
                transitUntil = 0;
                ctx.note("teleported back to hunt (logout at the gatekeeper)");
                ctx.act().logout();
                return 5000;
            }
            return 1000;
        }
        if (now < nextIdleAction) {
            return Math.max(500, Math.min(5000, nextIdleAction - now));
        }
        if (errandsLeft > 0) {
            final List<Assignment.Anchor> l = new java.util.ArrayList<>();
            for (Assignment.Anchor p : a.anchors()) {
                if (java.util.Arrays.asList(ERRAND_KINDS).contains(p.kind()) && !p.kind().equals(errandWhat) && !badAnchors.contains(p.pos())
                    && (p.pos().distance2d(me.pos()) < 4000)) {
                    l.add(p);
                }
            }
            if (!l.isEmpty()) {
                final Assignment.Anchor p = l.get(ctx.rnd().nextInt(l.size()));
                return startErrand(ctx, w, me, now, p.pos(), around(ctx, w, p.pos(), 60, 160), p.kind());
            }
            errandsLeft = 0;
        }
        final Pos gk = a.exit() != null ? a.exit() : (nearest(a, "gk", me.pos()) == null ? null : nearest(a, "gk", me.pos()).pos());
        if (gk == null) {
            ctx.act().logout();
            return 5000;
        }
        return startErrand(ctx, w, me, now, gk, around(ctx, w, gk, 60, 140), "gk");
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
        final long pc = partyCare(ctx, w, me, now, attacker);
        if (pc > 0) {
            return pc;
        }
        final long peeled = peel(ctx, w, me, now);
        if (peeled > 0) {
            return peeled;
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
        final long swept = sweep(ctx, w, me, now);
        if (swept > 0) {
            return swept;
        }
        if (now < lootUntil) {
            final long d = loot(ctx, w, me);
            if (d > 0) {
                return d;
            }
        }
        final long cared = care(ctx, w, me, now, a.center().distance2d(me.pos()) < (a.radius() + 1500));
        if (cared > 0) {
            return cared;
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
        final long afk = afk(ctx, me, now);
        if (afk > 0) {
            return afk;
        }
        final long together = together(ctx, w, me, now);
        if (together > 0) {
            return together;
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
        return startFight(ctx, me, mob, now, false);
    }

    /** @param assist on the party leader's monster (4g): it is ours, not someone else's claim */
    private long startFight(BrainContext ctx, SelfView me, NpcView mob, long now, boolean assist) {
        if (!assist && !claims.claim(mob.objectId(), botId, now, FIGHT_TIMEOUT * 2) && (mob.targetId() != me.objectId())) {
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
            if ((mob != null) && mob.spoiled()) {
                sweepId = mob.objectId();
                sweepUntil = now + SWEEP_MS;
            }
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
        petAttack(ctx, me, mob, ctx.now());
        if (petHeal(ctx, me, 50, ctx.now())) {
            return;
        }
        final boolean petTanks = me.hasPet() && (mob.targetId() == me.petId());
        if (!petTanks && kite(ctx, w, me, mob, ctx.now())) {
            return;
        }
        if (stance(ctx, me, me.mpPct() >= 15, ctx.now())) {
            return; // the stance is switched (it targets the bot itself): the next tick attacks again
        }
        if (sneak(ctx, w, me, mob, ctx.now())) {
            return;
        }
        if (petTanks && me.mage() && (me.mpPct() < SUMMONER_NUKE_MP)) {
            // the servitor holds the monster: a summoner keeps its MP for Servitor Heal instead of meleeing with a staff
            if ("ATTACK".equals(me.intention())) {
                ctx.act().stop();
            }
            detail = "fight " + fightName + ": servitor tanks";
            return;
        }
        if (playstyle(ctx, w, me, mob, ctx.now())) {
            return;
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

    // ------------------------------------------------------------------ roles (4c): class playstyles

    /** data/playstyles.xml (set by the module; tests set their own). */
    static volatile Playstyles playstyles = Playstyles.EMPTY;
    /** Config Playstyles of bots-brain-basic. */
    static volatile boolean usePlaystyles = true;
    private final PlaystyleEngine engine = new PlaystyleEngine();
    private int playstyleCasts;

    /** Skills cast by the class playstyle (tests, //bots info). */
    public int playstyleCasts() {
        return playstyleCasts;
    }

    private String styleName = "";

    @Override
    public String details() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Style: ").append(!usePlaystyles ? "off" : styleName.isEmpty() ? "(no fight yet)" : styleName);
        if (!engine.last().isEmpty()) {
            sb.append("\nLast skill: ").append(engine.last());
        }
        sb.append("\nSkills cast ").append(playstyleCasts).append(", buffs ").append(buffs).append(", heals ").append(heals).append(", summoned ").append(summoned)
            .append(", swept ").append(sweeps).append(", round to the back ").append(sneaks);
        sb.append(companionDetails(ctxNow));
        if (lastPvpVerdict != null) {
            sb.append("\nPvP: ").append(lastPvpVerdict.intent()).append(" (").append(lastPvpVerdict.why()).append(")");
            if (lastPvpTarget != null && !lastPvpTarget.isEmpty()) {
                sb.append(" → ").append(lastPvpTarget);
            }
            sb.append(", mode ").append(pvpMode.name().toLowerCase(java.util.Locale.ROOT));
        }
        return sb.toString();
    }

    /** Last playstyle line used ("ROTATION 3 Power Strike (ALWAYS)"). */
    public String playstyleLast() {
        return engine.last();
    }

    /**
     * One combat decision by the class playstyle (phase 4c).
     * @return true when a skill was cast (no plain hit this tick)
     */
    private boolean playstyle(BrainContext ctx, WorldView w, SelfView me, NpcView mob, long now) {
        if (!usePlaystyles || me.casting()) {
            return false;
        }
        final Playstyles.Playstyle style = playstyles.of(me.classId(), me.bow());
        styleName = style == null ? "none" : style.name();
        final PlaystyleEngine.Pick pick = engine.pick(style, me, mob, w, badSkills, now);
        if (pick == null) {
            return false;
        }
        final ActionResult r = ctx.act().cast(pick.skill().id(), pick.self() ? me.objectId() : mob.objectId());
        if (r == ActionResult.INVALID) {
            badSkills.put(badKey(pick.skill().id(), mob.npcId()), now + BAD_SKILL_MS);
        }
        engine.cast(pick, r.ok(), me.mage(), now);
        if (!r.ok()) {
            return false;
        }
        playstyleCasts++;
        detail = "fight " + fightName + ": " + pick.entry().name();
        return true;
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

    // ------------------------------------------------------------------ bot pairs and trios (4g)

    /** Phase 4g: heal, raise and buff party members; followers stay by the leader and fight its monster (bots-brain-basic PartyPlay). */
    static volatile boolean partyPlay = true;
    /** Phase 6d: bots look for party in towns (LFP/LFM shout/trade), answer party whispers and the player's LFP. */
    static volatile boolean social = true;
    /** Phase 6d: at most this many bots whisper back to one player LFP/LFM shout. */
    static volatile int lfpReplies = 2;
    /** Phase 6d: quiet=0.5, normal=1, lively=2 — scales how often bots post LFP/LFM. */
    static volatile double socialNoise = 1.0;
    /** A healer heals a party member below this HP %, even in a fight. */
    static final double PARTY_HEAL_HP = 60;
    static final int RESURRECTION = 1016;
    /** @deprecated KT12 F3: use {@link PartyBuffs#wanted}; kept for any external reference. */
    static final int[] PARTY_BUFFS = { 1204, 1040, 1068, 1045, 1048, 1086, 1077, 1242, 1085, 1059, 1062, 1035, 1078, 1240, 1087, 1268, 1044, 1036, 1243, 1257 };
    private final Map<String, Long> partyBuffed = new HashMap<>();
    private long partyTried;
    private long leaderWaitSince;
    private int partyHeals;
    private int partyBuffs;
    private int raised;

    public int partyHeals() {
        return partyHeals;
    }

    public int partyBuffs() {
        return partyBuffs;
    }

    public int raised() {
        return raised;
    }

    private long partyCare(BrainContext ctx, WorldView w, SelfView me, long now, NpcView attacker) {
        final List<PartyMember> pm = w.party();
        if (!partyPlay || pm.isEmpty() || me.casting() || me.sitting() || (now < partyTried)) {
            return 0;
        }
        PartyMember hurt = null;
        for (PartyMember m : pm) {
            if (!m.dead() && (m.hpPct() < PARTY_HEAL_HP) && (m.distance() < 1200) && ((hurt == null) || (m.hpPct() < hurt.hpPct()))) {
                hurt = m;
            }
        }
        if ((hurt != null) && (me.mpPct() >= 10)) {
            for (int id : HEALS) {
                final SkillView s = skill(me, id);
                if (usable(me, s)) {
                    final ActionResult r = ctx.act().cast(id, hurt.objectId());
                    partyTried = now + (r.ok() ? 2500 : 5000);
                    if (r.ok()) {
                        heals++;
                        partyHeals++;
                        detail = "heals " + hurt.name();
                        return 2500;
                    }
                    break;
                }
            }
        }
        if ((fightId != 0) || (attacker != null)) {
            return 0;
        }
        for (PartyMember m : pm) {
            if (m.dead() && (m.distance() < 1200)) {
                final SkillView s = skill(me, RESURRECTION);
                if (usable(me, s)) {
                    final ActionResult r = ctx.act().cast(RESURRECTION, m.objectId());
                    partyTried = now + (r.ok() ? 8000 : 10_000);
                    if (r.ok()) {
                        raised++;
                        ctx.note("resurrects " + m.name());
                        detail = "resurrects " + m.name();
                        return 6000;
                    }
                }
                break;
            }
        }
        if (me.mpPct() < 50) {
            return 0;
        }
        final boolean peace = me.inPeaceZone();
        for (PartyMember m : pm) {
            if (m.dead() || (m.distance() > 600)) {
                continue;
            }
            for (int id : PartyBuffs.wanted(me.classId(), m.classId(), peace)) {
                final SkillView s = skill(me, id);
                final String key = id + ">" + m.objectId();
                if (!usable(me, s) || (partyBuffed.getOrDefault(key, 0L) > now)) {
                    continue;
                }
                final ActionResult r = ctx.act().cast(id, m.objectId());
                partyBuffed.put(key, now + (r.ok() ? 18 * 60_000L : 60_000L));
                partyTried = now + 2500;
                if (r.ok()) {
                    partyBuffs++;
                    detail = "buffs " + m.name();
                    return 2500;
                }
            }
        }
        return 0;
    }

    /** Healers and buffers: the party protects them first (peel). Second and third professions of the support lines. */
    static final java.util.Set<Integer> SUPPORT_CLASSES = java.util.Set.of(15, 16, 17, 29, 30, 42, 43, 49, 50, 51, 52, 21, 34, 97, 98, 100, 105, 107, 112, 115, 116);
    /** Aggression (single target) and Aura of Hate: a tank pulls a monster off the support. */
    static final int AGGRESSION = 28;
    static final int HATE_AURA = 18;
    private long tauntNext;
    private int peels;

    public int peels() {
        return peels;
    }

    static boolean support(int classId) {
        return SUPPORT_CLASSES.contains(classId);
    }

    /** A living party member that can heal (a support class) within 1500. */
    private static boolean healerNear(WorldView w) {
        for (PartyMember m : w.party()) {
            if (!m.dead() && support(m.classId()) && (m.distance() < 1500)) {
                return true;
            }
        }
        return false;
    }

    /** How much a monster matters to the party: 3 = on a support, 2 = on another member, 1 = on this bot, 0 = on nobody of ours. */
    private static int peelRank(WorldView w, SelfView me, NpcView n) {
        if ((n == null) || n.dead() || !n.monster() || (n.targetId() == 0)) {
            return 0;
        }
        if (n.targetId() == me.objectId()) {
            return 1;
        }
        for (PartyMember m : w.party()) {
            if (!m.dead() && (m.objectId() == n.targetId())) {
                return support(m.classId()) ? 3 : 2;
            }
        }
        return 0;
    }

    /** The monster on a partner that matters most (supports first), nearest of a rank; null = nobody of the party is attacked. */
    private static NpcView onPartner(WorldView w) {
        if (w.party().isEmpty()) {
            return null;
        }
        final SelfView me = w.self();
        NpcView best = null;
        int bestRank = 1;
        for (NpcView n : w.npcs()) {
            final int r = peelRank(w, me, n);
            if ((r > bestRank) && (n.distance() < 1500) && n.attackable()) {
                best = n;
                bestRank = r;
            }
        }
        return best;
    }

    /**
     * Phase 4g, KT8: a monster on a party member is our fight. Supports first, then the others, then its own: it
     * switches to the monster that matters more and a tank pulls it off with Aggression / Aura of Hate.
     */
    private long peel(BrainContext ctx, WorldView w, SelfView me, long now) {
        if (!partyPlay || w.party().isEmpty()) {
            return 0;
        }
        final NpcView mob = onPartner(w);
        if (mob == null) {
            return 0;
        }
        final NpcView current = fightId == 0 ? null : w.npc(fightId);
        if ((current != null) && (peelRank(w, me, current) >= peelRank(w, me, mob))) {
            return 0; // already on something that matters as much
        }
        if (support(me.classId()) && (current == null) && (me.hpPct() < 50)) {
            return 0; // a hurt support heals (partyCare) rather than melees
        }
        standIfSitting(ctx, me);
        if (current != null) {
            claims.release(fightId, botId);
            fightId = 0;
        }
        peels++;
        ctx.note("defends the party from " + mob.name() + (peelRank(w, me, mob) == 3 ? " (on the support)" : ""));
        final long d = startFight(ctx, me, mob, now, true);
        taunt(ctx, me, mob, now);
        return d;
    }

    /** Pull the monster's aggro onto itself when the class can (Aggression, else Aura of Hate). */
    private void taunt(BrainContext ctx, SelfView me, NpcView mob, long now) {
        if ((now < tauntNext) || me.mage()) {
            return;
        }
        for (int id : new int[] { AGGRESSION, HATE_AURA }) {
            final SkillView s = skill(me, id);
            if (usable(me, s) && ctx.act().cast(id, mob.objectId()).ok()) {
                ctx.note("pulls " + mob.name() + " off its partner");
                tauntNext = now + 4000;
                return;
            }
        }
    }

    /** While leaving or in a fight of its own: monsters on itself or a partner are fought first. */
    private long defendParty(BrainContext ctx, WorldView w, SelfView me, long now) {
        if (fightId != 0) {
            final NpcView cur = w.npc(fightId);
            if ((cur != null) && (peelRank(w, me, cur) > 0)) {
                final long d = fight(ctx, w, me, now);
                if (d > 0) {
                    return d;
                }
            } else {
                endFight();
            }
        }
        final long peeled = peel(ctx, w, me, now);
        if (peeled > 0) {
            return peeled;
        }
        final NpcView a = attacker(w, me);
        if (a != null) {
            return startFight(ctx, me, a, now, true);
        }
        return 0;
    }

    /** The party keeps together: a follower by the leader and on its monster, the leader waits a little for the rest. */
    private long together(BrainContext ctx, WorldView w, SelfView me, long now) {
        final List<PartyMember> pm = w.party();
        if (!partyPlay || pm.isEmpty() || (fightId != 0)) {
            return 0;
        }
        if (w.partyLeader()) {
            PartyMember far = null;
            for (PartyMember m : pm) {
                if (!m.dead() && (m.distance() > 1200) && (m.distance() < 6000)) {
                    far = m;
                }
            }
            if (far == null) {
                leaderWaitSince = 0;
                return 0;
            }
            if (leaderWaitSince == 0) {
                leaderWaitSince = now;
            }
            if ((now - leaderWaitSince) > 30_000) {
                return 0; // it will catch up
            }
            if (me.moving()) {
                ctx.act().stop();
            }
            detail = "waits for " + far.name();
            return 2000;
        }
        PartyMember lead = null;
        for (PartyMember m : pm) {
            if (m.leader()) {
                lead = m;
            }
        }
        if ((lead == null) || lead.dead() || (lead.distance() > 6000)) {
            return 0; // hunts on its own meanwhile
        }
        if (lead.targetId() != 0) {
            final NpcView mob = w.npc(lead.targetId());
            if ((mob != null) && !mob.dead() && mob.monster() && (mob.distance() < 1500)) {
                errand = null;
                return startFight(ctx, me, mob, now, true);
            }
        }
        if (lead.distance() > 450) {
            detail = "follows " + lead.name();
            final Pos near = lead.pos().toward(me.pos(), 150 + ctx.rnd().nextInt(150));
            return walk(ctx, w, me, near, now);
        }
        detail = "by " + lead.name();
        return 1500 + ctx.rnd().nextInt(1000);
    }

    // ------------------------------------------------------------------ phase 6b/6c: a party with a real player

    /** Bot modes in a real player's party, set by his commands in the party chat. */
    public enum PartyMode {
        ASSIST, FOLLOW, FREE, STOP, SIT
    }

    /** A mate farther than this is "gone" (teleported, scroll of escape, died and went to town). */
    static final int MATE_FAR = 3000;
    /** How long a patient bot (or one asked to wait) waits for its mate. */
    static final long MATE_WAIT_MS = 10 * 60_000L;
    /** The mate stands still this long: AFK, the bot says goodbye. */
    static final long MATE_AFK_MS = 10 * 60_000L;
    /** Deaths in one party after which the bot has had enough. */
    static final int PARTY_DEATHS_MAX = 3;

    private record Speech(String channel, String to, String text, long due) {
    }

    private final List<Speech> speech = new ArrayList<>();
    private BotEvent.PartyInvite ptInvite;
    private PartyDecision.Answer ptAnswer;
    private long ptAnswerAt;
    private final Map<Integer, long[]> ptDeclined = new HashMap<>(); // charId -> {refusals in a row, last refusal}
    private int ptMateId;
    private String ptMate = "";
    private long ptSince;
    private long ptMinuteAt;
    private long ptSessionEnd;
    private boolean ptWarned;
    private long ptLeaveAt;
    private Pos ptMatePos;
    private long ptMateStillSince;
    private long ptFarSince;
    private boolean ptWaiting;
    private boolean ptAgreedWait;
    /** KT12 F1: catching up via gatekeeper (not sitting until the mate returns). */
    private boolean ptCatchingUp;
    /** Position when the last GK hop was asked; if unchanged, run to the mate instead of hopping again. */
    private Pos ptGkTriedAt;
    private PartyMode ptMode = PartyMode.ASSIST;
    private boolean ptBuffAsked;
    private boolean ptHealAsked;
    private boolean ptResAsked;
    private boolean ptDeadInParty;
    private int ptDeaths;
    private int ptParties;
    private int ptInvites;
    private int ptCommands;
    // phase 6d: LFP/LFM in towns
    long lfpShoutAt;
    long lfpNextTry;
    String askInviteTo;
    long askInviteAt;
    // phase 6e: whisper replies (never first) and party-chat reactions
    private static final long WHISPER_COOLDOWN_MS = 8_000L;
    private static final long WHISPER_DIALOG_MS = 15 * 60_000L;
    private static final long PARTY_REACT_COOLDOWN_MS = 150_000L; // 2.5 min mid of 2–3
    private final Map<Integer, Long> whisperNextAt = new HashMap<>();
    /** Phase 6g: after «отстань» — no whisper replies until this time. */
    private final Map<Integer, Long> whisperMuteUntil = new HashMap<>();
    private final Map<Integer, WhisperDialog> whisperDialog = new HashMap<>();
    private String pendingWhFrom;
    private String pendingWhText;
    private long pendingWhBusy;
    private long partyReactAt;
    private int ptMateLevel;
    private java.util.Set<Integer> ptSeenBuffs = java.util.Set.of();

    public PartyMode partyMode() {
        return ptMode;
    }

    /** The real player it plays with now (0 = none). */
    public int mateId() {
        return ptMateId;
    }

    public int partiesWithPlayers() {
        return ptParties;
    }

    /** The real player of its party (the first one), null = none. */
    static PartyMember mate(WorldView w) {
        for (PartyMember m : w.party()) {
            if (!m.bot()) {
                return m;
            }
        }
        return null;
    }

    /** Events of the party with real players and phase 6d town search (whispers, LFP shouts, pair redirect). */
    private void socialEvents(BrainContext ctx, WorldView w, SelfView me, long now) {
        for (BotEvent e : w.events()) {
            switch (e) {
                case BotEvent.PartyInvite inv -> onInvite(ctx, w, me, inv, now);
                case BotEvent.Whisper wh -> onWhisper(ctx, w, me, wh, now);
                case BotEvent.Chat c -> {
                    if ("PARTY".equals(c.channel()) && (c.fromId() == ptMateId) && (ptMateId != 0)) {
                        onPartyCommand(ctx, w, me, c.text(), now);
                    } else if (social && isLfpChannel(c.channel())) {
                        onLfpChat(ctx, w, me, c, now);
                    }
                }
                case BotEvent.PartyRedirect pr -> onPartyRedirect(ctx, pr, now);
                case BotEvent.DuelInvite di -> onDuelInvite(ctx, me, di, now);
                case BotEvent.PledgeInvite pi -> onPledgeInvite(ctx, me, pi, now);
                case BotEvent.ClanRally cr -> onClanRally(ctx, me, cr, now);
                case BotEvent.Died d -> {
                    if (ptMateId != 0) {
                        ptDeaths++;
                        ptDeadInParty = true;
                    }
                }
                default -> {
                }
            }
        }
    }

    private BotEvent.DuelInvite duelInvite;
    private DuelDecision.Answer duelAnswer;
    private long duelAnswerAt;

    private BotEvent.PledgeInvite pledgeInvite;
    private PledgeDecision.Answer pledgeAnswer;
    private long pledgeAnswerAt;
    private long nextClanRallyAt;
    private Pos clanRallyAt;
    private long clanRallyUntil;

    /** Phase 7e: ClanInvitePlayers (set from bots-core / module). */
    static volatile boolean clanInvitePlayers = true;

    /** Phase 7b: a live player challenged us — decide accept/decline, answer after a short reaction delay. */
    private void onDuelInvite(BrainContext ctx, SelfView me, BotEvent.DuelInvite di, long now) {
        if (duelInvite != null) {
            return;
        }
        duelAnswer = DuelDecision.decide(ctx.info(), ctx.memory().of(di.fromId()), me.level(), di.fromLevel(), ctx.rnd());
        duelInvite = di;
        duelAnswerAt = now + Math.min(6000, 800 + ctx.info().reactionMs() + ctx.rnd().nextInt(2500));
        ctx.note("duel from " + di.from() + ": " + duelAnswer + " in " + ((duelAnswerAt - now) / 1000) + " s");
    }

    private void answerDuel(BrainContext ctx, SelfView me, long now) {
        if ((duelInvite == null) || (now < duelAnswerAt) || me.dead()) {
            return;
        }
        final BotEvent.DuelInvite di = duelInvite;
        final DuelDecision.Answer a = duelAnswer;
        duelInvite = null;
        duelAnswer = null;
        final boolean yes = a == DuelDecision.Answer.ACCEPT;
        final ActionResult r = ctx.act().answerDuel(yes);
        if (!r.ok()) {
            ctx.note("duel of " + di.from() + " gone (" + r + ")");
            return;
        }
        ctx.pvp().note(yes ? org.l2start.bots.chat.PvpBoard.Stat.DUEL_OK : org.l2start.bots.chat.PvpBoard.Stat.DUEL_NO, now);
        ctx.note((yes ? "accepted" : "declined") + " duel from " + di.from());
    }

    private void onPledgeInvite(BrainContext ctx, SelfView me, BotEvent.PledgeInvite pi, long now) {
        if (pledgeInvite != null) {
            return;
        }
        final boolean academy = pi.pledgeType() == PledgeDecision.ACADEMY;
        pledgeAnswer = PledgeDecision.decideJoin(ctx.info(), ctx.memory().of(pi.fromId()), pi.joinEnabled(), pi.canLeaveBotClan(), me.level(), academy,
            ctx.rnd());
        pledgeInvite = pi;
        pledgeAnswerAt = now + Math.min(8000, 1000 + ctx.info().reactionMs() + ctx.rnd().nextInt(3000));
        ctx.note("pledge from " + pi.from() + " type " + pi.pledgeType() + ": " + pledgeAnswer);
    }

    private void answerPledge(BrainContext ctx, SelfView me, long now) {
        if ((pledgeInvite == null) || (now < pledgeAnswerAt) || me.dead()) {
            return;
        }
        final BotEvent.PledgeInvite pi = pledgeInvite;
        final PledgeDecision.Answer a = pledgeAnswer;
        pledgeInvite = null;
        pledgeAnswer = null;
        final boolean yes = a == PledgeDecision.Answer.ACCEPT;
        final ActionResult r = ctx.act().answerPledge(yes);
        if (!r.ok()) {
            ctx.note("pledge of " + pi.from() + " gone (" + r + ")");
            return;
        }
        ctx.note((yes ? "accepted" : "declined") + " pledge from " + pi.from());
        if (!yes && (ctx.rnd().nextDouble() < 0.15) && (ctx.social().tryWhisper(me.objectId(), now))) {
            final var lines = ctx.chat().lines("CLAN_DECLINE", java.util.Map.of("player", pi.from()), "EN");
            if (!lines.isEmpty()) {
                ctx.act().whisper(pi.from(), lines.get(0));
            }
        }
    }

    private void onClanRally(BrainContext ctx, SelfView me, BotEvent.ClanRally cr, long now) {
        if (me.clanId() == 0) {
            return;
        }
        clanRallyAt = new Pos(cr.x(), cr.y(), cr.z());
        clanRallyUntil = now + 5 * 60_000L;
        ctx.note("clan rally " + cr.kind() + " from " + cr.from());
    }

    private void maybeClanRally(BrainContext ctx, WorldView w, SelfView me, long now) {
        if ((clanRallyAt != null) && (now < clanRallyUntil) && !me.dead()) {
            if (me.pos().distance2d(clanRallyAt) > 250) {
                ctx.act().moveTo(clanRallyAt);
            }
            return;
        }
        clanRallyAt = null;
        if (!me.clanLeader() || me.dead() || (now < nextClanRallyAt)) {
            return;
        }
        nextClanRallyAt = now + 20 * 60_000L + ctx.rnd().nextInt(20 * 60_000);
        if (ctx.rnd().nextDouble() > 0.2) {
            return;
        }
        final var lines = ctx.chat().lines("CLAN_RALLY", java.util.Map.of(), "EN");
        if (!lines.isEmpty()) {
            ctx.act().sayClan(lines.get(0));
            ctx.note("clan rally shout");
        }
    }

    private static boolean isLfpChannel(String channel) {
        return "SHOUT".equals(channel) || "TRADE".equals(channel) || "ALL".equals(channel);
    }

    /** Phase 6d/6e/6g: whisper from a live player — party-ask first (6d), else template reply (6e). Never first. */
    private void onWhisper(BrainContext ctx, WorldView w, SelfView me, BotEvent.Whisper wh, long now) {
        final PlayerView from = playerByName(w, wh.from());
        if ((from == null) || from.bot()) {
            return;
        }
        if (ctx.act().playerBlocksUs(wh.from())) {
            return; // stock BlockList /blockall
        }
        if (social && LfpParse.isPartyAsk(wh.text()) && !WhisperParse.isLeaveAlone(wh.text())) {
            if (from.socialBusy()) {
                return;
            }
            onPartyWhisper(ctx, w, me, from, wh, now);
            return;
        }
        onSocialWhisper(ctx, w, me, from, wh.text(), now, false);
    }

    /** Phase 6d: player whispered about party (often after the bot's LFP/LFM). */
    private void onPartyWhisper(BrainContext ctx, WorldView w, SelfView me, PlayerView from, BotEvent.Whisper wh, long now) {
        ctx.social().noteAgreed(from.objectId(), me.objectId(), now);
        final boolean leaving = leavingSocial(ctx);
        final boolean can = canInvitePlayer(w, me, from, leaving);
        final Map<String, String> vars = Map.of("player", from.name());
        if (can && (LfpParse.isPartyYes(wh.text()) || LfpParse.isPartyAsk(wh.text()))) {
            speak(ctx, "WHISPER", from.name(), "ASK_OK", vars, now + ctx.reactionDelay());
            scheduleInvite(from.name(), Math.max(speechDue(), now + ctx.reactionDelay()) + 400 + ctx.rnd().nextInt(900));
        } else {
            speak(ctx, "WHISPER", from.name(), "ASK_NO", vars, now + ctx.reactionDelay());
        }
    }

    /**
     * Phase 6e: template whisper reply. Defers in combat; cooldown and short dialog memory; ANNOYED on insult.
     * @param afterCombat true when flushing a deferred line (preface with BUSY_COMBAT)
     */
    private void onSocialWhisper(BrainContext ctx, WorldView w, SelfView me, PlayerView from, String text, long now, boolean afterCombat) {
        if (WhisperParse.isFlood(text)) {
            return;
        }
        // phase 6g: «отстань» / stop spam — ANNOYED + 24 h silence
        if (WhisperParse.isLeaveAlone(text)) {
            ctx.memory().note(from.objectId(), -20, org.l2start.bots.api.Memory.Tag.ANNOYED, true);
            whisperMuteUntil.put(from.objectId(), now + SocialBoard.ANNOYED_WHISPER_MS);
            return;
        }
        final Long muted = whisperMuteUntil.get(from.objectId());
        if ((muted != null) && (now < muted)) {
            return;
        }
        if (from.socialBusy()) {
            return; // player in combat / store / NPC dialog
        }
        if (!afterCombat && (inCombat() || me.attacking() || (fightId != 0))) {
            pendingWhFrom = from.name();
            pendingWhText = text;
            pendingWhBusy = now;
            return;
        }
        final Long next = whisperNextAt.get(from.objectId());
        if ((next != null) && (now < next)) {
            return;
        }
        final WhisperParse.Intent intent = WhisperParse.parse(text);
        rememberWhisper(from.objectId(), intent, text, now);
        if (intent == WhisperParse.Intent.ASK_PARTY && social) {
            onPartyWhisper(ctx, w, me, from, new BotEvent.Whisper(from.name(), text), now);
            return;
        }
        if ((intent == WhisperParse.Intent.ASK_CLAN) && me.clanLeader() && clanInvitePlayers && (from.clanId() == 0)) {
            final var mem = ctx.memory().of(from.objectId());
            final boolean academy = text.toLowerCase(java.util.Locale.ROOT).contains("academ") || text.toLowerCase(java.util.Locale.ROOT).contains("академ");
            final PledgeDecision.Answer a = PledgeDecision.decideAskIn(ctx.info(), mem, true, true, from.level(), ctx.rnd());
            if ((a == PledgeDecision.Answer.ACCEPT) && ctx.act().inviteClan(from.name(), academy ? PledgeDecision.ACADEMY : 0).ok()) {
                ctx.note("clan invite after whisper from " + from.name());
            } else if (ctx.social().tryWhisper(ctx.info().botId(), now)) {
                final var lines = ctx.chat().lines("CLAN_DECLINE", java.util.Map.of("player", from.name()), WhisperParse.langOf(text));
                if (!lines.isEmpty()) {
                    ctx.act().whisper(from.name(), lines.get(0));
                }
            }
            whisperNextAt.put(from.objectId(), now + WHISPER_COOLDOWN_MS);
            return;
        }
        if (intent == WhisperParse.Intent.INSULT) {
            ctx.memory().note(from.objectId(), -15, org.l2start.bots.api.Memory.Tag.ANNOYED, true);
            if (ctx.rnd().nextDouble() > (0.35 + (ctx.info().chattiness() * 0.4))) {
                whisperNextAt.put(from.objectId(), now + WHISPER_COOLDOWN_MS);
                return; // short or silent — no long fights
            }
        }
        if (intent == WhisperParse.Intent.UNKNOWN) {
            if (ctx.rnd().nextDouble() > ctx.info().chattiness()) {
                whisperNextAt.put(from.objectId(), now + WHISPER_COOLDOWN_MS);
                return;
            }
        }
        if (ctx.memory().of(from.objectId()).has(org.l2start.bots.api.Memory.Tag.ANNOYED)
            && (intent != WhisperParse.Intent.INSULT) && (ctx.rnd().nextDouble() < 0.7)) {
            return;
        }
        if (!ctx.social().tryWhisper(ctx.info().botId(), now)) {
            return; // per-bot whisper hour limit
        }
        final String lang = WhisperParse.langOf(text);
        String phrase = whisperPhrase(intent);
        if (phrase == null) {
            return;
        }
        final org.l2start.bots.api.Memory.Of mem = ctx.memory().of(from.objectId());
        // phase 6f: acquaintances sometimes recall shared party time (never whisper first)
        if (((intent == WhisperParse.Intent.GREET) || (intent == WhisperParse.Intent.HOW_ARE_YOU)) && (mem.partyMinutes() >= 10)
            && ((mem.affinity() > 20) || mem.has(org.l2start.bots.api.Memory.Tag.PARTY)) && (ctx.rnd().nextDouble() < 0.4)) {
            phrase = "MEMORY_PARTY";
        }
        // buff/res asks while in party with this player: act like a party command
        if ((from.objectId() == ptMateId) && (ptMateId != 0)) {
            if (intent == WhisperParse.Intent.ASK_BUFF && canBuff(me)) {
                ptBuffAsked = true;
                partyBuffed.keySet().removeIf(k -> k.endsWith(">" + ptMateId));
                partyTried = 0;
            } else if (intent == WhisperParse.Intent.ASK_RES) {
                if (skill(me, RESURRECTION) == null) {
                    speak(ctx, "WHISPER", from.name(), "NO_RES", whisperVars(ctx, me, from, lang), now + ctx.reactionDelay(), lang);
                    whisperNextAt.put(from.objectId(), now + WHISPER_COOLDOWN_MS);
                    return;
                }
                ptResAsked = true;
            }
        }
        long due = now + ctx.reactionDelay();
        if (afterCombat) {
            speak(ctx, "WHISPER", from.name(), "BUSY_COMBAT", whisperVars(ctx, me, from, lang), due, lang);
            due = Math.max(speechDue(), due) + 400;
        }
        speak(ctx, "WHISPER", from.name(), phrase, whisperVars(ctx, me, from, lang), due, lang);
        whisperNextAt.put(from.objectId(), now + WHISPER_COOLDOWN_MS);
    }

    private void flushPendingWhisper(BrainContext ctx, WorldView w, SelfView me, long now) {
        if ((pendingWhFrom == null) || inCombat() || me.attacking() || (fightId != 0)) {
            return;
        }
        if ((now - pendingWhBusy) > 120_000L) {
            pendingWhFrom = null;
            pendingWhText = null;
            return;
        }
        final String fromName = pendingWhFrom;
        final String text = pendingWhText;
        pendingWhFrom = null;
        pendingWhText = null;
        final PlayerView from = playerByName(w, fromName);
        if ((from == null) || from.bot()) {
            return;
        }
        onSocialWhisper(ctx, w, me, from, text, now, true);
    }

    private static String whisperPhrase(WhisperParse.Intent intent) {
        return switch (intent) {
            case GREET -> "GREET";
            case BYE -> "WHISPER_BYE";
            case THANKS -> "WHISPER_THANKS";
            case SORRY -> "WHISPER_SORRY";
            case HOW_ARE_YOU -> "HOW_ARE_YOU";
            case ASK_LEVEL -> "ASK_LEVEL";
            case ASK_CLASS -> "ASK_CLASS";
            case ASK_SPOT -> "ASK_SPOT";
            case WHERE_TO_HUNT -> "WHERE_TO_HUNT";
            case ASK_RES -> "ASK_RES";
            case ASK_BUFF -> "ASK_BUFF";
            case INSULT -> "INSULT";
            case UNKNOWN -> "WHISPER_HUH";
            case ASK_PARTY, ASK_CLAN -> null;
        };
    }

    private Map<String, String> whisperVars(BrainContext ctx, SelfView me, PlayerView from, String lang) {
        final Assignment order = ctx.assignment();
        String spot = (order == null) || (order.place() == null) || order.place().isBlank() ? "" : order.place();
        if (spot.isEmpty()) {
            spot = (lang != null && lang.toUpperCase(java.util.Locale.ROOT).startsWith("EN")) ? "around here" : "тут рядом";
        }
        final String useLang = lang == null ? ctx.info().lang() : lang;
        final int mins = ctx.memory().of(from.objectId()).partyMinutes();
        return Map.of(
            "player", from.name(),
            "level", Integer.toString(me.level()),
            "class", ClassLabels.name(me.classId(), useLang),
            "role", roleWord(me.classId(), useLang),
            "spot", spot,
            "minutes", Integer.toString(Math.max(0, mins)));
    }

    private void rememberWhisper(int charId, WhisperParse.Intent intent, String text, long now) {
        final WhisperDialog d = whisperDialog.computeIfAbsent(charId, k -> new WhisperDialog());
        if ((now - d.lastAt) > WHISPER_DIALOG_MS) {
            d.intents.clear();
            d.lines.clear();
        }
        d.lastAt = now;
        d.intents.addLast(intent);
        d.lines.addLast(text == null ? "" : text);
        while (d.intents.size() > 6) {
            d.intents.removeFirst();
            d.lines.removeFirst();
        }
    }

    /** Short dialog memory with one player (last 6 lines / intents, 15 min). */
    private static final class WhisperDialog {
        final java.util.ArrayDeque<WhisperParse.Intent> intents = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
        long lastAt;
    }

    /** Phase 6d: player's LFP/LFM in shout/trade/all — a fitting bot may whisper back. */
    private void onLfpChat(BrainContext ctx, WorldView w, SelfView me, BotEvent.Chat c, long now) {
        final LfpParse.Call call = LfpParse.parse(c.text());
        if (call == null) {
            return;
        }
        if ((ptMateId != 0) || leavingSocial(ctx) || me.dead()) {
            return;
        }
        if (!LfpParse.fits(call.role(), me.classId()) || !LfpParse.levelOk(me.level(), call.level())) {
            return;
        }
        final PlayerView from = playerByName(w, c.from());
        if ((from != null) && (from.socialBusy() || from.bot())) {
            return;
        }
        if ((from != null) && ctx.act().playerBlocksUs(from.name())) {
            return;
        }
        // phase 6f: acquaintances reply more readily; strangers often stay quiet
        final org.l2start.bots.api.Memory.Of mem = ctx.memory().of(c.fromId());
        final boolean known = (mem.affinity() > 30) || mem.has(org.l2start.bots.api.Memory.Tag.PARTY) || (mem.partyMinutes() >= 60);
        if (!known && (ctx.rnd().nextDouble() > (0.35 + (ctx.info().sociability() * 0.4)))) {
            return;
        }
        if (!ctx.social().tryReply(c.fromId(), now, lfpReplies)) {
            return;
        }
        ctx.social().noteAgreed(c.fromId(), me.objectId(), now);
        speak(ctx, "WHISPER", c.from(), "LFP_REPLY", Map.of("player", c.from(), "level", Integer.toString(me.level()), "role", roleWord(me.classId(), ctx.info().lang())),
            now + ctx.reactionDelay());
    }

    /** Phase 6d: player invited a bot that is already in a bot-only party. */
    private void onPartyRedirect(BrainContext ctx, BotEvent.PartyRedirect pr, long now) {
        if (pr.whisper()) {
            speak(ctx, "WHISPER", pr.from(), "PAIR_REDIRECT", Map.of("player", pr.from()), now + ctx.reactionDelay());
        }
        if (pr.invite()) {
            final long base = pr.whisper() ? Math.max(speechDue(), now + ctx.reactionDelay()) : now + ctx.reactionDelay();
            scheduleInvite(pr.from(), base + 400 + ctx.rnd().nextInt(1200));
        }
    }

    private long speechDue() {
        return speech.isEmpty() ? 0 : speech.get(speech.size() - 1).due();
    }

    private void scheduleInvite(String name, long at) {
        askInviteTo = name;
        askInviteAt = at;
    }

    private void flushInvite(BrainContext ctx, long now) {
        if ((askInviteTo == null) || (now < askInviteAt)) {
            return;
        }
        final String to = askInviteTo;
        askInviteTo = null;
        askInviteAt = 0;
        final ActionResult r = ctx.act().inviteParty(to);
        if (!r.ok()) {
            ctx.note("did not invite " + to + " (" + r + ")");
        }
    }

    /** Phase 6d: in town near a live player, occasionally shout LFP/LFM. */
    private void maybeShoutLfp(BrainContext ctx, WorldView w, SelfView me, long now) {
        if (!social || (ptMateId != 0) || me.dead() || leavingSocial(ctx)) {
            return;
        }
        if (!inTownForLfp(ctx, me)) {
            return;
        }
        if (!hasLivePlayer(w)) {
            return;
        }
        if (now < lfpNextTry) {
            return;
        }
        final double noise = Math.max(0.25, socialNoise);
        lfpNextTry = now + (long) ((180_000 + ctx.rnd().nextInt(300_000)) / noise); // 3..8 min / noise
        final double p = Math.min(0.95, 0.15 * ctx.info().sociability() * noise);
        if (ctx.rnd().nextDouble() >= p) {
            return;
        }
        if ((lfpShoutAt > 0) && ((now - lfpShoutAt) < SocialBoard.BOT_SHOUT_COOLDOWN_MS)) {
            return;
        }
        if (!ctx.social().tryShout(now)) {
            return;
        }
        final boolean botParty = !w.party().isEmpty() && allBots(w.party());
        final String intent = botParty && (w.partyLeader() || (w.party().size() <= 1)) ? "LFM" : "LFP";
        final Assignment order = ctx.assignment();
        final String spot = (order == null) || (order.place() == null) ? "" : order.place();
        final String channel = (me.level() >= 20) && ctx.rnd().nextBoolean() ? "TRADE" : "SHOUT";
        speak(ctx, channel, null, intent,
            Map.of("level", Integer.toString(me.level()), "role", roleWord(me.classId(), ctx.info().lang()), "spot", spot, "player", ""), now);
        lfpShoutAt = now;
        ctx.note("lfp " + intent + " via " + channel);
    }

    private boolean inTownForLfp(BrainContext ctx, SelfView me) {
        if (me.inPeaceZone() || (phase == Phase.TOWN_IDLE)) {
            return true;
        }
        final Assignment a = ctx.assignment();
        return (a != null) && (a.kind() == Assignment.Kind.TOWN);
    }

    private static boolean hasLivePlayer(WorldView w) {
        for (PlayerView p : w.players()) {
            if (!p.bot()) {
                return true;
            }
        }
        return false;
    }

    private static boolean allBots(List<PartyMember> party) {
        for (PartyMember m : party) {
            if (!m.bot()) {
                return false;
            }
        }
        return true;
    }

    private static boolean leavingSocial(BrainContext ctx) {
        final Assignment a = ctx.assignment();
        return (a != null) && a.passing();
    }

    /** May invite a live player into its party (alone or as leader of a bot-only party). */
    private boolean canInvitePlayer(WorldView w, SelfView me, PlayerView from, boolean leaving) {
        if ((ptMateId != 0) || leaving || me.dead() || (from == null)) {
            return false;
        }
        if (!LfpParse.levelOk(me.level(), from.level())) {
            return false;
        }
        final List<PartyMember> pm = w.party();
        if (pm.isEmpty()) {
            return true;
        }
        if (!w.partyLeader() || (pm.size() >= 9) || !allBots(pm)) {
            return false;
        }
        return true;
    }

    private static PlayerView playerByName(WorldView w, String name) {
        if (name == null) {
            return null;
        }
        for (PlayerView p : w.players()) {
            if (name.equalsIgnoreCase(p.name())) {
                return p;
            }
        }
        return null;
    }

    /** Role word for LFP phrases: RU хил/баф/дд, EN heal/buff/dd. */
    static String roleWord(int classId, String lang) {
        final boolean ru = (lang != null) && lang.toUpperCase(java.util.Locale.ROOT).startsWith("RU");
        return switch (LfpParse.roleOf(classId)) {
            case HEAL -> ru ? "хил" : "heal";
            case BUFF -> ru ? "баф" : "buff";
            default -> ru ? "дд" : "dd";
        };
    }

    private void onInvite(BrainContext ctx, WorldView w, SelfView me, BotEvent.PartyInvite inv, long now) {
        ptInvites++;
        if (!w.party().isEmpty() || (ptInvite != null)) {
            return; // the stock request already said "member of another party", or one is pending
        }
        final Assignment order = ctx.assignment();
        final PartyDecision.Doing doing = me.dead() ? PartyDecision.Doing.BUSY
            : (order != null) && order.passing() ? PartyDecision.Doing.LEAVING
            : ((order == null) && (phase == Phase.TOWN_IDLE)) || ((order != null) && (order.kind() == Assignment.Kind.TOWN)) ? PartyDecision.Doing.TOWN
            : PartyDecision.Doing.HUNTING;
        final long[] dec = ptDeclined.getOrDefault(inv.fromId(), new long[] { 0, 0 });
        ptAnswer = PartyDecision.decide(ctx.info(), me.level(), inv, doing, ctx.memory().of(inv.fromId()), (int) dec[0], now - dec[1], support(me.classId()),
            ctx.social().isAgreed(inv.fromId(), me.objectId(), now), ctx.rnd());
        ptInvite = inv;
        // a person reads the dialog and clicks: 2-8 s by reaction (the stock invitation waits 15 s)
        ptAnswerAt = now + Math.min(9000, 1500 + ctx.info().reactionMs() * 2L + ctx.rnd().nextInt(5000));
        ctx.note("invited by " + inv.from() + " (lv " + inv.fromLevel() + "): " + ptAnswer + " in " + ((ptAnswerAt - now) / 1000) + " s");
    }

    /** Answers a pending invitation when its time comes. */
    private void answerInvite(BrainContext ctx, SelfView me, long now) {
        if ((ptInvite == null) || (now < ptAnswerAt)) {
            return;
        }
        final BotEvent.PartyInvite inv = ptInvite;
        final PartyDecision.Answer a = ptAnswer;
        ptInvite = null;
        ptAnswer = null;
        if (a == PartyDecision.Answer.IGNORE) {
            ctx.note("let the invitation of " + inv.from() + " expire");
            noteDeclined(inv.fromId(), now);
            ctx.memory().note(inv.fromId(), 0, org.l2start.bots.api.Memory.Tag.DECLINED, true);
            return;
        }
        final ActionResult r = ctx.act().answerParty(a.yes());
        if (!r.ok()) {
            ctx.note("invitation of " + inv.from() + " gone (" + r + ")");
            return;
        }
        if (a.yes()) {
            ctx.memory().note(inv.fromId(), 1, null, true);
            ptDeclined.remove(inv.fromId());
            ctx.social().noteInviteOk(now);
            if (ctx.rnd().nextDouble() < (0.6 + (ctx.info().chattiness() * 0.4))) {
                speak(ctx, "PARTY", null, a.intent, Map.of("player", inv.from()), now + 500);
            }
            return;
        }
        noteDeclined(inv.fromId(), now);
        ctx.memory().note(inv.fromId(), 0, org.l2start.bots.api.Memory.Tag.DECLINED, true);
        ctx.social().noteInviteNo(now);
        if ((a.intent != null) && (ctx.rnd().nextDouble() < (0.35 + (ctx.info().chattiness() * 0.4)))) {
            speak(ctx, "WHISPER", inv.from(), a.intent, Map.of("player", inv.from()), now); // an answer to his invitation, never first
        }
    }

    private void noteDeclined(int charId, long now) {
        final long[] d = ptDeclined.computeIfAbsent(charId, k -> new long[] { 0, 0 });
        if ((now - d[1]) > (PartyDecision.ASK_AGAIN_MS * 3)) {
            d[0] = 0;
        }
        d[0]++;
        d[1] = now;
    }

    /** Queue a phrase of this intent: it is "typed" first (Humanizer), a typo fix comes right after. */
    private void speak(BrainContext ctx, String channel, String to, String intent, Map<String, String> vars, long from) {
        speak(ctx, channel, to, intent, vars, from, null);
    }

    /** Same as {@link #speak} with an optional phrase language (phase 6e whisper replies). */
    private void speak(BrainContext ctx, String channel, String to, String intent, Map<String, String> vars, long from, String lang) {
        if ("PARTY".equals(channel) && !partyChatOk(ctx, intent)) {
            return;
        }
        if ("WHISPER".equals(channel) && (to != null) && ctx.act().playerBlocksUs(to)) {
            return;
        }
        long due = Math.max(from, speech.isEmpty() ? from : speech.get(speech.size() - 1).due());
        boolean said = false;
        for (String line : ctx.chat().lines(intent, vars, lang)) {
            due += line.startsWith("*") ? 600 + ctx.rnd().nextInt(900) : ctx.chat().typingMs(line);
            speech.add(new Speech(channel, to, line, due));
            said = true;
        }
        if (said && isPartyEmote(intent)) {
            ctx.social().noteEmote(ctx.now());
        }
    }

    /**
     * Phase 6g: while the mate is busy (combat / store / NPC), only urgent party lines («мобы» / «рес нет»).
     * When free, command replies always go; spontaneous reactions are scaled by {@link #socialNoise}.
     */
    private boolean partyChatOk(BrainContext ctx, String intent) {
        if (isUrgentParty(intent)) {
            return true;
        }
        if (ptMateId != 0) {
            final PlayerView mate = playerByName(ctx.world(), ptMate);
            if ((mate != null) && mate.socialBusy()) {
                return false;
            }
        }
        if (!isPartyEmote(intent)) {
            return true; // answers to party commands
        }
        // quiet ~0.55, normal ~0.7, lively ~1.0
        return ctx.rnd().nextDouble() <= Math.min(1.0, 0.4 + (0.3 * socialNoise));
    }

    /** «рес нет» and «мобы» (SORRY_KS) — still said when the mate is in combat / store / NPC dialog. */
    private static boolean isUrgentParty(String intent) {
        return "NO_RES".equals(intent) || "SORRY_KS".equals(intent);
    }

    private static boolean isPartyEmote(String intent) {
        return "GRATS".equals(intent) || "THANKS_BUFF".equals(intent) || "THANKS_RES".equals(intent) || "SORRY_KS".equals(intent);
    }

    private void flushSpeech(BrainContext ctx, long now) {
        while (!speech.isEmpty() && (speech.get(0).due() <= now)) {
            final Speech s = speech.remove(0);
            final ActionResult r = switch (s.channel()) {
                case "WHISPER" -> ctx.act().whisper(s.to(), s.text());
                case "SHOUT" -> ctx.act().sayShout(s.text());
                case "TRADE" -> ctx.act().sayTrade(s.text());
                default -> ctx.act().sayParty(s.text());
            };
            if (!r.ok()) {
                ctx.note("did not say '" + s.text() + "' (" + r + ")");
            }
        }
    }

    /** When something social is due next (0 = nothing pending). */
    private long socialDue() {
        long due = ptInvite == null ? 0 : ptAnswerAt;
        if (!speech.isEmpty()) {
            due = due == 0 ? speech.get(0).due() : Math.min(due, speech.get(0).due());
        }
        if (ptLeaveAt > 0) {
            due = due == 0 ? ptLeaveAt : Math.min(due, ptLeaveAt);
        }
        if ((askInviteTo != null) && (askInviteAt > 0)) {
            due = due == 0 ? askInviteAt : Math.min(due, askInviteAt);
        }
        if (social && (ptMateId == 0) && (lfpNextTry > 0)) {
            due = due == 0 ? lfpNextTry : Math.min(due, lfpNextTry);
        }
        return due;
    }

    private void onPartyCommand(BrainContext ctx, WorldView w, SelfView me, String text, long now) {
        final PartyCommands.Command c = PartyCommands.parse(text);
        if (c == PartyCommands.Command.NONE) {
            if (text.toLowerCase(java.util.Locale.ROOT).contains(me.name().toLowerCase(java.util.Locale.ROOT)) && (ctx.rnd().nextDouble() < 0.4)) {
                speak(ctx, "PARTY", null, "HUH", Map.of(), now + ctx.reactionDelay());
            }
            return;
        }
        ptCommands++;
        ctx.note("party command from " + ptMate + ": " + c);
        String reply = "ACK";
        switch (c) {
            case STOP -> {
                ptMode = PartyMode.STOP;
                endFight();
                ctx.act().stop();
            }
            case FOLLOW -> ptMode = PartyMode.FOLLOW;
            case ASSIST -> ptMode = PartyMode.ASSIST;
            case FREE_HUNT -> ptMode = PartyMode.FREE;
            case SIT -> ptMode = PartyMode.SIT;
            case WAIT -> {
                ptAgreedWait = true;
                reply = "WAIT";
            }
            case BUFF -> {
                if (!canBuff(me)) {
                    reply = "HUH";
                } else {
                    ptBuffAsked = true;
                    partyBuffed.keySet().removeIf(k -> k.endsWith(">" + ptMateId));
                    partyTried = 0;
                    reply = null; // "buffed" when it is done
                }
            }
            case HEAL -> {
                if (healSkill(me) == null) {
                    reply = "HUH";
                } else {
                    ptHealAsked = true;
                }
            }
            case RES -> {
                if (skill(me, RESURRECTION) == null) {
                    reply = "NO_RES";
                } else {
                    ptResAsked = true;
                }
            }
            case GIVE_LEAD -> {
                if (w.partyLeader() && ctx.act().giveLeader(ptMateId).ok()) {
                    reply = "LEADER";
                } else {
                    reply = null;
                }
            }
            case TAKE_LEAD -> reply = "ACK"; // only the leader can hand it over; the bot keeps following its mate anyway
            default -> {
            }
        }
        if ((reply != null) && (("ACK".equals(reply) && (ctx.rnd().nextDouble() < (0.5 + (ctx.info().chattiness() * 0.5)))) || !"ACK".equals(reply))) {
            speak(ctx, "PARTY", null, reply, Map.of("player", ptMate), now + ctx.reactionDelay());
        }
    }

    private static boolean canBuff(SelfView me) {
        for (int id : PartyBuffs.wanted(me.classId(), me.classId(), false)) {
            if (skill(me, id) != null) {
                return true;
            }
        }
        if (PartyBuffs.isBladedancer(me.classId()) || PartyBuffs.isSwordsinger(me.classId())) {
            return true; // dances/songs; FakeWorld may not list them until added
        }
        return false;
    }

    private static SkillView healSkill(SelfView me) {
        for (int id : HEALS) {
            final SkillView s = skill(me, id);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private void startCompanion(BrainContext ctx, PartyMember mate, long now) {
        ptMateId = mate.objectId();
        ptMate = mate.name();
        ptSince = now;
        ptMinuteAt = now + 60_000;
        // a session of 40-100 min, longer for patient personas
        ptSessionEnd = now + ((40 + Math.round(ctx.info().patience() * 40) + ctx.rnd().nextInt(20)) * 60_000L);
        ptWarned = false;
        ptLeaveAt = 0;
        ptMatePos = mate.pos();
        ptMateStillSince = now;
        ptFarSince = 0;
        ptWaiting = false;
        ptAgreedWait = false;
        ptCatchingUp = false;
        ptGkTriedAt = null;
        ptMode = PartyMode.ASSIST;
        ptBuffAsked = false;
        ptHealAsked = false;
        ptResAsked = false;
        ptDeaths = 0;
        ptDeadInParty = false;
        ptParties++;
        ptMateLevel = mate.level();
        ptSeenBuffs = activeBuffIds(ctx.world().self());
        partyReactAt = 0;
        endFight();
        errand = null;
        enter(ctx, Phase.LEVEL, "party with " + mate.name());
    }

    /**
     * Phase 6e: party-chat only — GRATS on mate level-up, thanks for a new buff, rare KS sorry.
     * Cooldown 2–3 min; never whispers first.
     */
    private void partyReact(BrainContext ctx, WorldView w, SelfView me, PartyMember mate, long now) {
        if (mate.level() > ptMateLevel) {
            ptMateLevel = mate.level();
            partySay(ctx, "GRATS", Map.of("player", mate.name()), now + ctx.reactionDelay());
        } else {
            ptMateLevel = mate.level();
        }
        final java.util.Set<Integer> buffs = activeBuffIds(me);
        if (!buffs.equals(ptSeenBuffs)) {
            boolean gained = false;
            for (Integer id : buffs) {
                if (!ptSeenBuffs.contains(id)) {
                    gained = true;
                    break;
                }
            }
            // startCompanion already baselined ptSeenBuffs; only thank when a new buff appears after that
            if (gained && (mate.distance() < 1200) && !mate.dead()) {
                ctx.memory().note(ptMateId, 2, org.l2start.bots.api.Memory.Tag.BUFFED_ME, false);
                partySay(ctx, "THANKS_BUFF", Map.of("player", mate.name()), now + ctx.reactionDelay());
            }
        }
        ptSeenBuffs = buffs;
    }

    private void maybeSorryKs(BrainContext ctx, PartyMember mate, int deadMobId, long now) {
        if ((ptMode != PartyMode.FREE) || (deadMobId == 0) || (mate.targetId() != deadMobId)) {
            return;
        }
        if (ctx.rnd().nextDouble() > 0.22) {
            return;
        }
        partySay(ctx, "SORRY_KS", Map.of("player", mate.name()), now + ctx.reactionDelay());
    }

    /** Party chat with the 2–3 min reaction cooldown (phase 6e). */
    private void partySay(BrainContext ctx, String intent, Map<String, String> vars, long from) {
        final long now = from;
        if ((partyReactAt > 0) && (now < partyReactAt)) {
            return;
        }
        speak(ctx, "PARTY", null, intent, vars, from);
        partyReactAt = now + PARTY_REACT_COOLDOWN_MS + ctx.rnd().nextInt(60_000);
    }

    private static java.util.Set<Integer> activeBuffIds(SelfView me) {
        final java.util.HashSet<Integer> ids = new java.util.HashSet<>();
        for (SkillView s : me.skills()) {
            if ((s.kind() == SkillView.Kind.BUFF) && (s.buffLeft() > 0)) {
                ids.add(s.id());
            }
        }
        return ids.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(ids);
    }

    /** The party is over (left, kicked, disbanded): it remembers how it went and is an ordinary bot again. */
    private void endCompanion(BrainContext ctx, long now, String why) {
        if (ptMateId == 0) {
            return;
        }
        final int minutes = (int) ((now - ptSince) / 60_000);
        ctx.memory().note(ptMateId, Math.min(15, 2 + (minutes / 5)) - (ptDeaths * 2), org.l2start.bots.api.Memory.Tag.PARTY, false);
        ctx.memory().forget(ptMateId, org.l2start.bots.api.Memory.Tag.DECLINED);
        ctx.note("party with " + ptMate + " over after " + minutes + " min: " + why);
        ptMateId = 0;
        ptMate = "";
        ptLeaveAt = 0;
        ptMode = PartyMode.ASSIST;
        speech.removeIf(s -> "PARTY".equals(s.channel()));
        endFight();
        if (ctx.assignment() == null) {
            idleUntil = now + 30_000;
        }
    }

    /** Say goodbye now, leave a few seconds later (after typing it). */
    private void beginLeave(BrainContext ctx, String intent, long now, String why) {
        if (ptLeaveAt > 0) {
            return;
        }
        ctx.note("leaves the party of " + ptMate + ": " + why);
        speak(ctx, "PARTY", null, intent, Map.of("player", ptMate), now + ctx.reactionDelay());
        final long said = speech.isEmpty() ? now : speech.get(speech.size() - 1).due();
        ptLeaveAt = Math.max(now + 2000, said + 1500 + ctx.rnd().nextInt(2500));
    }

    /**
     * Phase 6c: play with the real player of its party: follow him, fight his monster, heal, buff and raise him, obey
     * his commands, leave politely. @return ms to the next tick, 0 = not in such a party (the usual behaviour runs)
     */
    private long companion(BrainContext ctx, WorldView w, SelfView me, long now, NpcView attacker) {
        final PartyMember mate = mate(w);
        if (mate == null) {
            endCompanion(ctx, now, ptLeaveAt > 0 ? "left" : "the party broke up");
            return 0;
        }
        if (mate.objectId() != ptMateId) {
            if (ptMateId != 0) {
                endCompanion(ctx, now, "another player");
            }
            startCompanion(ctx, mate, now);
        }
        partyReact(ctx, w, me, mate, now);
        if ((phase == Phase.DEAD) || ptDeadInParty) {
            ptDeadInParty = false;
            enter(ctx, Phase.LEVEL, "party with " + ptMate);
            if (!me.inPeaceZone() && (mate.distance() < 2000)) {
                // raised where it fell: by its mate (or a healer of the party)
                ctx.memory().note(ptMateId, 8, org.l2start.bots.api.Memory.Tag.REZZED_ME, false);
                speak(ctx, "PARTY", null, "THANKS_RES", Map.of("player", ptMate), now + ctx.reactionDelay());
            }
        }
        if (now >= ptMinuteAt) {
            ptMinuteAt += 60_000;
            ctx.memory().partyMinutes(ptMateId, 1);
        }
        if (ptLeaveAt > 0) {
            if (now >= ptLeaveAt) {
                ctx.act().leaveParty();
                endCompanion(ctx, now, "said goodbye");
                return ctx.reactionDelay();
            }
            detail = "says goodbye to " + ptMate;
            return Math.max(300, ptLeaveAt - now);
        }
        // reasons to leave
        if (Math.abs(me.level() - mate.level()) >= PartyDecision.GAP_NO) {
            beginLeave(ctx, "BYE", now, "level gap grew");
        } else if (ptDeaths >= PARTY_DEATHS_MAX) {
            beginLeave(ctx, "BYE", now, "died " + ptDeaths + " times");
        } else if (!ptWarned && (now >= (ptSessionEnd - 60_000 - ctx.rnd().nextInt(120_000)))) {
            ptWarned = true;
            speak(ctx, "PARTY", null, "WARN_LEAVE", Map.of(), now + ctx.reactionDelay());
        } else if (ptWarned && (now >= ptSessionEnd) && (fightId == 0)) {
            beginLeave(ctx, "BYE", now, "end of its session");
        }
        if ((ptMatePos == null) || (ptMatePos.distance2d(mate.pos()) > 40) || mate.dead()) {
            ptMatePos = mate.pos();
            ptMateStillSince = now;
        } else if ((now - ptMateStillSince) > MATE_AFK_MS) {
            beginLeave(ctx, "AFK_BYE", now, ptMate + " AFK");
        }
        if (ptLeaveAt > 0) {
            return 500;
        }
        // the mate is gone (teleported, escaped, in town after a death): catch up via GK (KT12 F1)
        if (mate.distance() > MATE_FAR) {
            return catchUp(ctx, w, me, mate, now, attacker);
        }
        ptFarSince = 0;
        ptWaiting = false;
        ptCatchingUp = false;
        ptGkTriedAt = null;
        // KT12 F5: spoiler Sweep / Spoil while playing with a real player (companion path skips the solo sweep tick)
        final long swept = sweep(ctx, w, me, now);
        if (swept > 0) {
            return swept;
        }
        final long spoiled = partySpoil(ctx, w, me, mate, now);
        if (spoiled > 0) {
            return spoiled;
        }
        // heal, raise and buff the party (the mate included)
        if (ptHealAsked && !mate.dead() && (mate.hpPct() < 95) && (mate.distance() < 900) && !me.casting()) {
            final SkillView h = healSkill(me);
            ptHealAsked = false;
            if ((h != null) && usable(me, h) && ctx.act().cast(h.id(), mate.objectId()).ok()) {
                partyHeals++;
                detail = "heals " + ptMate;
                return 2500;
            }
        }
        if (me.sitting() && (attacker != null)) {
            ctx.act().stand();
            return 800;
        }
        final long pc = partyCare(ctx, w, me, now, attacker);
        if (pc > 0) {
            if (ptBuffAsked && detail.equals("buffs " + ptMate)) {
                ptBuffAsked = false;
                speak(ctx, "PARTY", null, "BUFFED", Map.of("player", ptMate), now + 2500);
            } else if (detail.equals("resurrects " + ptMate)) {
                ptResAsked = false;
                ctx.memory().note(ptMateId, 5, org.l2start.bots.api.Memory.Tag.I_REZZED, false);
                speak(ctx, "PARTY", null, "RESSED", Map.of("player", ptMate), now + 800);
            }
            return pc;
        }
        if (ptBuffAsked && (now > (partyTried + 6000))) {
            ptBuffAsked = false; // nothing left to put on: everything is on already
            speak(ctx, "PARTY", null, "BUFFED", Map.of("player", ptMate), now + ctx.reactionDelay());
        }
        if (mate.dead() && ptResAsked && (skill(me, RESURRECTION) == null)) {
            ptResAsked = false;
            speak(ctx, "PARTY", null, "NO_RES", Map.of(), now + ctx.reactionDelay());
        }
        final long peeled = peel(ctx, w, me, now);
        if (peeled > 0) {
            return peeled;
        }
        if (fightId != 0) {
            if (ptMode == PartyMode.STOP) {
                endFight();
                ctx.act().stop();
            } else {
                final int killing = fightId;
                final boolean mateOnIt = mate.targetId() == killing;
                final long d = fight(ctx, w, me, now);
                if ((d == 0) && (fightId == 0) && mateOnIt) {
                    maybeSorryKs(ctx, mate, killing, now);
                }
                if (d > 0) {
                    return d;
                }
            }
        }
        if ((attacker != null) && ((ptMode != PartyMode.STOP) || (me.hpPct() < 60))) {
            standIfSitting(ctx, me);
            return startFight(ctx, me, attacker, now);
        }
        if ((ptMode != PartyMode.STOP) && (ptMode != PartyMode.SIT)) {
            final long d = defendParty(ctx, w, me, now); // a monster on the mate (or a partner) is our fight
            if (d > 0) {
                return d;
            }
            if ((ptMode == PartyMode.ASSIST) || (ptMode == PartyMode.FREE)) {
                final NpcView mob = mate.targetId() == 0 ? null : w.npc(mate.targetId());
                if ((mob != null) && !mob.dead() && mob.monster() && mob.attackable() && (mob.distance() < 1500)
                    && ((mob.hpPct() < 100) || (mob.targetId() == mate.objectId()) || mob.attackingNow())) {
                    errand = null;
                    standIfSitting(ctx, me);
                    detail = "assists " + ptMate;
                    return startFight(ctx, me, mob, now, true);
                }
            }
            if (ptMode == PartyMode.FREE) {
                final NpcView mob = freeMob(w, me, mate);
                if (mob != null) {
                    standIfSitting(ctx, me);
                    detail = "hunts by " + ptMate;
                    return startFight(ctx, me, mob, now, true);
                }
            }
        }
        // rest: when its mate sits, when told to, or when it needs it (Living Worlds PhantomPartyDowntime)
        final boolean restNow = (ptMode == PartyMode.SIT) || (mate.sitting() && (mate.distance() < 600)) || needsRest(me);
        if (restNow && (aggroNear(w, me, AGGRO_NEAR) == null) && (mate.distance() < 900)) {
            if (!me.sitting()) {
                if (me.moving() || me.attacking() || me.casting()) {
                    ctx.act().stop();
                    return 800;
                }
                ctx.act().sit();
            }
            detail = "rests by " + ptMate;
            if ((ptMode == PartyMode.SIT) && !mate.sitting() && (mate.distance() > 300)) {
                ptMode = PartyMode.ASSIST; // the mate walked on: so do we
            }
            return 2000;
        }
        if (me.sitting()) {
            ctx.act().stand();
            return 1000;
        }
        if (ptMode != PartyMode.STOP) {
            final long buffed = selfBuff(ctx, me, now);
            if (buffed > 0) {
                return buffed;
            }
        }
        if (ptMode == PartyMode.STOP) {
            detail = "holds by " + ptMate;
            return 1500;
        }
        // its place in the formation: melee next to the mate, archers, mages and healers a little behind
        final boolean back = me.mage() || me.bow() || support(me.classId());
        final int keep = back ? 260 + (int) (botId % 140) : 110 + (int) (botId % 90);
        if (mate.distance() > (keep + 160)) {
            detail = "follows " + ptMate;
            ctx.act().setWalking(false);
            final Pos spot = mate.pos().toward(me.pos(), keep);
            return Math.min(1200, walk(ctx, w, me, spot, now));
        }
        detail = "by " + ptMate + (ptMode == PartyMode.FREE ? " (free hunt)" : "");
        return 1000 + ctx.rnd().nextInt(600);
    }

    /** Free hunt by the mate: the nearest fitting monster within 900 of him that nobody else fights. */
    private NpcView freeMob(WorldView w, SelfView me, PartyMember mate) {
        NpcView best = null;
        for (NpcView n : w.npcs()) {
            if (!n.monster() || n.dead() || !n.attackable() || n.engagedByOther() || (n.pos().distance2d(mate.pos()) > 900) || (n.level() > (me.level() + 5))
                || (n.level() < (me.level() - 9)) || blacklist.getOrDefault(n.objectId(), 0L) > ctxNow) {
                continue;
            }
            if ((best == null) || (n.distance() < best.distance())) {
                best = n;
            }
        }
        return best;
    }

    private long ctxNow;

    /**
     * Mate is farther than {@link #MATE_FAR}: walk to the nearest gatekeeper, take stock TeleporterData hops toward
     * him, then run. Timeout {@link #MATE_WAIT_MS} → goodbye. Never air-teleports to the player.
     */
    private long catchUp(BrainContext ctx, WorldView w, SelfView me, PartyMember mate, long now, NpcView attacker) {
        if (ptFarSince == 0) {
            ptFarSince = now;
        }
        if ((now - ptFarSince) > MATE_WAIT_MS) {
            beginLeave(ctx, "BYE", now, "could not catch up in 10 min");
            return 500;
        }
        if (!ptCatchingUp) {
            ptCatchingUp = true;
            ptWaiting = true; // "жди" / patience still means we try; phrase is WAIT
            speak(ctx, "PARTY", null, "WAIT", Map.of(), now + ctx.reactionDelay());
            ctx.note("catches up to " + ptMate + " via gatekeeper (up to 10 min)");
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
        standIfSitting(ctx, me);
        final Pos gk = ctx.act().nearestGk();
        final double meToMate = me.pos().distance2d(mate.pos());
        if (gk != null) {
            final double dGk = me.pos().distance2d(gk);
            // walk to the GK when it is not farther from the mate than we are (hop can help)
            final boolean towardMate = gk.distance2d(mate.pos()) <= (meToMate + 200);
            if ((dGk > 150) && towardMate) {
                ptGkTriedAt = null;
                detail = "catch-up to gk";
                return walk(ctx, w, me, gk, now);
            }
            if (dGk <= 150) {
                // hop already asked here with no move: same town — run to the mate
                if ((ptGkTriedAt != null) && (ptGkTriedAt.distance2d(me.pos()) < 80)) {
                    detail = "catch-up run";
                    return walk(ctx, w, me, mate.pos(), now);
                }
                ptGkTriedAt = me.pos();
                final org.l2start.bots.api.ActionResult r = ctx.act().teleportViaGk(mate.pos());
                detail = "catch-up teleport";
                if (r == org.l2start.bots.api.ActionResult.NOT_REACHABLE) {
                    return walk(ctx, w, me, gk, now);
                }
                return 2500;
            }
        }
        ptGkTriedAt = null;
        detail = "catch-up run";
        return walk(ctx, w, me, mate.pos(), now);
    }

    /** For //bots info. */
    private String companionDetails(long now) {
        if (ptMateId == 0) {
            return ptParties == 0 ? "" : "\nParties with players: " + ptParties + ", invitations " + ptInvites;
        }
        return "\nParty with " + ptMate + " for " + ((now - ptSince) / 60_000) + " min, mode " + ptMode + ", commands " + ptCommands + ", deaths " + ptDeaths
            + (ptCatchingUp ? ", catching up" : ptWaiting ? ", waiting" : "") + (ptWarned ? ", leaving soon" : "");
    }

    // ------------------------------------------------------------------ roles (4c, second part): summons, sweep, heals, back

    /** Config Summons of bots-brain-basic: summoners call and command their servitor. */
    static volatile boolean summons = true;
    /** A summoner whose servitor holds the monster casts only above this MP %; below it keeps MP for Servitor Heal. */
    static final double SUMMONER_NUKE_MP = 60;
    static final int SERVITOR_HEAL = 1127;
    static final int DASH = 4;
    static final int SPOIL = 254;
    static final int SWEEPER = 42;
    /** The body of a spoiled monster waits this long for Sweeper. */
    static final long SWEEP_MS = 6000;
    /** Scavenger / Bounty Hunter / Warsmith lines (KT12 F5 party spoil). */
    static final java.util.Set<Integer> SPOILER_CLASSES = java.util.Set.of(54, 55, 56, 57, 117, 118);

    static boolean spoiler(int classId) {
        return SPOILER_CLASSES.contains(classId);
    }
    /** Self heals, best first: Major Heal, Greater Battle Heal, Greater Heal, Battle Heal, Heal. */
    static final int[] HEALS = { 1401, 1218, 1217, 1015, 1011 };
    /** Out of a fight a healer heals itself below this HP % instead of sitting down. */
    static final double SELF_HEAL_HP = 70;
    private int sweepId;
    private long sweepUntil;
    private int petOrderedOn;
    private long petOrderedAt;
    private int sneakFor;
    private long sneakUntil;
    private Pos sneakTo;
    private int summoned;
    private int sweeps;
    private int heals;
    private int sneaks;

    /** Servitors called (tests, //bots info). */
    public int summoned() {
        return summoned;
    }

    /** Spoiled bodies swept. */
    public int sweeps() {
        return sweeps;
    }

    /** Heals cast out of a fight (itself and the summon). */
    public int heals() {
        return heals;
    }

    /** Times it walked round to a monster's back. */
    public int sneaks() {
        return sneaks;
    }

    private static SkillView skill(SelfView me, int id) {
        for (SkillView s : me.skills()) {
            if (s.id() == id) {
                return s;
            }
        }
        return null;
    }

    private static boolean usable(SelfView me, SkillView s) {
        return (s != null) && s.ready() && (s.mpCost() <= me.mp());
    }

    /**
     * Out of a fight: heal itself (a healer heals instead of sitting down while MP lasts), heal the summon, call the
     * servitor when there is none (on the hunting ground, crystals at hand).
     * @return the delay, 0 = nothing to do
     */
    private long care(BrainContext ctx, WorldView w, SelfView me, long now, boolean onGround) {
        if (me.casting() || me.sitting() || me.inPeaceZone()) {
            return 0;
        }
        if ((me.hpPct() < SELF_HEAL_HP) && (me.mpPct() >= 30) && (buffTried.getOrDefault(-1, 0L) <= now)) {
            for (int id : HEALS) {
                final SkillView s = skill(me, id);
                if (usable(me, s)) {
                    final ActionResult r = ctx.act().cast(id, me.objectId());
                    buffTried.put(-1, now + (r.ok() ? 2500 : 10_000));
                    if (r.ok()) {
                        heals++;
                        detail = "heals itself";
                        return 2500;
                    }
                    break;
                }
            }
        }
        if (petHeal(ctx, me, 70, now)) {
            return 2500;
        }
        if (onGround) {
            return summon(ctx, w, me, now);
        }
        return 0;
    }

    /** Servitor Heal on its summon below {@code below} % HP. */
    private boolean petHeal(BrainContext ctx, SelfView me, double below, long now) {
        if (!summons || !me.hasPet() || (me.petHpPct() >= below) || me.casting() || (buffTried.getOrDefault(-SERVITOR_HEAL, 0L) > now)) {
            return false;
        }
        final SkillView s = skill(me, SERVITOR_HEAL);
        if (!usable(me, s)) {
            return false;
        }
        final ActionResult r = ctx.act().cast(SERVITOR_HEAL, me.petId());
        buffTried.put(-SERVITOR_HEAL, now + (r.ok() ? 2000 : 10_000));
        if (r.ok()) {
            heals++;
            detail = "heals the servitor";
        }
        return r.ok();
    }

    /** The servitor summon it would call: the strongest tier it knows, within a tier by the bot's id. */
    static SkillView servitorSkill(SelfView me, int botId) {
        for (int t = SkillView.SERVITOR_TIERS.length - 1; t >= 0; t--) {
            final List<SkillView> known = new ArrayList<>();
            for (int id : SkillView.SERVITOR_TIERS[t]) {
                final SkillView s = skill(me, id);
                if ((s != null) && (s.kind() == SkillView.Kind.SUMMON)) {
                    known.add(s);
                }
            }
            if (!known.isEmpty()) {
                return known.get(Math.floorMod(botId, known.size()));
            }
        }
        return null;
    }

    private long summon(BrainContext ctx, WorldView w, SelfView me, long now) {
        if (!summons || me.hasPet() || (buffTried.getOrDefault(-2, 0L) > now)) {
            return 0;
        }
        final SkillView s = servitorSkill(me, botId);
        if ((s == null) || !usable(me, s) || (me.mpPct() < 40) || ((s.itemId() > 0) && (w.itemCount(s.itemId()) < s.itemCount()))) {
            return 0;
        }
        final ActionResult r = ctx.act().cast(s.id(), me.objectId());
        buffTried.put(-2, now + (r.ok() ? 20_000 : (r == ActionResult.COOLDOWN ? 10_000 : 600_000)));
        if (!r.ok()) {
            return 0;
        }
        summoned++;
        ctx.note("summons " + s.id());
        detail = "summons";
        return 3000; // the call takes a while (stock hit time); the next ticks wait while it casts
    }

    /** Send the summon at the bot's fight target (again when it went back to following). */
    private void petAttack(BrainContext ctx, SelfView me, NpcView mob, long now) {
        if (!summons || !me.hasPet() || (me.petTargetId() == mob.objectId())) {
            return;
        }
        if ((petOrderedOn == mob.objectId()) && ((now - petOrderedAt) < 4000)) {
            return;
        }
        petOrderedOn = mob.objectId();
        petOrderedAt = now;
        ctx.act().petAttack(mob.objectId());
    }

    /** Sweeper on the body of the monster it spoiled and killed. */
    private long sweep(BrainContext ctx, WorldView w, SelfView me, long now) {
        if (sweepId == 0) {
            final NpcView near = spoiledCorpseNear(w, me, 600);
            if (near != null) {
                sweepId = near.objectId();
                sweepUntil = now + SWEEP_MS;
            }
        }
        if (sweepId == 0) {
            return 0;
        }
        final NpcView body = w.npc(sweepId);
        final SkillView s = skill(me, SWEEPER);
        if ((now > sweepUntil) || (body == null) || !body.dead() || !body.spoiled() || (s == null)) {
            sweepId = 0;
            return 0;
        }
        if (me.casting() || !s.ready() || (s.mpCost() > me.mp())) {
            return 500;
        }
        final ActionResult r = ctx.act().cast(SWEEPER, sweepId);
        if (r == ActionResult.COOLDOWN) {
            return 500;
        }
        sweepId = 0;
        if (r.ok()) {
            sweeps++;
            detail = "sweep";
            return 1200;
        }
        return 0;
    }

    /**
     * KT12 F5: in a player's party a Scavenger/BH Spoils the mate's (or free-hunt) target when not yet spoiled,
     * and queues Sweeper on spoiled corpses next to the mate.
     */
    private long partySpoil(BrainContext ctx, WorldView w, SelfView me, PartyMember mate, long now) {
        if (me.casting() || me.sitting()) {
            return 0;
        }
        final SkillView spoil = skill(me, SPOIL);
        final SkillView sweeper = skill(me, SWEEPER);
        if ((spoil == null) && (sweeper == null) && !spoiler(me.classId())) {
            return 0;
        }
        if (sweeper != null) {
            for (NpcView n : w.npcs()) {
                if (n.dead() && n.spoiled() && (n.pos().distance2d(mate.pos()) < 900) && (n.distance() < 900)) {
                    sweepId = n.objectId();
                    sweepUntil = now + SWEEP_MS;
                    return sweep(ctx, w, me, now);
                }
            }
        }
        if ((spoil == null) || !usable(me, spoil) || (ptMode == PartyMode.STOP) || (ptMode == PartyMode.SIT)) {
            return 0;
        }
        NpcView target = mate.targetId() == 0 ? null : w.npc(mate.targetId());
        if ((target == null) || target.dead() || !target.monster() || target.spoiled() || (target.distance() > 900)) {
            if (ptMode == PartyMode.FREE) {
                target = freeMob(w, me, mate);
            } else {
                return 0;
            }
        }
        if ((target == null) || target.dead() || target.spoiled() || (target.distance() > 900)) {
            return 0;
        }
        final ActionResult r = ctx.act().cast(SPOIL, target.objectId());
        if (r.ok()) {
            detail = "spoils for " + ptMate;
            return 1500;
        }
        return 0;
    }

    private static NpcView spoiledCorpseNear(WorldView w, SelfView me, int range) {
        for (NpcView n : w.npcs()) {
            if (n.dead() && n.spoiled() && (n.distance() < range)) {
                return n;
            }
        }
        return null;
    }

    /** Dash before running away (rogues, daggers). */
    private boolean dash(BrainContext ctx, SelfView me) {
        final SkillView s = skill(me, DASH);
        return usable(me, s) && !me.casting() && ctx.act().cast(DASH, me.objectId()).ok();
    }

    /**
     * A class whose playstyle has a skill from behind (Backstab) walks round to the back of a monster that does not
     * look at it (not fighting the bot yet, or busy with someone else) before the first blow; once per target, at most
     * 6 s, never into free aggressive monsters.
     * @return true while walking round
     */
    private boolean sneak(BrainContext ctx, WorldView w, SelfView me, NpcView mob, long now) {
        if (!usePlaystyles) {
            return false;
        }
        if (sneakFor == mob.objectId()) {
            if ((now > sneakUntil) || mob.behind(me.pos(), PlaystyleEngine.REAR_DEG) || (mob.targetId() == me.objectId())) {
                sneakUntil = 0;
                return false;
            }
            if (!me.moving()) {
                if (me.pos().distance2d(sneakTo) < 40) {
                    sneakUntil = 0; // there, but it turned
                    return false;
                }
                if (!ctx.act().moveTo(sneakTo).ok()) {
                    sneakUntil = 0;
                    return false;
                }
            }
            return true;
        }
        if ((mob.targetId() == me.objectId()) || mob.behind(me.pos(), PlaystyleEngine.REAR_DEG)) {
            return false;
        }
        final Playstyles.Playstyle ps = playstyles.of(me.classId(), me.bow());
        if (ps == null) {
            return false;
        }
        boolean rear = false;
        for (Playstyles.Entry e : ps.entries()) {
            if (e.when().contains(Playstyles.Cond.REAR) && e.fitsLevel(me.level()) && usable(me, skill(me, e.skillId()))) {
                rear = true;
                break;
            }
        }
        if (!rear) {
            return false;
        }
        sneakFor = mob.objectId();
        final Pos back = mob.behindPoint(50);
        if (!w.geo().canMoveTo(me.pos(), back) || (clearance(me.pos(), back, threats(w, mob.objectId())) < 0) || !ctx.act().moveTo(back).ok()) {
            sneakUntil = 0;
            return false;
        }
        sneakTo = back;
        sneakUntil = now + 6000;
        sneaks++;
        detail = "fight " + fightName + ": round to its back";
        return true;
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
        if (partyPlay && !w.party().isEmpty()) {
            // in a party it stays with the others: only at a critical HP and with no healer alive to keep it up
            return (me.hpPct() < (fleeHp * 0.5)) && !healerNear(w);
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
        if (dash(ctx, me)) {
            return 700; // the next tick runs (mobFlee moves on when the bot stands still)
        }
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
        if (partyPlay && !w.party().isEmpty() && ((mob.targetId() != me.objectId()) || (onPartner(w) != null))) {
            return false; // stepping back would leave a partner tanking alone
        }
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
