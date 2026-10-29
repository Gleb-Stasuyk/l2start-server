# RB party bots — iteration 1

A player writes in trade or shout "рб"/"rb" plus the roles: танк, биш, пп, дд (сх, сс, некр).

1. **Search** (`bots-core` `RbParse`, `RbRecruit`). Only the named roles answer, level −2..+3 of the player.
   A bot already in the world is preferred (within shout range, free, no party); else an offline persona of that
   role is brought in out of sight (or a new one is created). The bot whispers, runs to the player and accepts his
   invitation (PartyBotsMax does not apply to it). Not invited in 3 min: it goes back to its life, or, brought in
   for the call, walks off and logs out. The below-40 ban applies only to bots starting a search themselves.
   **Second party.** The shout **«ищу фп на рб»** (case variants: «ищу ФП на рб») is a second full party of 9 bots:
   bishop + **SE** + 7 mage DD. It is not «еще пати» / «2 пати». SE is **only** in party 2 (never in the player's
   party 1). SE here is **Elven Elder 30 / Eva's Saint 105** (Empower 1059, Recharge 1013) — not Spellhowler and
   not the town-buffer Shillien Elder. Party 2 is a bot-only party (`RbRecruit` forms it around the bishop); the
   player does not invite them. No go / buff / stop commands of their own.
2. **Follow** (`BasicBrain` rb section, `RaidPlay`). The bot walks with the leader. If he takes a gatekeeper, the
   bots near him take the same gatekeeper to the same place 2–10 s later (paying adena); a scroll / return skill
   — they read a scroll of escape. On the way they only defend. A stuck or far-behind bot teleports to the party
   where nobody sees it. Party 2 follows the caller the same way, even though he is not in their party window.
3. **Prophet.** Wind Walk to everyone in town; any party message with "баф" — the short raid set for DD/mages,
   and for the tank the full hunt prophet set except Berserker (Might yes; Haste/Focus are melee-only, not hunt-tank).
   The prophet buffs **both** parties (nearby party-2 bots as well). Party 2 does **not** get Berserker.
   **SE (party 2):** Empower on everyone missing it, Recharge when a member's MP is low — no "баф" command.
4. **Fight.** Auto-engage when a raid boss (or the leader's target) is already on a party member — without waiting
   for "го". Else the leader hits a raid boss, or writes "го" (fallback: leader target / party attacker, even if
   the NPC is not tagged BOSS_RAID). On engage, lock `rbBossId` onto a nearby living `BOSS_RAID` even if the
   leader targeted a minion ("го" on Nellis' Follower). A minion is the fight only when the real boss is dead or
   far. Party 2 starts the moment party 1 is on the raid (caller in combat / boss pulled) and **assists** the same
   target (leader / party 1 / BOSS_RAID) — it does not pick its own. The tank taunts first, holds and kites the
   boss (constant kite while geodata allows, PR #16); two failed kite `moveTo` in a row → stand still and keep
   fighting (supports heal, tank taunts in place).
   RB tanks auto-use soulshots plus blessed spiritshots (Drain Health is magic; Aggression / Aura of Hate do not
   consume shots). Olympiad shots stay regular A/S, never blessed. DD kite at most one short burst per ~10 s.
   Physical DD: auto-attack first, at most one skill/stance per 2 s so they keep swinging in place (no multi-second
   idle gaps); they never drop `fightId` on the locked RB while it lives in range. The bishop heals during the
   fight (Major / Greater / Heal; holder then tank/supports); kite and keep-back must not cancel a heal cast.
   Mage DD nukes **only** Hydro Blast 1235 (Spellsinger), Hurricane 1239 (Spellhowler), Death Spike 1148 (Necro);
   playstyle ROTATION must not fire Wind Strike, Ice Dagger, Vampiric Claw, etc. Necromancer — servitor. Regular
   raid bosses only (grand/epic bosses: the bots refuse). Raid notes are `rb party [Name/Role]: …` in the game
   log (`Module[bots-brain-basic]`).
5. **End.** Party disbanded — scroll of escape home (bots brought in log out). Otherwise they follow the leader to
   the next boss; 10 min of nothing — goodbye and leave.

Config: `bots-core` `RbParty` (on by default, reloadable).
