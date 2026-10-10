# Геодата

Сервер работает с геодатой Living Worlds (формат L2J Mobius `.l2j`, 166 регионов, 548 МБ). В git её нет:
файлы лежат вне репозитория и подключаются в контейнер только для чтения.

| Что | Где |
|---|---|
| Файлы (ПК Глеба) | `F:\l2start-data\geodata\*.l2j` (скопировано из `E:\L2-Living-Worlds\...\dist\game\data\geodata`) |
| Контрольные суммы | `tools/geodata-manifest.txt` (sha256, размер, имя) |
| Проверка | `tools/check-geodata.sh <папка>` (bash/WSL) или PowerShell: `Get-FileHash -Algorithm SHA256 F:\l2start-data\geodata\*.l2j` |
| Подключение | `L2S_GEODATA_DIR=F:/l2start-data/geodata` в `docker/.env` (локальный файл, не в git) → `/opt/l2/game/data/geodata:ro` |
| Настройки ядра | стоковый `config/GeoEngine.ini`: `PathFinding = 2` |

Без `L2S_GEODATA_DIR` монтируется пустая папка `docker/geodata.empty` и сервер работает без геодаты, как раньше.
При старте в логе: `GeoEngine: Loaded 166 regions.`

## Память
Регион читается целиком (mmap) и разбирается в блоки в куче Java. Замер на solo: см. отчёт фазы 3 (КТ1);
оценка +0.6–0.9 ГБ кучи, поэтому `L2S_GAME_XMX=3g` в профиле `solo` (Docker Desktop: 6 ГБ через
`C:\Users\user\.wslconfig`, `[wsl2] memory=6GB`).

## Боты
Модуль `bots-core` по умолчанию (`RequireGeodata = true`) не заводит бота в мир, если в точке нет геодаты:
без неё бот проходит сквозь стены. Для тестов без геодаты: `L2_MOD_BOTS_CORE__REQUIREGEODATA=false`.
Стартовые деревни: Talking Island `17_25`, Elven `21_19`, Dark Elven `20_18`, Orc `18_14`, Dwarven `23_12`.
