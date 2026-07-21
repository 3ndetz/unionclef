# Progress

## Установка блоков + застройка кровати (2026-07-21) — СДЕЛАНО

- placeBlockAt(x,y,z): авто-выбор блока из хотбара, наведение на грань опорного
  соседа, interactBlock. inventorySpace(): свободные слоты + подсчёт блоков.
  Тест place_test PASS: 4/4 блока (линия+стек), free=35/blockCount=64.
- buildDefenseAround(x,y,z): защитный панцирь вокруг кровати (стороны+крыша),
  переиспользует placeBlockAt. Тест PASS: кольцо вокруг кровати 4/4 solid при
  обходе с 4 сторон. Это фундамент строительства (блоки 7-10: bridge/schematic/
  WorldEdit) и bed-defense для BedWars.
- Минор: buildDefenseAround возвращает завышенный placed-счётчик (88) — ground
  truth по rcon верен, проверить лишние вызовы placeBlockAt.

## Живой заход musteryworld + диагноз меню-квирка (2026-07-21)

Живьём на mc.musteryworld.top через тестового клиента (py4j):
- Коннект + авто-антибот-проверка («Вы успешно прошли проверку!») + /register + login — ✅
- Хаб прочитан: компас «Выбор сервера» в слоте 0, порталы миниигр по бокам — ✅
- Меню серверов открыто (useHeldItem) и ПРОЧИТАНО ПО ИМЕНИ: СКАЙБЛОК(0),
  ВЫЖИВАНИЕ(2), ГРИФЕРСКИЙ(4), АНАРХИЯ(6), МИНИ-ИГРЫ(8) — ✅
- Клик по МИНИ-ИГРЫ(8) → меню миниигр открылось (title подтверждён), BEDWARS
  (красная кровать) виден глазами — ✅
- Заход в bedwars-лобби — НЕ завершён из-за корневого квирка (ниже).

