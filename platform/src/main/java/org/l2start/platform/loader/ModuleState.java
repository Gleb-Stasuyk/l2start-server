package org.l2start.platform.loader;

public enum ModuleState {
    DISCOVERED,
    /** Switched off by modules.ini / env / defaultEnabled. Not an error. */
    DISABLED,
    /** Broken manifest, paths, config or reserved ids. */
    INVALID,
    /** Dependencies, conflicts, cycles, reservation overlap, platform version, downgrade. */
    REFUSED,
    RESOLVED,
    /** Migration, class loading, id collision with stock, onEnable or onServerReady failed. */
    FAILED,
    LOADED,
    ENABLED,
    RUNNING,
    /** Switched off at runtime (circuit breaker or //module disable); back only after a restart. */
    DEGRADED,
    STOPPED;

    /** Module code/data may be used. */
    public boolean isActive() {
        return this == LOADED || this == ENABLED || this == RUNNING;
    }

    public boolean isError() {
        return this == INVALID || this == REFUSED || this == FAILED;
    }
}
