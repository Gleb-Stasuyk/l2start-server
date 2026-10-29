package org.l2start.bots.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2start.bots.api.Assignment;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.Pos;
import org.l2start.bots.chat.RbParse;
import org.l2start.bots.director.Director;
import org.l2start.bots.persona.Persona;
import org.l2start.platform.api.ModuleContext;

/**
 * RB party, iteration 1 (docs/RB_PARTY_PLAN.md): a live player writes in trade / shout which roles he needs for a raid
 * boss ("ищу танка и биша на рб"). For every place asked for, one bot of that role at his level (-2..+3) answers: a
 * free bot in the world nearby, else an offline persona of that role, else a new one the presence director creates on
 * demand (DirectorPoolReserve keeps only starters ready) and brings in out of the player's sight. The chosen bot gets
 * {@link BotEvent.RbCall}: it whispers him, runs to him and waits for his invitation. From then on it is "pinned": the
 * director leaves it be until it has left the party and the player (a bot brought in for the call logs out then).
 */
public final class RbRecruit {
    /** How long a bot waits for the invitation (its brain gives up at 3 min; bots-core keeps the pin a little longer). */
    static final long WAIT_MS = 4 * 60_000L;
    /** Another call of the same player within this is the same call (he repeats it in two chats). */
    static final long REPEAT_MS = 20_000L;
    /** In-world bots that hear the call: like shout. */
    static final int HEAR = BotsCoreModule.HEAR_SHOUT;

    /** A bot that answered a call: whose it is and since when. */
    record Pin(int playerId, String player, RbParse.Role role, boolean summoned, long since) {
    }

    private final BotsCoreModule module;
    private final ModuleContext ctx;
    private final Map<Integer, Pin> pins = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastCall = new ConcurrentHashMap<>();
    /** Bots being brought in now (not in the world yet): token -> {playerId, until}. */
    private final Map<Long, long[]> coming = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong tokens = new java.util.concurrent.atomic.AtomicLong();

    RbRecruit(BotsCoreModule module) {
        this.module = module;
        this.ctx = module.ctx();
    }

    /** The director never commands a pinned bot. */
    boolean pinned(int botId) {
        return pins.containsKey(botId);
    }

    /** It answered this player's call (his invitation is not limited by PartyBotsMax). */
    boolean calledBy(int botId, int playerId) {
        final Pin p = pins.get(botId);
        return (p != null) && (p.playerId() == playerId);
    }

    int pinnedCount() {
        return pins.size();
    }

    /**
     * A live player's trade / shout line. @return true when it was an RB party request (bots-core does not hand it to
     * the ordinary LFP replies then)
     */
    boolean onChat(Player from, String text) {
        final List<RbParse.Slot> slots = RbParse.parse(text);
        if (slots.isEmpty() || !ctx.config().getBoolean("RbParty")) {
            return false;
        }
        final long now = System.currentTimeMillis();
        final Long last = lastCall.put(from.getObjectId(), now);
        if ((last != null) && ((now - last) < REPEAT_MS)) {
            return true;
        }
        int free = 9 - (from.getParty() == null ? 1 : from.getParty().getMemberCount()) - waitingFor(from.getObjectId());
        if (free <= 0) {
            return true;
        }
        final int level = from.getLevel();
        final Pos at = new Pos(from.getX(), from.getY(), from.getZ());
        final Set<Integer> taken = new HashSet<>();
        int answered = 0;
        int summoned = 0;
        final List<String> missed = new ArrayList<>();
        for (RbParse.Slot slot : slots) {
            if (free <= 0) {
                break;
            }
            final BotRecord near = inWorld(at, level, slot, taken);
            if (near != null) {
                taken.add(near.botId());
                pin(near, from, slot.role(), false);
                free--;
                answered++;
                continue;
            }
            if (bringIn(from, at, level, slot, taken)) {
                free--;
                summoned++;
            } else {
                missed.add(slot.role().name());
            }
        }
        ctx.log().info("rb party: " + from.getName() + " (lv " + level + ") asked for " + slots.size() + " place(s): " + answered + " bot(s) nearby answer, " + summoned
            + " come" + (missed.isEmpty() ? "" : ", nobody for " + missed));
        ctx.metrics().counter("rbCalls").inc();
        return true;
    }

