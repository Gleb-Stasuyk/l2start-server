# modules/

Модули сервера: одна папка `modules/<id>/` на модуль (см. `docs/MODULES.md`, минимальный пример там же).
Сейчас модулей нет: сервер работает как сток Mobius + платформа (`Platform: ... 0 discovered`).
Тестовые модули для проверки платформы лежат отдельно в `platform/test/fixtures/modules/` и попадают
только в smoke-образ (`docker build --target smoke`), никогда в настоящий сервер.
