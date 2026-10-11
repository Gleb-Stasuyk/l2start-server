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
    public static final double SPEED = 120; // units per second
    public static final int MELEE_RANGE = 80;
    public static final int NUKE_RANGE = 600;
    public static final int NUKE_MP = 10;
    public static final int VIEW = 1500;
    public static final int BOW_RANGE = 500;
    /** HP potions (Lesser, Healing, Greater): +30 % HP, reuse 10 s, like the stock 10 s reuse. */
    public static final int[] POTIONS = { 1060, 1061, 1539 };

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

    public void setTownRadius(int r) {
        townRadius = r;
    }

    public void teleport(Pos p) {
        pos = p;
        moveDest = null;
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
        // regeneration
        final double regen = sitting ? 0.05 : 0.01;
        hp = Math.min(maxHp(), hp + (maxHp() * regen * sec));
        mp = Math.min(maxMp(), mp + (maxMp() * (sitting ? 0.05 : 0.005) * sec));
        // aggressive monsters and monsters that fight the bot
        for (Mob m : mobs.values()) {
            if (m.dead()) {
                continue;
            }
            if (m.aggressive && (m.targetId == 0) && (m.engagedBy == 0) && (m.pos.distance2d(pos) < 300)) {
                m.targetId = SELF_ID;
            }
            if (m.targetId == SELF_ID) {
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
                if ((m == null) || m.dead()) {
                    intention = "ACTIVE";
                    break;
                }
                if (moveTowards(m.pos, sec, NUKE_RANGE - 20)) {
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
        if (m.engagedBy != 0) {
            stolenHits++;
        }
        m.hp -= dmg;
        if (m.targetId == 0) {
            m.targetId = SELF_ID;
        }
        if (m.dead()) {
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

    private void die(int killer) {
        dead = true;
        deaths++;
        hp = 0;
        sitting = false;
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
            m.targetId, (m.engagedBy != 0) || ((m.targetId != 0) && (m.targetId != SELF_ID)), m.attackRange, now < m.swingUntil);
    }

    /** Give the mage an undead-only nuke with a lower id than the real one (it is listed first). */
    public boolean undeadOnlyNuke;

    public SelfView self() {
        final List<SkillView> skills = new ArrayList<>();
        if (mage && undeadOnlyNuke) {
            skills.add(new SkillView(UNDEAD_NUKE_ID, 1, SkillView.Kind.NUKE, NUKE_MP, NUKE_RANGE, true));
        }
        if (mage) {
            skills.add(new SkillView(NUKE_ID, 1, SkillView.Kind.NUKE, NUKE_MP, NUKE_RANGE, now >= skillReadyAt.getOrDefault(NUKE_ID, 0L)));
        }
        skills.add(new SkillView(1012, 1, SkillView.Kind.OTHER, 5, 0, true));
        final boolean combat = mobs.values().stream().anyMatch(m -> !m.dead() && (m.targetId == SELF_ID));
        return new SelfView(SELF_ID, "Bot", mage ? 10 : 0, mage, level, (exp * 100.0) / expToNext(level), sp, hp, maxHp(), mp, maxMp(), pos, dead, sitting,
            "MOVE_TO".equals(intention) || "PICK_UP".equals(intention), combat, "ATTACK".equals(intention) && (now < (nextSwingAt - 500)), "CAST".equals(intention), targetId, peaceZone, false, intention, true, skills,
            0, 0, archer ? BOW_RANGE : 40, archer);
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
            final Mob m = mobs.get(objectId);
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
        public ActionResult logout() {
            return ActionResult.OK;
        }
    };

    public Actuators actuators() {
        return actuators;
    }
}
