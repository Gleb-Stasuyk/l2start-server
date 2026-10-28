# bots-olympiad

Олимпиада ботов, фаза 9, **MVP A–E** (Mobius Interlude). Модуль держит отдельный пул дворян у Grand Olympiad Manager
(NPC **31688**), записывает их в стоковые очереди через `Olympiad.registerNoble` и не трогает окна/очки `Olympiad.ini`.
На стадионе дворяне дерутся мозгом `bots-brain-basic` (олимпиадная стойка, не охота и не прогулка Гирана).

Решения Глеба 2026-10-28: включено везде одинаково (живой игрок в очереди **и** фон bot↔bot); боты **могут** стать
Hero / monthly winners (фильтра нет); `setNoble(true)` автоматически; RosterSize=40; 22 боевых 3-х профессии,
саппорты/саммонеры исключены. **E (полировка):** SS+SPS всем без blessed и без банок; смесь A/S; добор classed/non-classed
даже для редкого класса; лучник бежит в дальность; саммоны/бафы до Fight!.

Зависит от `bots-core` `^0.7` (сервис `OlympiadPool`, статус `OLYMPIAD` — режиссёр охотников их не берёт). Мозг — уже
прикреплённый `bots-brain-basic`, если модуль мозга включён. Без bots-core модуль грузится и ничего не делает
(сток олимпиады). `Enabled=false` — тоже сток: лишние дворяне выходят из мира, идущие матчи не прерываются.

## A — Правила, мост, скелет

- Чистые правила `OlympiadRules` (как LW PhantomOlympiadRules, без копипасты монолита): allowlist классов, квоты
  на 40, `poolCap` (чётное, не меньше стокового минимума участников), `shouldSignUp`, отдых после боя.
- Хук **`P9-OLY-WAIT`** в `Olympiad.java`: публичные снимки package-private списков ожидания под монитором
  `OlympiadManager` (`hookNonClassedWaiting` / `hookClassedWaiting`). Запись в очередь — только стоковый
  `registerNoble(Player, classed)`.
- Настройки: `Enabled`, `RosterSize=40`, `MinLevel=76`, `MaxLevel=80`, `RivalsPerClass=6`,
  `SignUpChancePercent=10`, `RestSeconds=180`, `RejoinParty=true` (зарезервировано под E), `GatherNpcId=31688`.

Allowlist 3-х: **88,89,90,91,92,93,94,95,99,100,101,102,103,106,107,108,109,110,113,114,117,118**.  
Исключены: 96,97,98,104,105,111,112,115,116.

Базовая квота на 40: 22 класса, первые 18 в списке получают 2 слота, последние 4 — по 1 (сумма 40).

## B — Ростер у NPC 31688

- До 40 дворян (отдельные аккаунты/статус `OLYMPIAD`, не охотники режиссёра): уровень 76–80, 3-я боевая,
  `setNoble(true)`, смесь **A/S** брони, оружия и бижутерии (не полный S).
- В окне соревнований собираются у 31688 (предпочитается спавн в Гиране 82053,147526); после окна / при
  `Enabled=false` idle-logout, **матч не абортится** (`isInOlympiadMode` / `olympiadGameId >= 0`).
- Не больше **1 create + 1 login** за тик ростера (2 с), чтобы старт не замораживал сервер.
- Живой игрок в classed-очереди класса C: цель этого класса = max(база + `RivalsPerClass`, сколько ботов нужно
  до `poolCap`); **дефицит класса важнее глобального RosterSize** — ростер может вырасти выше 40.
- `//bots oly` и `//oly`: размер ростера, разбивка по классам, noble, окно, живые в очередях.

## C — Запись

Каждые ~30 с, и сразу на тике ростера (2 с), если в очереди есть живой:

- Есть живой в non-classed или classed → свободные дворяне **всегда** добирают до чётного `poolCap`
  (classed min из стока 5, non-classed 9; 1 живой classed → cap 6, 1 живой non-classed → cap 10).
