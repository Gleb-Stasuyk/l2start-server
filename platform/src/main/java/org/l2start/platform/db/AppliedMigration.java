package org.l2start.platform.db;

/** Row of platform_module_migrations. */
public record AppliedMigration(int version, String script, String checksum, boolean success, String error) {
}
