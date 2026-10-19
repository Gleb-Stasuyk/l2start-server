-- bots-core starter zones S6 (docs/STARTER_ZONES_PLAN.md): live characters created on this server, and when their
-- newbie companions came (once per character).
CREATE TABLE IF NOT EXISTS mod_bots_core_newbie (
  char_id         INT UNSIGNED NOT NULL,
  created_at      BIGINT NOT NULL DEFAULT 0,
  companions_at   BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (char_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
