-- bots-core phase 6a: what a bot persona remembers of a real player (party, resurrections, refusals, annoyance).
-- affinity -100..100; tags: comma separated (PARTY, REZZED_ME, I_REZZED, BUFFED_ME, TRADED, DECLINED, ANNOYED, KILLED_ME).
CREATE TABLE IF NOT EXISTS mod_bots_core_memory (
  bot_id        INT UNSIGNED NOT NULL,
  char_id       INT UNSIGNED NOT NULL,
  affinity      SMALLINT NOT NULL DEFAULT 0,
  met           INT NOT NULL DEFAULT 0,
  party_minutes INT NOT NULL DEFAULT 0,
  last_seen     BIGINT NOT NULL DEFAULT 0,
  tags          VARCHAR(160) NOT NULL DEFAULT '',
  PRIMARY KEY (bot_id, char_id),
  KEY idx_char (char_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
