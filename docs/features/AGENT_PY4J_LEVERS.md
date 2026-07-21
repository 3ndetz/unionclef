# Agent py4j levers — карта рабочего места когнитивного агента

Это каталог **рычагов**, которыми когнитивный агент (Клод по py4j/MCP) управляет
ботом. Принцип (AGENTS.md): мод даёт примитивы и данные, **агент решает** где/когда/
что. Каждый метод — на `entry_point` py4j-шлюза (порт 25333, `docker exec ... python3`).

Философия композиции: **видеть → идти → действовать**. `getGameState` (перцепция) →
`gotoXYZ`/`pathStatus` (движение) → бой/строительство/меню (действие). Стратегию
(куда, кого, когда) держит агент; мод исполняет механику.

Возвраты `Map` — это dict в Python. Координаты — мировые (server-agnostic).

## Восприятие (что происходит)

| Рычаг | Что даёт | Когда |
|---|---|---|
| `getGameState()` | `self`(hp/maxHp/armor/pos/onGround/held/blocks) + `players[]`(name/pos/distance/hp/sprinting, сорт по дистанции) + `beds[]`(кровати в r=40) | Главный «глаз» боя — без скриншотов. Читать каждый цикл принятия решений |
| `inGame()` | в игре ли (bool) | Проверка перед действиями |
| `getHealth()` / `getHeldItem()` | ХП / id предмета в руке | Быстрые точечные проверки |
| `nearestPlayersInfo(limit, asString)` / `getPlayersInfo(limit)` | ближайшие игроки | Если нужен только список игроков |
| `getCrosshairTarget()` | на что смотрит прицел (блок/сущность) | Перед ударом/установкой — что под прицелом |
| `reachability(playerName)` | дотянемся ли до игрока (reach/LOS/угол) | Гейт перед атакой |
| `canReach(x,y,z,withBreaking)` | дойдём ли до клетки (reached/pathSize/breaks/endDistance) | Планирование маршрута/пролома |
| `canBreakBlock(x,y,z)` | можно ли ломать (deny-список/зоны/block-entity) | Перед сломом |
| `getBlockAt(x,y,z)` / `getGroundBlock()` | блок в точке / под ногами | Разведка местности |
| `getOpenScreen()` | открытый экран (title + все слоты: id/имя/count) | Читать меню магазина/сундука |
| `getInventoryFull()` / `inventorySpace()` | инвентарь / свободные слоты + счётчик блоков по типам | Планировщик ресурсов (мост не длиннее запаса) |
| `getRecentChat(n)` | последние n сообщений чата | Маркеры событий/ошибок |
| `getScreenshot()` | PNG-байты кадра | Когда нужны «глаза» на пиксели (визуал-пазл и т.п.) |

## Движение (идти к цели)

| Рычаг | Что делает | Когда |
|---|---|---|
| `gotoXYZ(x,y,z)` | навигация к координате через **tungsten**-пасфайндер (ходьба/паркур/бридж). Fire-and-poll | Основной рычаг «дойти». Им же репозиционироваться для дальних fillSelection-клеток |
| `pathStatus()` | `busy`/`pos`/`distance`(до цели gotoXYZ)/`arrived`(<1.5) | Крутить после gotoXYZ до `arrived`, затем действовать |
| `stopPathing()` | тотальный стоп (tungsten `;stop` + altoclef `@stop`) | Прервать навигацию/задачу |
| `hasActiveTask()` | занят ли бот задачей (bool) | Общая проверка занятости |
| `bridgeForward(dir, n)` / `bridgeTo(x,y,z)` | годбридж (непрерывное paving пола) в сторону/к цели | Мост через пропасть (bedwars — к чужому острову). Держать блок в руке |
| `bridgeActive()` / `bridgePlaced()` | активен ли бридж / сколько блоков положено | Поллинг годбриджа |

Для парсинга сложного рельефа baritone/shredder есть `ExecuteCommand("@goto x y z")`,
но headless движение ведёт именно tungsten — `gotoXYZ` уже на нём.