    private int waitingFor(int playerId) {
        final long now = System.currentTimeMillis();
        int n = 0;
        for (long[] c : coming.values()) {
            if ((c[0] == playerId) && (c[1] > now)) {
                n++;
            }
        }
        for (Map.Entry<Integer, Pin> e : pins.entrySet()) {
            final Pin pin = e.getValue();
            if ((pin.playerId() != playerId) || ((now - pin.since()) > WAIT_MS)) {
                continue;
            }
            final BotRecord r = module.registry().byBotId(e.getKey());
            final Player bp = r == null ? null : r.player();
            final boolean inHisParty = (bp != null) && (bp.getParty() != null) && bp.getParty().getMembers().stream().anyMatch(m -> m.getObjectId() == playerId);
            if (!inHisParty) {
                n++; // still on its way or waiting for the invitation (the party counts the ones in it)
            }
        }
        return Math.max(0, n);
    }

    /** Planned 2nd profession of a bot (its persona; older bots: from the current class). */
    static int future(BotRecord r) {
        if ((r.persona() != null) && (r.persona().futureClassId() > 0)) {
            return r.persona().futureClassId();
        }
        final Player p = r.player();
        return RbParse.futureOf(p == null ? r.classId() : p.getPlayerClass().getId());
    }

    /** A free bot in the world that hears him: the right role and level, alone, alive, nearest first. */
    private BotRecord inWorld(Pos at, int level, RbParse.Slot slot, Set<Integer> taken) {
        BotRecord best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (BotRecord r : module.registry().all()) {
            final Player p = r.player();
            if ((p == null) || (r.status() != BotRecord.Status.ACTIVE) || taken.contains(r.botId()) || pins.containsKey(r.botId()) || module.isAcademyRecruiter(r.botId())
                || ((r.persona() != null) && r.persona().pk()) || p.isDead() || (p.getParty() != null) || p.isInStoreMode() || (p.getKarma() > 0) || p.isTeleporting()) {
                continue;
            }
            if (!slot.fits(future(r)) || !RbParse.levelFits(p.getLevel(), level)) {
                continue;
            }
            final double d = Math.hypot(p.getX() - at.x(), p.getY() - at.y());
            if ((d <= HEAR) && (d < bestD)) {
                best = r;
                bestD = d;
            }
        }
        return best;
    }

    /** An offline persona of that role and level, else a new one, brought in out of his sight. */
    private boolean bringIn(Player from, Pos at, int level, RbParse.Slot slot, Set<Integer> taken) {
        final DirectorRunner d = module.director();
        if ((d == null) || d.stopping()) {
            return false;
        }
        if (module.inWorldActive() >= ctx.config().getInt("MaxBots")) {
            return false;
        }
        final Pos spot = MobiusActuators.hiddenSpotNear(at, null);
        if (spot == null) {
            return false;
        }
        final long now = System.currentTimeMillis();
        final List<BotRecord> offline = new ArrayList<>();
        for (BotRecord r : module.registry().all()) {
            if ((r.status() != BotRecord.Status.ACTIVE) || r.inWorld() || taken.contains(r.botId()) || pins.containsKey(r.botId()) || (r.persona() == null) || r.persona().pk()
                || d.isBusy(r.botId()) || module.isAcademyRecruiter(r.botId())) {
                continue;
            }
            if (slot.fits(r.persona().futureClassId()) && RbParse.levelFits(r.paperLevel(now), level)) {
                offline.add(r);
            }
        }
        offline.sort(Comparator.comparingLong(BotRecord::lastLogout)); // the one who rested longest
        final BotRecord known = offline.isEmpty() ? null : offline.get(0);
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();
        final int lvl = known != null ? known.paperLevel(now) : Math.max(1, Math.min(80, level - 2 + rnd.nextInt(6)));
        final List<Integer> futures = new ArrayList<>(slot.classes());
        final int futureClass = futures.get(rnd.nextInt(futures.size()));
        if (known != null) {
            taken.add(known.botId());
        }
        final Assignment a = new Assignment(0, Assignment.Kind.TOWN, "rb-call", spot, 800, lvl, lvl, List.of(spot), List.of(), null);
        final int playerId = from.getObjectId();
        final String player = from.getName();
        final RbParse.Role role = slot.role();
        final long token = tokens.incrementAndGet();
        coming.put(token, new long[] { playerId, now + 60_000L }); // an arrival that fails is forgotten after a minute
        d.materializeFor(new Director.Command.Materialize(known == null ? 0 : known.botId(), lvl, spot, a, -1), known != null ? null : () -> module.createOne(RbParse.startClassOf(futureClass),
            p -> new Persona(Persona.VERSION, p.seed(), p.name(), p.nameStyle(), p.lang(), p.classId(), futureClass, p.female(), p.face(), p.hairStyle(), p.hairColor(), p.traits(),
                p.chronotype(), p.timezone(), false)), bot -> {
                    coming.remove(token);
                    final Player pl = org.l2jmobius.gameserver.entity.World.getPlayer(playerId);
                    if (pl == null) {
                        module.brains().noteFor(bot, "rb party: " + player + " went offline before it came");
                        pins.put(bot.botId(), new Pin(playerId, player, role, true, System.currentTimeMillis()));
                        module.brains().post(bot, new BotEvent.RbCall(playerId, player, role.name(), true, a.center()));
                        return;
                    }
                    pin(bot, pl, role, true);
                });
        return true;
    }

