package org.l2start.bots.clan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.sql.CrestTable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.Crest;
import org.l2jmobius.gameserver.entity.clan.enums.CrestType;
import org.l2start.bots.core.BotRecord;
import org.l2start.bots.core.BotRegistry;
import org.l2start.platform.api.ModuleContext;

/**
 * Phase 7d/7e: bot clans — paper membership; stock {@link Clan} on leader enter; ClanBotShare fill; leave to join a
 * live player's clan.
 */
public final class ClanService {
    public static final int BIG_TARGET = 40;
    public static final int BIG_LEVEL = 8;

    private final ModuleContext ctx;
    private final BotRegistry registry;
    private final ClanStore store;
    private final List<ClanTemplate> templates;
    private final Map<Integer, Integer> botClan = new ConcurrentHashMap<>(); // botId -> bot-clan id (0 = none / player clan)
    private final Map<Integer, ClanStore.Row> byClan = new ConcurrentHashMap<>();
    private final Map<Integer, Long> inviteAt = new ConcurrentHashMap<>(); // live charId -> last invite ms
    private final Map<Integer, Long> adenaSpentDay = new ConcurrentHashMap<>(); // clanId -> spent today
    private final Map<Integer, Long> adenaDayStart = new ConcurrentHashMap<>();
    private final Set<Integer> playerClanBots = ConcurrentHashMap.newKeySet(); // botIds in a live player's clan
    private final Random rnd = new Random();

    private volatile int wantClans = 12;
    private volatile int membersMin = 8;
    private volatile int membersMax = 20;
    private volatile double mixedPartyChance = 0.25;
    private volatile double clanBotShare = 0.40;
    private volatile int bigClans = 1;
    private volatile long inviteCooldownMs = 24 * 60 * 60_000L;
    private volatile long adenaSpendLimit = 50_000_000L;
    /** Director brings a provisional-clan leader near an already-visible member so stock Clan can be created. */
    private volatile LeaderEnterHook leaderEnterHook;

    @FunctionalInterface
    public interface LeaderEnterHook {
        void request(int leaderBotId, int nearX, int nearY, int nearZ, int preferLevel);
    }

    public ClanService(ModuleContext ctx, BotRegistry registry, Path xml) throws Exception {
        this.ctx = ctx;
        this.registry = registry;
        this.store = new ClanStore(ctx.db());
        this.templates = ClanTemplates.load(xml);
        final Path crestsDir = xml.getParent() == null ? null : xml.getParent().resolve("crests");
        ClanCrests.init(crestsDir);
        if (ClanCrests.size() == 0) {
            ctx.log().warn("bot clans: no BMP crests in " + crestsDir
                + (ClanCrests.lastError() == null ? "" : " (" + ClanCrests.lastError() + ")"));
        } else {
            ctx.log().info("bot clans: " + ClanCrests.size() + " crest images loaded from " + crestsDir);
        }
    }

    public void setLeaderEnterHook(LeaderEnterHook hook) {
        leaderEnterHook = hook;
    }

    /** True when this bot leads a paper clan that still needs stock {@link Clan}+crest. */
    public boolean needsStockPromotion(int botId) {
        for (ClanStore.Row r : byClan.values()) {
            if ((r.leaderBotId() == botId) && isProvisional(r.clanId())) {
                return true;
            }
        }
        return false;
    }

    public void configure(int botClans, int minMembers, int maxMembers, double mixedChance, double share, int big,
        long inviteCdMs, long adenaLimit) {
        wantClans = Math.max(0, Math.min(20, botClans));
        membersMin = Math.max(1, Math.min(40, minMembers));
        membersMax = Math.max(membersMin, Math.min(40, maxMembers));
        mixedPartyChance = Math.max(0, Math.min(1, mixedChance));
        clanBotShare = Math.max(0, Math.min(1, share));
        bigClans = Math.max(0, Math.min(3, big));
        inviteCooldownMs = Math.max(0, inviteCdMs);
        adenaSpendLimit = Math.max(0, adenaLimit);
    }

