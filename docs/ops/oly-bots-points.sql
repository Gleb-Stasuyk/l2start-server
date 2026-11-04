-- OPTIONAL manual fallback. bots-olympiad 0.4.0 resets bot points itself every day before the competition start
-- (DailyPointsReset/DailyPoints, live cache, no game stop) and //oly points [n] does it at once in game.
-- This SQL sets bot nobles to exactly 18 (lower or higher). Stock caches olympiad_nobles in memory and writes it back,
-- so run ONLY with l2s-game stopped, after a DB backup:
--   docker stop l2s-game && docker exec -i l2s-db mariadb l2start < docs/ops/oly-bots-points.sql && docker start l2s-game
-- Only bots (mod_bots_core_identity.status = 'OLYMPIAD') are touched.
SELECT COUNT(*) AS bots_not_18 FROM olympiad_nobles n JOIN mod_bots_core_identity b ON b.char_id = n.charId
 WHERE b.status = 'OLYMPIAD' AND n.olympiad_points <> 18;
UPDATE olympiad_nobles n JOIN mod_bots_core_identity b ON b.char_id = n.charId
   SET n.olympiad_points = 18
 WHERE b.status = 'OLYMPIAD' AND n.olympiad_points <> 18;
SELECT ROW_COUNT() AS updated;
