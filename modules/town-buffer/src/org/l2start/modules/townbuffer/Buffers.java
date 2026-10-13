package org.l2start.modules.townbuffer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillLearn;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2start.bots.trade.TraderPool;
import org.l2start.platform.api.ModuleContext;

/**
 * Runtime of the buffers (only with bots-core). EXTRAS_PLAN §5, the x100 way: a paid buffer is a bot player (a Prophet persona of bots-core, status TRADER: no
 * director, no brain) sitting at a gatekeeper. Trade it the price in adena (stock trade window; the bot answers the
 * request through hook P6-BOT-TRADE and confirms last, only a deal of adena alone, at least the price), then it stands
 * up and casts the full set for a mage or a warrior (by the payer's class), at the skill levels a Prophet of its level
 * has, stock duration.
 */
final class Buffers {
    private static final class Buffer {
        final Towns.Town town;
        int botId;
        int requester;
        long acceptAt;
        long tradeSince;
        long confirmAt;
        int customer;
        long paidAt;
        long seenAt;
        boolean warnedFar;
        boolean resting;
        List<int[]> queue = List.of();
        int index;

        Buffer(Towns.Town town) {
            this.town = town;
        }
    }

    private static final int NEAR = 6000;
    private static final long STAY_MS = 5 * 60_000L;
    private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, Integer>> levels = new ConcurrentHashMap<>();
    private final ModuleContext ctx;
    private final TraderPool pool;

    private Buffers(ModuleContext c, TraderPool pool) {
        ctx = c;
        this.pool = pool;
        for (Towns.Town t : Towns.ALL) {
            buffers.put(t.name(), new Buffer(t));
        }
        c.scheduler().fixedRate("buffers-tick", java.time.Duration.ofSeconds(20), java.time.Duration.ofMillis(300), this::tick);
        c.scheduler().fixedRate("buffers-presence", java.time.Duration.ofSeconds(20), java.time.Duration.ofSeconds(10), this::presence);
    }

    /** @return null without the bots-core service (the class TraderPool is touched only here and below). */
    static Buffers start(ModuleContext c) {
        final TraderPool pool = c.services().find(TraderPool.class).orElse(null);
        return pool == null ? null : new Buffers(c, pool);
    }

    void stop() {
        for (Buffer b : buffers.values()) {
            final Player p = (b.botId > 0) ? pool.player(b.botId) : null;
            if (p == null) {
                continue;
            }
            if (p.getActiveTradeList() != null) {
                p.cancelActiveTrade();
            }
            pool.leave(b.botId);
        }
    }

    /** Like the Giran traders: a buffer is in the world only while a real player (with a client) is near its town. */
    private void presence() {
        final long now = System.currentTimeMillis();
        for (Buffer b : buffers.values()) {
            boolean near = false;
            for (Player p : World.getPlayers()) {
                if ((p != null) && (p.isOnlineInt() == 1) && !org.l2start.platform.hooks.CoreHooks.isBot(p) && (Math.hypot(p.getX() - b.town.x(), p.getY() - b.town.y()) <= NEAR)) {
                    near = true;
                    break;
                }
            }
            final Player bot = (b.botId > 0) ? pool.player(b.botId) : null;
            if (near) {
                b.seenAt = now;
                if (bot == null) {
                    try {
                        enter(b);
                    } catch (Exception e) {
                        ctx.log().warn("buffer " + b.town.name() + ": " + e);
                    }
                }
            } else if ((bot != null) && ((now - b.seenAt) > STAY_MS)) {
                synchronized (b) {
                    if ((b.customer == 0) && (b.requester == 0) && (bot.getActiveTradeList() == null)) {
                        pool.leave(b.botId);
                        ctx.log().info("buffer " + b.town.name() + ": nobody around, logged out");
                    }
                }
            }
        }
    }

    long price(Towns.Town t) {
        return Math.round(t.price() * ctx.config().getDouble("PriceMultiplier"));
    }

    /** Buff id -> level of a Prophet of this level (stock skill tree). */
    private Map<Integer, Integer> levelsAt(int level) {
        return levels.computeIfAbsent(level, lv -> {
            final List<BuffLevels.Learn> tree = new ArrayList<>();
            for (SkillLearn l : SkillTreeData.getInstance().getCompleteClassSkillTree(PlayerClass.PROPHET).values()) {
                tree.add(new BuffLevels.Learn(l.getSkillId(), l.getSkillLevel(), l.getGetLevel()));
            }
            return BuffLevels.at(tree, Towns.BUFFS, lv);
        });
    }

    // ---- login

