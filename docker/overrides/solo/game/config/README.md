Profile **solo**, game server overrides:

- `<File>.ini` with `Key = Value` lines: merged into stock `dist/game/config/<File>.ini`.
- `platform.local.ini`, `modules.local.ini`: copied as is next to `platform.ini` / `modules.ini` (docs/MODULES.md).
- `modules/<id>.ini`: settings of module `<id>` (keys from its module.json "config").
