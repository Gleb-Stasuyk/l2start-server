package org.l2start.bots.api;

/**
 * Service of bots-core ({@code ctx.services().require(BrainRegistry.class)} in a module that depends on bots-core).
 * Registering a factory with the configured id gives every bot in the world a brain; unregistering takes it away
 * (bots then stand idle).
 */
public interface BrainRegistry {
    void register(BrainFactory factory);

    void unregister(String id);
}
