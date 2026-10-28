-- bots-core phase 7d: bot clans (stock clan_id + module membership).
CREATE TABLE IF NOT EXISTS mod_bots_core_clan (
  clan_id        INT UNSIGNED NOT NULL,
  template_id    VARCHAR(64) NOT NULL,
  name           VARCHAR(16) NOT NULL,
  level          TINYINT UNSIGNED NOT NULL DEFAULT 2,
  leader_bot_id  INT UNSIGNED NOT NULL,
  target_size    SMALLINT UNSIGNED NOT NULL DEFAULT 8,
  status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at     BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (clan_id),
  UNIQUE KEY uq_name (name),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

CREATE TABLE IF NOT EXISTS mod_bots_core_clan_member (
  bot_id   INT UNSIGNED NOT NULL,
  clan_id  INT UNSIGNED NOT NULL,
  role     VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
  PRIMARY KEY (bot_id),
  KEY idx_clan (clan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
