package org.l2start.platform.runtime;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Global service map; visibility: the provider itself and modules that depend on it. */
public final class ServiceDirectory {
    private record Entry(String provider, Object impl) {
    }

    private final Map<Class<?>, Entry> services = new HashMap<>();

    public synchronized void provide(String provider, Class<?> type, Object impl) {
        if (!type.isInstance(impl)) {
            throw new IllegalArgumentException(impl + " is not a " + type.getName());
        }
        final Entry e = services.get(type);
        if (e != null) {
            throw new IllegalStateException("service " + type.getName() + " is already provided by module " + e.provider);
        }
        services.put(type, new Entry(provider, impl));
    }

    public synchronized void remove(String provider, Class<?> type) {
        final Entry e = services.get(type);
        if (e != null && e.provider.equals(provider)) {
            services.remove(type);
        }
    }

    public synchronized <T> Optional<T> find(String requester, Collection<String> visibleProviders, Class<T> type) {
        final Entry e = services.get(type);
        if (e == null || !(e.provider.equals(requester) || visibleProviders.contains(e.provider))) {
            return Optional.empty();
        }
        return Optional.of(type.cast(e.impl));
    }
}