    private void enter(Buffer b) throws Exception {
        final String table = ctx.db().table("bot");
        b.botId = ctx.db().queryOne("SELECT bot_id FROM " + table + " WHERE town = ?", rs -> rs.getInt(1), b.town.name()).orElse(0);
        if ((b.botId <= 0) || !pool.known(b.botId)) {
            b.botId = pool.create(new TraderPool.Look(PlayerClass.PROPHET.getId(), true, null));
            ctx.db().update("REPLACE INTO " + table + " (town, bot_id) VALUES (?, ?)", b.town.name(), b.botId);
        }
        final Towns.Town t = b.town;
        final String why = pool.enter(b.botId, t.x(), t.y(), t.z(), t.heading(), t.level(), p -> {
            final Map<Integer, Integer> lv = levelsAt(t.level());
            for (Map.Entry<Integer, Integer> e : lv.entrySet()) {
                final Skill s = SkillData.getInstance().getSkill(e.getKey(), e.getValue());
                if (s != null) {
                    p.addSkill(s, false);
                }
            }
            p.setTitle(Towns.title(t.level(), price(t)));
            p.setCurrentMp(p.getMaxMp());
            p.broadcastUserInfo();
            p.sitDown();
        });
        ctx.log().info("buffer " + t.name() + ": " + (why == null ? pool.name(b.botId) + " '" + Towns.title(t.level(), price(t)) + "', " + levelsAt(t.level()).size() + " buffs" : "not in the world: " + why));
    }

    // ---- trade request (game thread, hook P6-BOT-TRADE)

    private Buffer of(Player bot) {
        for (Buffer b : buffers.values()) {
            if ((b.botId > 0) && (pool.player(b.botId) == bot)) {
                return b;
            }
        }
        return null;
    }

    boolean onTradeRequest(Player requester, Player bot) {
        final Buffer b = of(bot);
        if (b == null) {
            return false;
        }
        synchronized (b) {
            if ((b.customer != 0) || (b.requester != 0) || (bot.getActiveTradeList() != null)) {
                deny(requester, bot);
                whisper(bot, requester, "Сейчас бафаю другого, подожди немного.");
                return true;
            }
            b.requester = requester.getObjectId();
            b.acceptAt = System.currentTimeMillis() + ctx.config().getDuration("AnswerDelay").toMillis();
        }
        if (bot.isSitting()) {
            bot.standUp();
        }
        return true;
    }

    private static void deny(Player requester, Player bot) {
        bot.setActiveRequester(null);
        requester.onTransactionResponse();
        final SystemMessage msg = new SystemMessage(SystemMessageId.S1_HAS_DENIED_YOUR_REQUEST_TO_TRADE);
        msg.addString(bot.getName());
        requester.sendPacket(msg);
    }

    private static void whisper(Player bot, Player to, String text) {
        to.sendPacket(new CreatureSay(bot, ChatType.WHISPER, bot.getName(), text));
    }

    // ---- tick (task thread)

    private void tick() {
        final long now = System.currentTimeMillis();
        for (Buffer b : buffers.values()) {
            final Player bot = (b.botId > 0) ? pool.player(b.botId) : null;
            if ((bot == null) || !bot.isOnline()) {
                continue;
            }
            try {
                synchronized (b) {
                    step(b, bot, now);
                }
            } catch (Exception e) {
                ctx.log().warn("buffer " + b.town.name() + ": " + e);
                b.requester = 0;
                b.customer = 0;
                b.tradeSince = 0;
            }
        }
    }

    private void step(Buffer b, Player bot, long now) {
        if (b.requester != 0) {
            accept(b, bot, now);
            return;
        }
        if (b.tradeSince != 0) {
            trade(b, bot, now);
            return;
        }
        if (b.customer != 0) {
            buff(b, bot, now);
            return;
        }
        if (!bot.isSitting() && !bot.isCastingNow() && !bot.isDead()) {
            bot.sitDown(); // a buffer in town sits (and restores MP)
        }
    }

    private void accept(Buffer b, Player bot, long now) {
        final Player r = World.getPlayer(b.requester);
        if ((r == null) || !r.isOnline() || (bot.getActiveRequester() != r) || r.isRequestExpired()) {
            b.requester = 0;
            bot.setActiveRequester(null);
            return;
        }
        if (now < b.acceptAt) {
            return;
        }
        bot.startTrade(r); // like AnswerTradeRequest "yes"
        bot.setActiveRequester(null);
        r.onTransactionResponse();
        b.requester = 0;
        b.tradeSince = now;
        b.confirmAt = 0;
        whisper(bot, r, "Полный баф " + (isMage(r) ? "мага" : "воина") + " — " + String.format("%,d", price(b.town)) + " адены. Положи адену в окно и подтверди.");
        ctx.log().info("buffer " + b.town.name() + ": trade with " + r.getName());
    }

    static boolean isMage(Player p) {
        final PlayerClass c = p.getPlayerClass();
        return (c != null) && c.isMage(); // old x100: by the payer's class
    }

