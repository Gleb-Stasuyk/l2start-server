package org.l2start.fixtures.flaky;

import java.time.Duration;

import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

/** Smoke fixture: a task that always throws; the circuit breaker must switch the module to DEGRADED. */
public final class Flaky implements GameModule {
    @Override
    public void onServerReady(ModuleContext ctx) {
        ctx.scheduler().fixedRate("flaky", Duration.ofMillis(200), Duration.ofMillis(200), () -> {
            throw new IllegalStateException("FIXTURE flaky task failure");
        });
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        ctx.log().info("FIXTURE fx-flaky enabled");
    }
}
