package org.l2start.modules.stocknpcs;

import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/**
 * Stock Scheme Buffer (50008) and GM Shop (50010) are spawned from {@code data/spawns}.
 * After each buff that NPC applies, the remaining time becomes {@code DurationSec}.
 */
public final class StockNpcsModule implements GameModule {
    private volatile int durationSec = 3600;

    @Override
    public void onEnable(ModuleContext ctx) {
        load(ctx);
        ctx.onSchemeBufferBuff((buffer, target, skillId) -> {
            if (StockNpcBuffs.extend(target, buffer, skillId, durationSec)) {
                ctx.metrics().counter("schemeBufferBuffs").inc();
            }
        });
        ctx.log().info("enabled: scheme buffer buffs " + durationSec + " s (NPC 50008), GM shop NPC 50010");
    }

    @Override
    public void onConfigReload(ModuleContext ctx) {
        load(ctx);
    }

    private void load(ModuleContext ctx) {
        durationSec = ctx.config().getInt("DurationSec");
    }
}
