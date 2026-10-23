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
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2start.bots.core.BotRecord;
import org.l2start.bots.core.BotRegistry;
import org.l2start.bots.core.BotRepository;
import org.l2start.platform.api.ModuleContext;

/**
 * Phase 7d/7e: bot clans are always stock Mobius {@link Clan}s with crest (created offline). Module tables track
 * membership; ClanBotShare fill; leave to join a live player's clan.
 */
public final class ClanService {
    public static final int BIG_TARGET = 40;
    public static final int BIG_LEVEL = 8;
    /** Interlude shows pledge crest only at clan skill level ≥3 (RequestSetPledgeCrest). */
    public static final int CREST_MIN_LEVEL = 3;
    /** Bot-clan leaders must be at least this paper level (stock clan lvl 8 needs a serious persona). */
    public static final int LEADER_MIN_LEVEL = 40;

    private final ModuleContext ctx;
    private final BotRegistry registry;
    private final BotRepository repo;
    private final ClanStore store;
    private final List<ClanTemplate> templates;
    private final Map<Integer, Integer> botClan = new ConcurrentHashMap<>(); // botId -> bot-clan id (0 = none / player clan)
    private final Map<Integer, ClanStore.Row> byClan = new ConcurrentHashMap<>();
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
    private volatile long adenaSpendLimit = 50_000_000L;
    /** Optional: director pull (legacy; stock clans no longer wait for leader enter). */
    private volatile LeaderEnterHook leaderEnterHook;

    @FunctionalInterface
    public interface LeaderEnterHook {
        void request(int leaderBotId, int nearX, int nearY, int nearZ, int preferLevel);
    }

