# Рантайм ботов и API мозгов (фаза 3, шаг 3c; дополнено в фазе 4)

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

## LOD (3c; в 3e «живыми игроками» для LOD считаются и `Anchors` режиссёра)
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
  (`info`, `world`, `act`, `rnd`, `now`, `blackboard`, `reactionDelay`, `note`, `assignment` — приказ режиссёра
  присутствия 3e: `Assignment` TOWN/HUNT/LEAVE_RUN/LEAVE_GK с местом, точками и выходом; `null` без режиссёра).
- `logout()` в режиме режиссёра идёт через него: добыча исчезает, уровень сохраняется в `mod_bots_core_progress`.
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

    BOTS world=25 ticking=25 active=6 present=19 brain=basic noBrain=0 ticks=… p50=0.08ms p99=0.9ms overrun=0 errors=0 safeIdle=0 paused=false moves=… attacks=… kills=… deaths=… levelUps=… rejected=… ticks/s=… shardCpu=…% walking=0

## Фаза 4: роли, пати и LOD

### LOD и нагрузка
LOD не менялся: ACTIVE (живой игрок ближе `PromoteRadius`) / PRESENT. Боевые роли (плейстайлы, кайт, бафы,
пати) работают только у ACTIVE-ботов рядом с игроком, поэтому стоимость тика растёт только там, где на бота
смотрят. При перегрузе (`Overload`, 3f) активные боты дальше 2500 от живых думают вдвое реже, затем режиссёр
хочет на 30 % меньше ботов. Метрики `p50/p99` тика — в строке `BOTS`.

### Роли (как мозг `basic` выбирает поведение)
| Роль | Кто | Что делает |
|---|---|---|
| Воин / танк | бойцы ближнего боя | удар + плейстайл класса (стойки, заряды, контроль); в пати сдёргивает мобов с саппорта Aggression / Aura of Hate |
| Даггер | Treasure Hunter, Plainswalker, Abyss Walker и 3-и | заход со спины + Backstab, Dash при бегстве, Ultimate Evasion |
| Спойлер | Scavenger / Bounty Hunter | Spoil, после убийства Sweeper по трупу |
| Лучник | лук в руках | кайт: отходит, когда моб подошёл, и снова стреляет |
| Маг | мистики с нюком | нюки по ротации, кайт, при MP < 20 % — оружием |
| Саммонер | Warlock, Elemental / Phantom Summoner | вызывает слугу (кристаллы), шлёт на цель, лечит его, бережёт MP |
| Хилер / саппорт | Cleric-линия, Oracle-линии, Shaman-линия, Swordsinger, Bladedancer | лечит себя (а в пати — напарника), ресает напарника, бафает пати |

Общие для всех: банки ниже личного порога, бегство при низком HP (в пати — только если нет живого хилера),
свои бафы вне боя, AFK, конец охоты по приказам режиссёра (SoE, выход на месте, ГК, дела в городе).

### API, добавленное в фазе 4
- `SelfView`: CP, дальность атаки, лук, заряды, свой слуга (id, HP %, цель); `SkillView.kind` BUFF/TOGGLE/SUMMON/CORPSE,
  `buffLeft`, расход кристаллов.
- `NpcView`: `heading` (куда смотрит), `undead`, `spoiled`, радиус агра, дальность атаки; `engagedByOther` не
  считает «чужими» членов своей пати.
- `WorldView.party()` → `PartyMember` (класс, уровень, позиция, расстояние, HP/MP %, мёртв ли, цель, лидер ли);
  `partyLeader()`; `itemCount(id)`.
- `Actuators`: `petAttack`, `petFollow`, `unsummon`; `cast` по мёртвому игроку для Resurrection.
- `Assignment`: LEAVE_SOE, LEAVE_SPOT, ERRANDS (4d) вдобавок к TOWN/HUNT/LEAVE_RUN/LEAVE_GK/TRANSIT/PASS.

### Тесты
`FakeWorld` умеет: SoE (каст 20 с), выходы, напарников по пати (`setParty`, хил/рес/баф по ним, `partyCasts`),
Aggression (моб переключается на бота). Набор: `LifeBrainTest` (4d), `PartyBrainTest` (4g и защита пати по КТ8),
`DirectorTest` (концы охоты, возвраты, пары, ожидание после смерти), `CatalogDataTest` (экипировка по грейду),
`PaperLevelTest` (рост по часам игры).

## Фаза 5: боты рынка (economy-market 0.4.0, bots-core 0.5.2)

- **Кто в мире.** Торговцы лавок (персоны `TraderPool`, статус TRADER) сидят в Гиране, пока рядом живой игрок:
  55 лавок в многоугольнике рынка, 5 крафтеров и 2 крафтера ресурсов (`giran-res1/2`) вокруг Adventure Guildsman, 4UDO.
  Пешие боты (персоны покупателей, до 6) приходят и уходят: покупатель в лавке живого игрока (5e) и курьер сделки из
  торгового чата (5f). Мозга у них нет: ими управляет задача рынка (тик 3 с), как и торговцами.
- **Предметы — только пока бот в мире.** Товар лавки, адена скупки, товар/адена курьера создаются при входе (приходе)
  и уничтожаются при выходе (`Traders.emptyBag`); учёт — в таблицах `mod_economy_market_*` (кошельки, бюджет 5d).
- **Окно трейда с ботом** (хук `P6-BOT-TRADE`, API 1.4, перенесён из фазы 6): у бота нет клиента, поэтому на запрос
  трейда отвечает модуль (`ctx.onBotTradeRequest`). Сейчас это делает только economy-market: курьер принимает трейд
  своего игрока, остальные боты рынка отказывают; бот подтверждает последним и только точную сделку
  (`Meetings.exchange`, `model/TradeMeet`). Запросы к остальным ботам (охотники) остаются без ответа, как раньше.
- **Тесты:** `TradeMeetTest`, `MarketAreasTest`, `StoreVisitTest`, `BudgetTest` и др.; smoke сценарий 8 (SELFTEST:
  сделки лавок, обмен в окне трейда бот↔бот, места встречи в Гиране).

