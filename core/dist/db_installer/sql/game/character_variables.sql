CREATE TABLE IF NOT EXISTS `character_variables` (
  `charId` int(10) UNSIGNED NOT NULL,
  `var` varchar(191) NOT NULL,
  `val` text NOT NULL,
  PRIMARY KEY (`charId`, `var`)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
