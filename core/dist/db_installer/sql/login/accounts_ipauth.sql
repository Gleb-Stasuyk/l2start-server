DROP TABLE IF EXISTS `accounts_ipauth`;
CREATE TABLE IF NOT EXISTS `accounts_ipauth` (
  `login` varchar(45) NOT NULL,
  `ip` char(15) NOT NULL,
  `type` enum('deny','allow') NULL DEFAULT 'allow'
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;