## Прицел и повороты (анти-чит-безопасно)

Все повороты идут через **mouse-pipeline** (как физическая мышь) — НИКОГДА не
setYaw/setPitch (античиты палят).

| Рычаг | Что делает |
|---|---|
| `lookAt(x,y,z)` / `lookAtPlayer(name)` | навести прицел на точку/игрока |
| `rotateCamera(dYaw, dPitch)` | относительный доворот камеры |

## Бой (примитивы; стратегию держит агент)

| Рычаг | Что делает |
|---|---|
| `mouseClick("left"/"right"/"middle")` | клик мышью (удар / использование) |
| `interactCrosshairEntity()` | правый клик по сущности под прицелом |
| `interactEntity(name, use)` | атаковать/использовать конкретного игрока |
| `attackPlayer(name)` / `isAttacking(name)` | назначить/проверить цель атаки (altoclef-мозг) |
| `shieldBlock(ticks)` | поднять щит на N тиков |
| `solveArrowAim(name)` | баллистика лука с упреждением (yaw/pitch/charge) — не стреляя |
| `shootArrowAt(name)` | выстрел из лука с траекторией (прицел→заряд→трекинг→релиз) |
| `useHeldItem()` | использовать предмет в руке (еда/пёрл/зелье) |

## Строительство и WorldEdit (реальная установка, работает в survival)

| Рычаг | Что делает | Возврат |
|---|---|---|
| `placeBlockAt(x,y,z)` | поставить блок в клетку (наведение на грань опоры, interactBlock) | ok/placed/support/side |
| `placeBlockLooking()` | поставить блок туда, куда смотрит прицел | ok/placed |
| `select(x1,y1,z1,x2,y2,z2)` | задать WorldEdit-регион (жёлтая подсветка) | min/max/volume |
| `clearSelection()` | снять выделение | ok |
| `fillSelection(block)` | **//set** — залить регион блоком (снизу вверх, в досягаемости, кап 96/вызов). Экипирует названный блок из хотбара | filled/remaining/already/truncated/complete |
| `wallsSelection(block)` | **//walls** — 4 вертикальные стены региона (полый центр) | как fillSelection |
| `buildDefenseAround(x,y,z)` | защитный панцирь вокруг точки (стороны+крыша) — застроить кровать | placed/remaining |

`fillSelection`/`wallsSelection` возвращают `remaining`>0, если часть клеток вне
досягаемости — агент делает `gotoXYZ` ближе и зовёт снова (агент оркестрирует).

## Меню, магазин, ручной ввод

| Рычаг | Что делает |
|---|---|
| `clickMenuByName(names, button, action, timeoutMs)` | клик по слоту меню **по имени предмета** (магазин/навигация хаба) — server-agnostic. Покупка = открыть магазин + этот метод |
| `clickUiSlot(slot, button, action)` | клик по слоту меню по индексу |
| `selectHotbar(slot)` | выбрать слот хотбара 0-8 |
| `getOpenScreen()` | прочитать открытое меню (см. Восприятие) |
| `closeOpenScreen()` | закрыть экран |
| `screenClickAt(x,y,button,scaled)` | клик по ЭКРАННЫМ координатам (произвольный GUI) |
| `tapKey(name)` / `holdKey(name,ms)` | нажать/удержать клавишу |

## Команды и связь

| Рычаг | Что делает |
|---|---|
| `ExecuteCommand("@...")` | altoclef-команда (`@goto`, `@get`, `@game`, `@stop`) |
| `ChatMessage(";...")` / `ChatMessage("текст")` | tungsten-команда (`;goto`, `;bridge`, `;stop`) или чат. Tungsten перехватывает именно chat send |
| `ConnectToServer(ip)` | подключиться к серверу |

---

Обновлять при добавлении рычагов. Единый источник описаний — javadoc на методах
`Py4jEntryPoint`; будущий MCP-сервер (TODO 5) оборачивает их, НЕ дублируя логику.
