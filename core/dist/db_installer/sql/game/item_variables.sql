DROP TABLE IF EXISTS `item_variables`;
CREATE TABLE IF NOT EXISTS `item_variables` (
  `id` int(10) UNSIGNED NOT NULL,
  `var` varchar(191) NOT NULL,
  `val` text NOT NULL,
  PRIMARY KEY (`id`, `var`)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
