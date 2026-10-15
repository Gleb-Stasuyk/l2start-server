package org.l2start.bots.testkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.l2start.bots.api.ActionResult;
import org.l2start.bots.api.Actuators;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.DropView;
import org.l2start.bots.api.Geo;
import org.l2start.bots.api.NpcView;
import org.l2start.bots.api.PartyMember;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SelfView;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.api.WorldView;

/**
 * A tiny game world for offline brain tests: one bot, monsters with HP and damage that hit back and respawn, drops,
 * other players, walls (rectangles) as "geodata", sitting regeneration, death and return to town, experience and
 * levels. Nothing here is the real game; it is good enough to see that a brain runs the loop
 * "town -> find a monster -> kill -> rest -> die -> come back -> level up".
 */
public final class FakeWorld {
    public static final int SELF_ID = 1;
    public static final int NUKE_ID = 1177;
    /** A nuke the game always refuses here (like Might of Heaven on a living monster); only with {@link #undeadOnlyNuke}. */
    public static final int UNDEAD_NUKE_ID = 1028;
    /** Phase 4b (with {@link #selfBuffs}): a 20-minute self buff (Shield) and, for fighters, a stance (Vicious Stance). */
    public static final int BUFF_ID = 1040;
    public static final int BUFF_MP = 10;
    public static final long BUFF_MS = 1_200_000;
    public static final int TOGGLE_ID = 312;
    /** Phase 4c (with {@link #strikeSkill}): a fighter's Power Strike - double damage in melee, 10 MP, reuse 3 s. */
    public static final int STRIKE_ID = 3;
    public static final int STRIKE_MP = 10;
    public static final double SPEED = 120; // units per second
    public static final int MELEE_RANGE = 80;
    public static final int NUKE_RANGE = 600;
    public static final int NUKE_MP = 10;
    public static final int VIEW = 1500;
    public static final int BOW_RANGE = 500;
    /** HP potions (Lesser, Healing, Greater): +30 % HP, reuse 10 s, like the stock 10 s reuse. */
    public static final int[] POTIONS = { 1060, 1061, 1539 };
    /** Scroll of Escape: useItem casts 20 s, then the bot is in town. */
    public static final int SOE = 736;
    public int soeUsed;
    public int logouts;
    private long escapeAt;
    /** KT12 F1: gatekeeper anchors and hop landings for catch-up tests. */
    public final List<Pos> gkAnchors = new ArrayList<>();
    /** From a GK position → list of stock-like landing positions (other towns). */
    public final Map<Pos, List<Pos>> gkHops = new HashMap<>();
    public int gkTeleports;
    /** Phase 4c: heals that put 30 % HP back on whoever they are cast on (the bot or its summon). */
    public static final java.util.Set<Integer> HEALS = java.util.Set.of(1011, 1015, 1217, 1218, 1401, 1127);
    public static final int SPOIL_ID = 254;
    public static final int SWEEPER_ID = 42;
    public static final int BACKSTAB_ID = 30;
    /** What Sweeper takes from a spoiled body. */
    public static final int SWEEP_ITEM = 1864;
    /** Skills that add one charge (Sonic Focus, Focused Force), at most {@link #MAX_CHARGES}. */
    public static final java.util.Set<Integer> CHARGE_GAIN = java.util.Set.of(8, 50);
    public static final int MAX_CHARGES = 7;

    /**
     * A skill added by a test: {@link #addSkill}.
     * @param buffLeft BUFF: seconds left on the bot (phase 6e THANKS_BUFF tests); 0 = none
     */
    public record Extra(int id, SkillView.Kind kind, int mp, long reuseMs, int itemId, int itemCount, int chargesUsed, int buffLeft) {
        public Extra(int id, SkillView.Kind kind, int mp, long reuseMs, int itemId, int itemCount, int chargesUsed) {
            this(id, kind, mp, reuseMs, itemId, itemCount, chargesUsed, 0);
        }
    }

    public static final class Mob {
        public final int objectId;
        public final int npcId;
        public final int level;
        public final Pos spawn;
        public Pos pos;
        public double hp;
        public final double maxHp;
        public boolean aggressive;
        public int targetId;
        /** Object id of another player fighting it (kill-steal tests). */
        public int engagedBy;
        /** Physical attack range: melee 40 (stops at {@link #MELEE_RANGE}), an archer monster 500. */
        public int attackRange = 40;
        /** An aggressive monster attacks the bot when it comes this close. */
        public int aggroRange = NpcView.AGGRO;
        /** Phase 4c: where it looks (client units, 0 = east); it turns to whoever it hits. */
        public int heading;
        public boolean undead;
        /** Spoil (254) worked on it: Sweeper (42) on the corpse gives {@link #SWEEP_ITEM}. */
        public boolean spoiled;
        long respawnAt = -1;
        long nextHitAt;
        long swingUntil;

        Mob(int objectId, int npcId, int level, Pos pos) {
            this.objectId = objectId;
            this.npcId = npcId;
            this.level = level;
            spawn = pos;
            this.pos = pos;
            maxHp = 40 + (level * 20);
            hp = maxHp;
        }

        public boolean dead() {
            return hp <= 0;
        }
    }

    private record Drop(int objectId, int itemId, long count, Pos pos) {
    }

    private record Wall(int x1, int y1, int x2, int y2) {
        boolean contains(double x, double y) {
            return (x >= x1) && (x <= x2) && (y >= y1) && (y <= y2);
        }
    }

