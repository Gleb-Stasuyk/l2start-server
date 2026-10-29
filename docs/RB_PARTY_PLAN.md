# RB party bots — iteration 1

A player writes in trade or shout "рб"/"rb" plus the roles: танк, биш, пп, дд (сх, сс, некр).

1. **Search** (`bots-core` `RbParse`, `RbRecruit`). Only the named roles answer, level −2..+3 of the player.
   A bot already in the world is preferred (within shout range, free, no party); else an offline persona of that
   role is brought in out of sight (or a new one is created). The bot whispers, runs to the player and accepts his
   invitation (PartyBotsMax does not apply to it). Not invited in 3 min: it goes back to its life, or, brought in
   for the call, walks off and logs out. The below-40 ban applies only to bots starting a search themselves.
2. **Follow** (`BasicBrain` rb section, `RaidPlay`). The bot walks with the leader. If he takes a gatekeeper, the
   bots near him take the same gatekeeper to the same place 2–10 s later (paying adena); a scroll / return skill
   — they read a scroll of escape. On the way they only defend. A stuck or far-behind bot teleports to the party
   where nobody sees it.
3. **Prophet.** Wind Walk to everyone in town; any party message with "баф" — the full buff set.
4. **Fight.** Auto-engage when a raid boss (or the leader's target) is already on a party member — without waiting
   for "го". Else the leader hits a raid boss, or writes "го" (fallback: leader target / party attacker, even if
   the NPC is not tagged BOSS_RAID). The tank taunts first, holds and kites the boss (constant kite, PR #16);
   DD kite at most one short burst per ~10 s. Physical DD: auto-attack first, at most one skill/stance per 2 s so
   they keep swinging in place (no multi-second idle gaps). The bishop heals the tank first; necromancer — servitor.
   Regular raid bosses only (grand/epic bosses: the bots refuse). `ctx.note` of the raid party also goes to the
   game log (`Module[bots-brain-basic]`).
5. **End.** Party disbanded — scroll of escape home (bots brought in log out). Otherwise they follow the leader to
   the next boss; 10 min of nothing — goodbye and leave.

Config: `bots-core` `RbParty` (on by default, reloadable).
