-- bots-core: one bot clan per recruiting town (its clan hall there, its own recruiter = its leader). Fixed mapping.
CREATE TABLE IF NOT EXISTS mod_bots_core_town_clan (
  town        VARCHAR(16) NOT NULL,
  clan_id     INT NOT NULL,
  created_at  BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (town)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
