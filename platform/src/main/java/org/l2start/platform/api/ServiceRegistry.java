package org.l2start.platform.api;

import java.util.Optional;

/** Services between modules: visible to the provider and to modules that declare a dependency on it. */
public interface ServiceRegistry {
    <T> void provide(Class<T> type, T implementation);

    <T> T require(Class<T> type);

    <T> Optional<T> find(Class<T> type);
}
