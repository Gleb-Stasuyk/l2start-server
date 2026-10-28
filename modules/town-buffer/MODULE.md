# town-buffer

Платные бафферы-БОТЫ, как на старом сервере x100 (`VirtualPlayers.ini` → TownBuff: Prophet 74 «TopPP» у ГК Диона,
титул `buff 74 lvl - 20k`, трейд 20k). Персонажи bots-core (статус TRADER: без директора и мозга), сидят у ГК:

| Город | Класс | Ур. | Цена | Титул |
|---|---|---|---|---|
| Gludio | PP | 48 | 20k | `Баф 48 ур - 20k` |
| Dion | PP | 55 | 62k | `Баф 55 ур - 62k` |
| Giran | PP | 64 | 116k | `Баф 64 ур - 116k` |
| Giran | SE | 64 | 20k | `SE 64 ур - 20k` |
| Aden | PP | 74 | 176k | `Баф 74 ур - 176k` |
| Goddard | PP | 78 | 200k | `Баф 78 ур - 200k` |
| Goddard | SE | 78 | 40k | `SE 78 ур - 40k` |

SE маг: Empower, Wild Magic (+ Arcane Protection на 78). SE воин: Vampiric Rage (+ Arcane на 78).
Схемы: [BUFFS.md](BUFFS.md). Berserker у PP — через временную пати.

Как работает (логика x100): запрос трейда → через 1 с бот встаёт и принимает (хук `P6-BOT-TRADE`); игрок кладёт адену ≥ цены
и подтверждает → через 1 с бот подтверждает последним (только адена, больше ничего; бот ничего не кладёт; проверка под
замком обоих списков, как в 5f) → бот кастует полный набор по одному скиллу реальным кастом (MP, анимация), время стоковое.
Маг/воин — по классу заплатившего (`isMage`): воинам Might, Focus, Death Whisper, Guidance, Haste, Greater Might; магам
Acumen, Concentration, Greater Shield; остальным всё: Wind Walk, Shield, Mental Shield, Blessed Body/Soul, Magic Barrier,
Regeneration, Bless Shield, Berserker Spirit. Уровни скиллов — как у пророка уровня города (стоковое дерево).
Таблица по городам/классам: [BUFFS.md](BUFFS.md).
Бот не ходит: клиент дальше 900 — «Подойди поближе», ждёт 2 мин. Нет MP — садится до 80 %. В простое сидит.
Адена из сделки сжигается. Нужен bots-core (сейчас только solo); без него модуль ничего не делает.
