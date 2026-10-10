-- Fixture table: a counter that survives restarts (smoke checks n=1 on first boot, n=2 after restart).
CREATE TABLE IF NOT EXISTS mod_fx_core_counter (
  id INT NOT NULL PRIMARY KEY,
  n  INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_unicode_ci;
