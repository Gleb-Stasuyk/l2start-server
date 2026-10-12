-- economy-market (decision of 12.10.2026): generated store places of a town, kept so they stay the same after a restart
CREATE TABLE IF NOT EXISTS mod_economy_market_spot (
  town        VARCHAR(24) NOT NULL,
  spot        VARCHAR(32) NOT NULL,
  idx         INT NOT NULL,
  x           INT NOT NULL,
  y           INT NOT NULL,
  z           INT NOT NULL,
  heading     INT NOT NULL,
  kind        VARCHAR(8) NOT NULL,
  profile     VARCHAR(24) NOT NULL,
  legacy      VARCHAR(35) NOT NULL DEFAULT '',
  race        VARCHAR(12) NOT NULL DEFAULT '',
  sex         VARCHAR(8) NOT NULL DEFAULT '',
  class_id    INT NOT NULL DEFAULT -1,
  PRIMARY KEY (town, spot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
