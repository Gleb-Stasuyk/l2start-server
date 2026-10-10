# Фаза 2: первые модули (утверждённый объём)

*10.10.2026 (MSK). База: `main` @ `3c40dbb1` (фаза 1). Ветка `phase2`, PR в `main` (не мёржить без Глеба).*
*Перед решением был сделан полный разбор кастомов сборки `E:\L2J_Mobius_CT_0_Interlude` (база = Mobius `55787efe66`, Release 15.08.2026)
и мелких улучшений Living Worlds; краткий итог — в приложении ниже.*

## Решение Глеба

| Что | Решение |
|---|---|
| `starter-kit` — стартовая экипировка 9 стартовых классов, как на E: | **делаем**, модуль, включён в solo и online |
| `lw-fixes` — обновление клиента после выпадения проклятого оружия (из Living Worlds), слушатель без хука | **делаем**, модуль, включён в solo и online |
| Рейты | **x1 в обоих профилях** (стоковые Mobius), никаких override рейтов |
| scheme-buffer, gm-shop, auto-learn, buff-slots, ssq-solo, guard-flag, admin-rates, rebirth | **не делаем** в фазе 2 |
| Стоковые опции (Class Master, чемпионы, автолут, вес, катакомбы) | **не включаем**: оба профиля остаются стоковыми |
| Боты (virtualplayer, pvpbots, FakePlayerStore) | не в фазе 2 |

Итог: **online = стоковые механики Mobius + 2 модуля; solo = то же самое.**

**Будущая работа:** соло-настройки (рейты, автолут, вес, бесплатные профы, чемпионы, катакомбы, баффер и т.п.) будут выставляться из
лаунчера. Механизм для этого уже есть: профильные override `docker/overrides/<profile>/game/config/<File>.ini` (`Key = Value`).
Для `config/Custom/*.ini` и XML-файлов (`ClassMaster.xml`) entrypoint придётся доработать, когда до этого дойдём.

## Что меняется

### Хук в core/ (один)

| Id | Файл | Место | Что |
|---|---|---|---|
| `P2-DATA-INITEQUIP` | `gameserver/data/xml/InitialEquipmentData.java` | `load()`, сразу после `parseDatapackFile("data/stats/players/initialEquipment.xml");` | `for (dir : CoreHooks.dataDirs("initialequipment", null)) parseDirectory(dir, true);` — стоковый парсер читает xml модулей; `put(classId, …)` заменяет список класса |

Одна добавленная строка, `check-hooks` зелёный, при `DisableAll` или без модуля — no-op.

### Платформа

- Новый тип данных модуля `data.initialEquipment` (`DataType.INITIAL_EQUIPMENT`, без резерва id).
- **API 1.0.0 → 1.1.0** (новая возможность манифеста; Java-API не менялся). Модуль с `initialEquipment` указывает `"platform": "^1.1"`:
  старая платформа такой модуль не загрузит (INVALID, «unknown data type»), новая грузит и старые модули с `^1.0`.

### Модуль `starter-kit` (kind `data`)

- `data/initialEquipment/initialEquipment.xml` — файл с E: (только путь к xsd поправлен под папку модуля).
- Воины (classId 0, 18, 31, 44): Long Sword, Bronze Breastplate/Gaiters, Leather Helmet, Bracer, Boots, Blue Diamond Necklace, 2× Blue Coral Ring,
  2× Earring of Strength (всё надето), 1000 Soulshot NG, Tutorial Guide. Гном (53): Ubiquitous Axe вместо Long Sword.
  Маги (10, 25, 38, 49): Mage Staff, Tunic/Stockings of Magic, та же бижутерия, но 2× Earring of Wisdom, 1000 Blessed Spiritshot NG, Tutorial Guide.
- Только новые персонажи; существующие не меняются. Настроек нет.

### Модуль `lw-fixes` (kind `jar`)

- Баг стока: при смерти владельца `CursedWeapon.dropIt` выбрасывает проклятое оружие, но не отправляет клиенту `ItemList`,
  и оно «висит» в руках/инвентаре до релога (в LW исправлено правкой `CursedWeapon.java`).
- Здесь: слушатель `ON_CREATURE_DEATH` для игроков (срабатывает до выпадения). Если у игрока проклятое оружие — через
  `CursedWeaponRefreshDelay` (300ms) проверяем, что оно ушло, и шлём `sendItemList` + `broadcastUserInfo`; строка в логе и счётчик в `//module info`.
