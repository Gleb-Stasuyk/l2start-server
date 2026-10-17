# Схема бафов PP / SE в городах

Источник: стоковые деревья Prophet / Shillien Elder / Shillien Saint. Маг/воин — `PlayerClass.isMage()`.
Berserker (1062) — `PARTY_MEMBER`: бафер зовёт в пати; отказ → скип Берса, остальное кастуется.

## PP (пророк)

| Город | Ур. | Цена | Класс |
|-------|-----|------|-------|
| Gludio | 48 | 20k | Prophet |
| Dion | 55 | 62k | Prophet |
| Giran | 64 | 116k | Prophet |
| Aden | 74 | 176k | Prophet |
| Goddard | 78 | 200k | Prophet |

Состав: `Towns.PP_BUFFS` (WW, Shield, Might, … Bers, Greater Might/Shield). Воин-only / маг-only — `PP_WARRIOR_ONLY` / `PP_MAGE_ONLY`.
Уровни скиллов растут с ур. города (см. таблицы раньше; Aden≈Goddard по капам Prophet).

## SE (рядом с PP)

| Id | Город | Ур. | Цена | Класс |
|----|-------|-----|------|-------|
| GiranSE | Giran | 64 | 20k | Shillien Elder |
| GoddardSE | Goddard | 78 | 40k | Shillien Saint |

| Роль | Скиллы |
|------|--------|
| маг | Empower (1059), Wild Magic (1303), Arcane Protection (1354) **только 78** |
| воин | Vampiric Rage (1268), Arcane Protection (1354) **только 78** |

1354 в дереве с getLevel 76 (Saint) — на SE64 отсутствует.

## Код

- `Towns` / `Deal.plan` / `BuffLevels.at` / `Buffers` (пати + P6-BOT-TRADE)
