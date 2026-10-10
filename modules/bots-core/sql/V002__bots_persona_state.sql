-- bots-core step 3b: persona (permanent character of a bot) and state (restored after a restart).
CREATE TABLE IF NOT EXISTS mod_bots_core_persona (
  bot_id      INT UNSIGNED NOT NULL PRIMARY KEY,
  seed        BIGINT NOT NULL,
  version     INT NOT NULL,
  name_style  VARCHAR(16) NOT NULL,
  data        TEXT NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

CREATE TABLE IF NOT EXISTS mod_bots_core_state (
  bot_id      INT UNSIGNED NOT NULL PRIMARY KEY,
  in_world    TINYINT NOT NULL DEFAULT 0,
  goal        VARCHAR(32) NOT NULL DEFAULT 'IDLE',
  home_x      INT NOT NULL DEFAULT 0,
  home_y      INT NOT NULL DEFAULT 0,
  home_z      INT NOT NULL DEFAULT 0,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
