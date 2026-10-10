package org.l2start.platform.api;

import java.nio.file.Path;

public interface ModuleInfo {
    String id();

    String name();

    String version();

    /** Active profile (solo, online, ...). */
    String profile();

    /** Module folder (read-only at runtime). */
    Path root();
}
