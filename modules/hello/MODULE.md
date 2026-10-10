# hello — platform demo module

Proves every mechanism of the platform; harmless, can stay on in `solo` (off by default in `online`).

| What | How to see it |
|---|---|
| SQL migration | table `mod_hello_counter`, row `hello / 1` in `platform_module_migrations` |
| event | greeting in chat on login (`GreetOnLogin`, off in `[online]`) |
| voiced command | `.hello` — counter per character and day in the DB, limit `MaxHelloPerDay` |
| admin command | `//hello_stats` |
| task | every `StatsLogPeriod` the log gets `Module[hello]: players online: N` |
| NPC + spawn | NPC 95000 "Hello Guide" in Talking Island village next to Leandro (`//move_to -82350 245260 -3712`) |
| html + bypass | talking to the NPC opens `data/html/95000.htm`; "Who said .hello?" is a module bypass |
| multisell | 95000: 10 Soulshots (No Grade) for 1 Adena |
| i18n | `i18n/messages_{en,ru}.properties`; language = player language (multilang) or platform `DefaultLanguage` |
| reload | edit `config/modules/hello.ini`, `//module reload hello` |

Reserved ids: NPC 95000–95009, multisell 95000–95009 (see `docs/ID_RANGES.md`). Remove data: `sql/remove.sql`.