    public ClanService(ModuleContext ctx, BotRegistry registry, BotRepository repo, Path xml) throws Exception {
        this.ctx = ctx;
        this.registry = registry;
        this.repo = repo;
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

    /** @deprecated kept for older callers; prefer {@link #ClanService(ModuleContext, BotRegistry, BotRepository, Path)} */
    @Deprecated
    public ClanService(ModuleContext ctx, BotRegistry registry, Path xml) throws Exception {
        this(ctx, registry, null, xml);
    }

    public void setLeaderEnterHook(LeaderEnterHook hook) {
        leaderEnterHook = hook;
    }

    /** True when this bot leads a clan that is still provisional / missing from ClanTable (should be rare after migrate). */
    public boolean needsStockPromotion(int botId) {
        for (ClanStore.Row r : byClan.values()) {
            if ((r.leaderBotId() == botId) && (isProvisional(r.clanId()) || (ClanTable.getInstance().getClan(r.clanId()) == null))) {
                return true;
            }
        }
        return false;
    }

    /** Admin: "✓" if stock clan exists with crest, "!" if stock without crest, "—" if missing. */
    public String stockCrestMark(ClanStore.Row r) {
        if (r == null) {
            return "—";
        }
        final Clan c = ClanTable.getInstance().getClan(r.clanId());
        if (c == null) {
            return "—";
        }
        return StockClanFactory.hasCrest(c) ? "✓" : "!";
    }

    public void configure(int botClans, int minMembers, int maxMembers, double mixedChance, double share, int big,
        long adenaLimit) {
        wantClans = Math.max(0, Math.min(20, botClans));
        membersMin = Math.max(1, Math.min(40, minMembers));
        membersMax = Math.max(membersMin, Math.min(40, maxMembers));
        mixedPartyChance = Math.max(0, Math.min(1, mixedChance));
        clanBotShare = Math.max(0, Math.min(1, share));
        bigClans = Math.max(0, Math.min(3, big));
        adenaSpendLimit = Math.max(0, adenaLimit);
    }

    /** Back-compat for 7d callers. */
    public void configure(int botClans, int minMembers, int maxMembers, double mixedChance) {
        configure(botClans, minMembers, maxMembers, mixedChance, clanBotShare, bigClans, adenaSpendLimit);
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
        return isBig(r, (r != null) && (townOf(r.clanId()) != null));
    }

    /** Pure: a big (mega) clan: level 8, 40 strong, and not a town clan (Goddard's clan is level 8 too, but recruits). */
    public static boolean isBig(ClanStore.Row r, boolean townClan) {
        return (r != null) && !townClan && (r.level() >= BIG_LEVEL) && (r.targetSize() >= BIG_TARGET);
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
     * {@link #wantClans}. Every clan is created as stock Mobius + crest immediately.
     */
    public synchronized void ensureFilled() {
        try {
            townClans.putAll(store.loadTownClans()); // before the big-clan rules: town clans are never big
            migratePaperToStock();
            ensureLeadersViable();
            normalizeBigTargets();
            ensureCrestIntegrity();
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
                if (!createStockClan(t, BIG_LEVEL, BIG_TARGET, free)) {
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
                if (!createStockClan(new ClanTemplate(t.id(), t.name(), lvl, t.crestSeed(), false), lvl, size, free)) {
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
            ensureCrestIntegrity();
            ensureTownClans();
        } catch (Exception e) {
            ctx.log().warn("bot clans ensureFilled: " + e.getMessage(), e);
        }
    }

    /** Turn every module row without a stock {@link Clan} into a real clan with crest. */
    private void migratePaperToStock() throws Exception {
        for (ClanStore.Row row : new ArrayList<>(byClan.values())) {
            final Clan existing = ClanTable.getInstance().getClan(row.clanId());
            if ((existing != null) && !isProvisional(row.clanId())) {
                continue;
            }
            final BotRecord leader = byBot(row.leaderBotId());
            if (leader == null) {
                ctx.log().warn("bot clan migrate skip " + row.name() + ": no leader bot");
                continue;
            }
            bumpLeaderLevel(leader);
            final List<Integer> memberChars = new ArrayList<>();
            final List<ClanStore.Member> memRows = store.membersOf(row.clanId());
            for (ClanStore.Member m : memRows) {
                final BotRecord br = byBot(m.botId());
                if (br != null) {
                    memberChars.add(br.charId());
                }
            }
            if (!memberChars.contains(leader.charId())) {
                memberChars.add(0, leader.charId());
            }
            final byte[] crest = ClanTemplates.crestBytes(templateSeed(row.templateId()));
            final String name = uniqueStockName(row.name());
            try {
                final StockClanFactory.Created created = StockClanFactory.create(name, row.level(),
                    StockClanFactory.membersOf(leader.charId(), memberChars), crest);
                final ClanStore.Row stockRow = new ClanStore.Row(created.clanId(), row.templateId(), name, row.level(),
                    row.leaderBotId(), row.targetSize(), "ACTIVE");
                final List<ClanStore.Member> remem = new ArrayList<>();
                for (ClanStore.Member m : memRows) {
                    remem.add(new ClanStore.Member(m.botId(), created.clanId(), m.role()));
                }
                store.rematerialize(row.clanId(), stockRow, remem);
                byClan.remove(row.clanId());
                byClan.put(created.clanId(), stockRow);
                for (ClanStore.Member m : remem) {
                    botClan.put(m.botId(), created.clanId());
                }
                ctx.log().info("bot clan migrated to stock: " + name + " id=" + created.clanId() + " crest=" + created.crestId()
                    + (isProvisional(row.clanId()) ? " (was paper " + row.clanId() + ")" : ""));
            } catch (Exception e) {
                ctx.log().warn("bot clan migrate failed " + row.name() + ": " + e.getMessage(), e);
            }
        }
    }

    private void ensureCrestIntegrity() {
        for (ClanStore.Row row : byClan.values()) {
            final Clan c = ClanTable.getInstance().getClan(row.clanId());
            if (c == null) {
                ctx.log().warn("bot clan " + row.name() + " id=" + row.clanId() + ": missing stock Clan");
                continue;
            }
            if (c.getLevel() < CREST_MIN_LEVEL) {
                c.changeLevel(CREST_MIN_LEVEL);
                final ClanStore.Row bumped = new ClanStore.Row(row.clanId(), row.templateId(), row.name(), CREST_MIN_LEVEL,
                    row.leaderBotId(), row.targetSize(), row.status());
                byClan.put(row.clanId(), bumped);
                try {
                    store.updateClan(bumped);
                } catch (Exception e) {
                    ctx.log().warn("bot clan " + row.name() + ": level bump persist failed: " + e.getMessage(), e);
                }
                ctx.log().info("bot clan " + row.name() + ": raised stock level to " + CREST_MIN_LEVEL + " (crest)");
            }
            final byte[] want = ClanTemplates.crestBytes(templateSeed(row.templateId()));
            if (StockClanFactory.hasMatchingCrest(c, want)) {
                continue;
            }
            ctx.log().warn("bot clan " + row.name() + " id=" + row.clanId()
                + ": crest missing, outdated, or not client-safe — reapplying");
            final int id = StockClanFactory.ensureCrest(c, want);
            if (id == 0) {
                ctx.log().error("bot clan " + row.name() + ": crest reapply failed",
                    new IllegalStateException("crest reapply failed"));
            } else {
                ctx.log().info("bot clan " + row.name() + ": crest restored id=" + id);
            }
        }
    }

    private String uniqueStockName(String base) {
        if (ClanTable.getInstance().getClanByName(base) == null) {
            return base;
        }
        for (int i = 2; i < 100; i++) {
            final String n = base.length() > 14 ? base.substring(0, 14) + i : base + i;
            if (ClanTable.getInstance().getClanByName(n) == null) {
                return n;
            }
        }
        return base + rnd.nextInt(1000);
    }

    private boolean createStockClan(ClanTemplate t, int level, int size, List<Integer> free) throws Exception {
        level = Math.max(level, CREST_MIN_LEVEL);
        if (free.size() < Math.min(membersMin, size)) {
            return false;
        }
        final int leaderBot = pickLeader(free);
        if (leaderBot < 0) {
            ctx.log().info("bot clans: no ACTIVE persona ≥" + LEADER_MIN_LEVEL + " for leader");
            return false;
        }
        free.remove(Integer.valueOf(leaderBot));
        final BotRecord leader = byBot(leaderBot);
        if (leader == null || leader.status() == BotRecord.Status.TRADER) {
            return false;
        }
        bumpLeaderLevel(leader);
        final List<Integer> memberBots = new ArrayList<>();
        memberBots.add(leaderBot);
        final List<Integer> memberChars = new ArrayList<>();
        memberChars.add(leader.charId());
        while ((memberBots.size() < size) && !free.isEmpty()) {
            final int botId = free.remove(rnd.nextInt(free.size()));
            if (playerClanBots.contains(botId) || isLeader(botId)) {
                continue;
            }
            final BotRecord br = byBot(botId);
            if (br == null) {
                continue;
            }
            memberBots.add(botId);
            memberChars.add(br.charId());
        }
        if (memberBots.size() < Math.min(membersMin, size)) {
            return false;
        }
        final byte[] crest = ClanTemplates.crestBytes(t.crestSeed());
        final StockClanFactory.Created created;
        try {
            created = StockClanFactory.create(t.name(), level, StockClanFactory.membersOf(leader.charId(), memberChars), crest);
        } catch (Exception e) {
            ctx.log().warn("bot clan stock create failed " + t.name() + ": " + e.getMessage(), e);
            return false;
        }
        final ClanStore.Row row = new ClanStore.Row(created.clanId(), t.id(), t.name(), level, leaderBot, size, "ACTIVE");
        store.insertClan(row);
        for (int i = 0; i < memberBots.size(); i++) {
            final int botId = memberBots.get(i);
            store.insertMember(botId, created.clanId(), botId == leaderBot ? "LEADER" : "MEMBER");
            botClan.put(botId, created.clanId());
        }
        byClan.put(created.clanId(), row);
        ctx.log().info("bot clan stock: " + t.name() + " id=" + created.clanId() + " lvl " + level + " leader "
            + leader.name() + " lv" + leader.paperLevel(System.currentTimeMillis()) + " members " + memberBots.size()
            + " crest=" + created.crestId() + (level >= BIG_LEVEL && size >= BIG_TARGET ? " BIG" : ""));
        return true;
    }

    private int pickLeader(List<Integer> free) {
        final long now = System.currentTimeMillis();
        int bestId = -1;
        int bestLvl = -1;
        for (Integer id : free) {
            final BotRecord r = byBot(id);
            if ((r == null) || (r.status() != BotRecord.Status.ACTIVE) || playerClanBots.contains(id)) {
                continue;
            }
            final int lvl = r.paperLevel(now);
            if (lvl < LEADER_MIN_LEVEL) {
                continue;
            }
            if (lvl > bestLvl) {
                bestLvl = lvl;
                bestId = id;
            }
        }
        if (bestId >= 0) {
            return bestId;
        }
        // no one ≥40 yet: take strongest ACTIVE and bump paper level
        for (Integer id : free) {
            final BotRecord r = byBot(id);
            if ((r == null) || (r.status() != BotRecord.Status.ACTIVE) || playerClanBots.contains(id)) {
                continue;
            }
            final int lvl = r.paperLevel(now);
            if (lvl > bestLvl) {
                bestLvl = lvl;
                bestId = id;
            }
        }
        return bestId;
    }

    private void bumpLeaderLevel(BotRecord leader) {
        if (leader == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (leader.paperLevel(now) >= LEADER_MIN_LEVEL) {
            return;
        }
        leader.ensureMinPaperLevel(LEADER_MIN_LEVEL, now);
        if (repo != null) {
            try {
                repo.saveProgress(leader.botId(), leader.levelBase(), leader.baseAt());
            } catch (Exception e) {
                ctx.log().warn("bot clan leader progress " + leader.name() + ": " + e.getMessage());
            }
        }
        ctx.log().info("bot clan: raised " + leader.name() + " paper level to ≥" + LEADER_MIN_LEVEL + " (leader)");
    }

    /** Leaders must be ≥ {@link #LEADER_MIN_LEVEL}; reassign / bump if needed. */
    private void ensureLeadersViable() throws Exception {
        final long now = System.currentTimeMillis();
        for (ClanStore.Row row : new ArrayList<>(byClan.values())) {
            final BotRecord cur = byBot(row.leaderBotId());
            if (cur != null && cur.paperLevel(now) >= LEADER_MIN_LEVEL) {
                continue;
            }
            int bestId = -1;
            int bestLvl = -1;
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
            if (best == null) {
                continue;
            }
            bumpLeaderLevel(best);
            if (bestId != row.leaderBotId()) {
                store.insertMember(row.leaderBotId(), row.clanId(), "MEMBER");
                store.insertMember(bestId, row.clanId(), "LEADER");
                final ClanStore.Row updated = new ClanStore.Row(row.clanId(), row.templateId(), row.name(), row.level(), bestId,
                    row.targetSize(), row.status());
                store.updateClan(updated);
                byClan.put(row.clanId(), updated);
                final Clan stock = ClanTable.getInstance().getClan(row.clanId());
                if (stock != null) {
                    try {
                        StockClanFactory.addMember(row.clanId(), best.charId(), true);
                    } catch (Exception e) {
                        ctx.log().warn("bot clan leader stock update " + row.name() + ": " + e.getMessage());
                    }
                }
                ctx.log().info("bot clan " + row.name() + ": leader -> " + best.name() + " lv "
                    + best.paperLevel(now));
            }
        }
    }

    /** Old 7d rows: lvl8 with small target — bump first {@link #bigClans} to BIG_TARGET. */
    private void normalizeBigTargets() throws Exception {
        int bumped = 0;
        for (ClanStore.Row row : new ArrayList<>(byClan.values())) {
            if ((row.level() < BIG_LEVEL) || (row.targetSize() >= BIG_TARGET) || (townOf(row.clanId()) != null)) {
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
        final boolean stock = ClanTable.getInstance().getClan(clanId) != null;
        while ((n < need) && !free.isEmpty()) {
            final int botId = free.remove(rnd.nextInt(free.size()));
            if (playerClanBots.contains(botId) || isLeader(botId)) {
                continue;
            }
            final BotRecord br = byBot(botId);
            if (br == null) {
                continue;
            }
            store.insertMember(botId, clanId, "MEMBER");
            botClan.put(botId, clanId);
            if (stock) {
                try {
                    StockClanFactory.addMember(clanId, br.charId(), false);
                } catch (Exception e) {
                    ctx.log().warn("bot clan add member stock " + br.name() + ": " + e.getMessage());
                }
            }
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

    /**
     * On enter: stock {@code characters.clanid} should already restore the clan. If the bot is in our table but not
     * yet on the Player, join the stock clan.
     */
    public void attachPlayer(BotRecord r, Player p) {
        if ((r == null) || (p == null) || playerClanBots.contains(r.botId())) {
            return;
        }
        final int cid = botClan.getOrDefault(r.botId(), 0);
        if (cid == 0) {
            return;
        }
        try {
            if (p.getClan() != null && p.getClanId() == cid) {
                return;
            }
            final Clan clan = ClanTable.getInstance().getClan(cid);
            if (clan == null) {
                ctx.log().warn("clan attach " + r.name() + ": module clan " + cid + " missing in ClanTable");
                return;
            }
            if (p.getClan() == null) {
                clan.addClanMember(p);
            }
        } catch (Exception e) {
            ctx.log().warn("clan attach " + r.name() + ": " + e.getMessage(), e);
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

    public static boolean isProvisional(int clanId) {
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

    /**
     * A full bot clan makes room for a live player: one offline bot member (not the leader, not a trader) leaves it, on
     * paper and in the stock clan. @return true when a place was freed
     */
    public synchronized boolean makeRoom(int clanId) {
        final Clan clan = org.l2jmobius.gameserver.data.sql.ClanTable.getInstance().getClan(clanId);
        if (clan == null) {
            return false;
        }
        for (Map.Entry<Integer, Integer> e : botClan.entrySet()) {
            if ((e.getValue() != clanId) || isLeader(e.getKey())) {
                continue;
            }
            final BotRecord r = registry.all().stream().filter(x -> x.botId() == e.getKey()).findFirst().orElse(null);
            if ((r == null) || r.inWorld() || (r.status() == BotRecord.Status.TRADER) || (clan.getClanMember(r.charId()) == null)
                || (clan.getClanMember(r.charId()).getPledgeType() != 0)) {
                continue;
            }
            try {
                store.removeMember(r.botId());
                botClan.remove(r.botId());
                clan.removeClanMember(r.charId(), 0);
                ctx.log().info("bot clan " + clan.getName() + " full: " + r.name() + " leaves it to make room for a player");
                return true;
            } catch (Exception ex) {
                ctx.log().warn("make room in " + clan.getName() + ": " + ex.getMessage());
                return false;
            }
        }
        return false;
    }

    /**
     * Grade of a bot clan by the town of its clan hall: clan level and the band of its members' levels (leader near the
     * top). Gludio/Dion 5 (20-45), Giran 6 (35-60), Aden 7 (45-70), Goddard 8 (55-78); null = a town without a grade.
     */
    public record TownGrade(int clanLevel, int minLevel, int maxLevel, int leaderLevel) {
    }

    /** The recruiting towns: each has its own bot clan, clan hall there and recruiter (the clan's leader). */
    public static final List<String> TOWNS = List.of("Gludio", "Dion", "Giran", "Aden", "Goddard");
    /** Members a new town clan starts with (below a big clan's 40: town clans are never "big"). */
    public static final int TOWN_CLAN_SIZE = 20;
    private final Map<String, Integer> townClans = new ConcurrentHashMap<>();

    /** The town's bot clan id, 0 = none yet. */
    public int townClan(String town) {
        return townClans.getOrDefault(town, 0);
    }

    /** Town of a town clan, null = not a town clan. */
    public String townOf(int clanId) {
        for (Map.Entry<String, Integer> e : townClans.entrySet()) {
            if (e.getValue() == clanId) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Pure: a clan recruiters (any kind) may recruit for: never a big (mega) clan, kept for wars and sieges. */
    public static boolean recruits(boolean big) {
        return !big;
    }

    /** Startup migration: every recruiting town without its clan gets one (a free template, the town's grade). */
    private void ensureTownClans() throws Exception {
        townClans.clear();
        townClans.putAll(store.loadTownClans());
        if (!townClans.containsKey("Gludio")) {
            for (ClanStore.Row r : byClan.values()) {
                if ("night_hawk".equals(r.templateId())) {
                    store.setTownClan("Gludio", r.clanId()); // Gludio keeps NightHawk (Volk's clan, Ruby Hall)
                    townClans.put("Gludio", r.clanId());
                }
            }
        }
        for (String town : TOWNS) {
            final Integer cid = townClans.get(town);
            if ((cid != null) && byClan.containsKey(cid) && (ClanTable.getInstance().getClan(cid) != null)) {
                continue;
            }
            final TownGrade g = townGrade(town);
            final ClanTemplate t = unusedSmallTemplate();
            if (t == null) {
                ctx.log().warn("town clan " + town + ": no free clan template left");
                continue;
            }
            final List<Integer> free = freeClanEligibleBots();
            if (free.size() < TOWN_CLAN_SIZE) {
                ctx.log().info("town clan " + town + ": need " + TOWN_CLAN_SIZE + " free bots, have " + free.size());
                continue;
            }
            if (!createStockClan(new ClanTemplate(t.id(), t.name(), g.clanLevel(), t.crestSeed(), false), g.clanLevel(), TOWN_CLAN_SIZE, free)) {
                continue;
            }
            for (ClanStore.Row r : byClan.values()) {
                if (r.templateId().equals(t.id())) {
                    store.setTownClan(town, r.clanId());
                    townClans.put(town, r.clanId());
                    ctx.log().info("town clan " + town + ": " + r.name() + " created (lv " + g.clanLevel() + ")");
                }
            }
        }
        for (Map.Entry<String, Integer> e : townClans.entrySet()) {
            gradeForTown(e.getValue(), e.getKey());
        }
    }

    public static TownGrade townGrade(String town) {
        return switch (town == null ? "" : town) {
            case "Gludio", "Dion" -> new TownGrade(5, 20, 45, 44);
            case "Giran" -> new TownGrade(6, 35, 60, 58);
            case "Aden" -> new TownGrade(7, 45, 70, 68);
            case "Goddard" -> new TownGrade(8, 55, 78, 76);
            default -> null;
        };
    }

    /** Pure: the level a member grows to in its clan's band (spread by its seed); levels never go down. */
    public static int memberTarget(long seed, TownGrade g) {
        final int span = (g.maxLevel() - g.minLevel()) + 1;
        return g.minLevel() + (int) Math.floorMod(seed * 0x9E3779B97F4A7C15L >>> 17, (long) span);
    }

    /**
     * Brings a bot clan to its hall town's grade: stock clan level set, offline members' paper levels raised into the
     * band, the leader near its top (never lowered: real experience counts). Runs at start for every hall-owning bot clan.
     */
    public synchronized void gradeForTown(int clanId, String town) {
        final TownGrade g = townGrade(town);
        final ClanStore.Row row = byClan.get(clanId);
        final Clan clan = org.l2jmobius.gameserver.data.sql.ClanTable.getInstance().getClan(clanId);
        if ((g == null) || (row == null) || (clan == null)) {
            return;
        }
        final long now = System.currentTimeMillis();
        int raised = 0;
        if (clan.getLevel() != g.clanLevel()) {
            clan.changeLevel(g.clanLevel());
            try {
                StockClanFactory.ensureAcademy(clan);
            } catch (Exception e) {
                ctx.log().warn("grade academy " + clan.getName() + ": " + e.getMessage());
            }
        }
        for (Map.Entry<Integer, Integer> e : botClan.entrySet()) {
            if (e.getValue() != clanId) {
                continue;
            }
            final BotRecord r = byBot(e.getKey());
            if ((r == null) || r.inWorld()) {
                continue;
            }
            final int want = r.botId() == row.leaderBotId() ? g.leaderLevel() : memberTarget(r.persona() == null ? r.charId() : r.persona().seed(), g);
            if (r.paperLevel(now) >= want) {
                continue;
            }
            r.ensureMinPaperLevel(want, now);
            raised++;
            if (repo != null) {
                try {
                    repo.saveProgress(r.botId(), r.levelBase(), r.baseAt());
                } catch (Exception ex) {
                    ctx.log().warn("grade progress " + r.name() + ": " + ex.getMessage());
                }
            }
        }
        if ((row.level() != g.clanLevel()) || (raised > 0)) {
            final ClanStore.Row graded = new ClanStore.Row(row.clanId(), row.templateId(), row.name(), g.clanLevel(), row.leaderBotId(), row.targetSize(), row.status());
            try {
                store.updateClan(graded);
                byClan.put(clanId, graded);
            } catch (Exception ex) {
                ctx.log().warn("grade clan row " + row.name() + ": " + ex.getMessage());
            }
            ctx.log().info("bot clan " + row.name() + " graded for " + town + ": clan lv " + g.clanLevel() + ", members " + g.minLevel() + "-" + g.maxLevel()
                + " (" + raised + " raised), leader " + g.leaderLevel());
        }
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
