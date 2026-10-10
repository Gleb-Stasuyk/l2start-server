-- How often each character used .hello (day = yyyymmdd of the last use).
CREATE TABLE IF NOT EXISTS mod_hello_counter (
  char_id    INT UNSIGNED NOT NULL PRIMARY KEY,
  day        INT          NOT NULL,
  count      INT          NOT NULL DEFAULT 0,
  total      INT          NOT NULL DEFAULT 0,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
