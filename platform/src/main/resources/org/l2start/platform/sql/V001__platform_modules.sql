-- Platform state per module: downgrade protection and last known state (see docs/MODULES.md).
CREATE TABLE IF NOT EXISTS platform_modules (
  module_id       VARCHAR(32) NOT NULL PRIMARY KEY,
  last_version    VARCHAR(32) NOT NULL,
  first_seen_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_enabled_at DATETIME    NULL,
  last_state      VARCHAR(16) NOT NULL,
  last_error      TEXT        NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
