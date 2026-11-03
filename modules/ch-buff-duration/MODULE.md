# ch-buff-duration

Clan hall buffs (support function of the clan hall manager, NPC skills 4342-4360) last **1 hour** for players
of level 39 and lower (level at cast time). Stock duration (20 min etc.) for everyone else and for the fortress
support NPC, which casts the same skills.

How: listener `ON_NPC_SKILL_FINISHED` (global). If the caster is a `ClanHallManager`, the skill is in `Skills` and the
target level <= `MaxLevel`, the buff on the target gets `abnormalTime = DurationSec` and the icons are refreshed
(one retry after 100 ms if the buff is not on the target yet). No core edits, no hooks.

- Re-cast: refreshes to a full hour again.
- Level up past MaxLevel: the running buff keeps its hour.
- Pets / servitors: not touched (the manager buffs the player only).

Config: `MaxLevel` (39, 0 = off), `DurationSec` (3600), `Skills` (`4342-4360`), `IncludeBots` (true). All reloadable.