    private final Random rnd;
    private final Map<Integer, Mob> mobs = new LinkedHashMap<>();
    private final List<Drop> drops = new ArrayList<>();
    private final List<Wall> walls = new ArrayList<>();
    private final List<PlayerView> players = new ArrayList<>();
    private final List<BotEvent> pending = new ArrayList<>();
    private final Map<Integer, Long> skillReadyAt = new HashMap<>();
    private final Pos town;
    private final boolean mage;
    private int nextId = 1000;
    private long now;

    // the bot
    private Pos pos;
    private int level = 1;
    private long exp;
    private long sp;
    private double hp;
    private double mp;
    private boolean dead;
    private boolean sitting;
    private int targetId;
    private String intention = "IDLE";
    private Pos moveDest;
    private int pickTarget;
    private int castSkill;
    private long nextSwingAt;
    private boolean peaceZone;
    private int townRadius = 800;
    private boolean archer;
    private final Map<Integer, Long> items = new HashMap<>();
    private long potionReadyAt;
    /** Monster speed as a share of the bot's (0.8: a bot can step away from a monster; 1.2: it cannot). */
    public double mobSpeed = 0.8;
    /** Damage the bot took from monsters (kiting tests). */
    public double damageTaken;
    public int potionsUsed;

    // phase 4c: added skills, charges, the summon
    private final Map<Integer, Extra> extras = new LinkedHashMap<>();
    /** Every added skill that went off: "id->target". */
    public final List<String> casts = new ArrayList<>();
    public int classId = -1;
    public int charges;
    public int petId;
    public double petHp;
    public int petTargetId;
    private long petNextHitAt;
    public int unsummons;
    public int sweeps;
    public int petKills;

    // statistics for tests
    public int kills;
    public int deaths;
    public int pickedUp;
    public double travelled;
    public int stolenHits;

    public FakeWorld(long seed, Pos town, boolean mage) {
        rnd = new Random(seed);
        this.town = town;
        this.mage = mage;
        pos = town;
        hp = maxHp();
        mp = maxMp();
        peaceZone = true;
    }

    // ------------------------------------------------------------------ setup

    public Mob addMob(int npcId, int level, Pos at) {
        final Mob m = new Mob(nextId++, npcId, level, at);
        mobs.put(m.objectId, m);
        return m;
    }

