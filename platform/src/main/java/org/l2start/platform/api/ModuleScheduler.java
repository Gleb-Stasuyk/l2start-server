package org.l2start.platform.api;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

/** Named tasks on the stock ThreadPool; guarded, measured and cancelled when the module is disabled. */
public interface ModuleScheduler {
    ScheduledFuture<?> schedule(String name, Duration delay, Runnable task);

    ScheduledFuture<?> fixedRate(String name, Duration initialDelay, Duration period, Runnable task);
}
