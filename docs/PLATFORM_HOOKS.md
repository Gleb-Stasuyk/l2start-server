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

Paths are relative to `core/java/org/l2jmobius/`.
Later hooks (combat, chat, items, AI, …) are added together with the modules that need them (phases 2–3).
