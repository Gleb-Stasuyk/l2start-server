# stock-npcs

Сток Mobius, без нового бафера и без своего магазина.

| NPC | id | Где |
|---|---|---|
| Scheme Buffer «Thiago» | 50008 | пять точек у ГК, те же координаты, что писал `docker/local-features.sh` |
| GM Shop «Mirellas» | 50010 | Гиран, рядом с площадью: 83680, 147780, -3404 |

Спавн один на точку, в `data/spawns/stock-npcs.xml`. Сервер читает его при каждом старте. `L2S_STOCK_NPC_BUFFER` в пакете local выключен: иначе entrypoint писал бы второй файл со теми же 50008.

Баф этого NPC длится `DurationSec` (3600). Хук `P11-BUFFER-TIME` срабатывает только после `applyEffects` у Scheme Buffer и трогает баф, чей кастер — этот NPC. Каст игрока, бота, предмета и чужой баф, если каст NPC не прошёл, остаются как были. Набор бафов и HTML — стоковые (`SchemeBufferSkills.xml`, `html/mods/SchemeBuffer/`).

GM Shop — стоковый торговец: `html/merchant/50010.htm` и мультиселлы с `<npc>50010</npc>`. Цены и состав не менялись. Обычный игрок открывает их, стоя у NPC.

`DurationSec = 0` выключает смену длительности. Модуль включён в solo и online.
