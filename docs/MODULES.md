# Модули l2start

Модуль: папка `modules/<id>/` с файлом `module.json`. Платформа (`platform/`) находит модули при старте сервера,
проверяет их, разрешает зависимости, накатывает SQL-миграции, загружает код и данные и включает в нужном порядке.
Сломанный модуль никогда не роняет сервер молча: его состояние и причина видны в логе и в `//modules`.

API для кода модулей: `docs/PLATFORM_API.md`. Диапазоны id: `docs/ID_RANGES.md`. Хуки в `core/`: `docs/PLATFORM_HOOKS.md`.
С сервером не поставляется ни одного модуля: минимальный пример ниже, раздел «Минимальный модуль».
Все механизмы платформы проверяются тестовым модулем `platform/test/fixtures/modules/fx-core` (только в smoke-тестах).

## Виды модулей

| kind | Что внутри |
|---|---|
| `jar` | Java-код (`src/` → `<id>.jar`), класс `entrypoint` реализует `org.l2start.platform.api.GameModule`. Может содержать и данные. |
| `data` | Только данные (NPC, спавны, мультиселлы, байлисты, HTML) и/или SQL. Без кода. |
| `script` | Пока не поддерживается (отложено). Такой модуль получает INVALID с понятной причиной. |

## Структура

```
modules/<id>/
  module.json            манифест (обязателен)
  MODULE.md              описание для людей
  src/...                Java (kind jar)
  config/defaults.ini    значения настроек модуля по умолчанию, с секциями [solo]/[online]
  sql/V001__name.sql     версионные миграции (накатываются один раз, по порядку)
  sql/remove.sql         ручное удаление таблиц модуля (платформа сама не запускает)
  i18n/messages_<lang>.properties
  data/npcs, data/spawns, data/multisell, data/buylists, data/html   (пути задаются в module.json)
  test/...               JUnit-тесты модуля (ant test)
```

## module.json

```json
{
  "id": "greeter",                    // ^[a-z][a-z0-9-]{1,31}$, совпадает с именем папки
  "name": "Greeter",
  "version": "1.0.0",                 // semver
  "kind": "jar",
  "platform": "^1.0",                 // версия API платформы (сейчас 1.0.0)
  "entrypoint": "org.l2start.modules.greeter.GreeterModule",
  "dependencies": [ {"id": "other", "version": "^1.2", "optional": false} ],
  "conflicts":    [ {"id": "old-greeter", "version": "*"} ],
  "defaultEnabled": { "solo": true, "online": false },
  "data": { "npcs": "data/npcs", "spawns": "data/spawns", "multisell": "data/multisell", "buylists": "data/buylists", "html": "data/html" },
  "reserves": { "npcs": [[95100, 95199]], "multisell": [[95100, 95199]] },
  "migrations": "sql",
  "config": {
    "Message": { "type": "string", "default": "Welcome!", "reloadable": true, "description": "..." }
  }
}
```
(комментарии `//` здесь только для пояснения, в настоящем JSON их быть не должно)

- Ошибки манифеста собираются все сразу (не по одной), модуль получает INVALID.
- Версии зависимостей: `^1.2`, `~1.2.3`, `>=1.0`, `<2`, `=1.0.0`, `*`, частичные версии (`1`, `1.2`).
- Типы настроек: `string`, `bool`, `int`, `long`, `double`, `duration` (`250ms`, `10s`, `5m`, `2h`, `1d`; число без единицы = мс), `enum` (`values`), `list` (через запятую). Для чисел и duration есть `min`/`max`.
- Данные с id (NPC, мультиселлы, байлисты) обязаны лежать в объявленных `reserves`. Совпадение id со стоковыми данными: модуль FAILED, его данные не грузятся.

## Включение и выключение

Порядок (последний выигрывает):
1. `defaultEnabled.<profile>` в `module.json`;
2. `game/config/modules.ini` (общие ключи, потом секция `[solo]`/`[online]`);
3. `game/config/modules.local.ini` (локальный файл, в Docker: `docker/overrides/<profile>/game/config/modules.local.ini`);
4. переменная окружения `L2_MODULE_<ID>=on|off` (`<ID>`: id в верхнем регистре, `-` → `_`; в Docker через `docker/modules.env`).