- Редкий classed (например 2 Storm Screamer + 1 живой): ростер **создаёт и логинит ещё ботов этого класса**,
  даже если всего уже 40; одноклассники из non-classed переходят в classed (`SWITCH_TO_CLASSED`).
- Нет живых → фон: `SignUpChancePercent` (10 %) в **non-classed** (bot↔bot). Шанс **только** для фона.
- Classed без живого: боты снимаются с classed, чтобы не сидеть весь вечер.
- `RestSeconds` (180) после `ON_OLYMPIAD_MATCH_RESULT` до новой записи.
- Стоковый пол очков: classed ≥ 3, non-classed ≥ 5 (если сток отвергнет — не записываем).

## D — Бой на стадионе

Отдельной «второй мозг» нет: `bots-brain-basic` на тике смотрит `SelfView.inOlympiadMatch()` (сток
`isInOlympiadMode` или `olympiadGameId ≥ 0`, поля отдаёт `bots-core` 0.7.3) и уходит в олимпиадную стойку.

- **До старта** (`isOlympiadStart=false`): стоит, вешает свои бафы, Hell Knight вызывает Dark Panther; не гуляет, не садится.
- **Бой по роли:** танк/мили — в ближний через `attack()`; лучник/маг/некро **бежит в рабочую дальность** со спавна
  (не стреляет с точки телепорта); лучник кайтит, если мили подошёл; даггер со спины; плейстайл с **PVP**.
- **Соски:** каждому дворянину **обычные** Soulshots **и** Spiritshots A+S (не blessed: `Olympiad.ini` их глушит).
  Лучнику — стрелы грейда оружия. Банок HP/MP и SoE в комплекте нет.
- **Нельзя:** банки и свиток (сток `ItemSkills` и так отказывает в олимпиаде — мозг даже не пробует);
  бегство со стадиона / в город; сидеть посреди матча; «В деревню» с трупа (`bots-core` не планирует возврат,
  пока сток держит матч).
- **После матча:** снова «far»-прогулка у менеджера. Охота / Гиран / PvP мира не меняются, пока бот не в матче.

Чистые правила: `OlympiadCombat` + тесты `OlympiadCombatTest` / `OlympiadBrainTest` в `bots-brain-basic`.
Новых хуков ядра нет.

## Как проверить в игре

**CT-B (ростер)**  
1. `Olympiad.ini` окно идёт (или подвинуть `OlympiadStartTime`), `bots-olympiad` и `bots-core` включены, `Enabled=true`.  
2. `//oly` / `//bots oly`: ростер растёт к 40 (по одному за 2 с), классы из allowlist, noble.  
3. Телепорт к 31688 в Гиране: дворяне стоят рядом.  
4. После окна (или `Enabled=false` через `//module reload bots-olympiad` после правки ini): idle выходят; бот в
   матче остаётся до конца.  
5. `Enabled=false` с самого старта: стоковая олимпиада, ростера нет.

**CT-C (запись)**  
1. Живой дворянин встаёт в classed: `//oly` показывает live classed, боты того же класса дописываются до cap,
   extra rivals до 6 сверх базы.  
2. Живой в non-classed: боты дописываются в non-classed до чётного cap.  
3. Без живых в окне: за несколько минут non-classed набирает ≥ 9 (шанс 10 % / 30 с) — идут bot↔bot.  
4. Classed без живого: боты с classed снимаются.  
5. После боя бот ~3 мин не записывается снова.

**CT-D (бой на стадионе)**  
1. Дождаться матча (живой vs бот в classed/non-classed, или фон bot↔bot). Наблюдать стадион (сток observe у 31688).  
2. После «The match has started. Fight!» оба бота **идут в цель и бьют скиллами своего класса** (воин — удар/ротация,
   маг — нюк, даггер — со спины если линия есть), не стоят AFK и не гуляют.  
