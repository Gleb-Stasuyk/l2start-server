# Docker: l2start-server

Проект compose `l2s`: контейнеры `l2s-db`, `l2s-db-init` (одноразовый импорт SQL), `l2s-login`, `l2s-game`;
тома `l2s-db-data`, `l2s-login-logs`, `l2s-game-logs`; сеть `l2s-net`. С `l2-*`, `lw-*`, `site-*` не пересекается.

## Профили

| | `solo` | `online` |
|---|---|---|
| Порты слушают | `127.0.0.1` | `0.0.0.0` |
| Адрес игрового сервера для клиента (`L2S_PUBLIC_HOST`) | `127.0.0.1` | IP VPS (в `docker/.env`) |
| `AutoCreateAccounts` | True | **False** |
| Heap игрового сервера | 512m..2g | 2g..4g |
| Перезапуск | нет | `unless-stopped` |

Профиль — это `profiles/<профиль>.env` плюс файлы `overrides/<профиль>/<login|game>/config/<File>.ini` со строками
`Key = Value`. Они накладываются на стоковые ini **внутри контейнера**, `core/` не меняется.
Секреты (пароль БД для `online`, IP) лежат в `docker/.env` (не в git, пример в `.env.example`).

## Команды

```bat
start.bat solo | online [rebuild]
stop.bat
docker logs -f l2s-game
docker compose --env-file profiles\solo.env ps
```

Без bat-файлов: `docker compose --env-file profiles/solo.env up -d --build`.

## База данных

При первом запуске `l2s-db-init` импортирует `core/dist/db_installer/sql/{login,game}/*.sql`, **только если БД пустая**.
Данные сохраняются в томе `l2s-db-data`. Полный сброс: `docker compose --env-file profiles\solo.env down -v` (удаляет все аккаунты и персонажей).

### Бэкап и восстановление

Перед каждым `start.bat solo rebuild` (или `online rebuild`) `start.bat` вызывает `backup-db.bat`:
дамп `mariadb-dump | gzip` в `F:\l2start-server\backups\l2s-YYYYMMDD-HHMM.sql.gz`, хранятся **последние 10** файлов.
Если контейнера `l2s-db` ещё нет (первый запуск) — бэкап пропускается. Если дамп падает — rebuild не идёт.

Вручную:
```bat
cd F:\l2start-server\docker
backup-db.bat solo
```

Восстановить (останавливает game/login, заливает дамп в `l2s-db`):
```bat
cd F:\l2start-server\docker
restore.bat ..\backups\l2s-20261019-1145.sql.gz
start.bat solo
```

Папка `backups/` в git не попадает (`.gitignore`).

## Геодата

Геодата не хранится в git. Чтобы подключить, укажи папку с `*.l2j` в `docker/.env`:
`L2S_GEODATA_DIR=F:/l2start-data/geodata`, затем перезапусти. Без неё сервер работает, как текущие серверы.

## Память

Docker Desktop сейчас ~3,8 ГБ. Профиль `solo` держит игровой сервер в 2 ГБ heap.
Если будет мало, увеличь память WSL в `%USERPROFILE%\.wslconfig` (`[wsl2]`, `memory=5GB`) и выполни `wsl --shutdown`
(остановит все контейнеры).

## Игровые настройки профилей (фаза 2)

Оба профиля (`solo` и `online`) используют **стоковые механики и рейты Mobius (x1)**. Игровые override (КТ3, оба профиля
одинаково) — только `overrides/<profile>/game/config/Player.ini`:

- `AutoLoot = True` — дроп с обычных мобов сразу в инвентарь (игрокам и ботам); травы (`AutoLootHerbs`) и дроп рейдов/боссов
  (`AutoLootRaids`) — стоково, руками; при полном инвентаре дроп падает на землю (`AutoLootSlotLimit`, сток);
- `DisableTutorial = False` — туториал и диалоги Newbie Helper / Grand Master после создания персонажа (как в стоке).
  Побочный эффект (сток): Newbie Helper больше не выдаёт награды туториала (новичковые заряды, жетоны).

В `overrides/<profile>/login/config/Server.ini` — только сетевые/аккаунт-ключи.
Сверху включены 2 модуля: `starter-kit` и `lw-fixes` (`modules/README.md`).

На будущее: соло-настройки (рейты, автолут, вес, бесплатные профы, чемпионы и т.п.) будет выставлять лаунчер через
этот же механизм override.

## Модули (фаза 1)

- Образ содержит модули из `modules/` (`/opt/l2/game/modules`) и `game/config/{platform.ini,modules.ini}`.
- Профиль платформы = `L2S_PROFILE` (`solo` / `online`), передаётся в контейнер как `L2_PROFILE`.
- Переключатели без правки файлов: скопируй `modules.env.example` в `modules.env` (`L2_MODULE_<ID>=on|off`, `L2_MOD_<ID>__<KEY>=...`, `L2_PLATFORM__<KEY>=...`), затем `start.bat solo`.
- Файлы: `overrides/<profile>/game/config/platform.local.ini`, `modules.local.ini`, `modules/<id>.ini` (см. `docs/MODULES.md`).
- Smoke-тест: `"C:\Program Files\Git\bin\bash.exe" tools/smoke.sh --build` (из корня репо). Работает на своей БД `l2s-smoke-db-data`
  (удаляется в конце), основную БД не трогает, но заменяет запущенные контейнеры `l2s-*`; после него снова `start.bat solo`.
