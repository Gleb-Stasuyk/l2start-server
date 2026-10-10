CREATE TABLE IF NOT EXISTS `global_variables` (
  `var`  VARCHAR(191) NOT NULL DEFAULT '',
  `value` VARCHAR(255),
  PRIMARY KEY (`var`)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;