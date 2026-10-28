# bots-olympiad

Олимпиада ботов, фаза 9, **MVP A–C** (Mobius Interlude). Модуль держит отдельный пул дворян у Grand Olympiad Manager
(NPC **31688**), записывает их в стоковые очереди через `Olympiad.registerNoble` и не трогает окна/очки `Olympiad.ini`.

Решения Глеба 2026-10-28: включено везде одинаково (живой игрок в очереди **и** фон bot↔bot); боты **могут** стать
Hero / monthly winners (фильтра нет); `setNoble(true)` автоматически; RosterSize=40; 22 боевых 3-х профессии,
саппорты/саммонеры исключены.

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
  `setNoble(true)`, S-грейд из `gear.xml`.
- В окне соревнований собираются у 31688 (предпочитается спавн в Гиране 82053,147526); после окна / при
  `Enabled=false` idle-logout, **матч не абортится** (`isInOlympiadMode` / `olympiadGameId >= 0`).
- Не больше **1 create + 1 login** за тик ростера (2 с), чтобы старт не замораживал сервер.
- Живой игрок в classed-очереди класса C: цель этого класса = база + `RivalsPerClass` (может чуть превысить 40).
- `//bots oly` и `//oly`: размер ростера, разбивка по классам, noble, окно, живые в очередях.

## C — Запись

Каждые ~30 с:

- Есть живой в non-classed или classed → свободные дворяне **всегда** добирают до чётного `poolCap`
  (classed min из стока 5, non-classed 9; 1 живой classed → cap 6, 1 живой non-classed → cap 10).
- Нет живых → фон: `SignUpChancePercent` (10 %) в **non-classed** (bot↔bot).
- Classed без живого: боты снимаются с classed, чтобы не сидеть весь вечер.
- `RestSeconds` (180) после `ON_OLYMPIAD_MATCH_RESULT` до новой записи.
- Стоковый пол очков: classed ≥ 3, non-classed ≥ 5 (если сток отвергнет — не записываем).

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

## Вне скоупа этого PR (D/E/F)

| | Что осталось |
|---|---|
| **D** | Боевой AI на стадионе: сейчас базовый мозг (PvP fight-back ~50 %, в Гиране «far» stroll). Нет отдельной olympiad-ротации, stance, не пить банки в олимпиаде, не убегать со стадиона. |
| **E** | Удержание пати (`RejoinParty`): кнопка есть, логики нет. |
| **F** | Hero/месяц: боты уже в стоковом зачёте (фильтра нет); нет отдельной полировки титулов, объявлений, доков сверх этого файла. |
| | Окна и очки `Olympiad.ini` не менялись. Сайт не трогали. |

## Ключевые файлы

| Файл | Зачем |
|---|---|
| `core/.../olympiad/Olympiad.java` | хук `P9-OLY-WAIT` — снимки очередей |
| `modules/bots-core/.../OlympiadPool.java` | сервис пула дворян |
| `modules/bots-core/.../OlympiadService.java` | create/enter/leave, `setNoble`, статус `OLYMPIAD` |
| `modules/bots-olympiad/.../OlympiadRules.java` | чистые правила + тесты |
| `modules/bots-olympiad/.../OlympiadBots.java` | ростер, gather, signup |
| `modules/bots-olympiad/.../BotsOlympiadModule.java` | модуль, тики 2 с / 30 с |

Выключить: `L2_MODULE_BOTS_OLYMPIAD=off` или `Enabled=false`.
