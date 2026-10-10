package org.l2start.platform.db;

/** One versioned migration file V###__description.sql. */
public record Migration(int version, String description, String script, String checksum, String sql) {
}
