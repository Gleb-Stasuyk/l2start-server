package org.l2start.rates.floating;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.Supplier;

import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.npc.DropType;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * Rates x10 with floating drop (docs/RATES_PLAN.md §0): XP/SP/quests/manor come from the Rates.ini override of the
 * profile; this module builds the drop and spoil of monsters out of extra stock rolls (hook P7-DROP-FLOAT) and keeps
 * valuable quest rewards x1 (hook P7-QUEST-REWARD).
 */
public final class RatesFloatingModule implements GameModule {
    private ModuleContext ctx;
    private volatile Rules rules = Rules.DEFAULT;
    private volatile ItemKinds kinds;
    /** Self test: the stock calculation for the baseline. */
    private static final ThreadLocal<Boolean> STOCK = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final AtomicLong kills = new AtomicLong();
    private final AtomicLong spoils = new AtomicLong();
    private final AtomicLong rolls = new AtomicLong();
    private final AtomicLong champions = new AtomicLong();
    private final AtomicLong stockAdena = new AtomicLong();
    private final AtomicLong adena = new AtomicLong();
    private final AtomicLong stackLines = new AtomicLong();
    private final DoubleAdder amountSum = new DoubleAdder();
    private final DoubleAdder fSum = new DoubleAdder();

    @Override
    public void onEnable(ModuleContext c) throws Exception {
        ctx = c;
        kinds = new ItemKinds(ItemRules.load(c.resources().path("data/exact-items.xml")));
        rules = readRules();
        c.onDrops(this::onDrops);
        c.onQuestReward(this::onQuestReward);
        c.handlers().admin(new AdminRates(this));
        if (c.config().getBoolean("DropView")) {
            c.handlers().wrapBypass("NpcViewMod", stock -> new DropView(this, stock));
        }
        final Duration every = c.config().getDuration("StatsInterval");
        if (every.toMillis() > 0) {
            c.scheduler().fixedRate("rates-stats", every, every, this::logStats);
        }
        c.log().info(String.format(Locale.ROOT, "enabled: drop chance %.1fxF, spoil %.1fxF, raid x%.1f, epic x1, champion x%.1f (adena x%.1f), adena x%.1f (raid x%.1f), amount x1..x%.1f (mean %.2f), F %.2f..%.2f, quest x1 list %d items",
            rules.dropChance(), rules.spoilChance(), rules.raidChance(), rules.championChance(), rules.championAdena(), rules.adenaRate(), rules.raidAdena(), rules.amountMax(), rules.meanAmount(), rules.fMin(), rules.fMax(),
            kinds.questX1Listed()));
    }

    @Override
    public void onServerReady(ModuleContext c) {
        if (c.config().getBoolean("SelfTest")) {
            c.scheduler().schedule("rates-selftest", Duration.ofSeconds(2), this::selfTest);
        }
    }

    @Override
    public void onConfigReload(ModuleContext c) {
        rules = readRules();
        c.log().info("reloaded: drop chance " + rules.dropChance() + "xF, adena x" + rules.adenaRate());
    }

    Rules readRules() {
        final var cf = ctx.config();
        final double[] clamp = pair(cf.getString("FClamp"), 0.35, 1.5);
        return new Rules(Curve.parse(cf.getString("LevelCurve")), Curve.parse(cf.getString("DiffCurve")), clamp[0], clamp[1], cf.getDouble("DropChance"), cf.getDouble("SpoilChance"),
            cf.getDouble("RaidChance"), cf.getDouble("AdenaRate"), cf.getDouble("RaidAdena"), cf.getDouble("AmountMax"), cf.getDouble("AmountSkew"), false, cf.getInt("MaxExtraRolls"),
            cf.getDouble("ChampionChance"), cf.getDouble("ChampionAdena"));
    }

    static double[] pair(String s, double lo, double hi) {
        try {
            final String[] p = s.split(",");
            final double a = Double.parseDouble(p[0].trim());
            final double b = Double.parseDouble(p[1].trim());
            return new double[] { Math.min(a, b), Math.max(a, b) };
        } catch (RuntimeException e) {
            return new double[] { lo, hi };
        }
    }

    /** The level F is counted from: the killer's, in a party the highest level of the party. */
    static int refLevel(Creature killer) {
        int level = killer.getLevel();
        final Player p = killer.asPlayer();
        if ((p != null) && p.isInParty()) {
            for (Player m : p.getParty().getMembers()) {
                level = Math.max(level, m.getLevel());
            }
        }
        return level;
    }

    List<ItemHolder> onDrops(boolean spoil, Creature victim, Creature killer, Supplier<List<ItemHolder>> stockRoll) {
        if (STOCK.get() || !ctx.config().getBoolean("Enabled") || (victim instanceof GrandBoss)) {
            return null; // epics: everything stock
        }
        final Rules r = rules;
        final boolean raid = victim.isRaid() && !victim.isRaidMinion();
        final double f = raid ? 1 : r.f(refLevel(killer), victim.getLevel());
        final boolean champion = !raid && victim.isChampion();
        final double chance = (raid ? r.raidChance() : r.chance(spoil, f)) * (champion ? r.championChance() : 1);
        final double adenaRate = (raid ? r.raidAdena() : r.adenaRate()) * (champion ? r.championAdena() : 1);
        final DropFloat.Result res = DropFloat.build(stockRoll, kinds::of, chance, r.maxExtraRolls() + (champion ? 2 : 0), adenaRate, !raid, r, ThreadLocalRandom.current());
        if (champion) {
            champions.incrementAndGet();
        }
        (spoil ? spoils : kills).incrementAndGet();
        rolls.addAndGet(res.rolls());
        stockAdena.addAndGet(res.stockAdena());
        adena.addAndGet(res.adena());
        stackLines.addAndGet(res.stackLines());
        amountSum.add(res.amountSum());
        fSum.add(f);
        return res.items();
    }

