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
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.groups.PartyMessageType;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillLearn;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.AskJoinParty;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2start.bots.trade.TraderPool;
import org.l2start.platform.api.ModuleContext;

/**
 * Paid buffer bots (PP + SE). Trade adena via P6-BOT-TRADE, then cast. Berserker needs party:
 * invite the payer; if they refuse, skip PARTY_MEMBER skills.
 */
final class Buffers {
    private static final class Buffer {
        final Towns.Spot spot;
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
        boolean invitedParty;
        List<int[]> queue = List.of();
        int index;

        Buffer(Towns.Spot spot) {
            this.spot = spot;
        }
    }

    private static final int NEAR = 6000;
    private static final long STAY_MS = 5 * 60_000L;
    private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();
    /** key = classId + ":" + level */
    private final Map<String, Map<Integer, Integer>> levels = new ConcurrentHashMap<>();
    private final ModuleContext ctx;
    private final TraderPool pool;

    private Buffers(ModuleContext c, TraderPool pool) {
        ctx = c;
        this.pool = pool;
        for (Towns.Spot s : Towns.ALL) {
            buffers.put(s.id(), new Buffer(s));
        }
        c.scheduler().fixedRate("buffers-tick", java.time.Duration.ofSeconds(20), java.time.Duration.ofMillis(300), this::tick);
        c.scheduler().fixedRate("buffers-presence", java.time.Duration.ofSeconds(20), java.time.Duration.ofSeconds(10), this::presence);
    }

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
            leaveParty(p);
            pool.leave(b.botId);
        }
    }

    private void presence() {
        final long now = System.currentTimeMillis();
        for (Buffer b : buffers.values()) {
            boolean near = false;
            for (Player p : World.getPlayers()) {
                if ((p != null) && (p.isOnlineInt() == 1) && !org.l2start.platform.hooks.CoreHooks.isBot(p)
                    && (Math.hypot(p.getX() - b.spot.x(), p.getY() - b.spot.y()) <= NEAR)) {
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
                        ctx.log().warn("buffer " + b.spot.id() + ": " + e);
                    }
                }
            } else if ((bot != null) && ((now - b.seenAt) > STAY_MS)) {
                synchronized (b) {
                    if ((b.customer == 0) && (b.requester == 0) && (bot.getActiveTradeList() == null)) {
                        leaveParty(bot);
                        pool.leave(b.botId);
                        ctx.log().info("buffer " + b.spot.id() + ": nobody around, logged out");
                    }
                }
            }
        }
    }

    long price(Towns.Spot s) {
        return Math.round(s.price() * ctx.config().getDouble("PriceMultiplier"));
    }

    private Map<Integer, Integer> levelsAt(Towns.Spot s) {
        final String key = s.clazz().getId() + ":" + s.level();
        return levels.computeIfAbsent(key, k -> {
            final List<BuffLevels.Learn> tree = new ArrayList<>();
            for (SkillLearn l : SkillTreeData.getInstance().getCompleteClassSkillTree(s.clazz()).values()) {
                tree.add(new BuffLevels.Learn(l.getSkillId(), l.getSkillLevel(), l.getGetLevel()));
            }
            return BuffLevels.at(tree, Towns.buffs(s.kind()), s.level());
        });
    }

    private void enter(Buffer b) throws Exception {
        final String table = ctx.db().table("bot");
        b.botId = ctx.db().queryOne("SELECT bot_id FROM " + table + " WHERE town = ?", rs -> rs.getInt(1), b.spot.id()).orElse(0);
        if ((b.botId <= 0) || !pool.known(b.botId)) {
            b.botId = pool.create(new TraderPool.Look(b.spot.clazz().getId(), true, null));
            ctx.db().update("REPLACE INTO " + table + " (town, bot_id) VALUES (?, ?)", b.spot.id(), b.botId);
        }
        final Towns.Spot t = b.spot;
        final String why = pool.enter(b.botId, t.x(), t.y(), t.z(), t.heading(), t.level(), p -> {
            final Map<Integer, Integer> lv = levelsAt(t);
            for (Map.Entry<Integer, Integer> e : lv.entrySet()) {
                final Skill s = SkillData.getInstance().getSkill(e.getKey(), e.getValue());
                if (s != null) {
                    p.addSkill(s, false);
                }
            }
            p.setTitle(Towns.title(t));
            p.setCurrentMp(p.getMaxMp());
            p.broadcastUserInfo();
            p.sitDown();
        });
        ctx.log().info("buffer " + t.id() + ": " + (why == null ? pool.name(b.botId) + " '" + Towns.title(t) + "', " + levelsAt(t).size() + " buffs"
            : "not in the world: " + why));
    }

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
            return true;
        }
    }

    private void deny(Player requester, Player bot) {
        requester.setActiveRequester(null);
        bot.setActiveRequester(null);
        requester.onTransactionResponse();
    }

    private void tick() {
        final long now = System.currentTimeMillis();
        for (Buffer b : buffers.values()) {
            try {
                final Player bot = (b.botId > 0) ? pool.player(b.botId) : null;
                if (bot == null) {
                    continue;
                }
                if (b.requester != 0) {
                    accept(b, bot, now);
                } else if (b.tradeSince > 0) {
                    trade(b, bot, now);
                } else if (b.customer != 0) {
                    buff(b, bot, now);
                }
            } catch (Exception e) {
                ctx.log().warn("buffer " + b.spot.id() + " tick: " + e);
            }
        }
    }

    private void accept(Buffer b, Player bot, long now) {
        final Player r = World.getPlayer(b.requester);
        if ((r == null) || !r.isOnline() || (r.getActiveRequester() != bot)) {
            b.requester = 0;
            bot.setActiveRequester(null);
            return;
        }
        if (now < b.acceptAt) {
            return;
        }
        bot.startTrade(r);
        bot.setActiveRequester(null);
        r.onTransactionResponse();
        b.requester = 0;
        b.tradeSince = now;
        b.confirmAt = 0;
        final String kind = b.spot.kind() == Towns.Kind.SE ? "SE " : "";
        whisper(bot, r, kind + "баф " + (isMage(r) ? "мага" : "воина") + " — " + String.format("%,d", price(b.spot)) + " адены. Положи адену в окно и подтверди.");
        ctx.log().info("buffer " + b.spot.id() + ": trade with " + r.getName());
    }

    static boolean isMage(Player p) {
        final PlayerClass c = p.getPlayerClass();
        return (c != null) && c.isMage();
    }

    private void trade(Buffer b, Player bot, long now) {
        final TradeList list = bot.getActiveTradeList();
        if (list == null) {
            b.tradeSince = 0;
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
        final long price = price(b.spot);
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
                    list.confirm();
                }
            }
        }
        if (v != Deal.Verdict.OK) {
            bot.cancelActiveTrade();
            b.tradeSince = 0;
            whisper(bot, p, v == Deal.Verdict.NOT_ENOUGH ? "Мало: баф стоит " + String.format("%,d", price) + " адены." : "Только адена, " + String.format("%,d", price) + ", больше ничего не клади.");
            return;
        }
        final long got = bot.getAdena() - before;
        b.tradeSince = 0;
        if (got < price) {
            ctx.log().warn("buffer " + b.spot.id() + ": exchange with " + p.getName() + " did not run (got " + got + ")");
            return;
        }
        bot.reduceAdena(ItemProcessType.DESTROY, (int) Math.min(Integer.MAX_VALUE, bot.getAdena()), null, false);
        b.customer = p.getObjectId();
        b.paidAt = now;
        b.warnedFar = false;
        b.resting = false;
        b.invitedParty = false;
        b.queue = Deal.plan(levelsAt(b.spot), Towns.buffs(b.spot.kind()), b.spot.kind(), isMage(p));
        b.index = 0;
        inviteParty(bot, p, b);
        ctx.metrics().counter("sets").inc();
        ctx.metrics().counter("adena").add(got);
        ctx.log().info("buffer " + b.spot.id() + ": " + p.getName() + " paid " + got + ", " + b.queue.size() + " buffs (" + (isMage(p) ? "mage" : "warrior") + ")");
    }

    /** AskJoinParty for Berserker (PARTY_MEMBER); player may refuse — then we skip those skills. */
    private void inviteParty(Player bot, Player p, Buffer b) {
        if (b.queue.stream().noneMatch(a -> Towns.isPartyMemberBuff(a[0]))) {
            return;
        }
        if (p.isInParty() || bot.isProcessingRequest() || p.isProcessingRequest()) {
            return;
        }
        try {
            final PartyDistributionType dist = PartyDistributionType.FINDERS_KEEPERS;
            if (bot.getParty() != null) {
                leaveParty(bot);
            }
            p.sendPacket(new AskJoinParty(bot.getName(), dist));
            p.setActiveRequester(bot);
            bot.onTransactionRequest(p);
            bot.setPartyDistributionType(dist);
            b.invitedParty = true;
            whisper(bot, p, "Прими пати — нужен Берсеркер. Откажешься — остальное всё равно бафну.");
        } catch (Exception e) {
            ctx.log().warn("buffer " + b.spot.id() + " party invite: " + e.getMessage());
        }
    }

    private static boolean sameParty(Player bot, Player c) {
        final Party bp = bot.getParty();
        final Party cp = c.getParty();
        return (bp != null) && (cp != null) && (bp == cp);
    }

    private static void leaveParty(Player bot) {
        final Party party = bot.getParty();
        if (party == null) {
            return;
        }
        if (party.isLeader(bot)) {
            party.disbandParty();
        } else {
            party.removePartyMember(bot, PartyMessageType.LEFT);
        }
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
        if (Towns.isPartyMemberBuff(next[0]) && !sameParty(bot, c)) {
            // wait a few seconds for invite accept, then skip
            if ((now - b.paidAt) < 8_000L) {
                return;
            }
            ctx.log().info("buffer " + b.spot.id() + ": skip " + next[0] + " (not in party with " + c.getName() + ")");
            b.index++;
            return;
        }
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
            b.resting = true;
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
        leaveParty(bot);
        ctx.log().info("buffer " + b.spot.id() + ": " + b.index + "/" + b.queue.size() + " buffs cast");
        b.customer = 0;
        b.queue = List.of();
        b.index = 0;
        b.invitedParty = false;
        bot.setTarget(null);
        if (!bot.isSitting()) {
            bot.sitDown();
        }
    }

    private static void whisper(Player bot, Player to, String text) {
        to.sendPacket(new CreatureSay(bot, ChatType.WHISPER, bot.getName(), text));
    }

    Map<String, Object> describe() {
        final Map<String, Object> m = new HashMap<>();
        buffers.forEach((k, v) -> m.put(k, v.botId));
        return m;
    }
}
