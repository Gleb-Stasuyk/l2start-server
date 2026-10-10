package org.l2start.fixtures.broken;

import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/** Smoke fixture: registers a command, then fails; the platform must undo the registration and mark it FAILED. */
public final class Broken implements GameModule {
    @Override
    public void onEnable(ModuleContext ctx) {
        ctx.scheduler().fixedRate("never-runs", java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), () -> ctx.log().info("FIXTURE fx-broken-enable task ran (must not happen)"));
        throw new IllegalStateException("FIXTURE intentional onEnable failure");
    }
}
