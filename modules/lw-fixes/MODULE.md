# lw-fixes

Мелкие исправления из L2-Living-Worlds, которым не нужны хуки в `core/`.

## Проклятое оружие (Zariche / Akamanah)

Баг стока: когда владелец проклятого оружия умирает, `CursedWeapon.dropIt` выбрасывает оружие на землю (или оно исчезает),
но список предметов клиенту не отправляется, и у бывшего владельца оружие «висит» в руках/инвентаре до релога.
В LW это исправлено правкой `CursedWeapon.java`. Здесь то же самое делается слушателем:

- `ON_CREATURE_DEATH` для игроков (срабатывает **до** выпадения оружия): если у игрока проклятое оружие, через
  `CursedWeaponRefreshDelay` проверяем, что оно ушло, и отправляем `ItemList` + `UserInfo` (`sendItemList`, `broadcastUserInfo`).
- В лог: `Module[lw-fixes]: cursed weapon <id> left <name>: client refreshed`; счётчик `cursedWeaponRefresh` в `//module info lw-fixes`.

| Настройка | По умолчанию | Что |
|---|---|---|
| `CursedWeaponRefresh` | true | включить исправление (reloadable) |
| `CursedWeaponRefreshDelay` | 300ms | пауза после смерти (0..5s, reloadable) |

Включён в профилях solo и online. Выключить: `L2_MODULE_LW_FIXES=off`.
