package org.l2start.bots.brain.basic;

import org.l2start.bots.api.BrainRegistry;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/** bots-brain-basic: registers brain "basic" with bots-core (step 3d). */
public final class BotsBrainBasicModule implements GameModule {
    private BrainRegistry registry;

    @Override
    public void onEnable(ModuleContext ctx) throws Exception {
        final LevelZones zones = LevelZones.load(ctx.resources().path("data/levelzones.xml"));
        applyConfig(ctx);
        registry = ctx.services().require(BrainRegistry.class);
        registry.register(new BasicBrainFactory(zones, Village.STARTING));
        ctx.cleanup("unregister brain basic", () -> registry.unregister(BasicBrainFactory.ID));
        ctx.log().info("brain 'basic' registered: " + zones.all().size() + " hunting grounds around " + Village.STARTING.size() + " starting villages, " + describe());
    }

    @Override
    public void onConfigReload(ModuleContext ctx) throws Exception {
        applyConfig(ctx);
        ctx.log().info("config reloaded: " + describe());
    }

    private static void applyConfig(ModuleContext ctx) {
        apply(ctx.config().getString("PvpFightBack"), ctx.config().getBoolean("Potions"), ctx.config().getString("PotionHpPercent"),
            ctx.config().getString("FleeHpPercent"), ctx.config().getString("KiteDistance"));
    }

    /** Parses the brain settings into BasicBrain (bad values: the defaults stay). */
    static void apply(String pvpFightBack, boolean potions, String potionHp, String fleeHp, String kite) {
        BasicBrain.fightBackBase = clamp(num(pvpFightBack, 0.5), 0, 1);
        BasicBrain.potions = potions;
        final double[] p = pair(potionHp, 40, 60);
        BasicBrain.potionLo = clamp(Math.min(p[0], p[1]), 0, 100);
        BasicBrain.potionHi = clamp(Math.max(p[0], p[1]), 0, 100);
        BasicBrain.fleeHp = clamp(num(fleeHp, 25), 0, 100);
        final double[] k = pair(kite, 120, 400);
        BasicBrain.kiteClose = (int) clamp(k[0], 0, 1000);
        BasicBrain.kiteTo = (int) clamp(k[1], 150, 1500);
    }

    static String describe() {
        return String.format(java.util.Locale.ROOT, "fights back real players with base chance %.2f, potions %s at hp %.0f-%.0f%%, flees below %.0f%%, kiting %s",
            BasicBrain.fightBackBase, BasicBrain.potions ? "on" : "off", BasicBrain.potionLo, BasicBrain.potionHi, BasicBrain.fleeHp,
            BasicBrain.kiteClose <= 0 ? "off" : ("reach+" + BasicBrain.kiteClose + " -> " + BasicBrain.kiteTo));
    }

    private static double num(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    /** "a,b" or a single "a" (both = a; "0" = off for kiting). */
    private static double[] pair(String s, double a, double b) {
        if (s == null) {
            return new double[] { a, b };
        }
        final String[] parts = s.split(",");
        final double x = num(parts[0], Double.NaN);
        if (Double.isNaN(x)) {
            return new double[] { a, b };
        }
        return new double[] { x, parts.length > 1 ? num(parts[1], b) : (x <= 0 ? b : x) };
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
