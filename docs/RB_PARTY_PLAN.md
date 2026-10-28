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
4. **Fight.** Bots hold until the leader hits the boss or writes "го". The tank taunts first, holds the boss and
   turns it away from the party; the others assist the leader's target. The bishop heals the tank first;
   necromancer — servitor. Regular raid bosses only (grand/epic bosses: the bots refuse).
5. **End.** Party disbanded — scroll of escape home (bots brought in log out). Otherwise they follow the leader to
   the next boss; 10 min of nothing — goodbye and leave.

Config: `bots-core` `RbParty` (on by default, reloadable).
