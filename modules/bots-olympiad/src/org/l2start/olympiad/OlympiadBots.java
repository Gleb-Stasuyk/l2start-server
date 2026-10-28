package org.l2start.olympiad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.l2jmobius.gameserver.config.OlympiadConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.mechanics.events.holders.olympiad.OnOlympiadMatchResult;
import org.l2jmobius.gameserver.mechanics.olympiad.Olympiad;
import org.l2jmobius.gameserver.util.LocationUtil;
import org.l2start.bots.olympiad.OlympiadPool;
import org.l2start.platform.api.ModuleContext;
import org.l2start.platform.hooks.CoreHooks;

/**
 * Roster at Grand Olympiad Manager (NPC 31688) and the periodic signup pass. Create/login are rate-limited
 * (1 + 1 per roster tick) so a cold start does not freeze the server.
 */
final class OlympiadBots {
    /** Giran Grand Olympiad Manager: prefer this spawn of GatherNpcId when several exist. */
    private static final int GIRAN_X = 82053;
    private static final int GIRAN_Y = 147526;

    private final ModuleContext ctx;
    private final OlympiadPool pool;
    /** charId → rest until (epoch ms). */
    private final Map<Integer, Long> restUntil = new ConcurrentHashMap<>();
    private volatile Cfg cfg;

    record Cfg(boolean enabled, int rosterSize, int minLevel, int maxLevel, int rivalsPerClass, int chancePercent, int restSeconds, int gatherNpcId) {
    }

    OlympiadBots(ModuleContext ctx, OlympiadPool pool, Cfg cfg) {
        this.ctx = ctx;
        this.pool = pool;
        this.cfg = cfg;
    }

    /**
     * @return the roster, or {@code null} when bots-core did not provide {@link OlympiadPool}. Loading this class
     *         without bots-core throws {@link NoClassDefFoundError} — the module entrypoint catches it.
     */
    static OlympiadBots start(ModuleContext ctx) {
        final OlympiadPool pool = ctx.services().find(OlympiadPool.class).orElse(null);
        if (pool == null) {
            return null;
        }
        return new OlympiadBots(ctx, pool, readCfg(ctx));
    }

    static Cfg readCfg(ModuleContext ctx) {
        int min = ctx.config().getInt("MinLevel");
        int max = ctx.config().getInt("MaxLevel");
        if (max < min) {
            max = min;
        }
        return new Cfg(ctx.config().getBoolean("Enabled"), ctx.config().getInt("RosterSize"), min, max, ctx.config().getInt("RivalsPerClass"),
            ctx.config().getInt("SignUpChancePercent"), ctx.config().getInt("RestSeconds"), ctx.config().getInt("GatherNpcId"));
    }

    void setCfg(Cfg cfg) {
        this.cfg = cfg;
    }

    Cfg cfg() {
        return cfg;
    }

    void onMatch(OnOlympiadMatchResult e) {
        final long until = System.currentTimeMillis() + (cfg.restSeconds * 1000L);
        rest(e.getWinner(), until);
        rest(e.getLoser(), until);
    }

    private void rest(Player p, long until) {
        if ((p != null) && CoreHooks.isBot(p)) {
            restUntil.put(p.getObjectId(), until);
        }
    }

    /** Idle-logout everyone not in a match (module off, window over, Enabled=false). */
    void idleLogout() {
        for (int id : pool.all()) {
            final Player p = pool.player(id);
            if ((p != null) && !inMatch(p)) {
                pool.leave(id);
            }
        }
    }

    void tickRoster() {
        final Cfg c = cfg;
        if (!c.enabled) {
            idleLogout();
            return;
        }
        final Olympiad oly = Olympiad.getInstance();
        if ((oly == null) || !oly.inCompPeriod()) {
            idleLogout();
            return;
        }
        final Waiting w = waiting();
        final Map<Integer, Integer> target = OlympiadRules.rosterTargets(c.rosterSize, c.rivalsPerClass, w.liveClassed);
        final Map<Integer, Integer> have = new HashMap<>();
        final Map<Integer, Integer> inWorld = new HashMap<>();
        final List<Integer> offline = new ArrayList<>();
        for (int id : pool.all()) {
            final int cls = pool.thirdClass(id);
            have.merge(cls, 1, Integer::sum);
            if (pool.player(id) != null) {
                inWorld.merge(cls, 1, Integer::sum);
            } else {
                offline.add(id);
            }
        }
        int total = 0;
        for (int n : have.values()) {
            total += n;
        }
        int want = 0;
        for (int n : target.values()) {
            want += n;
        }
        if (total < want) {
            final int needClass = pickDeficit(target, have);
            if (needClass > 0) {
                try {
                    pool.create(new OlympiadPool.Look(needClass));
                    ctx.metrics().counter("created").inc();
                } catch (Exception e) {
                    ctx.log().warn("cannot create olympiad noble of class " + needClass, e);
                }
            }
        }
        final Location npc = gather(c.gatherNpcId);
        if ((npc != null) && !offline.isEmpty()) {
            final int loginClass = pickDeficit(target, inWorld);
            int chosen = 0;
            if (loginClass > 0) {
                for (int id : offline) {
                    if (pool.thirdClass(id) == loginClass) {
                        chosen = id;
                        break;
                    }
                }
            }
            if (chosen == 0) {
                chosen = offline.get(0);
            }
            final Location at = scatter(npc, chosen);
            final int level = c.minLevel + ThreadLocalRandom.current().nextInt(Math.max(1, c.maxLevel - c.minLevel + 1));
            final String why = pool.enter(chosen, at.getX(), at.getY(), at.getZ(), at.getHeading(), level, null);
            if (why != null) {
                ctx.log().warn("olympiad login " + pool.name(chosen) + ": " + why);
            }
        }
        shrink(target);
    }

