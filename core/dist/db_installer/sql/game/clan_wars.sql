CREATE TABLE IF NOT EXISTS `clan_wars` (
  `clan1` varchar(35) NOT NULL DEFAULT '',
  `clan2` varchar(35) NOT NULL DEFAULT '',
  `wantspeace1` decimal(1,0) NOT NULL DEFAULT '0',
  `wantspeace2` decimal(1,0) NOT NULL DEFAULT '0',
  PRIMARY KEY (`clan1`,`clan2`)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;