    public void addWall(int x1, int y1, int x2, int y2) {
        walls.add(new Wall(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)));
    }

    /** Phase 4g: the other members of its party (tests move and hurt them with {@link #setParty}). */
    public final List<PartyMember> party = new ArrayList<>();
    public boolean partyLeader = true;
    /** "skillId>objectId" of every skill cast on a party member. */
    public final List<String> partyCasts = new ArrayList<>();
    public static final int RESURRECTION = 1016;
    public static final int AGGRESSION = 28;
    public int taunts;

    /** Phase 6: what the bot said, whispered, answered (say:, party:, whisper:to:, social:, answerParty:, leaveParty, giveLeader:). */
    public final List<String> chat = new ArrayList<>();
    /** Phase 6: the pending party invitation (the inviter joins {@link #party} on yes). */
    public PartyMember invite;

    /** Phase 6: a real player invites the bot; the event arrives on the next tick. */
    public void invite(PartyMember from, int partyMembers) {
        invite(from, partyMembers, false);
    }

    public void invite(PartyMember from, int partyMembers, boolean botLimit) {
        invite = from;
        event(new BotEvent.PartyInvite(from.objectId(), from.name(), from.level(), from.classId(), partyMembers, botLimit));
    }

    /** Phase 6d: a player whispered to the bot (name only; resolve id via {@link #players}). */
    public void whisperFrom(String name, String text) {
        event(new BotEvent.Whisper(name, text));
    }

    /** Phase 6d: a player wrote in shout (or pass channel ALL/TRADE). */
    public void chatFrom(String channel, int id, String name, String text) {
        event(new BotEvent.Chat(channel, id, name, text));
    }

    public void setParty(PartyMember... members) {
        party.clear();
        party.addAll(List.of(members));
    }

    private int partyIndex(int objectId) {
        for (int i = 0; i < party.size(); i++) {
            if (party.get(i).objectId() == objectId) {
                return i;
            }
        }
        return -1;
    }

    public void addPlayer(PlayerView p) {
        players.add(p);
    }

    /** A bow in the hands: shoots from {@link #BOW_RANGE}. */
    public void setArcher(boolean a) {
        archer = a;
    }

    public void giveItem(int itemId, long count) {
        items.merge(itemId, count, Long::sum);
    }

    public long itemCount(int itemId) {
        return items.getOrDefault(itemId, 0L);
    }

    /** Phase 4c: the bot knows this skill (damage: STRIKE in melee, NUKE from {@link #NUKE_RANGE}). */
    public void addSkill(int id, SkillView.Kind kind, int mp, long reuseMs) {
        addSkill(new Extra(id, kind, mp, reuseMs, 0, 0, 0));
    }

    public void addSkill(Extra e) {
        extras.put(e.id(), e);
    }

    public int petMaxHp() {
        return 60 + (level * 10);
    }

    public void setTownRadius(int r) {
        townRadius = r;
    }

    /** Force peace / hunt for KT12 F4 buff tests (otherwise derived from distance to town). */
    public void setPeace(boolean peace) {
        peaceZone = peace;
    }

    public void teleport(Pos p) {
        pos = p;
        moveDest = null;
        peaceZone = pos.distance2d(town) <= townRadius;
    }

    public void setLevel(int l) {
        level = l;
        hp = maxHp();
        mp = maxMp();
    }

    public void setHp(double v) {
        hp = v;
    }

    public void setMp(double v) {
        mp = v;
    }

    public void kill() {
        die(0);
    }

    public void event(BotEvent e) {
        pending.add(e);
    }

    // ------------------------------------------------------------------ state for asserts

    public int level() { return level; }
    public long exp() { return exp; }
    public long sp() { return sp; }
    public Pos pos() { return pos; }
    public boolean dead() { return dead; }
    public boolean sitting() { return sitting; }
    public String intention() { return intention; }
    public double hp() { return hp; }
    public double mp() { return mp; }
    public Map<Integer, Mob> mobs() { return mobs; }
    public long now() { return now; }

    public int maxHp() {
        return 80 + (level * 12);
    }

    public int maxMp() {
        return mage ? 60 + (level * 8) : 30 + (level * 3);
    }

    public static long expToNext(int level) {
        return 150L * level;
    }

    // ------------------------------------------------------------------ simulation

    /** Advance the world to {@code until} (ms) in 100 ms steps. */
    public void runUntil(long until) {
        while (now < until) {
            step(Math.min(100, until - now));
        }
    }

    private void step(long ms) {
        now += ms;
        final double sec = ms / 1000.0;
        peaceZone = pos.distance2d(town) <= townRadius;
        // respawn
        for (Mob m : mobs.values()) {
            if (m.dead() && (m.respawnAt >= 0) && (now >= m.respawnAt)) {
                m.hp = m.maxHp;
                m.pos = m.spawn;
                m.targetId = 0;
                m.engagedBy = 0;
                m.respawnAt = -1;
            }
        }
        if (dead) {
            return;
        }
        if (toggleOn && sitting) {
            toggleSittingMs += ms;
        }
        // regeneration
        final double regen = sitting ? 0.05 : 0.01;
        hp = Math.min(maxHp(), hp + (maxHp() * regen * sec));
        mp = Math.min(maxMp(), mp + (maxMp() * (sitting ? 0.05 : 0.005) * sec));
        // aggressive monsters and monsters that fight the bot
        for (Mob m : mobs.values()) {
            if (m.dead()) {
                continue;
            }
            if (m.aggressive && (m.targetId == 0) && (m.engagedBy == 0) && (m.pos.distance2d(pos) < m.aggroRange)) {
                m.targetId = SELF_ID;
            }
            if ((petId != 0) && (m.targetId == petId)) { // the summon stays by the bot
                final double d = m.pos.distance2d(pos);
                m.heading = headingTo(m.pos, pos);
                if (d > MELEE_RANGE) {
                    m.pos = m.pos.toward(pos, SPEED * mobSpeed * sec);
                } else if (now >= m.nextHitAt) {
                    m.nextHitAt = now + 1500;
                    m.swingUntil = now + 500;
                    petHp -= 2 + (m.level * 1.5);
                    if (petHp <= 0) {
                        petGone();
                    }
                }
                continue;
            }
            if (m.targetId == SELF_ID) {
                m.heading = headingTo(m.pos, pos);
                final double d = m.pos.distance2d(pos);
                final int reach = Math.max(MELEE_RANGE, m.attackRange);
                if (d > reach) {
                    m.pos = m.pos.toward(pos, SPEED * mobSpeed * sec);
                } else if (now >= m.nextHitAt) {
                    m.nextHitAt = now + 1500;
                    m.swingUntil = now + 500;
                    final double dmg = 2 + (m.level * 1.5);
                    hp -= dmg;
                    damageTaken += dmg;
                    pending.add(new BotEvent.Attacked(m.objectId, false, dmg));
                    if (sitting) {
                        sitting = false;
                    }
                    if (hp <= 0) {
                        die(m.objectId);
                        return;
                    }
                }
            }
        }
        if (petId != 0) {
            final Mob t = mobs.get(petTargetId);
            if ((t == null) || t.dead()) {
                petTargetId = 0;
            } else if ((t.pos.distance2d(pos) <= (MELEE_RANGE * 3)) && (now >= petNextHitAt)) {
                petNextHitAt = now + 1000;
                hit(t, 4 + level, petId);
            }
        }
        if ((escapeAt > 0) && (now >= escapeAt)) {
            escapeAt = 0;
            actuators.returnToTown();
            return;
        }
        if (sitting) {
            return;
        }
        switch (intention) {
            case "MOVE_TO" -> {
                if (moveTowards(moveDest, sec, 0)) {
                    intention = "ACTIVE";
                    moveDest = null;
                }
            }
            case "ATTACK" -> {
                final Mob m = mobs.get(targetId);
                if ((m == null) || m.dead()) {
                    intention = "ACTIVE";
                    break;
                }
                if (moveTowards(m.pos, sec, archer ? BOW_RANGE - 10 : MELEE_RANGE - 10) && (now >= nextSwingAt)) {
                    nextSwingAt = now + 1000;
                    hit(m, mage ? 3 + level : 6 + (level * 2));
                }
            }
            case "CAST" -> {
                final Mob m = mobs.get(targetId);
                final Extra ex = extras.get(castSkill);
                if ((ex != null) && (m != null) && (ex.kind() == SkillView.Kind.CORPSE)) {
                    if (moveTowards(m.pos, sec, MELEE_RANGE - 10)) {
                        applyExtra(ex, m);
                        intention = "ACTIVE";
                    }
                    break;
                }
                if ((m == null) || m.dead()) {
                    intention = "ACTIVE";
                    break;
                }
                if (ex != null) { // like the stock AI: walk up, then the skill goes off
                    if (moveTowards(m.pos, sec, (ex.kind() == SkillView.Kind.NUKE ? NUKE_RANGE : MELEE_RANGE) - 10)) {
                        applyExtra(ex, m);
                        intention = m.dead() ? "ACTIVE" : "ATTACK";
                    }
                    break;
                }
                if (castSkill == STRIKE_ID) {
                    if (moveTowards(m.pos, sec, MELEE_RANGE - 10)) {
                        if (mp >= STRIKE_MP) {
                            mp -= STRIKE_MP;
                            skillReadyAt.put(STRIKE_ID, now + 3000);
                            strikes++;
                            hit(m, 2 * (6 + (level * 2)));
                        }
                        intention = "ATTACK"; // like the stock AI: back to hitting the target
                    }
                } else if (moveTowards(m.pos, sec, NUKE_RANGE - 20)) {
                    if (mp >= NUKE_MP) {
                        mp -= NUKE_MP;
                        skillReadyAt.put(castSkill, now + 1500);
                        hit(m, 18 + (level * 4));
                    }
                    intention = "ACTIVE";
                }
            }
            case "PICK_UP" -> {
                final Drop d = drops.stream().filter(x -> x.objectId == pickTarget).findFirst().orElse(null);
                if (d == null) {
                    intention = "ACTIVE";
                } else if (moveTowards(d.pos, sec, 20)) {
                    drops.remove(d);
                    pickedUp++;
                    intention = "ACTIVE";
                }
            }
            default -> {
            }
        }
    }

    /** @return true when within {@code range} of the target */
    private boolean moveTowards(Pos to, double sec, int range) {
        if (to == null) {
            return true;
        }
        final double d = pos.distance2d(to);
        if (d <= Math.max(range, 1)) {
            return true;
        }
        final Pos next = pos.toward(to, Math.min(SPEED * sec, d - range));
        if (blocked(pos, next)) {
            intention = "ACTIVE"; // stock: the move ends at the wall
            moveDest = null;
            return false;
        }
        travelled += pos.distance2d(next);
        pos = next;
        return pos.distance2d(to) <= Math.max(range, 1);
    }

    private void hit(Mob m, double dmg) {
        hit(m, dmg, SELF_ID);
    }

    private void hit(Mob m, double dmg, int by) {
        if (m.engagedBy != 0) {
            stolenHits++;
        }
        m.hp -= dmg;
        if (m.targetId == 0) {
            m.targetId = by;
        }
        if (m.dead()) {
            if (by != SELF_ID) {
                petKills++;
            }
            m.respawnAt = now + 30_000;
            m.targetId = 0;
            kills++;
            exp += m.level * 40L;
            sp += m.level * 3L;
            pending.add(new BotEvent.Killed(m.objectId, m.npcId));
            if (rnd.nextInt(2) == 0) {
                drops.add(new Drop(nextId++, 57, 5 + rnd.nextInt(20), m.pos));
            }
            while (exp >= expToNext(level)) {
                exp -= expToNext(level);
                level++;
                pending.add(new BotEvent.LevelUp(level - 1, level));
            }
            intention = "ACTIVE";
        }
    }

    private void petGone() {
        petId = 0;
        petHp = 0;
        petTargetId = 0;
        for (Mob m : mobs.values()) {
            if ((m.targetId != 0) && (m.targetId != SELF_ID) && !m.dead() && (m.engagedBy == 0)) {
                m.targetId = 0;
            }
        }
    }

    static int headingTo(Pos from, Pos to) {
        return (int) Math.round((Math.atan2(to.y() - from.y(), to.x() - from.x()) * 65536) / (2 * Math.PI)) & 0xFFFF;
    }

    private void die(int killer) {
        dead = true;
        deaths++;
        hp = 0;
        sitting = false;
        buffUntil = 0; // effects go on death
        toggleOn = false;
        intention = "IDLE";
        moveDest = null;
        for (Mob m : mobs.values()) {
            if (m.targetId == SELF_ID) {
                m.targetId = 0;
            }
        }
        pending.add(new BotEvent.Died(killer));
    }

    private boolean blocked(Pos a, Pos b) {
        if (walls.isEmpty()) {
            return false;
        }
        final double d = a.distance2d(b);
        final int n = Math.max(1, (int) (d / 20));
        for (int i = 1; i <= n; i++) {
            final double x = a.x() + (((double) b.x() - a.x()) * i) / n;
            final double y = a.y() + (((double) b.y() - a.y()) * i) / n;
            for (Wall w : walls) {
                if (w.contains(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ views

    private final Geo geo = new Geo() {
        @Override
        public boolean canMoveTo(Pos a, Pos b) {
            return !blocked(a, b);
        }

        @Override
        public boolean canSee(Pos a, Pos b) {
            return !blocked(a, b);
        }

        @Override
        public int height(Pos p) {
            return p.z();
        }

        @Override
        public Optional<Pos> randomReachablePoint(Pos center, int radius, Random r) {
            for (int i = 0; i < 20; i++) {
                final double a = r.nextDouble() * Math.PI * 2;
                final double d = r.nextDouble() * radius;
                final Pos p = new Pos(center.x() + (int) (Math.cos(a) * d), center.y() + (int) (Math.sin(a) * d), center.z());
                if (!blocked(center, p)) {
                    return Optional.of(p);
                }
            }
            return Optional.empty();
        }
    };

    public Geo geo() {
        return geo;
    }

    private NpcView npcView(Mob m) {
        return new NpcView(m.objectId, m.npcId, "Mob" + m.npcId, m.level, m.pos, m.pos.distance(pos), m.dead() ? 0 : (m.hp * 100) / m.maxHp, m.dead(), true, m.aggressive, true,
            m.targetId, (m.engagedBy != 0) || ((m.targetId != 0) && (m.targetId != SELF_ID) && (m.targetId != petId) && (partyIndex(m.targetId) < 0)), m.attackRange, now < m.swingUntil,
            m.aggressive ? m.aggroRange : 0, m.heading, m.undead, m.spoiled);
    }

    /** Give the mage an undead-only nuke with a lower id than the real one (it is listed first). */
    public boolean undeadOnlyNuke;
    /** Phase 4b: the bot knows {@link #BUFF_ID} (and a fighter {@link #TOGGLE_ID}). */
    public boolean selfBuffs;
    /** Phase 4c: a fighter knows {@link #STRIKE_ID}. */
    public boolean strikeSkill;
    public int strikes;
    /** Self buff on until (ms of the fake clock). */
    public long buffUntil;
    public boolean toggleOn;
    public int buffCasts;
    /** How long the stance was on while the bot sat (it should switch it off before sitting). */
    public long toggleSittingMs;

    public SelfView self() {
        final List<SkillView> skills = new ArrayList<>();
        if (mage && undeadOnlyNuke) {
            skills.add(new SkillView(UNDEAD_NUKE_ID, 1, SkillView.Kind.NUKE, NUKE_MP, NUKE_RANGE, true));
        }
        if (mage) {
            skills.add(new SkillView(NUKE_ID, 1, SkillView.Kind.NUKE, NUKE_MP, NUKE_RANGE, now >= skillReadyAt.getOrDefault(NUKE_ID, 0L)));
        }
        skills.add(new SkillView(1012, 1, SkillView.Kind.OTHER, 5, 0, true));
        if (strikeSkill && !mage) {
            skills.add(new SkillView(STRIKE_ID, 1, SkillView.Kind.STRIKE, STRIKE_MP, 40, now >= skillReadyAt.getOrDefault(STRIKE_ID, 0L)));
        }
        if (selfBuffs) {
            skills.add(new SkillView(BUFF_ID, 3, SkillView.Kind.BUFF, BUFF_MP, 0, true, now < buffUntil ? (int) Math.max(1, (buffUntil - now) / 1000) : 0));
            if (!mage) {
                skills.add(new SkillView(TOGGLE_ID, 1, SkillView.Kind.TOGGLE, 5, 0, true, toggleOn ? -1 : 0));
            }
        }
        for (Extra e : extras.values()) {
            skills.add(new SkillView(e.id(), 1, e.kind(), e.mp(), e.kind() == SkillView.Kind.NUKE ? NUKE_RANGE : 40, now >= skillReadyAt.getOrDefault(e.id(), 0L), e.buffLeft(),
                e.itemId(), e.itemCount()));
        }
        final boolean combat = mobs.values().stream().anyMatch(m -> !m.dead() && (m.targetId == SELF_ID));
        return new SelfView(SELF_ID, "Bot", classId >= 0 ? classId : mage ? 10 : 0, mage, level, (exp * 100.0) / expToNext(level), sp, hp, maxHp(), mp, maxMp(), pos, dead, sitting,
            "MOVE_TO".equals(intention) || "PICK_UP".equals(intention), combat, "ATTACK".equals(intention) && (now < (nextSwingAt - 500)), "CAST".equals(intention), targetId, peaceZone, false, intention, true, skills,
            0, 0, archer ? BOW_RANGE : 40, archer, charges, petId, petId == 0 ? 0 : (petHp * 100) / petMaxHp(), petTargetId);
    }

    /** The view of the next tick: events since the previous view are handed over (and cleared). */
    public WorldView view() {
        final SelfView self = self();
        final List<BotEvent> events = new ArrayList<>(pending);
        pending.clear();
        return new WorldView() {
            @Override
            public SelfView self() {
                return self;
            }

            @Override
            public List<NpcView> npcs() {
                return mobs.values().stream().map(FakeWorld.this::npcView).filter(v -> v.distance() <= VIEW).sorted(Comparator.comparingDouble(NpcView::distance)).limit(64).toList();
            }

            @Override
            public List<PlayerView> players() {
                return players.stream().filter(p -> p.pos().distance(pos) <= VIEW).toList();
            }

            @Override
            public List<PartyMember> party() {
                return party.stream().map(m -> new PartyMember(m.objectId(), m.name(), m.classId(), m.level(), m.pos(), m.pos().distance(self.pos()), m.hpPct(), m.mpPct(), m.dead(),
                    m.targetId(), m.leader(), m.bot(), m.sitting())).toList();
            }

            @Override
            public boolean partyLeader() {
                return partyLeader;
            }

            @Override
            public List<DropView> drops() {
                return drops.stream().map(d -> new DropView(d.objectId, d.itemId, d.count, d.pos, d.pos.distance(pos), true)).filter(v -> v.distance() <= VIEW)
                    .sorted(Comparator.comparingDouble(DropView::distance)).toList();
            }

            @Override
            public List<BotEvent> events() {
                return events;
            }

            @Override
            public Geo geo() {
                return geo;
            }

            @Override
            public NpcView npc(int objectId) {
                final Mob m = mobs.get(objectId);
                return m == null ? null : npcView(m);
            }

            @Override
            public long itemCount(int itemId) {
                return FakeWorld.this.itemCount(itemId);
            }
        };
    }

    // ------------------------------------------------------------------ actions

    private boolean walking;

    /** The gait the brain asked for (bots run by default). */
    public boolean walking() {
        return walking;
    }

    private final Actuators actuators = new Actuators() {
        @Override
        public ActionResult moveTo(Pos to) {
            if (dead || sitting) {
                return ActionResult.INVALID;
            }
            if (to.distance2d(pos) > 4000) {
                return ActionResult.INVALID;
            }
            if (blocked(pos, to)) {
                return ActionResult.NOT_REACHABLE;
            }
            intention = "MOVE_TO";
            moveDest = to;
            return ActionResult.OK;
        }

        @Override
        public ActionResult follow(int objectId) {
            return ActionResult.INVALID;
        }

        @Override
        public ActionResult setTarget(int objectId) {
            if (dead) {
                return ActionResult.INVALID;
            }
            targetId = objectId;
            return ActionResult.OK;
        }

        @Override
        public ActionResult attack(int objectId) {
            final Mob m = mobs.get(objectId);
            if ((m == null) && !dead && !sitting && !peaceZone && players.stream().anyMatch(x -> x.objectId() == objectId)) {
                targetId = objectId; // a player (PvP): the hit itself is not simulated
                intention = "ATTACK";
                return ActionResult.OK;
            }
            if (dead || sitting || (m == null) || m.dead() || peaceZone) {
                return ActionResult.INVALID;
            }
            if (blocked(pos, m.pos)) {
                return ActionResult.NOT_REACHABLE;
            }
            targetId = objectId;
            intention = "ATTACK";
            return ActionResult.OK;
        }

        @Override
        public ActionResult cast(int skillId, int objectId) {
            final Extra extra = extras.get(skillId);
            if (extra != null) {
                return castExtra(extra, objectId);
            }
            if (selfBuffs && (objectId == SELF_ID) && ((skillId == BUFF_ID) || ((skillId == TOGGLE_ID) && !mage))) {
                if (dead || sitting) {
                    return ActionResult.INVALID;
                }
                if (skillId == TOGGLE_ID) {
                    toggleOn = !toggleOn;
                    return ActionResult.OK;
                }
                if (mp < BUFF_MP) {
                    return ActionResult.COOLDOWN;
                }
                mp -= BUFF_MP;
                buffUntil = now + BUFF_MS;
                buffCasts++;
                return ActionResult.OK;
            }
            final Mob m = mobs.get(objectId);
            if (strikeSkill && !mage && (skillId == STRIKE_ID)) {
                if (dead || sitting || (m == null) || m.dead() || peaceZone) {
                    return ActionResult.INVALID;
                }
                if ((now < skillReadyAt.getOrDefault(STRIKE_ID, 0L)) || (mp < STRIKE_MP)) {
                    return ActionResult.COOLDOWN;
                }
                targetId = objectId;
                castSkill = STRIKE_ID;
                intention = "CAST";
                return ActionResult.OK;
            }
            if (dead || sitting || (m == null) || m.dead() || peaceZone || !mage || (skillId != NUKE_ID)) {
                return ActionResult.INVALID;
            }
            if ((now < skillReadyAt.getOrDefault(skillId, 0L)) || (mp < NUKE_MP)) {
                return ActionResult.COOLDOWN;
            }
            targetId = objectId;
            castSkill = skillId;
            intention = "CAST";
            return ActionResult.OK;
        }

        @Override
        public ActionResult useItem(int itemId) {
            if ((itemId == SOE) && !dead && !sitting && (itemCount(SOE) > 0) && (escapeAt == 0)) {
                items.merge(SOE, -1L, Long::sum);
                escapeAt = now + 20_000; // the stock cast of a scroll of escape
                intention = "CAST";
                moveDest = null;
                soeUsed++;
                return ActionResult.OK;
            }
            if (dead || sitting || (itemCount(itemId) <= 0) || java.util.Arrays.stream(POTIONS).noneMatch(x -> x == itemId)) {
                return ActionResult.INVALID;
            }
            if (now < potionReadyAt) {
                return ActionResult.COOLDOWN;
            }
            potionReadyAt = now + 10_000;
            items.merge(itemId, -1L, Long::sum);
            potionsUsed++;
            hp = Math.min(maxHp(), hp + (maxHp() * 0.3));
            return ActionResult.OK;
        }

        @Override
        public ActionResult pickUp(int objectId) {
            if (dead || sitting || drops.stream().noneMatch(d -> d.objectId == objectId)) {
                return ActionResult.INVALID;
            }
            pickTarget = objectId;
            intention = "PICK_UP";
            return ActionResult.OK;
        }

        @Override
        public ActionResult sit() {
            if (dead || sitting) {
                return ActionResult.INVALID;
            }
            sitting = true;
            intention = "REST";
            return ActionResult.OK;
        }

        @Override
        public ActionResult stand() {
            if (dead || !sitting) {
                return ActionResult.INVALID;
            }
            sitting = false;
            intention = "ACTIVE";
            return ActionResult.OK;
        }

        @Override
        public ActionResult say(String text) {
            if (!dead) {
                chat.add("say:" + text);
            }
            return dead ? ActionResult.INVALID : ActionResult.OK;
        }

        @Override
        public ActionResult answerParty(boolean accept) {
            if (invite == null) {
                return ActionResult.INVALID;
            }
            final PartyMember from = invite;
            invite = null;
            chat.add("answerParty:" + accept);
            if (accept) {
                party.add(from);
                partyLeader = false;
            }
            return ActionResult.OK;
        }

        @Override
        public ActionResult leaveParty() {
            if (party.isEmpty()) {
                return ActionResult.INVALID;
            }
            party.clear();
            partyLeader = true;
            chat.add("leaveParty");
            return ActionResult.OK;
        }

        @Override
        public ActionResult giveLeader(int objectId) {
            final int i = partyIndex(objectId);
            if (!partyLeader || (i < 0)) {
                return ActionResult.INVALID;
            }
            partyLeader = false;
            final PartyMember m = party.get(i);
            party.set(i, new PartyMember(m.objectId(), m.name(), m.classId(), m.level(), m.pos(), m.distance(), m.hpPct(), m.mpPct(), m.dead(), m.targetId(), true, m.bot(), m.sitting()));
            chat.add("giveLeader:" + objectId);
            return ActionResult.OK;
        }

        @Override
        public ActionResult sayParty(String text) {
            if (party.isEmpty() || (text == null) || text.isBlank()) {
                return ActionResult.INVALID;
            }
            chat.add("party:" + text);
            return ActionResult.OK;
        }

        @Override
        public ActionResult whisper(String to, String text) {
            chat.add("whisper:" + to + ":" + text);
            return ActionResult.OK;
        }

        @Override
        public ActionResult sayShout(String text) {
            if (dead || (text == null) || text.isBlank()) {
                return ActionResult.INVALID;
            }
            chat.add("shout:" + text);
            return ActionResult.OK;
        }

        @Override
        public ActionResult sayTrade(String text) {
            if (dead || (text == null) || text.isBlank()) {
                return ActionResult.INVALID;
            }
            chat.add("trade:" + text);
            return ActionResult.OK;
        }

        @Override
        public ActionResult inviteParty(String playerName) {
            if (dead || (playerName == null) || playerName.isBlank()) {
                return ActionResult.INVALID;
            }
            if (!party.isEmpty() && !partyLeader) {
                return ActionResult.INVALID;
            }
            chat.add("inviteParty:" + playerName);
            return ActionResult.OK;
        }

        @Override
        public ActionResult social(int id) {
            chat.add("social:" + id);
            return dead ? ActionResult.INVALID : ActionResult.OK;
        }

        @Override
        public ActionResult returnToTown() {
            if (dead) {
                dead = false;
                hp = maxHp() * 0.7;
                mp = maxMp() * 0.7;
            }
            pos = town;
            intention = "IDLE";
            moveDest = null;
            sitting = false;
            for (Mob m : mobs.values()) {
                if (m.targetId == SELF_ID) {
                    m.targetId = 0;
                }
            }
            pending.add(new BotEvent.Teleported(town));
            return ActionResult.OK;
        }

        @Override
        public int learnSkills() {
            return 0;
        }

        @Override
        public ActionResult setWalking(boolean walk) {
            walking = walk;
            return dead ? ActionResult.INVALID : ActionResult.OK;
        }

        @Override
        public ActionResult stop() {
            if (!dead && !sitting) {
                intention = "ACTIVE";
                moveDest = null;
            }
            return ActionResult.OK;
        }

        @Override
        public ActionResult petAttack(int objectId) {
            final Mob m = mobs.get(objectId);
            if (dead || (petId == 0) || (m == null) || m.dead() || peaceZone) {
                return ActionResult.INVALID;
            }
            petTargetId = objectId;
            return ActionResult.OK;
        }

        @Override
        public ActionResult petFollow() {
            if (petId == 0) {
                return ActionResult.INVALID;
            }
            petTargetId = 0;
            return ActionResult.OK;
        }

        @Override
        public ActionResult unsummon() {
            if (petId == 0) {
                return ActionResult.INVALID;
            }
            unsummons++;
            petGone();
            return ActionResult.OK;
        }

        @Override
        public ActionResult logout() {
            logouts++;
            return ActionResult.OK;
        }

        @Override
        public Pos nearestGk() {
            Pos best = null;
            double bestD = Double.POSITIVE_INFINITY;
            for (Pos g : gkAnchors) {
                final double d = pos.distance2d(g);
                if (d < bestD) {
                    bestD = d;
                    best = g;
                }
            }
            return best;
        }

        @Override
        public ActionResult teleportViaGk(Pos dest) {
            if (dead || sitting || (dest == null)) {
                return ActionResult.INVALID;
            }
            Pos at = null;
            for (Pos g : gkAnchors) {
                if (pos.distance2d(g) <= 200) {
                    at = g;
                    break;
                }
            }
            if (at == null) {
                return ActionResult.NOT_REACHABLE;
            }
            final List<Pos> hops = gkHops.getOrDefault(at, List.of());
            Pos land = null;
            double bestD = Double.POSITIVE_INFINITY;
            for (Pos h : hops) {
                final double d = h.distance2d(dest);
                if (d < bestD) {
                    bestD = d;
                    land = h;
                }
            }
            if (land == null) {
                // no hop table: land at the nearest other GK to dest (still not the player's exact spot)
                for (Pos g : gkAnchors) {
                    if (g.equals(at)) {
                        continue;
                    }
                    final double d = g.distance2d(dest);
                    if (d < bestD) {
                        bestD = d;
                        land = g;
                    }
                }
            }
            if (land == null) {
                return ActionResult.INVALID;
            }
            if (land.distance2d(dest) >= pos.distance2d(dest)) {
                return ActionResult.OK; // already as close as this GK allows
            }
            gkTeleports++;
            teleport(land);
            pending.add(new BotEvent.Teleported(land));
            return ActionResult.OK;
        }
    };

    /** Register a gatekeeper anchor for catch-up tests. */
    public void addGk(Pos gk) {
        gkAnchors.add(gk);
    }

    /** From this GK the bot may teleport to these landings (stock-like hops). */
    public void addGkHop(Pos fromGk, Pos landing) {
        gkHops.computeIfAbsent(fromGk, k -> new ArrayList<>()).add(landing);
    }

    /** Casts of added skills that failed when they went off (Backstab from the front, a skill without its charges). */
    public int extraFailures;

    private ActionResult castExtra(Extra e, int objectId) {
        if (dead || sitting) {
            return ActionResult.INVALID;
        }
        if ((now < skillReadyAt.getOrDefault(e.id(), 0L)) || (mp < e.mp())) {
            return ActionResult.COOLDOWN;
        }
        final Mob m = mobs.get(objectId);
        if ((e.id() == AGGRESSION) && (m != null) && !m.dead()) {
            m.targetId = SELF_ID; // pulled onto the bot
            taunts++;
            spend(e, objectId);
            return ActionResult.OK;
        }
        switch (e.kind()) {
            case SUMMON -> {
                if ((petId != 0) || (itemCount(e.itemId()) < e.itemCount())) {
                    return ActionResult.INVALID;
                }
                if (e.itemId() > 0) {
                    items.merge(e.itemId(), (long) -e.itemCount(), Long::sum);
                }
                petId = nextId++;
                petHp = petMaxHp();
                petTargetId = 0;
                spend(e, objectId);
                return ActionResult.OK;
            }
            case CORPSE -> {
                if ((m == null) || !m.dead() || !m.spoiled) {
                    return ActionResult.INVALID;
                }
            }
            default -> {
                if (objectId == SELF_ID) {
                    if (HEALS.contains(e.id())) {
                        hp = Math.min(maxHp(), hp + (maxHp() * 0.3));
                    }
                    spend(e, objectId);
                    return ActionResult.OK;
                }
                final int pi = partyIndex(objectId);
                if (pi >= 0) {
                    final PartyMember pm = party.get(pi);
                    if (pm.pos().distance(pos) > 900) {
                        return ActionResult.NOT_REACHABLE;
                    }
                    if (e.id() == RESURRECTION) {
                        if (!pm.dead()) {
                            return ActionResult.INVALID;
                        }
                        party.set(pi, new PartyMember(pm.objectId(), pm.name(), pm.classId(), pm.level(), pm.pos(), 0, 30, 0, false, 0, pm.leader()));
                    } else if (pm.dead()) {
                        return ActionResult.INVALID;
                    } else if (HEALS.contains(e.id())) {
                        party.set(pi, new PartyMember(pm.objectId(), pm.name(), pm.classId(), pm.level(), pm.pos(), 0, Math.min(100, pm.hpPct() + 30), pm.mpPct(), false, pm.targetId(), pm.leader()));
                    }
                    partyCasts.add(e.id() + ">" + objectId);
                    spend(e, objectId);
                    return ActionResult.OK;
                }
                if ((petId != 0) && (objectId == petId)) {
                    if (HEALS.contains(e.id())) {
                        petHp = Math.min(petMaxHp(), petHp + (petMaxHp() * 0.3));
                    }
                    spend(e, objectId);
                    return ActionResult.OK;
                }
                if ((m == null) || m.dead() || peaceZone) {
                    return ActionResult.INVALID;
                }
                if (blocked(pos, m.pos)) {
                    return ActionResult.NOT_REACHABLE;
                }
            }
        }
        targetId = objectId;
        final int range = e.kind() == SkillView.Kind.NUKE ? NUKE_RANGE : MELEE_RANGE;
        if (m.pos.distance2d(pos) <= range) {
            applyExtra(e, m);
            if ((e.kind() != SkillView.Kind.CORPSE) && !m.dead()) {
                intention = "ATTACK"; // like the stock AI after a skill: back to hitting
            }
        } else {
            castSkill = e.id(); // walks up first (step: CAST)
            intention = "CAST";
        }
        return ActionResult.OK;
    }

    private void spend(Extra e, int objectId) {
        mp -= e.mp();
        skillReadyAt.put(e.id(), now + e.reuseMs());
        casts.add(e.id() + "->" + objectId);
    }

    /** The skill goes off on the monster (in range now); a failed one still costs MP and reuse, like stock. */
    private void applyExtra(Extra e, Mob m) {
        if (mp < e.mp()) {
            return;
        }
        if (e.kind() == SkillView.Kind.CORPSE) {
            if (m.dead() && m.spoiled) {
                m.spoiled = false;
                sweeps++;
                giveItem(SWEEP_ITEM, 1);
                spend(e, m.objectId);
            } else {
                extraFailures++;
            }
            return;
        }
        if (((e.id() == BACKSTAB_ID) && !npcView(m).behind(pos, 120)) || (e.chargesUsed() > charges)) {
            extraFailures++; // stock: Backstab only from behind; charge skills need their charges
            mp -= e.mp();
            return;
        }
        spend(e, m.objectId);
        charges -= e.chargesUsed();
        if (CHARGE_GAIN.contains(e.id())) {
            charges = Math.min(MAX_CHARGES, charges + 1);
        }
        if (e.id() == SPOIL_ID) {
            m.spoiled = true;
        }
        if ((e.kind() == SkillView.Kind.STRIKE) || (e.kind() == SkillView.Kind.NUKE)) {
            hit(m, 2 * (6 + (level * 2)));
        } else if (m.targetId == 0) {
            m.targetId = SELF_ID;
        }
    }

    public Actuators actuators() {
        return actuators;
    }
}