    void tickSignup() {
        final Cfg c = cfg;
        final Olympiad oly = Olympiad.getInstance();
        if (oly == null) {
            return;
        }
        if (!c.enabled) {
            unregisterIdle(oly);
            return;
        }
        final Waiting w = waiting();
        final int classedMin = OlympiadConfig.OLYMPIAD_CLASSED;
        final int nonMin = OlympiadConfig.OLYMPIAD_NONCLASSED;
        final long now = System.currentTimeMillis();
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();
        final Map<Integer, Integer> classedNow = new HashMap<>(w.classedSize);
        int nonNow = w.nonSize;
        for (int id : pool.all()) {
            final Player p = pool.player(id);
            if (p == null) {
                continue;
            }
            final int cls = p.getPlayerClass().getId();
            final boolean registered = oly.isRegistered(p);
            final boolean classed = w.classedMembers.getOrDefault(cls, List.of()).contains(p);
            int points = oly.getNoblePoints(p.getObjectId());
            if (points == 0) {
                points = -1;
            }
            final OlympiadRules.SignUpInput in = new OlympiadRules.SignUpInput(oly.inCompPeriod(), inMatch(p), registered, classed,
                OlympiadRules.stillResting(now, restUntil.getOrDefault(p.getObjectId(), 0L)), points, w.liveNon, w.liveByClass.getOrDefault(cls, 0),
                w.anyLive, classedNow.getOrDefault(cls, 0), nonNow, classedMin, nonMin, c.chancePercent, rnd.nextInt(100));
            switch (OlympiadRules.shouldSignUp(in)) {
                case REGISTER_CLASSED -> {
                    if (oly.registerNoble(p, true)) {
                        classedNow.merge(cls, 1, Integer::sum);
                        ctx.metrics().counter("signup classed").inc();
                    }
                }
                case REGISTER_NON_CLASSED -> {
                    if (oly.registerNoble(p, false)) {
                        nonNow++;
                        ctx.metrics().counter("signup non-classed").inc();
                    }
                }
                case UNREGISTER -> {
                    if (oly.unRegisterNoble(p)) {
                        if (classed) {
                            classedNow.merge(cls, -1, Integer::sum);
                        } else {
                            nonNow = Math.max(0, nonNow - 1);
                        }
                        ctx.metrics().counter("unregister").inc();
                    }
                }
                case NONE -> {
                }
            }
        }
    }

    private void unregisterIdle(Olympiad oly) {
        for (int id : pool.all()) {
            final Player p = pool.player(id);
            if ((p != null) && !inMatch(p) && oly.isRegistered(p)) {
                oly.unRegisterNoble(p);
            }
        }
    }

    private void shrink(Map<Integer, Integer> target) {
        final Map<Integer, List<Integer>> inWorldByClass = new HashMap<>();
        for (int id : pool.all()) {
            if (pool.player(id) == null) {
                continue;
            }
            inWorldByClass.computeIfAbsent(pool.thirdClass(id), k -> new ArrayList<>()).add(id);
        }
        for (Map.Entry<Integer, List<Integer>> e : inWorldByClass.entrySet()) {
            final int keep = target.getOrDefault(e.getKey(), 0);
            int extra = e.getValue().size() - keep;
            if (extra <= 0) {
                continue;
            }
            for (int id : e.getValue()) {
                if (extra <= 0) {
                    break;
                }
                final Player p = pool.player(id);
                if ((p == null) || inMatch(p) || Olympiad.getInstance().isRegistered(p)) {
                    continue;
                }
                pool.leave(id);
                extra--;
            }
        }
    }

    private static int pickDeficit(Map<Integer, Integer> target, Map<Integer, Integer> have) {
        int best = 0;
        int bestNeed = 0;
        for (int cls : OlympiadRules.COMBAT_THIRD) {
            final int need = target.getOrDefault(cls, 0) - have.getOrDefault(cls, 0);
            if (need > bestNeed) {
                bestNeed = need;
                best = cls;
            }
        }
        return best;
    }

    static boolean inMatch(Player p) {
        return (p != null) && (p.isInOlympiadMode() || (p.getOlympiadGameId() >= 0));
    }