    /** Back-compat for 7d callers. */
    public void configure(int botClans, int minMembers, int maxMembers, double mixedChance) {
        configure(botClans, minMembers, maxMembers, mixedChance, clanBotShare, bigClans, inviteCooldownMs, adenaSpendLimit);
    }

    public double mixedPartyChance() {
        return mixedPartyChance;
    }

    public double clanBotShare() {
        return clanBotShare;
    }

    public int bigClans() {
        return bigClans;
    }

    public int wantClans() {
        return wantClans;
    }

    public long inviteCooldownMs() {
        return inviteCooldownMs;
    }

    public long adenaSpendLimit() {
        return adenaSpendLimit;
    }

    public List<ClanTemplate> templates() {
        return templates;
    }

    public int clanIdOf(int botId) {
        return botClan.getOrDefault(botId, 0);
    }

    public boolean inPlayerClan(int botId) {
        return playerClanBots.contains(botId);
    }

    public Set<Integer> playerClanBotIds() {
        return Set.copyOf(playerClanBots);
    }

    public boolean isLeader(int botId) {
        for (ClanStore.Row r : byClan.values()) {
            if (r.leaderBotId() == botId) {
                return true;
            }
        }
        return false;
    }

    public boolean isBig(ClanStore.Row r) {
        return (r != null) && (r.level() >= BIG_LEVEL) && (r.targetSize() >= BIG_TARGET);
    }

    public List<ClanStore.Row> clans() {
        return List.copyOf(byClan.values());
    }

