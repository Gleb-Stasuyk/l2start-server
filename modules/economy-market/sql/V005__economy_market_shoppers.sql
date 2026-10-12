-- economy-market step 5e: bot personas that come to real players' private stores and buy (kept between restarts)
CREATE TABLE IF NOT EXISTS mod_economy_market_shopper (
  bot_id      INT UNSIGNED NOT NULL PRIMARY KEY,
  created_at  BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
