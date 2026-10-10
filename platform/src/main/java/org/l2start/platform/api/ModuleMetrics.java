package org.l2start.platform.api;

import java.util.Map;

public interface ModuleMetrics {
    Counter counter(String name);

    Map<String, Long> snapshot();

    interface Counter {
        void inc();

        void add(long n);

        long get();
    }
}