    long onQuestReward(Player player, int itemId, long base, long rated) {
        if (!ctx.config().getBoolean("Enabled") || (itemId == 57) || !kinds.questX1(itemId)) {
            return rated;
        }
        return Math.min(base, rated); // valuable rewards x1
    }

    String stats() {
        final long k = kills.get() + spoils.get();
        final long lines = stackLines.get();
        return String.format(Locale.ROOT, "RATES kills=%d spoils=%d champions=%d rolls/kill=%.2f F=%.2f adena x%.2f amount x%.2f (%d stack lines)", kills.get(), spoils.get(), champions.get(),
            k == 0 ? 0.0 : rolls.get() / (double) k, k == 0 ? 0.0 : fSum.sum() / k, stockAdena.get() == 0 ? 0.0 : adena.get() / (double) stockAdena.get(),
            lines == 0 ? 0.0 : amountSum.sum() / lines, lines);
    }

    private void logStats() {
        if ((kills.get() + spoils.get()) > 0) {
            ctx.log().info(stats());
        }
    }

    Rules rules() {
        return rules;
    }

    ItemKinds kinds() {
        return kinds;
    }

    boolean enabled() {
        return ctx.config().getBoolean("Enabled");
    }

    /**
     * Smoke: 3 000 kills of a monster near level 20 / 40 / 60 with a killer of the same level (F = level curve), stock
     * calculation against the floating one, averages in the log.
     */
    private void selfTest() {
        for (int lvl : new int[] { 20, 40, 60 }) {
            final NpcTemplate t = NpcData.getInstance().getTemplates(x -> x.isType("Monster") && (x.getLevel() == lvl) && (x.getDropGroups() != null) && (x.getSpoilList() != null))
                .stream().max(Comparator.comparingInt((NpcTemplate x) -> x.getSpoilList().size()).thenComparingInt(x -> -x.getId())).orElse(null); // the richest spoil list: enough pieces for an average
            if (t == null) {
                ctx.log().warn("SELFTEST no monster of level " + lvl);
                continue;
            }
            final Monster victim = new Monster(t);
            final Monster killer = new Monster(t);
            final int n = 3000;
            final long stockAd0 = stockAdena.get(), ad0 = adena.get(); // adena rate from the same rolls (independent runs differ by luck)
            long stockLines = 0, floatLines = 0, stockAd = 0, floatAd = 0, stockSpoil = 0, floatSpoil = 0;
            int exactMax = 0;
            for (int i = 0; i < n; i++) {
                STOCK.set(Boolean.TRUE);
                final List<ItemHolder> s;
                final List<ItemHolder> sp;
                try {
                    s = t.calculateDrops(DropType.DROP, victim, killer);
                    sp = t.calculateDrops(DropType.SPOIL, victim, killer);
                } finally {
                    STOCK.set(Boolean.FALSE);
                }
                final List<ItemHolder> fl = t.calculateDrops(DropType.DROP, victim, killer);
                final List<ItemHolder> fsp = t.calculateDrops(DropType.SPOIL, victim, killer);
                for (ItemHolder h : nn(s)) {
                    if (h.getId() == 57) {
                        stockAd += h.getCount();
                    } else {
                        stockLines++;
                    }
                }
                for (ItemHolder h : nn(fl)) {
                    if (h.getId() == 57) {
                        floatAd += h.getCount();
                    } else {
                        floatLines++;
                        if (kinds.of(h.getId()) == Kind.EXACT) {
                            exactMax = Math.max(exactMax, h.getCount());
                        }
                    }
                }
                stockSpoil += nn(sp).stream().mapToLong(ItemHolder::getCount).sum();
                floatSpoil += nn(fsp).stream().mapToLong(ItemHolder::getCount).sum();
            }
            final long dStock = stockAdena.get() - stockAd0, dAd = adena.get() - ad0;
            final double f = rules.f(lvl, lvl);
            ctx.log().info(String.format(Locale.ROOT,
                "SELFTEST mob %d lvl %d F=%.2f C=%.2f kills=%d: adena x%.3f (expect %.1f; independent runs %d -> %d), drop items/kill %.3f -> %.3f (x%.2f), spoil pieces/kill %.3f -> %.3f (x%.2f, expect ~%.1f), exact max %d",
                t.getId(), lvl, f, rules.chance(false, f), n, dStock == 0 ? 0.0 : dAd / (double) dStock, rules.adenaRate(), stockAd, floatAd, stockLines / (double) n, floatLines / (double) n,
                stockLines == 0 ? 0.0 : floatLines / (double) stockLines, stockSpoil / (double) n, floatSpoil / (double) n, stockSpoil == 0 ? 0.0 : floatSpoil / (double) stockSpoil,
                rules.chance(true, f) * rules.meanAmount(), exactMax));
        }
        ctx.log().info("SELFTEST done");
    }

    private static List<ItemHolder> nn(List<ItemHolder> l) {
        return (l == null) ? List.of() : l;
    }

}
