# l2start-server

Сервер Lineage 2 Interlude для l2start.ru на основе **L2J Mobius CT_0 Interlude**, с модульной системой
дополнений и ботами, похожими на живых игроков (см. `docs/ARCHITECTURE.md`).

**Статус: фаза 3** в ветке `phase3` (шаги 3a–3d): ядро ботов — модуль `bots-core` (персонажи без клиента, создание «как у игрока», персоны, возврат после рестарта, рантайм с шардами и LOD, API мозгов, `//bots`), минимальный мозг `bots-brain-basic` (деревня → охота → отдых → смерть → снова), хуки `P3-BOT-CHARINFO` и `P3-BOT-WHISPER`, API платформы 1.2.0, геодата Living Worlds (`docs/GEODATA.md`). Рантайм и API мозгов: `docs/BOTS_RUNTIME.md`. План: `docs/PHASE3_PLAN.md`, аудит ядра: `docs/BOTS_CLIENTLESS.md`. Фазы 0–2 (платформа модулей, `starter-kit`, `lw-fixes`) завершены 10.10.2026.

**На будущее:** настройки соло-игры (рейты, автолут, вес, бесплатные профы, чемпионы и т.п.) будут выставляться из лаунчера; сервер для этого уже умеет профильные override (`docker/overrides/<profile>/`). Сейчас ни один профиль их не использует.

## Структура

| Папка | Что |
|---|---|
| `core/` | L2J Mobius CT_0 Interlude **байт в байт** (база: `upstream.lock`) + 9 помеченных хуков `[HOOK:id]` (`docs/PLATFORM_HOOKS.md`). |
| `platform/` | Платформа модулей: загрузчик, API для модулей, тесты, тестовые модули для smoke (`docs/MODULES.md`, `docs/PLATFORM_API.md`). |
| `modules/` | Модули сервера: `starter-kit`, `lw-fixes` (`modules/README.md`); минимальный пример в `docs/MODULES.md`. Диапазоны id: `docs/ID_RANGES.md`. |
| `docker/` | Docker: Dockerfile, compose, профили `solo` / `online`, `start.bat` / `stop.bat`. |
| `tools/` | Импорт и проверка upstream, проверка хуков (`check-hooks.sh`, `test-check-hooks.sh`, `hook-report.sh`), Docker smoke-тест (`smoke.sh`). |
| `docs/` | Архитектура, планы фаз 0–2, модули, API, хуки, диапазоны id, обновление upstream. |

## Ветки

- `upstream/mobius`: только чистые импорты Mobius (`vendor: …`).
- `main`: рабочая ветка = upstream + наша инфраструктура. Порядок обновления в `docs/UPSTREAM.md`.

**Важно для Windows:** в этом репо должно быть `git config core.autocrlf false`. В `.gitattributes` стоит `* -text`, чтобы git не трогал концы строк в `core/`.

## Быстрый старт (Docker Desktop, одиночная игра)

Требуется только Docker Desktop. Java и Ant ставить не нужно: сборка идёт внутри Docker (JDK 27, Ant 1.10.15).

```bat
cd F:\l2start-server\docker
start.bat solo            :: первый запуск собирает образ (несколько минут) и создаёт БД
stop.bat
start.bat solo rebuild    :: пересобрать после изменений кода
```

- Логин-сервер `127.0.0.1:2106`, игровой `127.0.0.1:7777`. В лаунчере выбери «Локальный сервер».
- В профиле `solo` аккаунт создаётся автоматически при первом входе: любой логин и пароль.
- Порты общие со старыми серверами (`l2-*`, `lw-*`). `start.bat` предложит остановить их, `stop.bat` предложит запустить обратно.
- Подробности в `docker/README_DOCKER.md`.

## Модули

- Список и состояние: в игре `//modules`, `//module info <id>`; в логе строка `Platform: profile=..., N discovered: ...`.
- Включить/выключить: `game/config/modules.ini` или `docker/modules.env` (`L2_MODULE_<ID>=off`), подробно в `docs/MODULES.md`.
- Сломанный модуль: в `solo` сервер стартует без него, в `online` не стартует (`FailurePolicy` в `platform.ini`).
- Сервер как сток без модулей: `L2_PLATFORM__DISABLEALL=true`.

## Сборка без Docker

`ant dist` в корне (нужны JDK 27 и Ant ≥ 1.10): `build/dist/libs/{GameServer,LoginServer}.jar` (ядро + платформа) и
`build/dist/game/modules/`. Тесты: `ant test`. `core/build.xml` (сток) не используется и не меняется.

## CI

GitHub Actions (`.github/workflows/ci.yml`):
- `build`: `ant dist fixtures` на JDK 27;
- `test`: `ant test` (JUnit 5);
- `verify-core`: импорт совпадает с upstream, в `core/` только помеченные и описанные хуки (+ самотест проверки);
- `docker`: сборка образа;
- `smoke`: `tools/smoke.sh` (сток-база, сломанные модули, рестарт, online abort, настоящие модули в online и solo) на PR, в main и вручную.

## Лицензия

GPL-3.0, как у L2J Mobius.
