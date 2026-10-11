# Бот без клиента: аудит ядра (фаза 3a)

Бот `bots-core` — обычный `Player` из стоковой таблицы `characters`, загруженный `Player.load` и введённый в мир
так же, как офлайн-торговец (`OfflineTraderTable`), но без `GameClient`. Ниже — что в ядре зависит от клиента
и как это решено. Принцип: **хук только там, где без него бот выдаёт себя игроку**; остальное — плюс или
откладывается до фазы, где понадобится.

| Место в ядре | Без клиента | Решение |
|---|---|---|
| `Player.sendPacket` | пакеты молча выбрасываются (`_client == null`) | так и надо: боту пакеты не нужны |
| `Player.broadcastCharInfo` | `isOnlineInt()==0` → ранний `return`: другие не видят сидения, смены оружия, бафов | хук **P3-BOT-CHARINFO** (у бота рассылка идёт как у игрока) |
| `ChatWhisper` | отправителю «Player is in offline mode.» | хук **P3-BOT-WHISPER**; текст приходит боту стоковым `ON_PLAYER_CHAT` |
| `Creature.teleToLocation` | `onTeleported()` вызывается пакетом клиента `Appearing`; без клиента бот навсегда «в телепорте» (невидим, неуязвим) | без хука: `bots-core` каждые 500 мс вызывает `onTeleported()` у ботов с `isTeleporting()` |
| `isOnlineInt()` → `characters.online` | бот пишется в БД с `online = 0` | так и надо: сайт и счётчики его не видят (одобрено) |
| `LoginServerThread` (список аккаунтов онлайн для логин-сервера) | `isInOfflineMode()` → бот не попадает в список | так и надо: логин-сервер ботов не считает |
| Логин в аккаунт бота | — | `accounts.accessLevel = -1` + неизвестный пароль: стоковый логин отказывает (сначала пароль, потом бан); проверяется при каждом старте модуля |
| `characters.accesslevel` у бота | — | модуль при старте сбрасывает в 0, если кто-то поднял |
| `RequestJoinParty` | «Player is in offline mode.» | фаза 4 (партии ботов): отдельный хук |
| `ClanMember.isOnline` | бот в клане показан офлайн | фаза кланов: отдельный хук |
| `AutoPlay/AutoUse/AutoPotion` таск-менеджеры | пропускают офлайн-игроков | не нужно: поведение ботов своё |
| `FameTask`, `PcCafePointsManager`, `ItemManaTaskManager` | пропускают офлайн-игроков | так и надо |
| `AdminOnline`, `//character_list` | бот показан как «offline» (серым) | для GM так даже удобнее; `//bots list` — полный список |
| `PrecautionaryRestartManager` | бот не считается игроком | так и надо |
| `CharacterCreate.initNewChar` | требует клиент | `BotFactory` повторяет его (стартовые вещи через `InitialEquipment` → работает `starter-kit`, скиллы, ярлыки, `ON_PLAYER_CREATE`); туториал пропускается |
| Смерть | «В деревню» — пакет клиента `RequestRestartPoint` | `BotWorld.returnToTown`: та же стоковая последовательность (`MapRegionData` → `teleToLocation` с воскрешением) через 5–40 с |
| Выход | — | `Disconnection.of(p).storeAndDelete()` (стоковое сохранение и удаление из мира), при `//bots despawn`, выключении модуля и сервера |

Без модуля `bots-core` (или при его ошибке) оба хука — одна проверка `CoreHooks.isBot(...)`, которая возвращает
`false`: поведение ядра стоковое.
