package org.l2start.fixtures.okdep;

import org.l2jmobius.gameserver.data.xml.BuyListData;
import org.l2start.fixtures.core.FxCoreApi;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/** Smoke fixture: must reach RUNNING; proves dependency class visibility, services and buylist loading. */
public final class OkDep implements GameModule {
    @Override
    public void onEnable(ModuleContext ctx) {
        ctx.log().info("FIXTURE fx-ok-dep enabled, sees " + FxCoreApi.class.getName() + ", service answer=" + ctx.services().require(FxCoreApi.class).answer());
    }

    @Override
    public void onServerReady(ModuleContext ctx) {
        ctx.log().info("FIXTURE fx-ok-dep ready, buylist 95010 " + (BuyListData.getInstance().getBuyList(95010) != null ? "loaded" : "MISSING"));
    }
}
