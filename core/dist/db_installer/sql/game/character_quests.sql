CREATE TABLE IF NOT EXISTS `character_quests` (
  `charId` INT UNSIGNED NOT NULL DEFAULT 0,
  `name` VARCHAR(60) NOT NULL DEFAULT '',
  `var`  VARCHAR(20) NOT NULL DEFAULT '',
  `value` VARCHAR(255) ,
  PRIMARY KEY (`charId`,`name`,`var`)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_charId_var ON character_quests (charId, var);
