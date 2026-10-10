CREATE TABLE IF NOT EXISTS `account_gsdata` (
  `account_name` VARCHAR(45) NOT NULL DEFAULT '',
  `var`  VARCHAR(191) NOT NULL DEFAULT '',
  `value` text NOT NULL,
  PRIMARY KEY (`account_name`,`var`)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;