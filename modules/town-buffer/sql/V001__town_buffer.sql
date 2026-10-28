-- town-buffer: the bot persona (bots-core, status TRADER) of each town buffer, kept across restarts.
CREATE TABLE IF NOT EXISTS mod_town_buffer_bot (
  town        VARCHAR(16) NOT NULL PRIMARY KEY,
  bot_id      INT UNSIGNED NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
