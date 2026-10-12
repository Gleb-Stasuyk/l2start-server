-- economy-market steps 5b/5c: bot trader stores in the world.
-- lines_start: lines of the last restock (a store that sold more than half refills); level: the trader's level.
ALTER TABLE mod_economy_market_store ADD COLUMN IF NOT EXISTS lines_start INT NOT NULL DEFAULT 0;

-- goods bot buyers bought (from players or "on paper"): about half comes back to bot sell stores of the town
CREATE TABLE IF NOT EXISTS mod_economy_market_resale (
  item_id     INT UNSIGNED NOT NULL PRIMARY KEY,
  count       BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

-- pieces of an item a real character sold to bot buy stores on a day (SellToBotsItemDay)
CREATE TABLE IF NOT EXISTS mod_economy_market_player_day (
  char_id     INT UNSIGNED NOT NULL,
  item_id     INT UNSIGNED NOT NULL,
  day         INT NOT NULL,
  count       BIGINT NOT NULL,
  PRIMARY KEY (char_id, item_id, day),
  KEY idx_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
