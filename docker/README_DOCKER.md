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

## Геодата

Геодата не хранится в git. Чтобы подключить, укажи папку с `*.l2j` в `docker/.env`:
`L2S_GEODATA_DIR=F:/l2start-data/geodata`, затем перезапусти. Без неё сервер работает, как текущие серверы.

## Память

Docker Desktop сейчас ~3,8 ГБ. Профиль `solo` держит игровой сервер в 2 ГБ heap.
Если будет мало, увеличь память WSL в `%USERPROFILE%\.wslconfig` (`[wsl2]`, `memory=5GB`) и выполни `wsl --shutdown`
(остановит все контейнеры).
