-- bots-core phase 7c: PK bot gear ledger (real items; death drop is stock; no re-issue of the same object id).
CREATE TABLE IF NOT EXISTS mod_bots_core_pk_ledger (
  bot_id     INT UNSIGNED NOT NULL,
  object_id  INT UNSIGNED NOT NULL,
  item_id    INT NOT NULL,
  issued_at  BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (bot_id, object_id),
  KEY idx_bot (bot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
