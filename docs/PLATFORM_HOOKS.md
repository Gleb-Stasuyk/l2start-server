# Platform hooks in core/

`core/` is stock L2J Mobius CT_0 (see `UPSTREAM.md`). The only allowed changes are **platform hooks**:
a few ADDED lines that call `org.l2start.platform.hooks.CoreHooks` and are marked with `[HOOK:<id>]`.
With no modules (or `DisableAll = true` in `platform.ini`) every hook is a no-op and the server behaves
exactly like stock Mobius.

## Rules (enforced by `tools/check-hooks.sh` in CI, job "verify-core")

1. Only existing core files are modified: no new, deleted or renamed files in `core/`.
2. No stock line is removed or changed; the diff against the `vendor:` commit only adds lines.
3. Every added line contains `[HOOK:<id>]`, or is inside a block
   `// >>> [HOOK:<id>]` ... `// <<< [HOOK:<id>]` (for hooks longer than one line).
4. Every id used in `core/` has a row in the table below, and every row is used (no stale rows).
5. Hooks use fully qualified class names (no new imports) and never throw: `CoreHooks` catches everything.
6. Root `build.xml` compiles for the same Java release as `core/build.xml`.

Local check before committing: `bash tools/check-hooks.sh --worktree`.
Self-test of the checker: `bash tools/test-check-hooks.sh`. Where each hook sits now: `bash tools/hook-report.sh`.
After an upstream update (`tools/import-upstream.ps1`) re-apply the hooks by their anchors below.

## Hooks