**КОРНЕВОЙ ДИАГНОЗ (то, что юзер назвал «приколы устаревшего таска»):**
`getOpenScreen`/`getInventoryFull` ПЕРИОДИЧЕСКИ читают слоты контейнера/инвентаря
ПУСТЫМИ, хотя меню открыто и предметы рендерятся (подтверждено: title='Выбор
сервера' open=True, но named slots=[] и хотбар=[]). Гонка сэмплинга в headless:
сервер периодически пере-шлёт инвентарь, и onClientThread-чтение попадает в окно,
где slots пусты. Клик ПО ИНДЕКСУ (clickUiSlot) уходит на сервер ВСЕГДА (МИНИ-ИГРЫ
открылось при пустом чтении), но НАДЁЖНО определить индекс ПО ИМЕНИ нельзя →
ломается и autojoin (getCustomItemSlot читает те же слоты), и ручная/когнитивная
навигация, и будущее чтение магазина.

**ФИКС СДЕЛАН И ПРОВЕРЕН ЖИВЬЁМ (2026-07-21):** добавлен py4j `clickMenuByName(
names, button, action, timeoutMs)` — ретраит чтение меню сквозь флаки-окна пока
слот с нужным ИМЕНЕМ не найдётся, потом кликает. Переиспользует read/click,
без дублей. Живой результат: компас → clickMenuByName("МИНИ-ИГРЫ")→idx 8 →
clickMenuByName("bedwars")→idx **11** (ручной guess slot 2 был неверен — метод
нашёл по имени) → «Сервер | Подключение к серверу bwlobby-1...» → **Я В BEDWARS-
ЛОББИ** (скриборд BEDWARS, ник tester1, живые игроки, «Прыгай чтобы начать»).
Хаб-навигация musteryworld РЕШЕНА и надёжна. Линчпин восприятия для когнитивного
агента (блок 6) готов.

Осталось по bedwars: очередь в матч (прыжок «быстро начать») → реальный бой →
тест tungsten-нападения; + когнитивная поверхность (магазин/застройка кровати).

Что построено по пути (запушено): interactCrosshairEntity, mouseClick(l/r/m),
screenClickAt — слой ввода; на mlegacy доказано, что клики крутят капчу-рамки.

## Комбат-примитивы: фасад + щит (2026-07-21) — СДЕЛАНО

- CombatPrimitives (tungsten/combat): canHit-гейт, attack, shieldHold/Release,
  solveArrow, shootArrow — исполнительная поверхность для мозга altoclef.
- ShieldBlocker: держит use N тиков, уступает клавишу луку. py4j shieldBlock.
- Тест shield_test.py PASS: дуэль примитивов — лучник (BowShooter tester2)
  против щитоносца: контроль без щита 2/2 попадания, со щитом 0/3 урона.
- Дальше по арсеналу: примитивы бросков (трезубец/снежок/пёрл), mace-удар;
  мозговая часть (выбор оружия, ХП-логика, тайминги щита) — altoclef.

## Траекторный движок лука (2026-07-21) — СДЕЛАНО

- TrajectorySolver (tungsten/combat): ваниль-баллистика (0.99 drag / 0.05 g),
  питч бисекцией по симулированным полётам, упреждение фиксированной точкой
  (3 раунда). BowShooter-примитив: прицел→заряд с трекингом→relase в конусе
  3.5°. py4j: shootArrowAt, solveArrowAim.
- bow_test.py PASS: 3/5 стоя + 2/5 по бегущей (жертва бежит СВОИМ ;goto —
  tp-движение имеет velocity=0 и упреждение по нему невозможно by design).
- Дальше: связка с altoclef-логикой лука (когда стрелять/чем — у него),
  щит-примитив и формализация комбат-API (#10).

## Пакет «BreakRules + prediction API + конфиг-справочник» (2026-07-21) — СДЕЛАНО

- BreakRules (tungsten/path): единая политика ломания — allowBreak, deny-блоки,
  deny-зоны, block entities, canBreakHook (altoclef break-avoiders через
  AltoClefSettings.shouldAvoidBreaking). Планировщик и исполнитель (live-
  перепроверка) ходят через неё.
- py4j: canBreakBlock, canReach(withBreaking) — reached/breaks/endDistance.
  Грабля: залипший stop-флаг мгновенно ломал поиск в canReach (2-нодный
  огрызок) — сбрасывается перед пробой; «found» честно заменён на «reached».
- docs/features/TUNGSTEN_CONFIG.md — полный справочник конфига.
- Автотест F_api PASS вместе с регрессией C/D/E (цикл 13).

## Пакет «видимое ломание + погоня» (2026-07-21) — СДЕЛАНО

- Визуализация майнинга: BREAK_PLAN-контейнер (план — оранжевые боксы,
  текущий блок — красный), рендер в MixinDebugRenderer. Регрессия C/D/E PASS.
- Взгляд без телепорта при майнинге: плавный поворот 16°/тик, атака
  зажимается только когда курсор доведён (<12°).
- Бесшовный резюм goto после слома: resumeGotoAfterMining — немедленный
  рестарт поиска вместо ожидания retry-цепочки.
- Погоня (fix 0540a24): пере-план не чаще 2с + порог max(3.0, 25% дистанции)
  вместо 0.75с/1.5 блока (поиск не доживал до эмиссии). Автотест
  follow_test.py: средняя дистанция 2.0/лимит 10, 0 фризов — PASS.
- Осталось по фидбеку юзера: PVP в реальном бою (редкие клики, вечные
  ожидания — диагностика с движущейся жертвой), rich API (#16), конфиги с
  описаниями (#15), skypvp-крещение mlegacy.net (@game skypvp, капча вручную
  через noVNC на первом заходе).

## Need-fulfiller API, ступень 1: инструменты (2026-07-20) — СДЕЛАНО

- Дизайн: docs/features/TUNGSTEN_ALTOCLEF_API.md (сплит tungsten=исполнение /
  altoclef=инвентарь, для комбата и для майнинга/строительства).
- Реализация: TungstenModDataContainer.equipToolHook ← altoclef
  (getBestToolSlot + forceEquipItem), вызов из PathExecutor.tickBreaking.
- Автотест: курс E_tool (deepslate-дверь, кирка вне хотбара) PASS вместе с
  регрессией C/D. Коммит dcbb3a2.
- Дальше: bestBreakTicks-хук (cost от лучшего инструмента), затем
  формализация комбат-API (#10), траектории лука (#11), ступени 2-3
  (количество блоков, установка).

## PVP rework + tungsten block breaking (2026-07-20, в работе)

### Investigate (аудиты завершены)

**Комбат — причины симптомов:**
- «Боится бить»: триггер стреляет ТОЛЬКО при `mc.targetedEntity == target`
  (ванильный OUTLINE-пик), а прицел ведёт цель с упреждением по COLLIDER →
  крестик мимо текущего хитбокса → удар подавлен (TriggerBot.java:38,48).
- «Зависает в траве»: OUTLINE-пик блокируется травой (у неё есть outline),
  COLLIDER-прицел траву игнорит → вечный лок без ударов + осцилляция
  COMBAT↔APPROACH через hasNoProgress(60) (PunkPlayerTask.java:76).
- Пассивность: ESCAPE первую половину КАЖДОГО кулдаун-цикла
  (SafetySystem.java:448: cooldown<0.5 → ESCAPE — убегает и отворачивается
  после каждого удара); движение к цели выключено по умолчанию
  (TungstenConfig.combatMovementsEnabled=false); DANGER_BATTLE при
  предсказанном KB-падении ≥2 блоков — стопорит сближение на любом рельефе.
- Микро-фризы: BFS до 2000 нод каждые 10 тиков на главном потоке
  (CombatPathfinder.java:47,201,237).

### Plan — комбат (фиксы) — СДЕЛАНО, ТЕСТ PASS

- [x] TriggerBot: свой гейт (reach ≤3.0 до хитбокса + COLLIDER LOS + угол <40°
  + cooldown ≥0.95 через getAttackCooldownProgress(0f)) и ПРЯМАЯ доставка
  `interactionManager.attackEntity` + swingHand (обход crosshairTarget);
  крит-окно: при падении бить с ≥0.85.
- [x] SafetySystem: убрать «ESCAPE при cooldown<0.5»; KB_FALL_THRESHOLD 2→4.
- [x] TungstenConfig: combatMovementsEnabled default true.
- [x] PunkPlayerTask: COMBAT_RANGE 3.5→4.5, hasNoProgress 60→100.
- [x] FollowEntityTask.hasLineOfSight: рейкаст в центр тела, не в feet.
- [x] CombatPathfinder: MAX_NODES 2000→800.
- [x] Тест pvp_test.py: **PASS** (v6, jar 0.24.0+) — первый урон 4.3с,
  жертва УБИТА (20.0 урона), 0 freeze-окон, бой ВНУТРИ пятна tall_grass.
  NB: первый «PASS» (6.6с) был невалиден — деплоился старый jar (см. урок 1
  в блоке block breaking) и жертва стояла в чистом поле.
- [x] Финальные PVP-фиксы по трассе гейта: direct-charge на последние
  полблока (BFS-допуск 1.5 оставлял бота в 3.06 при риче 3.0) и пин
  combatMovementsEnabled в тесте (persisted конфиг перебивал новый дефолт).

### Итог — block breaking: ОБА КУРСА PASS (цикл 8)

- C_wall: дверь пробита (0,-60,20 → air), бот у цели за 12с
- D_sand: дверь пробита, упавший песок доломан, бот у цели
- Дорога к зелёному (уроки, все пофикшены):
  1. **Пайплайн деплоил СТАРЫЙ jar**: в build/libs лежали 0.23.3 и 0.24.0,
     `ls | head -1` брал алфавитно-первый → все тесты после релиза гоняли
     утренний код. Фикс: `ls -t` (по свежести). Из-за этого же PVP-тест
     «прошёл» на старом комбате в чистом поле.
  2. Прямые вызовы updateBlockBreakingProgress обнулялись ванилью каждый
     тик (attackKey не зажат) — 15с без слома 2 dirt. Фикс: aim + держать
     attackKey, ваниль майнит сама.
  3. Бесконечно-плоский мир: обход любой конечной стены дешевле пролома
     (block-space A* НЕ аккумулирует cost) — тесты переехали в запечатанные
     бедроковые коробки с dirt-дверью.
  4. Огрызок-guidance после усечения морил физпоиск («Ran out of nodes») —
     фикс: если игрок уже у стены, майнить без физического лега (пустой
     путь + breakQueue).
  5. Протискивание в 1-блочную дыру даёт дрифт 0.84 при пороге 0.8 —
     в тестах ;settings driftThreshold 1.5.
  6. shouldResetSearch: сброс bestSoFar/closed при пере-руте (стейл-цепочки
     от старого корня); guard в setCurrentPath (корень дальше 2 блоков от
     игрока → отказ); 253-branch re-search timeout 220ms → 3s.
  7. Persisted tungsten.json переопределяет новые дефолты конфига —
     тест-раннеры пинят критичные сеттинги явно (;settings ...).

### Plan — block breaking (v1, прагматичный срез)

Архитектура: НЕ оборачивать мир и не паузить replay. Сегментация через
существующий retry-механизм GotoCommand (MAX_RETRIES):
1. BlockNode.toBreak: block-space разрешает «пробить стенку» для СОСЕДНЕЙ
   клетки, если feet/head-блоки ломаемы (calcBlockBreakingDelta>0, не
   bedrock); cost += ticks_ломания (ваниль: 1/delta) + рекурсия по
   FallingBlock сверху (baritone-паттерн). Config: allowBreak (def true),
   breakCostMultiplier.
2. PathFinder: усекать blockPath до первой break-ноды — физика ведёт бота
   ДО стенки; breakQueue передаётся исполнителю.
3. PathExecutor: после конца replay, если breakQueue непуста и блок в
   reach 4.4 — BREAKING-хвост: aim на центр блока, attackBlock +
   updateBlockBreakingProgress + swingHand каждый тик, пока клетки прохода
   не проходимы (цикл покрывает упавший песок), потом cb → retry GotoCommand
   → новый поиск в уже-чистом мире.
4. Тесты: course C (dirt-стенка 2 высотой на пути), course D (песок сверху
   → падает в проход → доламывание). deploy/runner/break_test.py.

Ключевые точки кода (из аудита): BlockNode.shouldRemoveNode:421 (normal cube
reject) и wasCleared:552; children cost BlockNode:373; исполнение брейка —
baritone-паттерн interactionManager.attackBlock/updateBlockBreakingProgress
(BaritonePlayerController.java:60-93, BlockBreakHelper.java:52); PathInput
менять не нужно (брейк вне replay).

## Tungsten slime parkour + автотест фазы 0 (2026-07-20)

### Investigate

- Слайм-механика в tungsten была частично начата и откачена (48dc410); статус в
  `docs/features/TUNGSTEN_SURFACES.md`. Физика Agent.tick корректна (bounce,
  no fall damage, onSteppedOn slowdown) — сломан был именно роутинг.
- Найденные блокеры: `checkForFallDamage` (обрез любых падений >2.75),
  `isJumpImpossible` (обрез детей выше +1.4 до slime-исключений),
  airborne/midfall прунинг в Node, debug Thread.sleep(250) в слайм-ветке
  block-space, off-by-one верхнего bounce-уровня, SlimeBounceMove жал прыжок
  на тике приземления (jump() перезаписывает bounce velY на 0.42).

### Plan

- [x] Слайм-исключения во всех местах прунинга (isSlimeColumnBelow, скан 32)
- [x] Bounce-высота детей block-space от кумулятивного падения (мин 1.25)
- [x] Переписать SlimeBounceMove (initiate только из покоя)
- [x] Стенд фазы 0 из AUTOTESTING.md на маке (deploy/compose.test.yml)
- [x] Прогнать slime_test.py (курсы A: drop-4 → +3, B: drop-3 → +2) до PASS

### Implement

- [x] tungsten: bf48a82 — слайм-роутинг (6 файлов)
- [x] deploy/: 549bf11 — compose (itzg vanilla 1.21.11 + mineswarm-mc:amd64),
  runner slime_test.py (py4j через docker exec + rcon-cli), autotest.sh
- [x] Сборка на маке: BUILD SUCCESSFUL 47s, jar задеплоен в deploy/run/mods
- [x] Итерации по фейлам:
  - `;goto` молча игнорировался после `;stop` — залипший `PATHFINDER.stop`
    (fix e1647fd: сброс флагов в GotoCommand)
  - fill до загрузки чанков — forceload + верификация постройки (c80018f)
  - деревня на трассе (суперплоский генерит структуры) —
    GENERATE_STRUCTURES=false + пересоздание мира (4fc7502)
  - drift-abort 8.9 блоков: shouldResetSearch пере-рутил поиск без эмиссии
    префикса при простаивающем executor (fix 8ec354e: inline resetSearch)
  - курс B «flat bounce» невозможен по ванильной физике (апекс ~1.9) —
    убран минимум 1.25 в block-space, курс переделан на drop-3 (8ec354e)
- [x] **Итог: оба курса PASS** (A за 6с, B за 6с, health 20 — без урона).
  Стенд остаётся поднятым на маке (noVNC http://192.168.1.20:5820,
  повторный запуск: `sh deploy/autotest.sh [--no-build]`)

## Autotesting — дизайн пайплайна автодеплой/автотест (2026-07-20)

### Investigate

- mineswarm (`../mineswarm`): headless MC-**клиенты** в Docker (PortableMC → Fabric 1.21.11,
  llvmpipe, noVNC), py4j baked-in; мод деплоится копией jar в `game/minecraft/mods/` + restart.
  Gateway ходит к py4j через `docker exec` (py4j слушает loopback внутри контейнера).
- Мак (mactrindetz, M4 Max/48GB): Docker Desktop есть, mineswarm мини-стек уже крутится
  (`docker-compose.mac.yml`, `mc-crossentropy` как linux/amd64 под Rosetta), клоны
  unionclef/mineswarm лежат в `~/repos/pet`, Java 21 установлена.
- В unionclef уже есть: `Py4jEntryPoint` (~100 методов), скелет e2e-теста
  `scripts/custom/example_server_test.py`, autoConnectServer, мульти-версии (replaymod
  preprocessor), ClefForge docker-сборка.
- Прецеденты: agicraftmc (RCON тест-сервер + push-автодеплой), nettyan-toolkit
  (self-hosted runner деплой).

### Plan

- [x] Написать `docs/AUTOTESTING.md`: архитектура (тест-сервер + N клиентов на образе
  mineswarm-mc + python-раннер), раскладка `deploy/`, сценарии (@goto, ;goto паркур,
  ;followPlayer, #goto bridge, nightly @gamer), триггер (self-hosted GH runner на маке),
  фазы 0–3 с оценками, риски (Rosetta/llvmpipe FPS → флаки, arm64-образ как фикс).

### Implement

- [x] `docs/AUTOTESTING.md` — только дизайн, код пайплайна не писался (фазы — отдельные TODO).

## Shredder — pathfinder v2 (baritone + tungsten)

### Investigate

- Изучена структура baritone: 341 Java-файл, 75 пакетов
- API surface: 158 файлов в `baritone/api/`, altoclef делает 144 импорта из baritone
- Ключевые точки входа: `BaritoneAPI.java`, `IBaritone.java`, `Settings.java`
- Baritone подключается как Gradle subproject через `namedElements` configuration
- Mixins: 19 клиентских миксинов в `baritone.launch.mixins`
- Внешние зависимости: nether-pathfinder, mixin, jsr305

### Plan

- [x] Выбрать имя → **Shredder**
- [x] Скопировать baritone → shredder (пакеты `baritone.*` сохранены)
- [x] Настроить metadata: build.gradle, fabric.mod.json, mixins.shredder.json
- [x] Зарегистрировать в settings.gradle.kts и build.gradle
- [x] TODO 2.3: Переключить altoclef с `:baritone` на `:shredder`
- [ ] TODO 2.4: Реализовать windMouse / AI smooth camera movement
- [x] TODO 2.5: WindMouse + интеграция tungsten в shredder

### Implement

- [x] Скопирован baritone → shredder/ (341 файлов, пакеты `baritone.*` оставлены как есть)
- [x] shredder/build.gradle — archivesBaseName "shredder", version 0.1.0, group "shredder"
- [x] fabric.mod.json — id "shredder", name "Shredder", автор "3ndetz", GPL-3.0
- [x] mixins.baritone.json → mixins.shredder.json (содержимое без изменений)
- [x] BaritoneMixinConnector → ссылается на `mixins.shredder.json`
- [x] settings.gradle.kts — добавлен `include(":shredder")`
- [x] build.gradle — заменено `:baritone` → `:shredder`, baritone dep убрана
- [x] Altoclef импорты не изменены — `import baritone.*` работает, т.к. shredder экспортирует те же пакеты

#### TODO 2.5 — WindMouse + Tungsten integration

##### 2.5.1 WindMouse в LookBehavior (render-frame camera smoothing)

- [x] Заменён exponential-decay `updateSmoothRotation()` на WindMouse алгоритм в `LookBehavior.java`
  - WindMouse physics: gravity (pull к target), wind (random perturbation), velocity clamping
  - Dual-mode: `windMouseLook=true` → WindMouse, `false` → старый exp-decay (fallback)
  - Frame-time scaling: корректное поведение при любом FPS (не привязано к 60)
  - Human-like flick: maxStep масштабируется с distance (далёкие углы → быстрый flick)
  - Snap threshold: при <0.3° до цели — snap, сброс velocity
- [x] Добавлены настройки в `Settings.java`:
  - `windMouseLook` (Boolean, default true) — включить WindMouse
  - `windMouseGravity` (Double, default 3.5) — сила притяжения к target
  - `windMouseWind` (Double, default 1.2) — амплитуда random wind
  - `windMouseMaxStep` (Double, default 5.0) — макс. градусов за frame
- [x] Server-side rotation integrity: game tick по-прежнему использует `peekRotation()` (mouse quantization + random jitter) → server packets не затронуты
- [x] WindMouse state reset при: smoothActive activation, onWorldEvent, cancel

##### 2.5.2 TungstenBridge — delegation shredder → tungsten

- [x] Создан `baritone.tungsten.TungstenBridge` — координатор между shredder и tungsten
  - State machine: INACTIVE → PATHFINDING → EXECUTING → RETURNING
  - Smart segment evaluator: проверяет ≥N consecutive flat MovementTraverse/Diagonal без break/place
  - Delegation: запускает tungsten PathFinder с коротким timeout (3s), мониторит executor
  - Stall detection: abort если нет прогресса >60 тиков (3 сек)
  - Arrival detection: abort если player в пределах 1.5 блоков от target
  - Callback-based completion: executor.cb → RETURNING state
- [x] Добавлены настройки в `Settings.java`:
  - `useTungsten` (Boolean, default false) — включить делегирование tungsten
  - `tungstenMinSegment` (Integer, default 8) — минимум простых движений для делегирования
- [x] Wired into `PathExecutor.onTick()`:
  - Bridge tick ПЕРЕД movement.update() — если tungsten active, shredder yields (clearKeys)
  - Segment evaluation каждый тик когда bridge inactive и не sprint-jumping
  - pathPosition snap forward к resume point после tungsten completion
  - Bridge reset в cancel()
- [x] Build dependency: `shredder/build.gradle` → `implementation project(":tungsten", "namedElements")`

#### TODO 2.7 — Fix Jump Bridging

##### Investigate

- Текущая реализация в `PathExecutor.java` (строки 885-1050): двухфазная state machine (SPRINT → AIRBORNE)
- **Root cause 1 — rotation/objectMouseOver timing**: listener order в Baritone: LookBehavior(1st) → PathingBehavior(2nd) → InputOverrideHandler(4th). `tickJumpBridge` ставит rotation target + CLICK_RIGHT в onTick. Затем `blockPlaceHelper.tick()` обрабатывает клик с `objectMouseOver` от **предыдущего** render frame (ещё forward-looking). Rotation применяется только в `onPlayerUpdate(PRE)` — ПОСЛЕ обработки клика. Результат: клик уходит в пустоту.
- **Root cause 2 — no placement verification**: строка 1035 безусловно продвигает `jumpBridgeLastSolid` после клика, без проверки что блок реально поставлен. Все последующие клики targeting несуществующие блоки.
- **Root cause 3 — 180° mid-air rotation**: SPRINT фаза смотрит forward, AIRBORNE пытается развернуться на 180° за 1-2 тика. WindMouse smoothing (3.5°/frame max) делает это невозможным за время полёта. Даже с `blockInteract=true` snap, objectMouseOver обновляется только на следующем render frame.
- BlockPlaceHelper: `rightClickSpeed=4` → cooldown 3 тика между кликами. За 12 тиков airborne = max 3-4 клика.

##### Plan

- [x] Диагностика: 3 root causes (timing, verification, rotation)
- [ ] Новая state machine: `SPRINT → PRE_ROTATE → BRIDGE`
  - SPRINT: sprint к краю (как сейчас), transition to PRE_ROTATE вместо прыжка
  - PRE_ROTATE: повернуться на 180° backward ПЕРЕД прыжком (стоя на земле)
  - BRIDGE: ходить назад (MOVE_BACK = forward в мире), прыгать с края, ставить блоки в полёте
- [ ] Placement verification: проверять `canWalkOn` перед продвижением lastSolid
- [ ] Continuous bridging: после приземления оставаться в BRIDGE (не сбрасывать в NONE)

##### Implement

- [x] Новая state machine в `PathExecutor.java`:
  - `SPRINT → PRE_ROTATE → BRIDGE` (вместо `SPRINT → AIRBORNE`)
  - SPRINT: sprint к краю, transition to PRE_ROTATE при `distToDest < 1.0`
  - PRE_ROTATE: sneak на краю + rotate backward (yaw+180°, pitch 75°), ждём `yawDiff < 20°`
  - BRIDGE (on ground): MOVE_BACK (= forward в мире), JUMP при `distToEdge < 0.9`
  - BRIDGE (airborne): MOVE_BACK для momentum, track face center, click + verify
- [x] Placement verification:
  - `canWalkOn(bsi, expectedPlace)` проверяет что блок реально появился в мире
  - lastSolid продвигается ТОЛЬКО после подтверждения placement
  - Retry click каждый тик до подтверждения (вместо optimistic advance)
- [x] Continuous bridging:
  - BRIDGE фаза НЕ сбрасывается при landing
  - На земле: snap pathPosition, проверка nextMove, re-select throwaway, walk + jump
  - Между прыжками: бот остаётся backward-facing → нет 180° rotation mid-air
- [x] Removed unused `jumpBridgeRandom` field
- [x] Added `jumpBridgeAirborne` + `jumpBridgeAirborneTicks` sub-state tracking
- [x] Added `wrapDegrees()` helper for rotation comparison

##### Rewrite — Sprint-Speed Telly Bridge (2025-03-20)

Complete rewrite of the jump bridge state machine. Key breakthroughs:

- [x] **TestBridgingCommand GoalBlock fix**: GoalXZ → GoalBlock at player Y level (prevents pathfinder descending to ground)
- [x] **processRightClickBlock bypass**: objectMouseOver raycast misses at 86°+ pitch. Direct `ctx.playerController().processRightClickBlock()` with calculated BlockHitResult bypasses crosshair entirely.
- [x] **setSprinting(true) force**: `Input.SPRINT` override alone doesn't re-trigger sprint. `ctx.player().setSprinting(true)` forces sprint at entity level.
- [x] **5-phase telly cycle**:
  1. FJ_SPRINT: face forward, W+Sprint, jump at edge (setSprinting on ground)
  2. FJ_AIRBORNE (placement): face backward (dynamic aim), no movement keys (pure inertia)
  3. FJ_AIRBORNE (recovery): snap forward + W+Sprint when nearing ground
  4. Landing: face forward, W+Sprint, setSprinting(true) → sprint preserved
  5. Continuous cycle back to FJ_SPRINT
- [x] **Y-level safety**: exits immediately if player drops 0.8 blocks below bridge
- [x] **Sneak on path end**: sneaks when jumpBridgeCanContinue fails
- [x] **bridgeCount ≥ 6**: prevents overshoot near goal, slow bridge handles last 5 blocks
- [x] **Cooldown 20 ticks**: fast re-activation after path transitions
- [x] **Scan-ahead 15**: finds longer consecutive bridge segments

Result: sprint=true on every jump, 2-3 blocks/jump, 30+ blocks without falling.

##### Optimizations

- [x] Debug logging behind `JB_DEBUG` flag (default false)
- [x] itemUseCooldown reset via reflection before each processRightClickBlock
- [x] Lateral drift correction in FJ_SPRINT (sign was inverted, fixed)
- [x] bridgeCount threshold tuned (4 minimum, scan-ahead 15)
- [x] Cooldown reduced to 10 ticks for faster re-activation between path segments
- [x] Graceful exit when <3 bridge moves remain (prevents overshoot at path end)
- [x] Dead FJ_LAND/FJ_BACKUP phases removed (continuous telly doesn't stop)

###### Remaining

- [ ] itemUseCooldown: replace reflection with @Accessor mixin
- [ ] Pre-sprint during slow bridge runway (first jump is walk-speed)
- [ ] A/D strafing during camera flick
- [ ] Anticheat-friendly rotation (WindMouse for the backward flick)
