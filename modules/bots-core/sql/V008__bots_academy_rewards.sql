-- bots-core phase 7f: one-time academy join/graduate adena rewards for live players.
CREATE TABLE IF NOT EXISTS mod_bots_core_academy_reward (
  char_id         INT UNSIGNED NOT NULL,
  bot_clan_id     INT NOT NULL DEFAULT 0,
  join_paid       TINYINT NOT NULL DEFAULT 0,
  graduate_paid   TINYINT NOT NULL DEFAULT 0,
  joined_at       BIGINT NOT NULL DEFAULT 0,
  graduated_at    BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (char_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