| Id | Core file | Anchor (inserted next to) | What it does | Since |
|---|---|---|---|---|
| `P1-BOOT` | `gameserver/GameServer.java` | after `ScriptEngine.getInstance();` | `CoreHooks.bootstrap()`: settings, discovery, validation, dependency resolution, SQL migrations, class loading (before any data that modules extend is loaded) | phase 1 |
| `P1-DATA-NPC` | `gameserver/data/xml/NpcData.java` | before `loadNpcsSkillLearn();` | parses `data/npcs` folders of active modules; ids must be in the module's reserve and must not exist in stock | phase 1 |
| `P1-DATA-MSELL` | `gameserver/data/xml/MultisellData.java` | before `verify();` | parses module `data/multisell` folders (same id rules) | phase 1 |
| `P1-DATA-BUY` | `gameserver/data/xml/BuyListData.java` | before the `Loaded ... buyLists` log line | parses module `data/buylists` folders (same id rules) | phase 1 |
| `P1-ENABLE` | `gameserver/GameServer.java` | before `SpawnData.getInstance();` | `CoreHooks.enableModules()`: `onEnable` of every loaded module (listeners, commands, tasks), `//modules` admin commands | phase 1 |
| `P1-DATA-SPAWN` | `gameserver/data/xml/SpawnData.java` | after `checkMissingFiles(...data/spawns)` | parses module `data/spawns` folders | phase 1 |
| `P1-HTML` | `gameserver/cache/HtmCache.java` | before `// In case localisation does not exist try the default path.` | on a cache miss for `data/html/modules/<id>/...` serves the module's `data/html` (with `lang/<lang>/`) | phase 1 |
| `P1-READY` | `gameserver/GameServer.java` | before `new ConnectionManager<>(...)` | `CoreHooks.serverReady()`: `onServerReady`, summary log line, FailurePolicy checkpoint | phase 1 |
| `P1-SHUTDOWN` | `gameserver/Shutdown.java` | after `_countdownFinished = true;` in `startShutdownActions` | `CoreHooks.shutdown()`: `onShutdown` in reverse order, cancels tasks, unregisters everything (bounded by `ShutdownTimeout`) | phase 1 |
| `P2-DATA-INITEQUIP` | `gameserver/data/xml/InitialEquipmentData.java` | in `load()`, after `parseDatapackFile("data/stats/players/initialEquipment.xml");` | parses `data/initialEquipment` folders of active modules (stock format); each `classId` in a module file replaces the stock starting items of that class (module `starter-kit`) | phase 2 |
| `P3-BOT-CHARINFO` | `gameserver/entity/actor/Player.java` | in `broadcastCharInfo()`, directly before the stock `if (isOnlineInt() == 0)` ("Client is disconnected.") | guard prefix `if (!CoreHooks.isBot(this))`: the stock early return for players without a client does not apply to bots, so players around a bot see its gear, sitting, title, etc. Inert without a bot module (`isBot` is false) | phase 3 |
| `P3-BOT-WHISPER` | `dist/game/data/scripts/handlers/chat/channels/ChatWhisper.java` | in `handleChat`, directly before the stock `if ((receiver.getClient() == null) \|\| receiver.getClient().isDetached())` | guard prefix `if (!CoreHooks.isBot(receiver))`: a whisper to a bot is delivered like to an online player (sender gets the normal `->Name` echo instead of "Player is in offline mode."); the bot module reads the text from the stock `ON_PLAYER_CHAT` event. Inert without a bot module | phase 3 |
| `P5-STORE-DEAL` | `gameserver/network/holders/TradeList.java` | in `privateStoreBuy` directly after `removeItem(item.getObjectId(), -1, item.getCount());`; in `privateStoreSell` directly after `ok = true;` (after `removeItem(-1, item.getItemId(), item.getCount());`) | `CoreHooks.storeDeal(seller, buyer, itemId, count, price, buyStore)`: tells modules (`ctx.onStoreDeal`, API 1.3) about each line of a successful private store deal; never changes it. Inert without listeners | phase 5 |
| `P5-STORE-ADENA` | `gameserver/network/holders/TradeList.java` | in `privateStoreBuy` directly before the stock `playerIU.addItem(adenaItem);` (after `final Item adenaItem = playerInventory.getAdenaInstance();`) | guard prefix `if (CoreHooks.storeBuyerAdenaLeft(player, adenaItem))`: fixes a stock bug — a buyer who pays with **all** his adena has no adena item left, the stock line threw `NullPointerException` after the adena was taken and before the goods moved (adena lost, item not given). With the platform active the line is skipped then (the buyer gets his item list) and the deal completes; with `DisableAll` the stock line runs as before | phase 5 |
| `P6-BOT-TRADE` | `gameserver/network/clientpackets/TradeRequest.java` | in `runImpl` directly after `partner.sendPacket(new SendTradeRequest(player.getObjectId()));` | `CoreHooks.botTradeRequest(requester, partner)`: a bot has no client to answer a trade request; a module (`ctx.onBotTradeRequest`, API 1.4) may answer for it (economy-market: whispered WTS/WTB deals). Only when the partner is a bot; inert without listeners | phase 5 (taken from phase 6) |
| `P6-BOT-PARTY` | `gameserver/network/clientpackets/RequestJoinParty.java` | in `runImpl`, directly before the stock `if ((target.getClient() == null) \|\| target.getClient().isDetached())` ("Player is in offline mode."); directly before the stock `if (target.isInParty())` (`CoreHooks.botPartyBusy`); in `addTargetToParty` directly after `party.setPendingInvitation(true);`; in `createNewParty` directly after `requestor.setPartyDistributionType(partyDistributionType);` | guard prefix `if (!CoreHooks.isBot(target))`: an invitation reaches a bot like an online player; `CoreHooks.botPartyBusy(requestor, target)` (API 1.8) when the bot is already in a party lets the module redirect (bot-only pair) instead of the stock "member of another party"; then `CoreHooks.botPartyRequest(requestor, target)` after the stock invitation is pending tells the bot module (`ctx.onBotPartyRequest`, API 1.5), which answers for the bot with the stock `RequestAnswerJoinParty` sequence. Phase 6g: module also respects stock `BlockList` (`/block`, `/blockall`) — blocked bot ignores the invite. Inert without a bot module | phase 6 |
| `P7-BOT-DUEL` | `gameserver/network/clientpackets/RequestDuelStart.java` | in `runImpl` 1v1 and party-duel branches, directly after the stock `ExDuelAskStart` / challenge messages | `CoreHooks.botDuelRequest(requester, target, partyDuel)`: a bot has no client to answer a duel ask; a module (`ctx.onBotDuelRequest`, API 1.9) may answer for it with the stock `RequestDuelAnswerStart` sequence. Only when the asked player is a bot; inert without listeners | phase 7 |
| `P7-DROP-FLOAT` | `gameserver/entity/actor/templates/NpcTemplate.java` | first thing in `calculateDrops(DropType, Creature, Creature)` | block `// >>> [HOOK:P7-DROP-FLOAT]`: `CoreHooks.drops(spoil, victim, killer, stockRoll)`; a module (rates-floating) builds the drop/spoil out of stock rolls (`stockRoll` re-enters the stock method, which then skips the hook); null = stock. Inert without listeners |
| `P7-QUEST-REWARD` | `gameserver/mechanics/script/Quest.java` | in `rewardItems` directly before `final Item itemInstance = player.getInventory().addItem(ItemProcessType.QUEST, itemId, count, ...)` | `count = CoreHooks.questReward(player, itemId, countValue, count)`: a module may change the count after the stock rates (rates-floating: valuable rewards x1). Without listeners the stock count |
| `P8-TELEPORT-NAME` | `gameserver/entity/teleporter/TeleportHolder.java` | in `showTeleportList` directly after `String finalName = loc.getName();` | `finalName = CoreHooks.teleportName(player, loc, finalName)`: a module may change the shown name (world-info: monster levels near the destination). Without listeners the stock name |

Paths are relative to `core/java/org/l2jmobius/`.
Later hooks (combat, chat, items, AI, …) are added together with the modules that need them (phases 2–3).