- Настройки: `CursedWeaponRefresh` (bool, true), `CursedWeaponRefreshDelay` (duration, 300ms, 0..5s), обе reloadable.

## Тесты

- **Unit** (`ant test`):
  - платформа: манифест с `initialEquipment`, ключ хука `initialequipment`, запрет резервов;
  - `starter-kit`: xsd, ровно 9 стартовых классов (как в стоковом файле), все id предметов существуют в стоковых `data/stats/items`,
    у надетых вещей есть слот, содержимое как на E:;
  - `lw-fixes`: решение «обновлять/нет», манифест (включён в обоих профилях).
- **check-hooks**: 10 id, только добавления.
- **Smoke** (`tools/smoke.sh`):
  - 1: сток (`DisableAll`) == baseline, модульной экипировки нет;
  - 2: solo + fixtures + настоящие модули: 11 модулей, `starter-kit`/`lw-fixes` RUNNING, fx-core видит в памяти стартовую экипировку классов 0/10/53 = Long Sword/Mage Staff/Ubiquitous Axe;
  - 3, 4 без изменений;
  - 5: online без fixtures: 2 RUNNING, счётчики загрузки == сток;
  - **6 (новый)**: solo без fixtures (ровно то, что запускает игрок): 2 RUNNING, счётчики == сток, состояния в `platform_modules`.
- **CI** на PR зелёный (все джобы, включая smoke).

## Критерии приёмки

1. `ant dist test`, check-hooks, smoke (все сценарии) и CI зелёные.
2. Стек `l2s` пересобран и запущен на solo на БД Глеба (без `down -v`): `Platform: profile=solo, 2 discovered: 2 running`.
3. Тест Глеба в клиенте (чек-лист в отчёте): новый персонаж воина/мага/гнома в NG-топе; проклятое оружие исчезает из рук сразу после смерти.
4. Рейты и механики в обоих профилях стоковые (в репо нет игровых override).

## Риски

- Override стартовой экипировки — полная копия списков классов: если upstream поменяет стоковый файл, наши списки останутся прежними
  (это и нужно), но новые стартовые предметы стока (например, гайд) не появятся сами. Unit-тест сверяет набор классов со стоком.
- `lw-fixes` полагается на порядок событий в `Player.doDie` (`super.doDie` → событие → выпадение оружия). Проверено по коду `9fb01cb205`;
  при обновлении upstream проверить; если порядок поменяется, исправление просто не сработает (без ошибок).

## Оценка

~1 день: хук и платформа 0,25; модули и unit-тесты 0,25; smoke и документация 0,25; сборка, рестарт, проверка 0,25.

## Приложение: что было на E: и куда ушло

| Кастом на E: | Как сделан там | Итог |
|---|---|---|
| Стартовая экипировка | `data/stats/players/initialEquipment.xml` | **модуль `starter-kit`** |
| Scheme Buffer (NPC 50008) в 15 городах, бафы 1 ч | спавны + правка `SchemeBuffer.java` + `BufferBuffDuration` | не в фазе 2 |
| GM Shop (NPC 50010) в 15 городах, урезанное меню | спавны + HTML `merchant/50010*.htm` | не в фазе 2 |
| Автоизучение скиллов до 75 | стоковые `AutoLearnSkills*` + свой фильтр в `Player.giveAvailableSkills` | не в фазе 2 |
| Бесплатные 1-я/2-я профы | стоковый `config/ClassMaster.xml` | сток, не включаем (позже — лаунчер) |
| Чемпионы, автолут, вес x5, катакомбы без SS | стоковые ключи ini | сток, не включаем (позже — лаунчер) |
| Рейты XP/SP x100, дроп/спойл x30, адена x10 | стоковый `Rates.ini` | **x1** (решение Глеба) |
| RebirthManager | целиком стоковый Mobius, на E: выключен | сток (выключен) |
| Fake-торговцы, VirtualPlayers, PvP-боты, защита `World` от повторного добавления | свой код | боты, не в фазе 2 |
| Living Worlds: проклятое оружие | правка `CursedWeapon.java` | **модуль `lw-fixes`** (без хука) |
| Living Worlds: лимиты бафов 20+12, Seven Signs без расписания, флаг за удар стражника, `//rates` | правки ядра LW | не в фазе 2 |
