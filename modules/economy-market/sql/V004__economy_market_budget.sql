-- economy-market step 5d: wallets of hunter bots (paper earnings pay their shots and potions) and the daily money flows
-- (the MARKET line). The adena bots paid a real character on a day is kept in mod_economy_market_player_day with item_id 0.
CREATE TABLE IF NOT EXISTS mod_economy_market_hunter (
  bot_id      INT UNSIGNED NOT NULL PRIMARY KEY,
  adena       BIGINT NOT NULL,
  seen_at     BIGINT NOT NULL,
  spent       BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

CREATE TABLE IF NOT EXISTS mod_economy_market_flow (
  day         INT NOT NULL,
  kind        VARCHAR(16) NOT NULL,
  adena       BIGINT NOT NULL,
  PRIMARY KEY (day, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
