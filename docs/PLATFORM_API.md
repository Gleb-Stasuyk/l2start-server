# API платформы для модулей (версия 1.2.0)

Пакет `org.l2start.platform.api`. Модуль kind `jar` реализует `GameModule`; всё остальное приходит через `ModuleContext`.
Правило: **регистрируй всё только через контекст** — тогда платформа знает, что принадлежит модулю, и снимает это
при ошибке, `//module disable`, срабатывании circuit breaker и выключении сервера. Каждый вызов кода модуля
(слушатель, команда, задача) обёрнут: исключение логируется, считается и не долетает до ядра.

```java
public interface GameModule {
    void onEnable(ModuleContext ctx) throws Exception;          // регистрации (до спавнов)
    default void onServerReady(ModuleContext ctx) throws Exception {}  // мир загружен, задачи можно запускать
    default void onConfigReload(ModuleContext ctx) throws Exception {} // после //module reload
    default void onShutdown(ModuleContext ctx) throws Exception {}     // сохранить состояние (ShutdownTimeout)
}
```

| `ctx.` | Что даёт |
|---|---|
| `self()` | `id()`, `name()`, `version()`, `profile()` (solo/online), `root()` (папка модуля) |
| `log()` | `info/warn/error/debug`; в логе с префиксом `Module[<id>]:` |
| `config()` | `getString/getBoolean/getInt/getLong/getDouble/getDuration/getList/getEnum`, `source(key)`, `asMap()`; ключи только из `module.json` |
| `events()` | `onGlobal/onPlayers/onNpcs/onMonsters(EventType, Consumer)`, `onNpc(npcId, ...)`, `on(container, ...)` — слушатели событий Mobius |
| `handlers()` | `admin(IAdminCommandHandler)`, `voiced(IVoicedCommandHandler)`, `bypass(IBypassHandler)`; занятая команда → ошибка `onEnable` |
| `scheduler()` | `schedule(name, delay, task)`, `fixedRate(name, initialDelay, period, task)` (ThreadPool Mobius) |
| `db()` | `connection()`, `update(sql, params...)`, `query/queryOne(sql, RowMapper, params...)`, `table("x")` → `mod_<id>_x` |
| `resources()` | `path("data/...")`, `html("95000.htm")` → путь для `NpcHtmlMessage.setFile` (`data/html/modules/<id>/95000.htm`) |
| `i18n()` | `get(player, key, args...)`, `get(lang, key, args...)`, `lang(player)`; файлы `i18n/messages_<lang>.properties`, формат `MessageFormat` |
| `services()` | `provide(Type.class, impl)`, `find/require(Type.class)`; видны только модулям, которые объявили зависимость |
| `botPresence(BotPresence)` | сообщить ядру, какие `Player` — боты (`player -> boolean`): хуки `P3-BOT-CHARINFO`/`P3-BOT-WHISPER` ведут себя с ботом как с игроком. Только один модуль; снимается при выключении |
| `cleanup(name, Runnable)` | действие при выключении модуля (disable, circuit breaker, остановка сервера), в обратном порядке регистраций — например вывести ботов из мира |
| `metrics()` | `counter(name).inc()/add()/get()`, `snapshot()`; видны в `//module info` |

Классы модуля грузит отдельный class loader: сначала ядро и платформа, потом свой jar, потом jar'ы объявленных зависимостей.
Другие модули невидимы.

История: **1.2.0** (фаза 3) — `botPresence` и `cleanup` (модуль `bots-core`, хуки `P3-BOT-CHARINFO`, `P3-BOT-WHISPER`);
модули с `^1.0`/`^1.1` работают как раньше.
**1.1.0** (фаза 2) — новый тип данных `initialEquipment` в `module.json` (стартовая экипировка, хук `P2-DATA-INITEQUIP`);
Java-API не менялся, модули с `^1.0` работают как раньше.

Совместимость: модуль указывает `"platform": "^1.0"`. Изменения API, ломающие модули, поднимают major-версию платформы.
