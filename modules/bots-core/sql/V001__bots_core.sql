-- bots-core: bot accounts and bot characters. Stock tables (accounts, characters, items, ...) hold the real data;
-- these tables only say WHICH accounts and characters belong to bots. bots-core never touches anything not listed here.
CREATE TABLE IF NOT EXISTS mod_bots_core_account (
  account_name VARCHAR(45) NOT NULL PRIMARY KEY,
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

CREATE TABLE IF NOT EXISTS mod_bots_core_identity (
  bot_id       INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  char_id      INT UNSIGNED NOT NULL,
  account_name VARCHAR(45) NOT NULL,
  char_name    VARCHAR(35) NOT NULL,
  class_id     TINYINT UNSIGNED NOT NULL,
  status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login   BIGINT NOT NULL DEFAULT 0,
  last_logout  BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_char (char_id),
  UNIQUE KEY uk_account (account_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
