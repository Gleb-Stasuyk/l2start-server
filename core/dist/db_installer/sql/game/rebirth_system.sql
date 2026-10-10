CREATE TABLE IF NOT EXISTS rebirth_system (
  charId INT NOT NULL,
  rebirthCount INT NOT NULL DEFAULT 0,
  selectedSkills VARCHAR(255) NOT NULL DEFAULT '',
  PRIMARY KEY (charId)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;