    private void trade(Buffer b, Player bot, long now) {
        final TradeList list = bot.getActiveTradeList();
        if (list == null) {
            b.tradeSince = 0; // cancelled by the player
            return;
        }
        final Player p = list.getPartner();
        if ((p == null) || !p.isOnline()) {
            bot.cancelActiveTrade();
            b.tradeSince = 0;
            return;
        }
        if ((now - b.tradeSince) > ctx.config().getDuration("TradeTimeout").toMillis()) {
            bot.cancelActiveTrade();
            b.tradeSince = 0;
            whisper(bot, p, "Время вышло, попробуй ещё раз.");
            return;
        }
        final TradeList theirs = p.getActiveTradeList();
        if ((theirs == null) || !theirs.isConfirmed()) {
            b.confirmAt = 0;
            return;
        }
        if (b.confirmAt == 0) {
            b.confirmAt = now + ctx.config().getDuration("AnswerDelay").toMillis();
            return;
        }
        if (now < b.confirmAt) {
            return;
        }
        final long price = price(b.town);
        final TradeList sync1 = (bot.getObjectId() > p.getObjectId()) ? theirs : list;
        final TradeList sync2 = (sync1 == list) ? theirs : list;
        final long before = bot.getAdena();
        Deal.Verdict v;
        synchronized (sync1) {
            synchronized (sync2) {
                if ((p.getActiveTradeList() != theirs) || (bot.getActiveTradeList() != list) || !theirs.isConfirmed() || theirs.isLocked() || list.isLocked()) {
                    return;
                }
                final List<long[]> lines = new ArrayList<>();
                for (TradeItem ti : theirs.getItems()) {
                    lines.add(new long[] { ti.getItem().getId(), ti.getCount() });
                }
                v = Deal.check(lines, list.getItems().size(), price);
                if (v == Deal.Verdict.OK) {
                    list.confirm(); // the player is confirmed: the stock exchange runs now
                }
            }
        }
        if (v != Deal.Verdict.OK) {
            bot.cancelActiveTrade();
            b.tradeSince = 0;
            whisper(bot, p, v == Deal.Verdict.NOT_ENOUGH ? "Мало: полный баф стоит " + String.format("%,d", price) + " адены." : "Только адена, " + String.format("%,d", price) + ", больше ничего не клади.");
            return;
        }
        final long got = bot.getAdena() - before;
        b.tradeSince = 0;
        if (got < price) {
            ctx.log().warn("buffer " + b.town.name() + ": exchange with " + p.getName() + " did not run (got " + got + ")");
            return;
        }
        bot.reduceAdena(ItemProcessType.DESTROY, (int) Math.min(Integer.MAX_VALUE, bot.getAdena()), null, false); // a sink: the bot keeps nothing
        b.customer = p.getObjectId();
        b.paidAt = now;
        b.warnedFar = false;
        b.resting = false;
        b.queue = Deal.plan(levelsAt(b.town.level()), isMage(p));
        b.index = 0;
        ctx.metrics().counter("sets").inc();
        ctx.metrics().counter("adena").add(got);
        ctx.log().info("buffer " + b.town.name() + ": " + p.getName() + " paid " + got + ", " + b.queue.size() + " buffs (" + (isMage(p) ? "mage" : "warrior") + ")");
    }

    private void buff(Buffer b, Player bot, long now) {
        final Player c = World.getPlayer(b.customer);
        if ((c == null) || !c.isOnline() || c.isDead() || (b.index >= b.queue.size())) {
            done(b, bot, c);
            return;
        }
        if (bot.calculateDistance3D(c) > ctx.config().getInt("CastRange")) {
            if ((now - b.paidAt) > ctx.config().getDuration("BuffWait").toMillis()) {
                done(b, bot, c);
                return;
            }
            if (!b.warnedFar) {
                b.warnedFar = true;
                whisper(bot, c, "Подойди поближе, бафну.");
            }
            return;
        }
        if (bot.isCastingNow()) {
            return;
        }
        final int[] next = b.queue.get(b.index);
        Skill s = bot.getKnownSkill(next[0]);
        if ((s == null) || (s.getLevel() != next[1])) {
            final Skill want = SkillData.getInstance().getSkill(next[0], next[1]);
            if (want == null) {
                b.index++;
                return;
            }
            bot.addSkill(want, false);
            s = want;
        }
        final double mpPct = (bot.getCurrentMp() * 100) / Math.max(1, bot.getMaxMp());
        if (bot.isSitting()) {
            if ((bot.getCurrentMp() >= s.getMpConsume()) && (!b.resting || (mpPct >= ctx.config().getInt("StandMpPercent")))) {
                b.resting = false;
                bot.standUp();
            }
            return;
        }
        if (bot.getCurrentMp() < s.getMpConsume()) {
            b.resting = true; // out of MP: sit up to StandMpPercent before going on
            bot.sitDown();
            return;
        }
        if (bot.isSkillDisabled(s)) {
            return;
        }
        bot.setTarget(c);
        if (bot.useMagic(s, false, true)) {
            b.index++;
        }
    }

    private void done(Buffer b, Player bot, Player c) {
        if ((c != null) && c.isOnline() && (b.index >= b.queue.size()) && !b.queue.isEmpty()) {
            whisper(bot, c, "Готово, удачной охоты!");
        }
        ctx.log().info("buffer " + b.town.name() + ": " + b.index + "/" + b.queue.size() + " buffs cast");
        b.customer = 0;
        b.queue = List.of();
        b.index = 0;
        bot.setTarget(null);
    }

    Map<String, Object> describe() {
        final Map<String, Object> m = new HashMap<>();
        buffers.forEach((k, v) -> m.put(k, v.botId));
        return m;
    }
}
