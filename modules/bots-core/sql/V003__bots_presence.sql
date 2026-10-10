-- bots-core step 3e (presence director): a persona's level "on paper" keeps growing while the bot is out of the world.
-- level now = max(real level, level_base + growth since base_at); see org.l2start.bots.director.PaperLevel.
CREATE TABLE IF NOT EXISTS mod_bots_core_progress (
  bot_id      INT UNSIGNED NOT NULL PRIMARY KEY,
  level_base  INT NOT NULL,
  base_at     BIGINT NOT NULL,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
