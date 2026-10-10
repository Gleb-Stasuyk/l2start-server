# starter-kit

Стартовая экипировка новых персонажей для всех 9 стартовых классов (перенесено с `E:\L2J_Mobius_CT_0_Interlude`,
`data/stats/players/initialEquipment.xml`).

| Классы | Что получает персонаж |
|---|---|
| Human / Elf / Dark Elf / Orc Fighter (classId 0, 18, 31, 44) | Long Sword, Bronze Breastplate + Gaiters, Leather Helmet, Bracer, Boots, Blue Diamond Necklace, 2× Blue Coral Ring, 2× Earring of Strength (всё надето), 1000 Soulshot: No Grade, Tutorial Guide |
| Dwarf Fighter (53) | то же, но Ubiquitous Axe вместо Long Sword |
| Human / Elf / Dark Elf / Orc Mystic (10, 25, 38, 49) | Mage Staff, Tunic + Stockings of Magic, Leather Helmet, Bracer, Boots, та же бижутерия, но 2× Earring of Wisdom, 1000 Blessed Spiritshot: No Grade, Tutorial Guide |

- Как работает: `data/initialEquipment/*.xml` в стоковом формате (xsd `data/xsd/initialEquipment.xsd`). Хук
  `P2-DATA-INITEQUIP` в `InitialEquipmentData.load()` читает его после стокового файла; каждый `classId` из файла
  модуля **заменяет** стоковый список этого класса. Если файл перечисляет не все классы, остальные остаются стоковыми.
- Касается только **новых** персонажей. Уже созданные не меняются.
- Выключить: `L2_MODULE_STARTER_KIT=off` (или `modules.ini`): после рестарта снова стоковые Squire's/Apprentice's вещи.
- Настроек нет. Если несколько модулей задают один класс, побеждает последний в порядке загрузки.
- Тест (`test/`): каждый класс из стокового файла есть в модуле, все id предметов существуют в стоковых `data/stats/items`,
  надеваемые вещи имеют слот (`bodypart`), файл проходит xsd.
