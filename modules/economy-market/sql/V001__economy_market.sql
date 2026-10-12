-- economy-market step 5a: price index, trade log, bot wallets and bot stores (stores are filled from step 5b).
-- Bot money and goods are book-keeping only: real items exist in a bot's inventory only while it stands in the world.
CREATE TABLE IF NOT EXISTS mod_economy_market_price_index (
  item_id     INT UNSIGNED NOT NULL PRIMARY KEY,
  ema         DOUBLE NOT NULL,
  deals       BIGINT NOT NULL DEFAULT 0,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

-- one row per line of a private store deal (hook P5-STORE-DEAL); rows older than TradeLogDays are deleted
CREATE TABLE IF NOT EXISTS mod_economy_market_trade_log (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  at          BIGINT NOT NULL,
  seller_id   INT UNSIGNED NOT NULL,
  buyer_id    INT UNSIGNED NOT NULL,
  item_id     INT UNSIGNED NOT NULL,
  count       BIGINT NOT NULL,
  price       BIGINT NOT NULL,
  buy_store   TINYINT(1) NOT NULL,
  seller_bot  TINYINT(1) NOT NULL,
  buyer_bot   TINYINT(1) NOT NULL,
  KEY idx_at (at),
  KEY idx_item_at (item_id, at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

CREATE TABLE IF NOT EXISTS mod_economy_market_wallet (
  bot_id      INT UNSIGNED NOT NULL PRIMARY KEY,
  adena       BIGINT NOT NULL DEFAULT 0,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

-- a bot trader's store on paper: its place, kind (SELL, PACKAGE, BUY, CRAFT) and sign
CREATE TABLE IF NOT EXISTS mod_economy_market_store (
  bot_id      INT UNSIGNED NOT NULL PRIMARY KEY,
  town        VARCHAR(24) NOT NULL,
  spot        VARCHAR(32) NOT NULL,
  kind        VARCHAR(8) NOT NULL,
  profile     VARCHAR(24) NOT NULL,
  title       VARCHAR(64) NOT NULL DEFAULT '',
  opened_at   BIGINT NOT NULL,
  touched_at  BIGINT NOT NULL,
  UNIQUE KEY uq_spot (town, spot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;

CREATE TABLE IF NOT EXISTS mod_economy_market_store_item (
  bot_id      INT UNSIGNED NOT NULL,
  item_id     INT UNSIGNED NOT NULL,
  count       BIGINT NOT NULL,
  price       BIGINT NOT NULL,
  PRIMARY KEY (bot_id, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
