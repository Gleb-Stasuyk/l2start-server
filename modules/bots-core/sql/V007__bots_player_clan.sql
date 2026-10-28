-- bots-core phase 7e: bots that left bot-clans to join a live player's clan (persistent pin).
CREATE TABLE IF NOT EXISTS mod_bots_core_player_clan (
  bot_id     INT UNSIGNED NOT NULL,
  joined_at  BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (bot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