    private void pin(BotRecord r, Player from, RbParse.Role role, boolean summoned) {
        pins.put(r.botId(), new Pin(from.getObjectId(), from.getName(), role, summoned, System.currentTimeMillis()));
        restockReagents(r); // in-world hunter answering the call: bones / ore before the fight
        module.brains().noteFor(r, "rb party: answers " + from.getName() + " as " + role + (summoned ? " (brought in)" : ""));
        module.brains().post(r, new BotEvent.RbCall(from.getObjectId(), from.getName(), role.name(), summoned, new Pos(from.getX(), from.getY(), from.getZ())));
    }

    /** Top up skill reagents so a hunter who spent bones on the ground still nukes the raid. */
    private void restockReagents(BotRecord r) {
        final Player p = r.player();
        final DirectorRunner d = module.director();
        if ((p == null) || (d == null)) {
            return;
        }
        d.materializer().skillReagents(p);
    }

    /**
     * Every 10 s: a pin ends when the bot left the world, or (a bot that lives here anyway) when its wait is over and
     * it is in no party with a live player. A bot brought in for the call keeps its pin until it logs out.
     */
    void tick() {
        final long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Pin> e : pins.entrySet()) {
            final BotRecord r = module.registry().byBotId(e.getKey());
            final Player p = r == null ? null : r.player();
            if (p == null) {
                pins.remove(e.getKey());
                continue;
            }
            final Pin pin = e.getValue();
            final boolean withLive = withLive(p);
            if (!pin.summoned() && !withLive && ((now - pin.since()) > WAIT_MS)) {
                pins.remove(e.getKey());
            } else if (pin.summoned() && !withLive && ((now - pin.since()) > (WAIT_MS * 3)) && (module.director() != null)) {
                // its brain should have left long ago (SoE + log out): the director takes it out
                module.director().requestLogout(r, "rb party over");
            }
            if (withLive && ((now - pin.since()) > WAIT_MS)) {
                pins.put(e.getKey(), new Pin(pin.playerId(), pin.player(), pin.role(), pin.summoned(), now - WAIT_MS + 60_000L)); // in his party: renew
            }
        }
        lastCall.values().removeIf(t -> (now - t) > REPEAT_MS);
        coming.values().removeIf(c -> c[1] <= now);
    }

    private boolean withLive(Player p) {
        final org.l2jmobius.gameserver.entity.groups.Party pt = p.getParty();
        if (pt == null) {
            return false;
        }
        for (Player m : pt.getMembers()) {
            if ((m != null) && (m != p) && !module.registry().isBot(m) && m.isOnline()) {
                return true;
            }
        }
        return false;
    }

    String statsLine() {
        return "rb party: " + pins.size() + " bot(s) answering players";
    }
}
