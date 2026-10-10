package org.l2start.bots.core;

import java.util.concurrent.ThreadLocalRandom;

import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.network.Disconnection;
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
        if (r.status() != BotRecord.Status.ACTIVE) {
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
        int x = p.getX();
        int y = p.getY();
        int z = p.getZ();
        if (near != null) {
            final Location spot = spotNear(near);
            x = spot.getX();
            y = spot.getY();
            z = spot.getZ();
        } else if (exact != null) {
            x = exact.getX();
            y = exact.getY();
            z = exact.getZ();
        }
        if (requireGeodata && !GeoEngine.getInstance().hasGeo(x, y)) {
            return r.name() + ": no geodata at " + x + "," + y + " (RequireGeodata)";
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
        final Location loc = MapRegionData.getInstance().getTeleToLocation(p, TeleportWhereType.TOWN);
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

    private static Location spotNear(Location c) {
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 10; i++) {
            final int x = c.getX() + rnd.nextInt(-200, 201);
            final int y = c.getY() + rnd.nextInt(-200, 201);
            if (GeoEngine.getInstance().canMoveToTarget(c.getX(), c.getY(), c.getZ(), x, y, c.getZ(), 0)) {
                return new Location(x, y, GeoEngine.getInstance().getHeight(x, y, c.getZ()));
            }
        }
        return new Location(c.getX(), c.getY(), c.getZ());
    }
}