Явно выключенный модуль даже не проверяется (состояние DISABLED).

## Настройки модуля

Порядок (последний выигрывает): `default` из `module.json` → `modules/<id>/config/defaults.ini` → `game/config/modules/<id>.ini`
(в Docker: `docker/overrides/<profile>/game/config/modules/<id>.ini`) → `L2_MOD_<ID>__<KEY>=value`.
В каждом ini-файле общие ключи, затем секция текущего профиля. Неверное значение: модуль INVALID, в причине указан источник
(например `MaxBots: 5000 > max 1000 (from config/modules/bots-core.ini)`). `//module info <id>` показывает, откуда взято каждое значение.
Ключи с `"reloadable": true` можно менять без рестарта: `//module reload <id>`.

## Настройки платформы: `game/config/platform.ini`

| Ключ | По умолчанию | Что |
|---|---|---|
| `ModuleRoots` | `modules` | папки с модулями (через запятую, относительно `game/`) |
| `FailurePolicy` | `[solo]` continue, `[online]` abort | что делать, если включённый модуль сломан (см. ниже) |
| `BreakerErrors` / `BreakerWindow` | 20 / 60s | столько ошибок за окно → модуль DEGRADED до рестарта |
| `SlowCall` | 100ms | предупреждение о медленном вызове кода модуля |
| `ShutdownTimeout` | 10s | максимум на `onShutdown` одного модуля |
| `DefaultLanguage` | ru | язык сообщений модулей, если мультиязычность Mobius выключена |
| `DisableAll` | false | `true` = не грузить ни одного модуля (сервер ведёт себя как сток) |

Переопределение: `platform.local.ini` или `L2_PLATFORM__<KEY>=value` (например `L2_PLATFORM__DISABLEALL=true`).
Профиль: `L2_PROFILE` (в Docker: `L2S_PROFILE` из `docker/profiles/<profile>.env`), по умолчанию `solo`.

### FailurePolicy (политика сломанных модулей)

- `continue` (профиль **solo**): сервер стартует без сломанного модуля и без модулей, которые от него зависят. Причина в логе и в `//modules`.
- `abort` (профиль **online**): если включённый модуль не прошёл проверку, зависимости, миграцию, загрузку, `onEnable` или `onServerReady`,
  сервер не открывает порт для игроков и завершается с кодом 1. В логе: `Platform: FailurePolicy=abort (profile online): ...`.
  Исправь модуль или выключи его в `modules.ini` и запусти снова.
- Выключенный модуль (`off`) ошибкой не считается.
- Ошибки во время игры (исключения в слушателях, командах, задачах) сервер не останавливают ни в одном профиле:
  они ловятся, считаются, и при превышении порога модуль переходит в DEGRADED (все его регистрации снимаются).

## Состояния

| Состояние | Значит |
|---|---|
| DISCOVERED | папка найдена |
| DISABLED | выключен настройками |
| INVALID | ошибка в `module.json` или в настройках |
| REFUSED | не хватает зависимости, конфликт, версия платформы, даунгрейд, зависимость сломана |
| RESOLVED | зависимости в порядке, миграции накатаны |
| FAILED | ошибка миграции, загрузки класса, данных (id), `onEnable`/`onServerReady` |
| LOADED | класс загружен, данные подключены |
| ENABLED | `onEnable` выполнен |
| RUNNING | `onServerReady` выполнен, модуль работает |
| DEGRADED | выключен на ходу (circuit breaker или `//module disable`) до рестарта |
| STOPPED | остановлен при выключении сервера |

Последнее состояние каждого модуля хранится в таблице `platform_modules`, история миграций в `platform_module_migrations`.

## Порядок старта (хуки)

`P1-BOOT` (поиск, проверка, зависимости, миграции, загрузка классов) → загрузка данных (`P1-DATA-*`: NPC, мультиселлы, байлисты)
→ `P1-ENABLE` (`onEnable`) → спавны (`P1-DATA-SPAWN`) → `P1-READY` (`onServerReady`, сводка в логе) → при выключении `P1-SHUTDOWN`.

