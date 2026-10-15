package org.l2start.bots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2start.bots.api.Pos;
import org.l2start.bots.director.GearTable;
import org.l2start.bots.persona.Persona;
import org.l2start.bots.trade.TraderPool;

/** bots-core side of the phase 5 traders (service {@link TraderPool}). */
final class TraderService implements TraderPool {
    private final BotsCoreModule core;
    private final BotFactory factory;
    private final BotRepository repo;
    private final Materializer materializer;

    TraderService(BotsCoreModule core, BotFactory factory, BotRepository repo, GearTable gear) {
        this.core = core;
        this.factory = factory;
        this.repo = repo;
        materializer = new Materializer(gear);
    }

    /** Starting class of a profession (stock class tree). */
    static int startClass(int profession) {
        PlayerClass c = PlayerClass.getPlayerClass(profession);
        if (c == null) {
            return 0;
        }
        while (c.getParent() != null) {
            c = c.getParent();
        }
        return c.getId();
    }

    /** The 2nd profession of a class (a 3rd one gives its parent, a 1st or starting one stays). */
    static int second(int profession) {
        final PlayerClass c = PlayerClass.getPlayerClass(profession);
        if ((c != null) && (c.level() == 3) && (c.getParent() != null)) {
            return c.getParent().getId();
        }
        return profession;
    }

    @Override
    public int create(Look look) throws Exception {
        final int start = startClass(look.profession());
        final int future = second(look.profession());
        final BotRecord r = factory.create(start, p -> {
            final String name = ((look.name() != null) && !look.name().isEmpty() && !BotFactory.nameTaken(look.name())) ? look.name() : p.name();
            final int hair = look.female() ? p.hairStyle() : Math.min(p.hairStyle(), 4); // male styles 0-4, female 0-6
            return new Persona(p.version(), p.seed(), name, p.nameStyle(), p.lang(), p.classId(), (future == start) ? p.futureClassId() : future, look.female(), p.face(), hair, p.hairColor(), p.traits(),
                p.chronotype(), p.timezone(), false); // traders are never PK
        });
        repo.setStatus(r.botId(), BotRecord.Status.TRADER.name());
        r.setStatus(BotRecord.Status.TRADER);
        core.ctx().log().info("trader created: " + r.name() + " (class " + future + ", " + (look.female() ? "female" : "male") + ")");
        return r.botId();
    }

    private BotRecord rec(int botId) {
        for (BotRecord r : core.registry().all()) {
            if (r.botId() == botId) {
                return r;
            }
        }
        return null;
    }

    @Override
    public boolean known(int botId) {
        final BotRecord r = rec(botId);
        return (r != null) && (r.status() == BotRecord.Status.TRADER);
    }

    @Override
    public String name(int botId) {
        final BotRecord r = rec(botId);
        return r == null ? null : r.name();
    }

    @Override
    public String lang(int botId) {
        final BotRecord r = rec(botId);
        return ((r == null) || (r.persona() == null)) ? "EN" : r.persona().lang().name();
    }

    @Override
    public double greed(int botId) {
        final BotRecord r = rec(botId);
        return ((r == null) || (r.persona() == null)) ? 0.5 : r.persona().traits().greed();
    }

    @Override
    public double chattiness(int botId) {
        final BotRecord r = rec(botId);
        return ((r == null) || (r.persona() == null)) ? 0.5 : r.persona().traits().chattiness();
    }

    @Override
    public String enter(int botId, int x, int y, int z, int heading, int level, Consumer<Player> ready) {
        final BotRecord r = rec(botId);
        if ((r == null) || (r.status() != BotRecord.Status.TRADER)) {
            return "no trader " + botId;
        }
        final String[] what = { "" };
        final String why = core.world().enterPrepared(r, new Location(x, y, z), p -> {
            what[0] = materializer.prepare(p, r, level);
            materializer.cleanup(p); // shots, potions, scrolls of a hunter: a trader's bag holds only its goods
            p.setHeading(heading);
        });
        if (why != null) {
            return why;
        }
        final Player p = r.player();
        if (p == null) {
            return r.name() + " is not in the world";
        }
        ready.accept(p);
        core.ctx().metrics().counter("trader logins").inc();
        return null;
    }

    @Override
    public boolean leave(int botId) {
        final BotRecord r = rec(botId);
        return (r != null) && core.world().leave(r, false);
    }

    @Override
    public Player player(int botId) {
        final BotRecord r = rec(botId);
        return r == null ? null : r.player();
    }

    @Override
    public List<int[]> watchers() {
        final List<int[]> out = new ArrayList<>();
        for (Player p : World.getPlayers()) {
            if ((p != null) && (p.isOnlineInt() == 1) && !core.registry().isBot(p)) { // with a client: not bots, not offline traders
                out.add(new int[] { p.getX(), p.getY(), p.getZ() });
            }
        }
        final DirectorRunner d = core.director();
        if (d != null) {
            for (Pos a : d.anchors()) {
                out.add(new int[] { a.x(), a.y(), a.z() });
            }
        }
        return out;
    }
}
