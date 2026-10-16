package org.l2start.bots.clan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Phase 7d: bot clans — paper membership in module tables; stock {@link Clan} created on first leader enter via
 * {@link ClanTable#createClan}.
 */
public final class ClanService {
    private final ModuleContext ctx;
    private final BotRegistry registry;
    private final ClanStore store;
    private final List<ClanTemplate> templates;
    private final Map<Integer, Integer> botClan = new ConcurrentHashMap<>(); // botId -> stock clanId
    private final Map<Integer, ClanStore.Row> byClan = new ConcurrentHashMap<>();
    private final Random rnd = new Random();

    private volatile int wantClans = 12;
    private volatile int membersMin = 8;
    private volatile int membersMax = 20;
    private volatile double mixedPartyChance = 0.25;

    public ClanService(ModuleContext ctx, BotRegistry registry, Path xml) throws Exception {
        this.ctx = ctx;
        this.registry = registry;
        this.store = new ClanStore(ctx.db());
        this.templates = ClanTemplates.load(xml);
    }

    public void configure(int botClans, int minMembers, int maxMembers, double mixedChance) {
        wantClans = Math.max(0, Math.min(20, botClans));
        membersMin = Math.max(1, Math.min(40, minMembers));
        membersMax = Math.max(membersMin, Math.min(40, maxMembers));
        mixedPartyChance = Math.max(0, Math.min(1, mixedChance));
    }

    public double mixedPartyChance() {
        return mixedPartyChance;
    }

    public int wantClans() {
        return wantClans;
    }

    public List<ClanTemplate> templates() {
        return templates;
    }

    public int clanIdOf(int botId) {
        return botClan.getOrDefault(botId, 0);
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

    /** Load module tables into memory (stock clans already restored by ClanTable at boot). */
    public void load() throws Exception {
        botClan.clear();
        byClan.clear();
        for (ClanStore.Row r : store.loadClans()) {
            byClan.put(r.clanId(), r);
        }
        for (ClanStore.Member m : store.loadMembers()) {
            botClan.put(m.botId(), m.clanId());
        }
        ctx.log().info("bot clans: " + byClan.size() + " active, " + botClan.size() + " members (templates " + templates.size() + ")");
    }

    /**
     * Ensure {@link #wantClans} paper clans exist and are filled to their target size. Creates stock clan when the
     * leader is (or becomes) online via {@link #attachPlayer}.
     */
    public synchronized void ensureFilled() {
        try {
            final List<Integer> free = freeActiveBots();
            while (byClan.size() < wantClans) {
                if (free.size() < membersMin) {
                    ctx.log().info("bot clans: need more personas to create clan (" + free.size() + " free, min " + membersMin + ")");
                    break;
                }
                final ClanTemplate t = unusedTemplate();
                if (t == null) {
                    ctx.log().warn("bot clans: no free template names left");
                    break;
                }
                final int size = membersMin + rnd.nextInt((membersMax - membersMin) + 1);
                final int leaderBot = free.remove(rnd.nextInt(free.size()));
                final BotRecord leader = byBot(leaderBot);
                if (leader == null) {
                    continue;
                }
                // provisional id far from IdManager range; rewritten to stock clan id on leader enter
                final int paperId = provisionalClanId(leaderBot);
                if (ClanTable.getInstance().getClan(paperId) != null) {
                    continue;
                }
                final ClanStore.Row row = new ClanStore.Row(paperId, t.id(), t.name(), t.level(), leaderBot, size, "ACTIVE");
                store.insertClan(row);
                store.insertMember(leaderBot, paperId, "LEADER");
                botClan.put(leaderBot, paperId);
                byClan.put(paperId, row);
                fillMembers(paperId, size - 1, free);
                ctx.log().info("bot clan paper: " + t.name() + " lvl " + t.level() + " leader " + leader.name() + " target " + size);
            }
            // refill existing
            for (ClanStore.Row r : new ArrayList<>(byClan.values())) {
                final int have = store.memberCount(r.clanId());
                if (have < r.targetSize()) {
                    fillMembers(r.clanId(), r.targetSize() - have, freeActiveBots());
                }
            }
        } catch (Exception e) {
            ctx.log().warn("bot clans ensureFilled: " + e.getMessage(), e);
        }
    }

    private void fillMembers(int clanId, int need, List<Integer> free) throws Exception {
        int n = 0;
        while ((n < need) && !free.isEmpty()) {
            final int botId = free.remove(rnd.nextInt(free.size()));
            store.insertMember(botId, clanId, "MEMBER");
            botClan.put(botId, clanId);
            n++;
        }
    }

    private List<Integer> freeActiveBots() throws Exception {
        final List<Integer> all = new ArrayList<>();
        for (BotRecord r : registry.all()) {
            if (r.status() == BotRecord.Status.ACTIVE) {
                all.add(r.botId());
            }
        }
        return store.freeBotIds(all);
    }

    private ClanTemplate unusedTemplate() {
        final Set<String> usedNames = new HashSet<>();
        final Set<String> usedIds = new HashSet<>();
        for (ClanStore.Row r : byClan.values()) {
            usedNames.add(r.name().toLowerCase());
            usedIds.add(r.templateId());
        }
        // also stock names
        for (Clan c : ClanTable.getInstance().getClans()) {
            usedNames.add(c.getName().toLowerCase());
        }
        final List<ClanTemplate> free = new ArrayList<>();
        for (ClanTemplate t : templates) {
            if (!usedIds.contains(t.id()) && !usedNames.contains(t.name().toLowerCase())) {
                free.add(t);
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
     * On bot enter world: create stock clan if this is the paper leader and stock missing; join stock clan otherwise.
     */
    public void attachPlayer(BotRecord r, Player p) {
        if ((r == null) || (p == null)) {
            return;
        }
        final int paper = botClan.getOrDefault(r.botId(), 0);
        if (paper == 0) {
            return;
        }
        try {
            ClanStore.Row row = byClan.get(paper);
            if (row == null) {
                // maybe remapped to stock id
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
            if (isProvisional(row.clanId()) && row.leaderBotId() == r.botId()) {
                promoteToStock(row, r, p);
                return;
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
            // materializer should raise level; if not, skip
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
        if (crest != null) {
            clan.changeClanCrest(crest.getId());
        }
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
        ctx.log().info("bot clan stock: " + paper.name() + " id=" + stockId + " lvl " + clan.getLevel());
        // join online members already in world
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

    /** Provisional paper clan ids live in a high band so they never collide with stock IdManager ids. */
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

    /** Test helper: drop a member from paper (triggers refill on next ensureFilled). */
    public void removeMemberForTest(int botId) throws Exception {
        store.removeMember(botId);
        botClan.remove(botId);
    }

    /** Pure rule: should this clan hunter take mixed mates? */
    public static boolean useMixedParty(boolean clanBot, double chance, Random rnd) {
        return clanBot && (rnd.nextDouble() < chance);
    }

    /** Prefer same-clan candidates unless mixed. */
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
}