    private static Location gather(int npcId) {
        Spawn best = null;
        long bestD = Long.MAX_VALUE;
        for (Spawn s : SpawnTable.getInstance().getSpawns(npcId)) {
            if (s == null) {
                continue;
            }
            final Npc n = s.getLastSpawn();
            final int x = n != null ? n.getX() : s.getX();
            final int y = n != null ? n.getY() : s.getY();
            final long d = dist2(x - GIRAN_X, y - GIRAN_Y);
            if ((best == null) || (d < bestD)) {
                best = s;
                bestD = d;
            }
        }
        if (best == null) {
            return null;
        }
        final Npc n = best.getLastSpawn();
        if (n != null) {
            return new Location(n.getX(), n.getY(), n.getZ(), n.getHeading());
        }
        return new Location(best.getX(), best.getY(), best.getZ(), best.getHeading());
    }

    private static Location scatter(Location npc, int botId) {
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();
        final double ang = (botId * 2.399_963) + rnd.nextDouble(0.4); // golden-angle-ish plus a little jitter
        final int dist = 90 + Math.abs(botId * 17) % 160;
        int x = npc.getX() + (int) (Math.cos(ang) * dist);
        int y = npc.getY() + (int) (Math.sin(ang) * dist);
        int z = npc.getZ();
        if (GeoEngine.getInstance().hasGeo(x, y)) {
            z = GeoEngine.getInstance().getHeight(x, y, z);
        }
        final int heading = LocationUtil.calculateHeadingFrom(x, y, npc.getX(), npc.getY());
        return new Location(x, y, z, heading);
    }

    private static long dist2(int dx, int dy) {
        return (long) dx * dx + (long) dy * dy;
    }

    record Waiting(int liveNon, int nonSize, Map<Integer, Integer> liveByClass, Map<Integer, Integer> classedSize, Map<Integer, List<Player>> classedMembers, Set<Integer> liveClassed,
        boolean anyLive) {
    }

    static Waiting waiting() {
        final Olympiad oly = Olympiad.getInstance();
        final List<Player> non = oly.hookNonClassedWaiting();
        final Map<Integer, List<Player>> classed = oly.hookClassedWaiting();
        int liveNon = 0;
        for (Player p : non) {
            if (live(p)) {
                liveNon++;
            }
        }
        final Map<Integer, Integer> liveByClass = new HashMap<>();
        final Map<Integer, Integer> classedSize = new HashMap<>();
        final Map<Integer, List<Player>> members = new HashMap<>();
        final Set<Integer> liveClassed = new HashSet<>();
        for (Map.Entry<Integer, List<Player>> e : classed.entrySet()) {
            final List<Player> list = e.getValue() == null ? List.of() : e.getValue();
            members.put(e.getKey(), list);
            classedSize.put(e.getKey(), list.size());
            int liveN = 0;
            for (Player p : list) {
                if (live(p)) {
                    liveN++;
                }
            }
            if (liveN > 0) {
                liveByClass.put(e.getKey(), liveN);
                liveClassed.add(e.getKey());
            }
        }
        return new Waiting(liveNon, non.size(), liveByClass, classedSize, members, liveClassed, (liveNon > 0) || !liveClassed.isEmpty());
    }

    static boolean live(Player p) {
        return (p != null) && !CoreHooks.isBot(p);
    }

    List<String> diag() {
        final Cfg c = cfg;
        final Olympiad oly = Olympiad.getInstance();
        final Waiting w = waiting();
        final List<String> lines = new ArrayList<>();
        lines.add("Olympiad bots: Enabled=" + c.enabled + ", window=" + ((oly != null) && oly.inCompPeriod()) + ", roster " + pool.all().size() + "/" + c.rosterSize
            + ", rivals/class " + c.rivalsPerClass + ", chance " + c.chancePercent + "%, rest " + c.restSeconds + "s");
        lines.add("Waiting: live non-classed " + w.liveNon + "/" + w.nonSize + ", live classed classes " + w.liveClassed);
        int inWorld = 0;
        int noble = 0;
        final Map<Integer, int[]> by = new java.util.TreeMap<>();
        for (int id : pool.all()) {
            final Player p = pool.player(id);
            final int cls = pool.thirdClass(id);
            final int[] row = by.computeIfAbsent(cls, k -> new int[2]);
            row[0]++;
            if (p != null) {
                inWorld++;
                row[1]++;
                if (p.isNoble()) {
                    noble++;
                }
            }
        }
        lines.add("In world " + inWorld + ", noble now " + noble);
        for (Map.Entry<Integer, int[]> e : by.entrySet()) {
            final PlayerClass pc = PlayerClass.getPlayerClass(e.getKey());
            lines.add("  " + e.getKey() + " " + (pc == null ? "?" : pc.name()) + ": " + e.getValue()[0] + " (" + e.getValue()[1] + " in world)");
        }
        return lines;
    }
}
