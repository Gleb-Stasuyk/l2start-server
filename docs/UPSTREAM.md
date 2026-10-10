# Обновление Mobius (upstream)

- `upstream/mobius`: только чистые импорты L2J Mobius CT_0 Interlude в `core/` + `upstream.lock`. Никаких других коммитов.
- `main`: merge из `upstream/mobius` + платформа, модули, docker, CI.

## Текущая база

См. `upstream.lock`: коммит `9fb01cb2051` (2026-10-10), 19 144 файла, manifest `8b0a1264…b0b1`.

## Как обновиться

1. Скачать свежий Mobius и распаковать папку `L2J_Mobius_CT_0_Interlude` (например `F:\L2J_Mobius_Interlude_vNEW`).
   Узнать коммит upstream: последний коммит в GitLab по папке `L2J_Mobius_CT_0_Interlude`.
2. Убедиться, что `git config core.autocrlf` = `false` (иначе испортятся концы строк).
3. `tools\import-upstream.ps1 -Source F:\L2J_Mobius_Interlude_vNEW -Commit <hash> -CommitDate <дата> -Subject "<тема>"`
   Скрипт заменит `core/` на ветке `upstream/mobius`, посчитает manifest, запишет `upstream.lock`, сделает коммит `vendor: …`.
4. Сверить manifest с деревом upstream (на машине с клоном Mobius: `git ls-tree -r <hash> -- L2J_Mobius_CT_0_Interlude`, та же формула, что в `tools/verify-upstream.sh`).
5. `git switch main` → `git merge upstream/mobius`. Конфликты возможны только в строках `[HOOK:…]`.
6. Пуш, CI: `verify-core` проверит байт-точность импорта и что в `core/` только помеченные хуки.

## Формула manifest

Строки `<git blob sha1> <путь относительно core/>`, сортировка по байтам (`LC_ALL=C sort` / `StringComparer.Ordinal`),
склейка через `\n` без завершающего перевода строки, SHA-256 от результата.
