package org.l2start.economy.market;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.l2start.economy.market.model.MarketBook;
import org.l2start.economy.market.model.MarketItems;
import org.l2start.economy.market.model.PriceModel;
import org.l2start.economy.market.model.Stage;
import org.l2start.economy.market.model.TownQuota;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;
import org.l2start.platform.api.StoreDeal;

/**
 * Phase 5a: the market catalogue, the price index of real store deals (hook P5-STORE-DEAL), the trade log, the server
 * stage and the price model. Deals are queued on the game thread and written by a task (never a database call inside
 * a trade).
 */
public final class EconomyMarketModule implements GameModule {
    private ModuleContext ctx;
    private MarketRepository repo;
    private MarketBook book;
    private final ConcurrentLinkedQueue<long[]> pendingAt = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<StoreDeal> pending = new ConcurrentLinkedQueue<>();
    private volatile int autoStage = 1;
    private volatile TownQuota quota = TownQuota.parse("");
    /** Bot traders (5b/5c): only with the bots-core service; the class is never touched without it. */
    private Object traders;

    @Override
    public void onEnable(ModuleContext context) throws Exception {
        ctx = context;
        repo = new MarketRepository(ctx.db());
        final MarketItems items = MarketItems.load(ctx.resources().path("data/market-items.xml"));
        long seed = ctx.config().getLong("Seed");
        if (seed == 0) {
            seed = repo.seed();
        }
        book = new MarketBook(items, model(), drift(), seed);
        final int restored = repo.loadIndex(book, System.currentTimeMillis());
        quota = TownQuota.parse(ctx.config().getString("StoresPerTown"));
        traders = startTraders();
        ctx.onStoreDeal(this::onDeal);
        ctx.handlers().admin(new AdminMarket(this));
        ctx.log().info("market: " + items.size() + " items, " + restored + " indexed, market " + (marketOn() ? "on" : "off (log and index only)") + ", stores " + quota.active() + ((traders == null) ? ", no traders (bots-core is off)" : ""));
    }

    @Override
    public void onServerReady(ModuleContext context) throws Exception {
        refreshStage();
        ctx.scheduler().fixedRate("write deals", Duration.ofSeconds(10), Duration.ofSeconds(10), () -> unchecked(this::flush));
        if (traders != null) {
            ctx.scheduler().fixedRate("traders", Duration.ofSeconds(5), Duration.ofSeconds(3), () -> unchecked(() -> ((Traders) traders).tick()));
        }
        ctx.scheduler().fixedRate("stage and rotation", Duration.ofMinutes(10), Duration.ofMinutes(10), () -> unchecked(() -> {
            refreshStage();
            repo.rotatePlayerDays((int) java.time.LocalDate.now().toEpochDay() - 2);
            final int gone = repo.rotate(System.currentTimeMillis() - (Math.max(1, ctx.config().getInt("TradeLogDays")) * 86_400_000L));
            if (gone > 0) {
                ctx.log().info("trade log: " + gone + " old deals removed");
            }
        }));
    }

    @Override
    public void onConfigReload(ModuleContext context) {
        book.configure(model(), drift());
        quota = TownQuota.parse(ctx.config().getString("StoresPerTown"));
    }

    @Override
    public void onShutdown(ModuleContext context) throws Exception {
        if (traders != null) {
            ((Traders) traders).closeAll("server stop"); // before bots-core logs the traders out
        }
        flush();
    }

    private interface Work {
        void run() throws Exception;
    }

    /** Task bodies: the platform guard logs and counts what is thrown. */
    private static void unchecked(Work w) {
        try {
            w.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Game thread of the deal: memory only. */
    private void onDeal(StoreDeal d) {
        final long now = System.currentTimeMillis();
        book.deal(d.itemId(), d.price(), d.ownerReal(), now);
        pending.add(d);
        pendingAt.add(new long[] { now });
        ctx.metrics().counter(d.ownerReal() ? "deals real" : "deals bot").inc();
        if (traders != null) {
            ((Traders) traders).onDeal(d);
        }
    }

    private Object startTraders() throws Exception {
        try {
            final Traders t = Traders.start(this);
            if (t != null) {
                t.cleanupAtStart(); // before the stock offline traders are restored
            }
            return t;
        } catch (NoClassDefFoundError e) {
            return null; // bots-core not installed
        }
    }

    /** Lines for //market traders (null = no traders). */
    java.util.List<String> traderLines() {
        return (traders == null) ? null : ((Traders) traders).describe();
    }

    private void flush() throws Exception {
        if (traders != null) {
            ((Traders) traders).flush();
        }
        StoreDeal d;
        while ((d = pending.poll()) != null) {
            final long[] at = pendingAt.poll();
            repo.logDeal(d, (at == null) ? System.currentTimeMillis() : at[0]);
        }
        final Map<Integer, double[]> dirty = book.takeDirty();
        if (!dirty.isEmpty()) {
            repo.saveIndex(dirty);
        }
    }

    void refreshStage() throws Exception {
        if ("solo".equals(ctx.self().profile())) {
            final double[] p = repo.realPlayers();
            autoStage = Stage.solo((int) p[0], p[1]);
        } else {
            final double[] a = repo.serverAge(System.currentTimeMillis());
            autoStage = Stage.online(a[0], a[1]);
        }
    }

    public int stage() {
        return Stage.resolve(ctx.config().getString("MarketStage"), autoStage);
    }

    public int autoStage() {
        return autoStage;
    }

    public boolean marketOn() {
        return auto("Market");
    }

    private boolean drift() {
        return auto("SimulatedDrift");
    }

    private boolean auto(String key) {
        final String v = ctx.config().getString(key);
        return "on".equalsIgnoreCase(v) || ("auto".equalsIgnoreCase(v) && "solo".equals(ctx.self().profile()));
    }

    private PriceModel model() {
        final double[] sell = pair(ctx.config().getString("SellMarkup"), 1.0, 1.25);
        final double[] bid = pair(ctx.config().getString("BuyBid"), 0.55, 0.85);
        return new PriceModel(new PriceModel.Settings(ctx.config().getDouble("PriceMultiplier"), sell[0], sell[1], bid[0], bid[1], ctx.config().getDouble("NpcArbitrageGuard")));
    }

    static double[] pair(String s, double lo, double hi) {
        try {
            final String[] p = s.split(",");
            final double a = Double.parseDouble(p[0].trim());
            final double b = (p.length > 1) ? Double.parseDouble(p[1].trim()) : a;
            return new double[] { Math.min(a, b), Math.max(a, b) };
        } catch (RuntimeException e) {
            return new double[] { lo, hi };
        }
    }

    public MarketBook book() {
        return book;
    }

    public TownQuota quota() {
        return quota;
    }

    MarketRepository repo() {
        return repo;
    }

    public ModuleContext ctx() {
        return ctx;
    }
}
