package org.l2start.bots.core;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.gameserver.data.sql.CharSummonTable;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.network.Disconnection;
import org.l2start.bots.api.Pos;
import org.l2start.bots.director.Scatter;
import org.l2start.platform.api.ModuleLog;

/**
 * Brings bots into the world and out of it, the way the stock server does it for players without a client
 * (OfflineTraderTable for login, Disconnection.storeAndDelete for logout).
 */
public final class BotWorld {
    private final BotRepository repo;
    private final ModuleLog log;
    private volatile boolean requireGeodata = true;
    private volatile Listener listener;
    private final AtomicInteger traderEnters = new AtomicInteger();

    /** Told about bots entering and leaving the world (the brain runtime). */
    public interface Listener {
        void entered(BotRecord r);

        void left(BotRecord r);

        void teleported(BotRecord r);
    }

    void setListener(Listener l) {
        listener = l;
    }

    public boolean requireGeodata() {
        return requireGeodata;
    }

    public BotWorld(BotRepository repo, ModuleLog log) {
        this.repo = repo;
        this.log = log;
    }

    public void setRequireGeodata(boolean value) {
        requireGeodata = value;
    }

    /** Probes the five starting villages (the phase 3 bot area). */
    public static boolean geodataLoaded() {
        final int[][] probe = { { -84318, 244579 }, { 46934, 51467 }, { 9745, 15606 }, { -44836, -112524 }, { 115113, -178212 } };
        for (int[] p : probe) {
            if (GeoEngine.getInstance().hasGeo(p[0], p[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Log a bot in. {@code near} = place it next to this location (admin test) instead of where it logged out.
     * @return null on success, otherwise why not
     */
    public synchronized String enter(BotRecord r, Location near) {
        return enter(r, near, null, null);
    }

    /**
     * Log a bot in exactly at {@code at} after {@code prepare} changed the loaded character (level, class, skills,
     * gear) while nobody can see it (presence director).
     * @return null on success, otherwise why not
     */
    public synchronized String enterPrepared(BotRecord r, Location at, java.util.function.Consumer<Player> prepare) {
        return enter(r, null, at, prepare);
    }

    private String enter(BotRecord r, Location near, Location exact, java.util.function.Consumer<Player> prepare) {
        if ((r.status() != BotRecord.Status.ACTIVE) && (r.status() != BotRecord.Status.TRADER)) {
            return r.name() + " is retired";
        }
        if (r.inWorld()) {
            return r.name() + " is already in the world";
        }
        if (World.getPlayer(r.charId()) != null) {
            return r.name() + " is already in the world (not by bots-core)";
        }
        final Player p = Player.load(r.charId());
        if (p == null) {
            return "cannot load character " + r.charId();
        }
        final Pos wanted = near != null ? WorldScatter.pos(near) : exact != null ? WorldScatter.pos(exact) : new Pos(p.getX(), p.getY(), p.getZ());
        final boolean store = r.status() == BotRecord.Status.TRADER; // a store place is laid out by economy-market
        final org.l2start.bots.director.StarterZones sz = zones;
        final int race = raceOf(r);
        final Pos around = (sz != null) && sz.foreign(race, wanted) ? sz.village(race).center() : wanted;
        final List<Pos> occupied = WorldScatter.occupied(around, p);
        final org.l2start.bots.director.EnterPoint placed = org.l2start.bots.director.EnterPoint.decide(wanted, near != null ? "near" : exact != null ? "exact" : "saved", store, race, sz,
            occupied, terrain, WorldScatter.GROUND, ThreadLocalRandom.current());
        final String source = placed.source();
        final Pos from = placed.from();
        if (placed.raceMoved()) {
            log.info("enter: " + r.name() + " (race " + race + ") not in the starting zone at " + wanted + " (race " + sz.raceAt(wanted) + "): to its own village");
        }
        if (placed.fallback()) {
            log.warn("enter: " + r.name() + ": no free spot around " + from + " (" + source + "), at the centre");
        }
        final int x = placed.pos().x();
        final int y = placed.pos().y();
        final int z = placed.pos().z();
        if (requireGeodata && !GeoEngine.getInstance().hasGeo(x, y)) {
            return r.name() + ": no geodata at " + x + "," + y + " (RequireGeodata)";
        }
        if (r.status() != BotRecord.Status.TRADER) {
            try {
                final int fixed = Materializer.fixGear(p);
                if (fixed > 0) {
                    log.info("enter: " + r.name() + " lv " + p.getLevel() + ": " + fixed + " items above its grade/enchant corrected");
                }
            } catch (Exception e) {
                log.warn("gear check " + r.name() + ": " + e.getMessage());
            }
        }
        if (prepare != null) {
            try {
                prepare.accept(p); // not in the world yet: nobody sees level, class or gear change
            } catch (Exception e) {
                log.warn("cannot prepare " + r, e);
                try {
                    Disconnection.of(p).storeAndDelete();
                } catch (Exception ignored) {
                    // best effort
                }
                return r.name() + ": prepare failed: " + e;
            }
        }
        final long now = System.currentTimeMillis();
        r.enteredWorld(p, now); // registered before it becomes visible: hooks see a bot from the first packet
        try {
            p.setOnlineStatus(true, false);
            p.setRunning(); // like stock GameClient on login: running is the default (a bot has no client to ask for it)
            p.spawnMe(x, y, z);
            p.restoreEffects();
            p.onPlayerEnter();
            final String stale = dismissSummon(p); // a servitor row stored by an older run is never restored for a bot
            if (stale != null) {
                log.warn(stale);
            }
            p.setOnlineStatus(true, true); // online=0 in the database anyway: no client (hidden from site counters)
            p.broadcastUserInfo();
            repo.login(r.botId(), now);
            repo.setInWorld(r.botId(), true);
            r.setRestore(true);
            r.setTrouble(null);
            final Listener l = listener;
            if (l != null) {
                l.entered(r);
            }
            if (store) {
                traderEnters.incrementAndGet();
            } else {
                final org.l2start.bots.api.Assignment a = r.assignment();
                log.info("enter: " + r.name() + " lv " + p.getLevel() + " class " + p.getPlayerClass().getId() + " " + source + " " + from + " -> "
                    + (placed.fallback() ? "fallback " : "scatter ") + placed.pos() + " (" + (int) placed.pos().distance2d(from) + " away, try " + placed.tries()
                    + "), nearest " + nearestText(Scatter.nearest(placed.pos(), occupied)) + ", " + (a == null ? "goal " + r.goal() : a.kind() + " " + a.place()));
            }
            return null;
        } catch (Exception e) {
            log.warn("cannot log in " + r, e);
            r.leftWorld(System.currentTimeMillis());
            try {
                Disconnection.of(p).storeAndDelete();
            } catch (Exception ignored) {
                // best effort
            }
            return r.name() + ": " + e;
        }
    }

    /**
     * Send the bot's summon away for good: not stored for a later login (stock deleteMe stores a servitor for
     * RestoreServitorOnReconnect, and only a client's first action would bring it back - a bot would never see it
     * again, the row would just stay). Also drops such a row left by an older run or a crash.
     */
    static String dismissSummon(Player p) {
        String error = null;
        final Summon s = p.getSummon();
        if (s != null) {
            try {
                s.setRestoreSummon(false);
                if (s.isDead() && s.isServitor()) {
                    s.deleteMe(p);
                } else {
                    s.unSummon(p);
                }
            } catch (Exception e) {
                error = "cannot unsummon the servitor of " + p.getName() + ": " + e;
            }
        }
        try {
            if (CharSummonTable.getInstance().getServitors().containsKey(p.getObjectId())) {
                CharSummonTable.getInstance().removeServitor(p);
            }
        } catch (Exception e) {
            error = "cannot drop the stored servitor of " + p.getName() + ": " + e;
        }
        return error;
    }

    /** Log a bot out (saved like a player who logs out). */
    /** @param comeBack keep it marked "in the world" so the next start brings it back (shutdown, module stop) */
    public synchronized boolean leave(BotRecord r, boolean comeBack) {
        final Player p = r.player();
        if (p == null) {
            return false;
        }
        final Listener l = listener;
        if (l != null) {
            l.left(r); // the brain stops before the body leaves
        }
        try {
            if (p.isSitting()) {
                p.standUp();
            }
            final String unsummoned = dismissSummon(p); // a bot's servitor is never kept for a later login (stock would store and restore it)
            if (unsummoned != null) {
                log.warn(unsummoned);
            }
            Disconnection.of(p).storeAndDelete();
        } catch (Exception e) {
            log.warn("cannot log out " + r, e);
        } finally {
            final long now = System.currentTimeMillis();
            r.leftWorld(now);
            try {
                repo.logout(r.botId(), now);
                if (!comeBack) {
                    repo.setInWorld(r.botId(), false);
                    r.setRestore(false);
                }
            } catch (Exception e) {
                log.warn("cannot store logout time of " + r, e);
            }
        }
        return true;
    }

    /** Like a player pressing "To village" after death. */
    public void returnToTown(BotRecord r) {
        final Player p = r.player();
        if ((p == null) || !p.isDead()) {
            return;
        }
        // same as RequestRestartPoint (stock "To Village"): pending revive + teleport; the revive happens in teleToLocation
        final Location loc = townPoint(r, p);
        if (loc == null) {
            return;
        }
        p.setInstanceId(0);
        p.setIn7sDungeon(false);
        p.setIsPendingRevive(true);
        p.teleToLocation(loc, true);
    }

    /**
     * A player's teleport ends when its client answers with the Appearing packet (stock: Appearing.runImpl calls
     * onTeleported). A bot has no client, so without this it would stay invisible after every teleport
     * (death, gatekeeper, //recall). Called every 500 ms; the delay looks like the client's loading screen.
     * @return how many teleports were finished
     */
    public int completeTeleports(Iterable<BotRecord> bots) {
        int n = 0;
        for (BotRecord r : bots) {
            final Player p = r.player();
            if ((p != null) && p.isTeleporting()) {
                try {
                    p.onTeleported();
                    p.updateUserInfo();
                    n++;
                    final Listener l = listener;
                    if (l != null) {
                        l.teleported(r);
                    }
                } catch (Exception e) {
                    log.warn("cannot finish teleport of " + r, e);
                }
            }
        }
        return n;
    }

    /**
     * Where "To village" / a scroll of escape takes a bot: the stock town point (a few fixed respawn nodes, e.g. the four
     * of Einhovant's School), scattered so bots do not pile up on them.
     */
    Location townPoint(BotRecord r, Player p) {
        final org.l2start.bots.director.StarterZones z = zones;
        if (z != null) {
            // in a starting zone: to a spread point of its village (the stock point of Einhovant's School is a dead end)
            final Pos here = new Pos(p.getX(), p.getY(), p.getZ());
            final int zoneRace = z.raceAt(here);
            if (zoneRace >= 0) {
                final int race = raceOf(r);
                final Pos v = z.villagePoint(race >= 0 ? race : zoneRace, WorldScatter.occupied(z.village(race >= 0 ? race : zoneRace).center(), p), Scatter.CLEAR, terrain,
                    ThreadLocalRandom.current());
                if (v != null) {
                    return WorldScatter.loc(v);
                }
            }
        }
        final Location stock = MapRegionData.getInstance().getTeleToLocation(p, TeleportWhereType.TOWN);
        if (stock == null) {
            return null;
        }
        final Pos at = WorldScatter.pos(stock);
        final List<Pos> occupied = WorldScatter.occupied(at, p);
        if (z != null) {
            occupied.addAll(z.nodesNear(at, 3000)); // off the stock respawn nodes themselves, like off another bot
        }
        final Scatter.Result s = WorldScatter.pick(at, occupied, ThreadLocalRandom.current());
        if (s.fallback()) {
            log.warn("town: " + r.name() + ": no free spot around " + at + ", at the town point");
        }
        return WorldScatter.loc(s.pos());
    }

    private volatile org.l2start.bots.director.StarterZones zones;
    private volatile org.l2start.bots.director.Director.Terrain terrain = q -> q;

    void setStarterZones(org.l2start.bots.director.StarterZones z, org.l2start.bots.director.Director.Terrain t) {
        zones = z;
        terrain = t;
    }

    static int raceOf(BotRecord r) {
        return org.l2start.bots.director.StarterZones.raceOfStartClass(r.persona() == null ? r.classId() : r.persona().classId());
    }

    /** Store traders entered since the last call (one summary line instead of one per trader). */
    int drainTraderEnters() {
        return traderEnters.getAndSet(0);
    }

    private static String nearestText(double d) {
        return Double.isInfinite(d) ? "nobody" : ((int) d) + "";
    }
}
