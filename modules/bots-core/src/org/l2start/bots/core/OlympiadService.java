package org.l2start.bots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2start.bots.director.GearTable;
import org.l2start.bots.olympiad.OlympiadPool;
import org.l2start.bots.persona.Persona;

/** bots-core side of the phase 9 olympiad nobles (service {@link OlympiadPool}). */
final class OlympiadService implements OlympiadPool {
    private final BotsCoreModule core;
    private final BotFactory factory;
    private final BotRepository repo;
    private final Materializer materializer;

    OlympiadService(BotsCoreModule core, BotFactory factory, BotRepository repo, GearTable gear) {
        this.core = core;
        this.factory = factory;
        this.repo = repo;
        materializer = new Materializer(gear);
        materializer.setGradeLag(0, 0); // olympiad: wear the grade of the level (S at 76+)
    }

    static int startClass(int third) {
        final PlayerClass c = PlayerClass.getPlayerClass(third);
        return c == null ? 0 : c.getRootClass().getId();
    }

    static int secondClass(int third) {
        PlayerClass c = PlayerClass.getPlayerClass(third);
        if (c == null) {
            return third;
        }
        while ((c.level() > 2) && (c.getParent() != null)) {
            c = c.getParent();
        }
        return c.getId();
    }

    static int thirdOf(Persona p) {
        if (p == null) {
            return 0;
        }
        final PlayerClass second = PlayerClass.getPlayerClass(p.futureClassId());
        if (second == null) {
            return p.futureClassId();
        }
        if (second.level() >= 3) {
            return second.getId();
        }
        for (PlayerClass third : second.getNextClasses()) {
            return third.getId();
        }
        return p.futureClassId();
    }

    @Override
    public int create(Look look) throws Exception {
        final PlayerClass third = PlayerClass.getPlayerClass(look.thirdClass());
        if ((third == null) || (third.level() != 3)) {
            throw new IllegalArgumentException("olympiad class " + look.thirdClass() + " is not a 3rd profession");
        }
        final int start = startClass(look.thirdClass());
        final int second = secondClass(look.thirdClass());
        final BotRecord r = factory.create(start, p -> {
            final int hair = p.female() ? p.hairStyle() : Math.min(p.hairStyle(), 4);
            return new Persona(p.version(), p.seed(), p.name(), p.nameStyle(), p.lang(), p.classId(), second, p.female(), p.face(), hair, p.hairColor(), p.traits(),
                p.chronotype(), p.timezone(), false); // olympiad nobles are never PK
        });
        repo.setStatus(r.botId(), BotRecord.Status.OLYMPIAD.name());
        r.setStatus(BotRecord.Status.OLYMPIAD);
        core.ctx().log().info("olympiad noble created: " + r.name() + " (3rd " + look.thirdClass() + ", " + (r.persona().female() ? "female" : "male") + ")");
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
        return (r != null) && (r.status() == BotRecord.Status.OLYMPIAD);
    }

    @Override
    public String name(int botId) {
        final BotRecord r = rec(botId);
        return r == null ? null : r.name();
    }

    @Override
    public int thirdClass(int botId) {
        final BotRecord r = rec(botId);
        if (r == null) {
            return 0;
        }
        final Player p = r.player();
        if (p != null) {
            return p.getPlayerClass().getId();
        }
        return thirdOf(r.persona());
    }

    @Override
    public List<Integer> all() {
        final List<Integer> out = new ArrayList<>();
        for (BotRecord r : core.registry().sorted()) {
            if (r.status() == BotRecord.Status.OLYMPIAD) {
                out.add(r.botId());
            }
        }
        return out;
    }

    @Override
    public String enter(int botId, int x, int y, int z, int heading, int level, Consumer<Player> ready) {
        final BotRecord r = rec(botId);
        if ((r == null) || (r.status() != BotRecord.Status.OLYMPIAD)) {
            return "no olympiad noble " + botId;
        }
        final String why = core.world().enterPrepared(r, new Location(x, y, z), p -> {
            materializer.prepare(p, r, level, true);
            p.setHeading(heading);
            if (!p.isNoble()) {
                p.setNoble(true);
            }
        });
        if (why != null) {
            return why;
        }
        final Player p = r.player();
        if (p == null) {
            return r.name() + " is not in the world";
        }
        if (ready != null) {
            ready.accept(p);
        }
        core.ctx().metrics().counter("olympiad logins").inc();
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
}
