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
        BasicBrain.fightBackBase = Math.max(0, Math.min(1, Double.parseDouble(ctx.config().getString("PvpFightBack").trim())));
        registry = ctx.services().require(BrainRegistry.class);
        registry.register(new BasicBrainFactory(zones, Village.STARTING));
        ctx.cleanup("unregister brain basic", () -> registry.unregister(BasicBrainFactory.ID));
        ctx.log().info("brain 'basic' registered: " + zones.all().size() + " hunting grounds around " + Village.STARTING.size() + " starting villages, fights back real players with base chance " + BasicBrain.fightBackBase);
    }
}
