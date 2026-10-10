package org.l2start.platform.api;

public interface ModuleLog {
    void info(String message);

    void warn(String message);

    void warn(String message, Throwable t);

    void error(String message, Throwable t);

    void debug(String message);
}
