package org.l2start.fixtures.okdep;

import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/** Smoke fixture: must reach RUNNING; proves that a dependency's classes are visible. */
public final class OkDep implements GameModule {
    @Override
    public void onEnable(ModuleContext ctx) {
        final String dep = org.l2start.modules.hello.Greeting.class.getName();
        ctx.log().info("FIXTURE fx-ok-dep enabled, sees " + dep);
    }

    @Override
    public void onServerReady(ModuleContext ctx) {
        ctx.log().info("FIXTURE fx-ok-dep ready");
    }
}