    public Optional<ClanStore.Row> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (ClanStore.Row r : byClan.values()) {
            if (r.name().equalsIgnoreCase(name)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public List<ClanStore.Member> members(int clanId) {
        try {
            return store.membersOf(clanId);
        } catch (Exception e) {
            ctx.log().warn("clan members load: " + e.getMessage());
            return List.of();
        }
    }

    public void load() throws Exception {
        botClan.clear();
        byClan.clear();
        playerClanBots.clear();
        for (ClanStore.Row r : store.loadClans()) {
            byClan.put(r.clanId(), r);
        }
        for (ClanStore.Member m : store.loadMembers()) {
            botClan.put(m.botId(), m.clanId());
        }
        for (Integer id : store.loadPlayerClanBots()) {
            playerClanBots.add(id);
            botClan.remove(id);
        }
        ctx.log().info("bot clans: " + byClan.size() + " active, " + botClan.size() + " members, player-clan bots "
            + playerClanBots.size() + " (templates " + templates.size() + ")");
    }

    /**
     * Fill to {@link #clanBotShare} of the ACTIVE+TRADER pool. One big clan (lvl8×40) first, then small clans up to
     * {@link #wantClans}.
     */
    public synchronized void ensureFilled() {
        try {
            ensureLeadersViable();
            normalizeBigTargets();
            final int pool = poolSize();
            final int wantMembers = (int) Math.round(pool * clanBotShare);
            if (wantMembers < membersMin && bigClans <= 0) {
                return;
            }
            List<Integer> free = freeClanEligibleBots();
            // 1) big clan(s)
            int bigHave = 0;
            for (ClanStore.Row r : byClan.values()) {
                if (isBig(r)) {
                    bigHave++;
                }
            }
            while (bigHave < bigClans) {
                if (free.size() < Math.min(membersMin, BIG_TARGET)) {
                    ctx.log().info("bot clans: need more personas for big clan (free " + free.size() + ")");
                    break;
                }
                final ClanTemplate t = unusedLargeTemplate();
                if (t == null) {
                    ctx.log().warn("bot clans: no large template left");
                    break;
                }
                if (!createPaperClan(t, BIG_LEVEL, BIG_TARGET, free)) {
                    break;
                }
                bigHave++;
            }
            // refill big first
            for (ClanStore.Row r : new ArrayList<>(byClan.values())) {
                if (!isBig(r)) {
                    continue;
                }
                final int have = store.memberCount(r.clanId());
                if (have < r.targetSize()) {
                    free = freeClanEligibleBots();
                    fillMembers(r.clanId(), r.targetSize() - have, free);
                }
            }
            int membersNow = botClan.size();
            int remaining = Math.max(0, wantMembers - membersNow);
            // 2) small clans
            while ((byClan.size() < wantClans) && (remaining >= membersMin) && (freeClanEligibleBots().size() >= membersMin)) {
                free = freeClanEligibleBots();
                final ClanTemplate t = unusedSmallTemplate();
                if (t == null) {
                    break;
                }
                final int cap = Math.min(membersMax, remaining);
                if (cap < membersMin) {
                    break;
                }
                final int size = membersMin + (cap > membersMin ? rnd.nextInt(cap - membersMin + 1) : 0);
                final int lvl = ClanTemplates.smallLevelForSize(size, membersMin, membersMax);
                if (!createPaperClan(new ClanTemplate(t.id(), t.name(), lvl, t.crestSeed(), false), lvl, size, free)) {
                    break;
                }
                remaining -= size;
            }
            // refill small toward target / remaining share
            membersNow = botClan.size();
            remaining = Math.max(0, wantMembers - membersNow);
            for (ClanStore.Row r : new ArrayList<>(byClan.values())) {
                if (isBig(r) || remaining <= 0) {
                    continue;
                }
                final int have = store.memberCount(r.clanId());
                final int need = Math.min(r.targetSize() - have, remaining);
                if (need > 0) {
                    free = freeClanEligibleBots();
                    final int added = fillMembers(r.clanId(), need, free);
                    remaining -= added;
                }
            }
            requestLeadersForOnlineMembers();
        } catch (Exception e) {
            ctx.log().warn("bot clans ensureFilled: " + e.getMessage(), e);
        }
    }

    /** If paper-clan members are already in the world, pull their leader so stock Clan can appear. */
    private void requestLeadersForOnlineMembers() {
        final LeaderEnterHook hook = leaderEnterHook;
        if (hook == null) {
            return;
        }
        for (ClanStore.Row row : byClan.values()) {
            if (!isProvisional(row.clanId())) {
                continue;
            }
            final BotRecord leader = byBot(row.leaderBotId());
            if ((leader != null) && leader.inWorld()) {
                continue;
            }
            try {
                for (ClanStore.Member m : store.membersOf(row.clanId())) {
                    final BotRecord br = byBot(m.botId());
                    final Player p = br == null ? null : br.player();
                    if ((p == null) || (br.botId() == row.leaderBotId())) {
                        continue;
                    }
                    final int prefer = Math.max(20, Math.max(p.getLevel(), leader == null ? 0 : leader.paperLevel(System.currentTimeMillis())));
                    hook.request(row.leaderBotId(), p.getX(), p.getY(), p.getZ(), prefer);
                    break;
                }
            } catch (Exception e) {
                ctx.log().warn("bot clan leader pull " + row.name() + ": " + e.getMessage());
            }
        }
    }

    private boolean createPaperClan(ClanTemplate t, int level, int size, List<Integer> free) throws Exception {
        if (free.size() < Math.min(membersMin, size)) {
            return false;
        }
        final int leaderBot = pickLeader(free);
        if (leaderBot < 0) {
            return false;
        }
        free.remove(Integer.valueOf(leaderBot));
        final BotRecord leader = byBot(leaderBot);
        if (leader == null || leader.status() == BotRecord.Status.TRADER) {
            return false;
        }
        final int paperId = provisionalClanId(leaderBot);
        if (ClanTable.getInstance().getClan(paperId) != null) {
            return false;
        }
        final ClanStore.Row row = new ClanStore.Row(paperId, t.id(), t.name(), level, leaderBot, size, "ACTIVE");
        store.insertClan(row);
        store.insertMember(leaderBot, paperId, "LEADER");
        botClan.put(leaderBot, paperId);
        byClan.put(paperId, row);
        fillMembers(paperId, size - 1, free);
        ctx.log().info("bot clan paper: " + t.name() + " lvl " + level + " leader " + leader.name() + " target " + size
            + (level >= BIG_LEVEL && size >= BIG_TARGET ? " BIG" : ""));
        return true;
    }

    private int pickLeader(List<Integer> free) {
        final long now = System.currentTimeMillis();
        int bestId = -1;
        int bestLvl = -1;
        final List<Integer> eligible = new ArrayList<>();
        for (Integer id : free) {
            final BotRecord r = byBot(id);
            if ((r == null) || (r.status() != BotRecord.Status.ACTIVE) || playerClanBots.contains(id)) {
                continue;
            }
            eligible.add(id);
            final int lvl = r.paperLevel(now);
            if (lvl > bestLvl) {
                bestLvl = lvl;
                bestId = id;
            }
        }
        if (eligible.isEmpty()) {
            return -1;
        }
        // stock createClan needs level >= 10: prefer the strongest free ACTIVE
        if (bestLvl >= 10) {
            return bestId;
        }
        return eligible.get(rnd.nextInt(eligible.size()));
    }

    /**
     * Paper clans whose leader is still under level 10 never become visible (stock createClan). Reassign LEADER to the
     * strongest ACTIVE member of the same paper clan.
     */
    private void ensureLeadersViable() throws Exception {
        final long now = System.currentTimeMillis();
        for (ClanStore.Row row : new ArrayList<>(byClan.values())) {
            if (!isProvisional(row.clanId())) {
                continue;
            }
            final BotRecord cur = byBot(row.leaderBotId());
            final int curLvl = cur == null ? 0 : cur.paperLevel(now);
            if (curLvl >= 10) {
                continue;
            }
            int bestId = -1;
            int bestLvl = 0;
            BotRecord best = null;
            for (ClanStore.Member m : store.membersOf(row.clanId())) {
                final BotRecord br = byBot(m.botId());
                if ((br == null) || (br.status() != BotRecord.Status.ACTIVE)) {
                    continue;
                }
                final int lvl = br.paperLevel(now);
                if (lvl > bestLvl) {
                    bestLvl = lvl;
                    bestId = br.botId();
                    best = br;
                }
            }
            if ((bestId < 0) || (bestLvl < 10) || (bestId == row.leaderBotId())) {
                continue;
            }
            store.insertMember(row.leaderBotId(), row.clanId(), "MEMBER");
            store.insertMember(bestId, row.clanId(), "LEADER");
            final ClanStore.Row updated = new ClanStore.Row(row.clanId(), row.templateId(), row.name(), row.level(), bestId,
                row.targetSize(), row.status());
            store.updateClan(updated);
            byClan.put(row.clanId(), updated);
            ctx.log().info("bot clan " + row.name() + ": leader -> " + best.name() + " lv " + bestLvl
                + " (was lv " + curLvl + ", stock create needs >=10)");
        }
    }

    /** Old 7d rows: lvl8 with small target — bump first {@link #bigClans} to BIG_TARGET. */
    private void normalizeBigTargets() throws Exception {
        int bumped = 0;
        for (ClanStore.Row row : new ArrayList<>(byClan.values())) {
            if ((row.level() < BIG_LEVEL) || (row.targetSize() >= BIG_TARGET)) {
                continue;
            }
            if (bumped >= bigClans) {
                break;
            }
            final ClanStore.Row updated = new ClanStore.Row(row.clanId(), row.templateId(), row.name(), BIG_LEVEL, row.leaderBotId(),
                BIG_TARGET, row.status());
            store.updateClan(updated);
            byClan.put(row.clanId(), updated);
            bumped++;
            ctx.log().info("bot clan " + row.name() + ": target " + row.targetSize() + " -> " + BIG_TARGET + " (big)");
        }
    }

    private int fillMembers(int clanId, int need, List<Integer> free) throws Exception {
        int n = 0;
        while ((n < need) && !free.isEmpty()) {
            final int botId = free.remove(rnd.nextInt(free.size()));
            if (playerClanBots.contains(botId) || isLeader(botId)) {
                continue;
            }
            store.insertMember(botId, clanId, "MEMBER");
            botClan.put(botId, clanId);
            n++;
        }
        return n;
    }

    private int poolSize() {
        int n = 0;
        for (BotRecord r : registry.all()) {
            if ((r.status() == BotRecord.Status.ACTIVE) || (r.status() == BotRecord.Status.TRADER)) {
                n++;
            }
        }
        return n;
    }

    /** Free ACTIVE+TRADER not in a bot clan and not in a player clan. */
    private List<Integer> freeClanEligibleBots() throws Exception {
        final List<Integer> all = new ArrayList<>();
        for (BotRecord r : registry.all()) {
            if (((r.status() == BotRecord.Status.ACTIVE) || (r.status() == BotRecord.Status.TRADER))
                && !playerClanBots.contains(r.botId())) {
                all.add(r.botId());
            }
        }
        return store.freeBotIds(all);
    }

    private ClanTemplate unusedLargeTemplate() {
        return unusedTemplate(true);
    }

    private ClanTemplate unusedSmallTemplate() {
        return unusedTemplate(false);
    }

    private ClanTemplate unusedTemplate(boolean large) {
        final Set<String> usedNames = new HashSet<>();
        final Set<String> usedIds = new HashSet<>();
        for (ClanStore.Row r : byClan.values()) {
            usedNames.add(r.name().toLowerCase());
            usedIds.add(r.templateId());
        }
        for (Clan c : ClanTable.getInstance().getClans()) {
            usedNames.add(c.getName().toLowerCase());
        }
        final List<ClanTemplate> free = new ArrayList<>();
        for (ClanTemplate t : templates) {
            if (t.large() != large) {
                continue;
            }
            if (!usedIds.contains(t.id()) && !usedNames.contains(t.name().toLowerCase())) {
                free.add(t);
            }
        }
        // fallback: any unused small can become large paper with forced lvl
        if (free.isEmpty() && large) {
            for (ClanTemplate t : templates) {
                if (!usedIds.contains(t.id()) && !usedNames.contains(t.name().toLowerCase())) {
                    free.add(new ClanTemplate(t.id(), t.name(), BIG_LEVEL, t.crestSeed(), true));
                }
            }
        }
        return free.isEmpty() ? null : free.get(rnd.nextInt(free.size()));
    }

    private BotRecord byBot(int botId) {
        for (BotRecord r : registry.all()) {
            if (r.botId() == botId) {
                return r;
            }
        }
        return null;
    }

    public void attachPlayer(BotRecord r, Player p) {
        if ((r == null) || (p == null)) {
            return;
        }
        if (playerClanBots.contains(r.botId())) {
            // stock clanid should already restore; nothing to do for bot-clan
            return;
        }
        final int paper = botClan.getOrDefault(r.botId(), 0);
        if (paper == 0) {
            return;
        }
        try {
            ClanStore.Row row = byClan.get(paper);
            if (row == null) {
                for (ClanStore.Row x : byClan.values()) {
                    if (x.leaderBotId() == r.botId() || members(x.clanId()).stream().anyMatch(m -> m.botId() == r.botId())) {
                        row = x;
                        break;
                    }
                }
            }
            if (row == null) {
                return;
            }
            if (isProvisional(row.clanId())) {
                if (row.leaderBotId() == r.botId()) {
                    if (p.getLevel() >= 10) {
                        promoteToStock(row, r, p);
                    } else {
                        ctx.log().info("bot clan " + row.name() + ": leader " + r.name() + " lv " + p.getLevel()
                            + " in world but stock createClan needs >=10");
                    }
                    return;
                }
                // member entered first: promote via online leader, else pull leader nearby
                final BotRecord leader = byBot(row.leaderBotId());
                if ((leader != null) && leader.inWorld() && (leader.player() != null) && (leader.player().getLevel() >= 10)) {
                    promoteToStock(row, leader, leader.player());
                    row = byClan.get(botClan.getOrDefault(r.botId(), 0));
                    if (row == null || isProvisional(row.clanId())) {
                        return;
                    }
                } else {
                    final LeaderEnterHook hook = leaderEnterHook;
                    if (hook != null) {
                        final int prefer = Math.max(20, Math.max(p.getLevel(), leader == null ? 0 : leader.paperLevel(System.currentTimeMillis())));
                        hook.request(row.leaderBotId(), p.getX(), p.getY(), p.getZ(), prefer);
                    }
                    return;
                }
            }
            final Clan clan = ClanTable.getInstance().getClan(row.clanId());
            if (clan == null) {
                if (row.leaderBotId() == r.botId()) {
                    promoteToStock(row, r, p);
                }
                return;
            }
            if (p.getClan() == null) {
                if (p.getLevel() < 1) {
                    return;
                }
                clan.addClanMember(p);
            }
        } catch (Exception e) {
            ctx.log().warn("clan attach " + r.name() + ": " + e.getMessage(), e);
        }
    }

    private synchronized void promoteToStock(ClanStore.Row paper, BotRecord leader, Player p) throws Exception {
        if (p.getClan() != null) {
            return;
        }
        if (p.getLevel() < 10) {
            return;
        }
        if (ClanTable.getInstance().getClanByName(paper.name()) != null) {
            ctx.log().warn("bot clan name taken in stock: " + paper.name());
            return;
        }
        final Clan clan = ClanTable.getInstance().createClan(p, paper.name());
        if (clan == null) {
            ctx.log().warn("createClan failed for " + paper.name() + " / " + leader.name());
            return;
        }
        final Crest crest = CrestTable.getInstance().createCrest(ClanTemplates.crestBytes(templateSeed(paper.templateId())), CrestType.PLEDGE);
        if (crest == null) {
            ctx.log().warn("bot clan crest failed for " + paper.name() + " — dissolving stock create");
            ClanTable.getInstance().destroyClan(clan.getId());
            return;
        }
        clan.changeClanCrest(crest.getId());
        if (paper.level() > clan.getLevel()) {
            clan.changeLevel(paper.level());
        }
        final int stockId = clan.getId();
        final ClanStore.Row stockRow = new ClanStore.Row(stockId, paper.templateId(), paper.name(), paper.level(), paper.leaderBotId(), paper.targetSize(), "ACTIVE");
        final List<ClanStore.Member> mem = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : botClan.entrySet()) {
            if (e.getValue() == paper.clanId()) {
                mem.add(new ClanStore.Member(e.getKey(), stockId, e.getKey() == paper.leaderBotId() ? "LEADER" : "MEMBER"));
            }
        }
        store.rematerialize(paper.clanId(), stockRow, mem);
        for (ClanStore.Member m : mem) {
            botClan.put(m.botId(), stockId);
        }
        byClan.remove(paper.clanId());
        byClan.put(stockId, stockRow);
        ctx.log().info("bot clan stock: " + paper.name() + " id=" + stockId + " lvl " + clan.getLevel() + " crest=" + crest.getId());
        for (ClanStore.Member m : mem) {
            if (m.botId() == leader.botId()) {
                continue;
            }
            final BotRecord br = byBot(m.botId());
            final Player q = br == null ? null : br.player();
            if ((q != null) && (q.getClan() == null)) {
                clan.addClanMember(q);
            }
        }
    }

    /**
     * Leave bot-clan paper+stock so the bot can join a live player's clan. Leaders and traders refuse.
     * @return true if left (or was not in a bot clan)
     */
    public synchronized boolean leaveBotClanForPlayer(BotRecord r, Player p) {
        if ((r == null) || (p == null)) {
            return false;
        }
        if (r.status() == BotRecord.Status.TRADER) {
            return false;
        }
        if (isLeader(r.botId())) {
            return false;
        }
        final int cid = botClan.getOrDefault(r.botId(), 0);
        try {
            if (cid != 0) {
                store.removeMember(r.botId());
                botClan.remove(r.botId());
                final Clan clan = p.getClan();
                if ((clan != null) && (clan.getId() == cid || byClan.containsKey(cid))) {
                    clan.removeClanMember(p.getObjectId(), 0);
                } else if (p.getClan() != null) {
                    p.getClan().removeClanMember(p.getObjectId(), 0);
                }
            } else if (p.getClan() != null) {
                // already in some clan — leave before joining another
                p.getClan().removeClanMember(p.getObjectId(), 0);
            }
            store.markPlayerClan(r.botId(), true);
            playerClanBots.add(r.botId());
            return true;
        } catch (Exception e) {
            ctx.log().warn("leaveBotClan " + r.name() + ": " + e.getMessage(), e);
            return false;
        }
    }

    /** Mark bot as belonging to a live player's clan (after stock join). */
    public void markJoinedPlayerClan(BotRecord r) {
        if (r == null) {
            return;
        }
        try {
            store.removeMember(r.botId());
            botClan.remove(r.botId());
            store.markPlayerClan(r.botId(), true);
            playerClanBots.add(r.botId());
        } catch (Exception e) {
            ctx.log().warn("mark player clan " + r.name() + ": " + e.getMessage());
        }
    }

    public void clearPlayerClan(int botId) {
        try {
            store.markPlayerClan(botId, false);
            playerClanBots.remove(botId);
        } catch (Exception e) {
            ctx.log().warn("clear player clan " + botId + ": " + e.getMessage());
        }
    }

    /** @return true if this live player may receive another bot-clan invite now */
    public boolean inviteCooldownOk(int liveCharId, long now) {
        if (inviteCooldownMs <= 0) {
            return true;
        }
        final long last = inviteAt.getOrDefault(liveCharId, 0L);
        return (now - last) >= inviteCooldownMs;
    }

    public void noteInvite(int liveCharId, long now) {
        inviteAt.put(liveCharId, now);
    }

    /** Pure: invite cooldown check. */
    public static boolean inviteCooldownAllows(long lastInviteMs, long now, long cooldownMs) {
        if (cooldownMs <= 0) {
            return true;
        }
        return (now - lastInviteMs) >= cooldownMs;
    }

    public boolean canSpendAdena(int clanId, long amount, long now) {
        if (adenaSpendLimit <= 0) {
            return true;
        }
        final long day = now / 86_400_000L;
        final long start = adenaDayStart.getOrDefault(clanId, -1L);
        if (start != day) {
            adenaDayStart.put(clanId, day);
            adenaSpentDay.put(clanId, 0L);
        }
        return (adenaSpentDay.getOrDefault(clanId, 0L) + amount) <= adenaSpendLimit;
    }

    public void noteAdenaSpend(int clanId, long amount, long now) {
        final long day = now / 86_400_000L;
        final long start = adenaDayStart.getOrDefault(clanId, -1L);
        if (start != day) {
            adenaDayStart.put(clanId, day);
            adenaSpentDay.put(clanId, 0L);
        }
        adenaSpentDay.merge(clanId, amount, Long::sum);
    }

    static int provisionalClanId(int leaderBotId) {
        return 1_900_000_000 + leaderBotId;
    }

    static boolean isProvisional(int clanId) {
        return clanId >= 1_900_000_000;
    }

    private long templateSeed(String templateId) {
        for (ClanTemplate t : templates) {
            if (t.id().equals(templateId)) {
                return t.crestSeed();
            }
        }
        return templateId.hashCode();
    }

    public void removeMemberForTest(int botId) throws Exception {
        store.removeMember(botId);
        botClan.remove(botId);
    }

    public static boolean useMixedParty(boolean clanBot, double chance, Random rnd) {
        return clanBot && (rnd.nextDouble() < chance);
    }

    public static List<org.l2start.bots.director.Director.Candidate> filterMates(
        List<org.l2start.bots.director.Director.Candidate> mates, int anchorClanId, boolean mixed) {
        if ((anchorClanId <= 0) || mixed) {
            return mates;
        }
        final List<org.l2start.bots.director.Director.Candidate> same = new ArrayList<>();
        for (org.l2start.bots.director.Director.Candidate c : mates) {
            if (c.clanId() == anchorClanId) {
                same.add(c);
            }
        }
        return same;
    }

    /** Target member count for share of pool (pure). */
    public static int wantMembersForPool(int pool, double share) {
        return (int) Math.round(Math.max(0, pool) * Math.max(0, Math.min(1, share)));
    }
}
