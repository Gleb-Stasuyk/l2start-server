# l2start-server

Сервер Lineage 2 Interlude для l2start.ru на основе **L2J Mobius CT_0 Interlude**, с модульной системой
дополнений и ботами, похожими на живых игроков (см. `docs/ARCHITECTURE.md`).

**Статус: фаза 0.** В `core/` лежит чистый Mobius без изменений. Платформы, модулей и ботов пока нет.

## Структура

| Папка | Что |
|---|---|
| `core/` | L2J Mobius CT_0 Interlude **байт в байт** (база: `upstream.lock`). Менять можно только помеченными хуками `[HOOK:id]` (с фазы 1). |
| `platform/` | Платформа модулей (фаза 1). |
| `modules/` | Модули (фаза 1+). |
| `docker/` | Docker: Dockerfile, compose, профили `solo` / `online`, `start.bat` / `stop.bat`. |
| `tools/` | Импорт и проверка upstream, проверка хуков. |
| `docs/` | Архитектура, план фазы 0, хуки, обновление upstream. |

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

## Сборка без Docker

`ant -f core/build.xml jar` (нужны JDK 27 и Ant ≥ 1.9.10), результат в `build/dist/libs/`.

## CI

GitHub Actions (`.github/workflows/ci.yml`):
- сборка Ant на JDK 27;
- `verify-core`: импорт совпадает с upstream, в `core/` только помеченные хуки;
- сборка Docker-образа;
- smoke-тест сервера при ручном запуске (workflow_dispatch).

## Лицензия

GPL-3.0, как у L2J Mobius.