Сводка в логе:
```
Platform: profile=solo, 1 discovered: 1 running, 0 failed, 0 refused, 0 invalid, 0 disabled (bootstrap 120 ms)
Platform:   greeter 1.0.0 RUNNING
```

## SQL-миграции

- Файлы `V<число>__<описание>.sql`, выполняются по возрастанию номера, каждая один раз (под `GET_LOCK`, чтобы два сервера не накатили одно и то же).
- Изменять уже накатанный файл нельзя: контрольная сумма не совпадёт и модуль получит FAILED. Нужна новая миграция.
- Упавшая миграция записывается (`success = 0`), модуль FAILED. После исправления удали эту строку из `platform_module_migrations` (и частично созданные таблицы).
- Даунгрейд модуля (версия ниже записанной в `platform_modules`) запрещён: REFUSED.
- Таблицы модуля называй `mod_<id>_...` (`ctx.db().table("counter")` у модуля `greeter` вернёт `mod_greeter_counter`).

## Админ-команды

| Команда | Что |
|---|---|
| `//modules` | список модулей, состояния, причины |
| `//module info <id>` | версия, зависимости, настройки с источниками, регистрации, ошибки, метрики |
| `//module reload <id>` | перечитать настройки, i18n, HTML; вызвать `onConfigReload` |
| `//module reload-all` | то же для всех работающих модулей |
| `//module disable <id>` | снять все регистрации модуля до рестарта (DEGRADED) |

Доступ: команды не прописаны в `AdminCommands.xml`, поэтому Mobius даёт их высшему уровню доступа (GM).

## Минимальный модуль

Модуль, который приветствует игрока при входе (текст берётся из настроек и меняется через `//module reload greeter`).

```
modules/greeter/
  module.json
  src/org/l2start/modules/greeter/GreeterModule.java
```

`module.json`:
```json
{
  "id": "greeter",
  "name": "Greeter",
  "version": "1.0.0",
  "kind": "jar",
  "platform": "^1.0",
  "entrypoint": "org.l2start.modules.greeter.GreeterModule",
  "defaultEnabled": { "solo": true, "online": false },
  "config": {
    "Message": { "type": "string", "default": "Welcome!", "reloadable": true }
  }
}
```

`GreeterModule.java`:
```java
package org.l2start.modules.greeter;

import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogin;
import org.l2start.platform.api.GameModule;
import org.l2start.platform.api.ModuleContext;

public final class GreeterModule implements GameModule {
    @Override
    public void onEnable(ModuleContext ctx) {
        // registered through ctx -> removed automatically on errors, //module disable and shutdown
        ctx.events().<OnPlayerLogin> onPlayers(EventType.ON_PLAYER_LOGIN,
            e -> e.getPlayer().sendMessage(ctx.config().getString("Message")));
        ctx.log().info("enabled, message: " + ctx.config().getString("Message"));
    }
}
```

`ant dist` соберёт `build/dist/game/modules/greeter/`, Docker-образ возьмёт его оттуда. Переопределить текст:
`docker/overrides/solo/game/config/modules/greeter.ini` со строкой `Message = Привет!` или `L2_MOD_GREETER__MESSAGE=...`.
Данные (NPC, спавны, мультиселлы, байлисты, HTML), SQL, i18n, команды, задачи и сервисы подключаются так же;
рабочий пример каждого механизма: `platform/test/fixtures/modules/fx-core` (+ `fx-ok-dep` для зависимостей и сервисов).

## Сборка и тесты

```
ant dist       # GameServer.jar/LoginServer.jar (core + платформа), модули → build/dist/game/modules
ant test       # JUnit 5: платформа + test/ каждого модуля
ant fixtures   # тестовые модули для smoke-теста → build/fixtures (в настоящий сервер не попадают)
bash tools/smoke.sh --build   # Docker smoke-тест (см. комментарий в начале скрипта)
```
