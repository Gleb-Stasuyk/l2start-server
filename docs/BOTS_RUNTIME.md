# Рантайм ботов и API мозгов (фаза 3, шаг 3c)

Код: `modules/bots-core` — пакеты `org.l2start.bots.api` (API, без классов Mobius), `org.l2start.bots.runtime`
(тикер, почта, LOD — без Mobius, покрыто тестами), `org.l2start.bots.core` (`BrainHost`, `MobiusWorldView`,
`MobiusActuators`, `SkillLearner` — связь со стоком), `org.l2start.bots.testkit` (оффлайн-мир для тестов мозгов).

## Потоки и тик
- `Shards` шардов (0 = `max(2, ядра/4)`), бот закреплён за шардом `botId % N`. Каждый шард — одна периодическая
  задача платформы (`ctx.scheduler().fixedRate`, стоковый ThreadPool, защита ModuleGuard). Задач на бота нет.
- Волна раз в `WaveMs` (100 мс): шард тикает ботов, у которых наступило `nextTickAt`, пока не кончится бюджет
  `WaveBudgetMs` (25 мс); остальные — в следующей волне (`overrun`). Порядок обхода сдвигается каждую волну.
- Тик бота: LOD (раз в 2 с), разбор почты, снимок мира (`WorldView`), `brain.tick(ctx)` → желаемая задержка.
  Рантайм ограничивает её снизу: ACTIVE в бою 300 мс, ACTIVE 1 с, PRESENT 3–5 с (джиттер).
- События стока (из чужих потоков) → `Mailbox` бота (лимит 64, старые выбрасываются, одинаковый `key()`
  схлопывается, урон от одного атакующего суммируется) и «будильник»: тик не позже чем через время реакции
  персоны (200–1200 мс).
- Исключение в мозге: бот 60 с в `SAFE_IDLE` (стоит), предупреждение в лог (первые 3 и последнее);
  10 исключений за 10 мин → бот выходит из мира, причина в `//bots info`. Волна и шард не падают.
- `//bots pause` останавливает все шарды (и сбрасывает текущие интенции ботов), `resume` — продолжает.

## LOD (3c — упрощённый, полный population manager — 3e)
| LOD | Когда | Тик |
|---|---|---|
| ACTIVE | живой (не бот) игрок ближе `PromoteRadius` (3000) | 0,3–1 с |
| PRESENT | иначе; из ACTIVE — только если дальше `DemoteRadius` (4500) дольше `DemoteDelay` (45 с) | 3–5 с |

## API (`org.l2start.bots.api`)
- `WorldView`: `self()` (уровень, HP/MP, позиция, сидит/движется/в бою/атакует/кастует, цель, интенция AI,
  мирная зона, скиллы с типом NUKE/STRIKE/OTHER, MP, дальностью и откатом), `npcs()` / `players()` / `drops()`
  (в `ViewRadius`, до 64, ближние первыми, строятся при первом обращении), `npc(id)`, `events()`, `geo()`
  (`canMoveTo`, `canSee`, `height`, `randomReachablePoint`).
- `NpcView.engagedByOther` — моба бьёт/ненавидит кто-то другой (kill-steal), `PlayerView.bot` — только для мозга.
- `Actuators` — только то, что мог бы попросить клиент: стоковые интенции AI (`MOVE_TO`, `ATTACK`, `CAST`,
  `PICK_UP`, `FOLLOW`), `sitDown`/`standUp`, обработчик предмета, `CreatureSay` (6 фраз/мин). Валидатор до стока:
  жив, не телепортируется, не сидит, дистанция (ход ≤ 4000, цель ≤ 2500), мирная зона, атакуемость,
  геодата (`RequireGeodata`), откат и MP. Результат — `ActionResult` (`OK`/`INVALID`/`COOLDOWN`/`NOT_REACHABLE`),
  каждое действие пишется в журнал бота (последние 10 в `//bots info`) и метрики.
- `learnSkills()` — как `RequestAcquireSkill` (CLASS) без тренера: доступные по `SkillTreeData` скиллы за SP,
  без книг/предметов (упрощение до фазы 4). Само изучение пишет в БД, поэтому идёт сразу после тика в стоковом
  пуле, а не в шарде.
- Мозг `basic` охотится только в 30 000 от одной из 5 стартовых деревень; бот, поставленный GM у другой стартовой
  деревни (`//bots spawn N here`), охотится у неё; бот далеко от всех деревень просто гуляет на месте.
- `returnToTown()` — мёртвый: стоковое «В деревню»; живой: как свиток возврата (`TeleportWhereType.TOWN`).
- `Brain` (`tick`, `state`, `inCombat`, `stop`), `BrainFactory` (`id`, `create(BotInfo)`), `BrainContext`
  (`info`, `world`, `act`, `rnd`, `now`, `blackboard`, `reactionDelay`, `note`).
- Сервис `BrainRegistry`: модуль с зависимостью `bots-core` делает
  `ctx.services().require(BrainRegistry.class).register(factory)` и `unregister(id)` в `ctx.cleanup`.
  Боты получают мозг с id из ключа `Brain` (по умолчанию `basic`).

## Оффлайн-тесты мозгов
`FakeWorld` (мобы с HP/уроном/респауном, агрессивные, «чужие» мобы, дроп, стены как геодата, реген сидя,
смерть, опыт и уровни), `RecordingActuators`, `ManualClock`, `BrainHarness` (тикает мозг как рантайм, сам
возвращает мёртвого в город как `bots-core`). 3 часа игры — около секунды.

## Метрики
Счётчики `ctx.metrics()` модуля `bots-core`: `actions`, `actionsRejected`, `moves`, `attacks`, `kills`, `deaths`,
`levelUps`, `brainErrors`, `lodChanges`, `logins`, … Строка в лог раз в `StatsLogInterval` (60 с):

    BOTS world=25 ticking=25 active=6 present=19 brain=basic noBrain=0 ticks=… p50=0.08ms p99=0.9ms overrun=0 errors=0 safeIdle=0 paused=false moves=… attacks=… kills=… deaths=… levelUps=… rejected=… ticks/s=… shardCpu=…%
