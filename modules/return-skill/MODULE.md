# return-skill

Каждый игрок (новые и старые персонажи, все классы) при входе получает скилл **2099** (стоковый «Escape: 5 minutes», переопределён модулем):
как Scroll of Escape (эффект `Escape TOWN`, те же условия, что у стокового 2013), но каст 60 с, без свитка,
откат 30 мин. Скилл выдаётся на каждом входе без записи в базу (проверка дерева скиллов его не трогает).

Данные: `data/skills` (тип данных модуля `skills`, API 1.12, хук `P9-DATA-SKILLS`).

Клиент: имя и иконку клиент Interlude берёт из своих `skillname-e.dat` / `skillgrp.dat` по id. Собственный id (был 95100)
клиент не знает: скилл без иконки и названия, и использовать его клиент не даёт. Поэтому взят 2099 — его клиент
знает, а сервер его нигде не выдаёт и не использует. Файл модуля применяется после всех стоковых
(хук `P9-DATA-SKILLS` в `SkillData.load()`), так что версия модуля всегда заменяет стоковую.

## Auto-learn (live players)

`AutoLearnMaxLevel` (int, default 19, reloadable): live players learn every skill of their class automatically on login and level up while their level is <= this; 0 = off. Bots are never touched (their skills stay with bots-core).