3. Банки не пьют (в логе бота нет `potion …`; сток всё равно отказал бы). Свиток в город не читают.  
4. Не выбегают за арену и не садятся, пока матч идёт; мёртвый лежит до стокового конца, не жмёт «В деревню».  
5. После порта обратно к 31688 — снова стоят/гуляют у менеджера, **не бегут охотиться**. Охотники в мире без изменений.  
6. `//bots info <имя>` в матче: `LEVEL/olympiad …`, в details строка `Olympiad: fight …` / `wait`.

**CT-E (полировка)**  
1. Живой Storm Screamer (или другой редкий 3rd) в classed: за ~20–40 с `//oly` показывает ≥ 5 ботов этого класса
   в мире / в очереди, матч стартует (не висит на 2+1). Фон bot↔bot на это время стоит.  
2. Шифт+клик / взгляд на бота: смесь A и S (не весь Imperial/Draconic/Arcana), бижутерия Phoenix/Majestic + Tateossian.  
3. У каждого в инвентаре обычные SS **и** SPS (A и S), у лучника стрелы; **нет** Greater Healing Potion и blessed SPS.  
4. Лучник после Fight! бежит к сопернику в ~400, не стоит на спавне. Hell Knight до старта вызывает пантеру.  
5. Некро (Soultaker) бьёт Vampiric Claw / Death Spike с обычными SPS (трупный саммон Interlude на 1v1 без трупа не кастуется).

## E — Полировка (этот PR)

- Комплект: `Materializer.prepare(..., olympiad=true)` — смесь A/S, SS+SPS, стрелы, кристаллы/зверь-шоты для пантеры; без банок и SoE.
- Очередь: `pickCreateClass` по дефициту класса (не `total < want`); `SWITCH_TO_CLASSED`; signup на тике ростера, пока ждёт живой.
- Стойка: `OlympiadCombat.Role` + `shouldCloseIn` / `fightRange`; саммон и self-buff на wait.

## Вне скоупа (F)

| | Что осталось |
|---|---|
| **RejoinParty** | Кнопка есть, логики нет (пати после матча). |
| **F** | Hero/месяц: боты уже в стоковом зачёте; нет полировки титулов/объявлений. |
| **Некро-саммон** | Сток Interlude: Reanimated/Corrupted/Cursed Man = `CORPSE_MOB`. В 1v1 без трупа не вызвать. |
| | Окна и очки `Olympiad.ini` не менялись. Сайт не трогали. |

## Ключевые файлы

| Файл | Зачем |
|---|---|
| `core/.../olympiad/Olympiad.java` | хук `P9-OLY-WAIT` — снимки очередей |
| `modules/bots-core/.../OlympiadPool.java` | сервис пула дворян |
| `modules/bots-core/.../OlympiadService.java` | create/enter/leave, `setNoble`, статус `OLYMPIAD` |
| `modules/bots-core/.../SelfView.java` / `PlayerView.java` | `olympiadMode` / `olympiadGameId` / `olympiadStart` для мозга |
| `modules/bots-olympiad/.../OlympiadRules.java` | чистые правила + тесты |
| `modules/bots-olympiad/.../OlympiadBots.java` | ростер, gather, signup |
| `modules/bots-olympiad/.../BotsOlympiadModule.java` | модуль, тики 2 с / 30 с |
| `modules/bots-brain-basic/.../OlympiadCombat.java` | чистые правила стойки D |
| `modules/bots-brain-basic/.../BasicBrain.java` | гейт тика на матч, бой плейстайлом |

Выключить: `L2_MODULE_BOTS_OLYMPIAD=off` или `Enabled=false`.

## Smoke

`tools/smoke.sh` держит `L2_MOD_BOTS_OLYMPIAD__ENABLED=false` (как `StoresPerTown Giran:0` у рынка): модуль **RUNNING** в solo и online, но ростер не создаёт 40 дворян и не ломает счётчики охотников 20/10. Online без bots-core — idle (`bots-core is off: idle`), сток олимпиады, FailurePolicy=abort не срабатывает. CT-D — в игре.
