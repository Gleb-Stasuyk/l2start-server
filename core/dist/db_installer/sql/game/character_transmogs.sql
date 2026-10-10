CREATE TABLE IF NOT EXISTS `character_transmogs` (
  `owner` VARCHAR(45) NOT NULL,
  `itemId` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`owner`, `itemId`)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
