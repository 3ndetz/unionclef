# Progress

## DROP-IN SWAP baritone→tungsten + КОРЕНЬ headless-затыка (2026-07-21) — СДЕЛАНО

Юзер: тщательно закрыть drop-in замену baritone на tungsten. Оказалось — глубокий
pre-existing баг, не связанный с tungsten.
- setTungstenPathing(true)/pathingMode (py4j+MCP): tungsten-primary. При нём
  CustomBaritoneGoalTask.driveTungstenPrimary зовёт tungsten PATHFINDER.find
  напрямую (как ;goto — clear EXECUTOR.stop + find, async). Хук в GetToBlockTask.
  onTick ДО wander. TungstenHelper.setPrimary флаг (volatile). NB: TungstenHelper-
  рефлексия была МЁРТВА (искала searchTimeoutMs на PathFinder, а он на
  TungstenConfig) — обошёл прямым вызовом (altoclef зависит от tungsten).
- КОРЕНЬ, почему @goto/@gamer НЕ двигались на стенде (диагностика [trtick]-логом
  побеждающего чейна): **MobDefenseChain выигрывал КАЖДЫЙ тик prio 70** — на
  PEACEFUL закомmenчена peaceful-проверка, залипала ложная run-away → UserTaskChain
  (навигация) НИКОГДА не тикался. Восстановил peaceful-шорткат. Это глушило и
  baritone, и tungsten (баритон был НЕ мёртв, а заблокирован). Плюс UnstuckChain+
  WorldSurvivalChain shimmy тоже преемптили — defer при primary.
- Побочно найден баг МОЕЙ диагностики: ConnectToServer в игре → altoclef DISCONNECT
  CHAIN (self-inflicted вылеты клиента). Тесты коннектятся только если не в игре.
- Тест swap_test PASS: @goto доходит (dist 0.6) и с tungsten-primary, и baritone.

## Baritone-совместимость: приваты + multi-target комбат (2026-07-21) — СДЕЛАНО

Ответ на большой блок целей юзера (полная совместимость tungsten с baritone-
ограничениями + рычаги агенту).
- PLACE-защита (симметрично BREAK): PlaceRules.canPlace → canPlaceHook →
  altoclef shouldAvoidPlacingAt. Консультируется в placeBlockAtRaw (весь
  WorldEdit/build) и BridgeTask (годбридж стопается на приватах). Config
  allowPlace/placeDenyZones. Приват как РЫЧАГ: markProtectedArea(x,y,z,r)/
  clearProtectedAreas кладёт в ОБА deny-списка (place+break) — конвенция «claim».
  Предикты агенту: canPlaceBlock/canBreakBlock (py4j+MCP). Тест protect_test
  PASS: и place, и break внутри приват-зоны запрещены, снаружи ок, clear снимает.
- PLACE_PLAN виз (renderPlacePlan): годбридж рисует «сюда поставим» зелёным.
- Multi-target комбат: PunkPlayerTask.startAny(allow, avoid) — ближайшая
  допустимая цель, авто-ретаргет; punk/punkAny/punkAvoid/punkStop/punkStatus
  (py4j+MCP). Мозг решает кого бить, tungsten исполняет. Тест multitarget_test
  PASS: avoid→нет цели, allow→цель взята, stop→сброс.
- Записаны/обновлены цели 12-18. Осталось крупное: 13 drop-in замена baritone,
  15 дальние маршруты (receding-horizon), 18 tungsten_speedrun/@gamer.

## MCP-сервер В МОДЕ по LAN (2026-07-21) — СДЕЛАНО

Юзер: «mcp не сможешь прямиком в java клиенте по порту и пробросить на LAN?» — да.
- McpServer.java: `com.sun.net.httpserver` (JDK, без зависимостей) bind 0.0.0.0:
  mcpPort. Streamable HTTP JSON-RPC 2.0: initialize/tools/list/tools/call/ping.
  24 инструмента-рычага поверх Py4jEntryPoint (single source — те же методы, без
  py4j-хопа и docker-exec). Каждый с описанием + JSON-схемой.
- Настройки Settings.mcpEnabled(true)/mcpPort(25350). Старт после py4j-шлюза в
  initializePythonSender. compose публикует 25350 (LAN).
- Тест mcp_test PASS: initialize→serverInfo unionclef, tools/list=24, getGameState
  (чтение) inGame=true, fillSelection (ДЕЙСТВИЕ) через HTTP → 4/4 dirt. Клод рулит
  ботом по http://<lan-ip>:25350/mcp — заявленный control-surface живой.
- Записаны новые МЕГА-ЦЕЛИ 2 (TODOS 12-17): drop-in замена baritone, полная
  break/place-совместимость + приват-детект, дальние маршруты, viz планов,
  multi-target комбат.

## Интеграция: цикл агента see→move→build (2026-07-21) — СДЕЛАНО

Капстоун-валидация всего рабочего места. agent_loop_test гоняет РОВНО bedwars-
микросценарий только через рычаги агента:
1. getGameState → нашёл кровать (17,-60,16, dist 20.5) из позиции бота (2.5,2.5)
2. gotoXYZ → дошёл (2.5→15.5, arrived dist 0.0), поллинг pathStatus
3. buildDefenseAround → застроил, shell 4/4, placed 7
PASS. `remaining=['16,-59,16','17,-58,16']`, `complete=false` — верхние клетки вне
reach → корректное «агент репозиционируется и зовёт снова». buildDefense placed=7
(не раздутые 88 из старой заметки — в этом сценарии счётчик ок). Композиция
перцепция→движение→строительство работает как единое целое, а не по-отдельности.

## Movement-рычаг: gotoXYZ + pathStatus + stopPathing (2026-07-21) — СДЕЛАНО

Keystone когнитивного агента — связка perception (getGameState) → action.
- gotoXYZ(x,y,z): навигация к координате через ТUNGSTEN-пасфайндер (ChatMessage
  с КОНФИГУРИРУЕМЫМ префиксом TungstenMod.getCommandPrefix(), не хардкод ';').
  Fire-and-poll. Им же агент репозиционируется для дальних fillSelection-клеток.
- pathStatus(): busy(hasActiveTask)/pos/distance-to-goal/arrived(<1.5). Агент
  крутит gotoXYZ→pathStatus до arrived, затем действует.
- stopPathing(): тотальный стоп — и ;stop (tungsten), и @stop (altoclef).
- ДИАГНОЗ на стенде: сначала роутил через @goto (baritone/shredder) — цель
  ставилась (busy=true), но бот НЕ двигался headless (pos застыл 70с). tungsten
  ;goto повёл чисто (1.5→18.5, dist 0.6). Перевёл на tungsten (и по цели проекта
  «единый tungsten-пасфайндер»). Тест goto_test PASS (dist 1.5, arrived).
- retreat/chase намеренно НЕ примитивы — агент композит из goto+getGameState
  (философия block 6: агент решает стратегию, мод исполняет).

## WorldEdit-like //set: select + fillSelection (2026-07-21) — СДЕЛАНО

Рычаги для агента (block 9), НЕ команды сервера — чистые координаты, работает в
выживании через реальную установку блоков (physics-примитив placeBlockAtRaw).
- select(x1,y1,z1,x2,y2,z2): хранит регион (_selMin/_selMax), рендерит жёлтую
  подсветку (SELECTION-контейнер, гейтится renderVisualization), возвращает
  min/max/volume. clearSelection() чистит.
- fillSelection(block): //set — ставит блок в каждую replaceable-клетку выделения
  В ДОСЯГАЕМОСТИ, снизу вверх (каждой клетке есть опора: пол или ранее
  поставленный блок). Кап 96 установок/вызов (truncated-флаг) чтобы не морозить
  рендер-тред; возвращает filled/remaining/already/complete → агент
  репозиционируется (tungsten goto) и зовёт снова для дальних клеток. Философия:
  примитив исполняет, агент оркестрирует достижимость.
- БАГ + ФИКС: fillSelection крутился внутри onClientThread и звал placeBlockAt,
  который ОБorачивал onClientThread снова → вложенность ДЕДЛОЧИТ рендер-тред
  (client-thread timeout, 0 клеток). Вынес ядро в placeBlockAtRaw (assume
  on-thread, single source); placeBlockAt = обёртка, fillSelection зовёт Raw
  напрямую. Тест worldedit_test PASS (4/4 dirt, complete=true).
- //walls: wallsSelection(block) — 4 вертикальные стены (x/z==min/max), пол/
  потолок/центр открыты; плоский слой → кольцо. Общее ядро fillCells(predicate)
  (//set=все, //walls=границы) — без дублей.
- ЧЕСТНЫЙ blockName: equipHotbarBlock экипирует названный блок из хотбара (id-
  матч с/без "minecraft:") — агент называет блок, мод его держит; не найден →
  placeBlockAtRaw авто-выбирает любой. Тест: //set cobblestone держа dirt → 4/4
  cobblestone (доказан equip), //walls → кольцо 8/8 + центр air (полый).
- Осталось в block 9: //replace (нужен синхронный break-примитив),
  //hollow/cyl/sphere (генераторы позиций поверх fillCells).

## Восприятие когнитивного агента + виз-тумблеры (2026-07-21) — СДЕЛАНО

- getGameState() py4j: self(hp/maxHp/armor/pos/onGround/held/blocks) + players[]
  (name/pos/distance/hp/sprinting, сорт по дистанции) + beds[] (детект кроватей
  в радиусе 40 для bedwars — куда атаковать/защищать). Тест gamestate_test PASS.
  Read-only, ядро не трогает — база для когнитивного агента (block 6).
- canReach захардил: ретрай block-space поиска до 4х, берём маршрут что дошёл до
  цели (флак частичных стабов лечится) — F_api снова PASS reached=true/breaks=2.
- Виз-тумблеры: renderVisualization (мастер) + renderPathMoves/renderBreakPlan/
  renderCombat (;settings), подсветка клеток бриджа. Регрессия slime/bridge/break
  PASS — рендер-гейтинг не сломал пасфайндинг.
- Решение: глубокая A*-авто-интеграция бриджа ОТЛОЖЕНА (риск ядра) — годбридж есть
  как примитив, агент сам решает когда мостить (философия block 6). Референс на
  будущее: baritone MovementTraverse:122-168.

## Анти-чит гуманизация поворотов (2026-07-21) — СДЕЛАНО

Поинт юзера: античиты палят прямой setYaw/setPitch — крутить надо через mouse-
pipeline «как физическая мышь» (в боевой ауре WindMouseRotation уже настроено).
Перевёл ВСЕ примитивы: BridgeTask+BowShooter+майнинг-прицел → WindMouse (тикаются,
сходятся человеко-подобно через ванильный mouse pipeline); placeBlockAt →
changeLookDirection (одношот, пиксельно-квантованно). Path-replay уже был на
changeLookDirection. Тесты PASS: bridge с гуманизацией (естественный z-разброс),
break+place регрессия зелёная. clearTarget при завершении майнинга (иначе
конфликт с path-replay). Осталось: тоньше humanize (пауза «поднять мышь»), живой
тест против анти-чита в bedwars.

ПРИНЦИП (юзер): рядом исходник baritone — для A*-интеграции/схематик/WorldEdit
смотреть их MovementParkourPlace/BuilderProcess/MovementHelper, внедрять
проверенное, не повторять ошибок.

## ГОДБРИДЖ (2026-07-21) — СДЕЛАНО

Переписал бридж на НЕПРЕРЫВНУЮ pave-ahead модель (по идее юзера «физика
просчитывает ходы чтобы эффективно ставить блоки»): БЕЗ sneak, sprint вперёд +
мостим до 2 клеток пола вперёд КАЖДЫЙ тик. Целевая клетка на уровне пола →
плоское расширение, падать неоткуда; физика точно знает позицию → кладём блок
до того как нога дойдёт до края. Тест PASS: ровно N=5 блоков на sprint-скорости,
не упал, gap 5/5. Первый прогон без стоп-условия промостил 138 блоков подряд.
Остановка по дистанции (advanced>=N). Сломанная sneak+step-phase версия
(падала на sneak-edge-hold) выброшена целиком.

ГОТЧА (подтверждена): инкрементальная сборка НЕ перекомпилирует tungsten-правки
→ для tungsten ВСЕГДА ./gradlew clean build.

## Sneak-бридж (2026-07-21) — предыстория (сломанная версия, до годбриджа)

- BridgeTask: машина состояний PLACE (стоя ставит блок вперёд) → STEP (шаг на
  него) → повтор, sneak всегда. Триггер py4j bridgeForward.
- Путь фиксов: геометрия (ofFloored(y-0.1) — это опора, не .down() — двойной
  .down() ставил в пустоту, placed=0) → машина состояний → kill horizontal
  velocity в PLACE. Результат: 1 блок СТАВИТСЯ + бот ШАГАЕТ (advanced 1.8), но
  на 2-м блоке инерция+sneak-edge сносит за дальний край в пропасть.
- Чистая сборка (clean build) дала ПОБИТОВО тот же результат → стейлности нет,
  momentum-kill скомпилирован но ЭФФЕКТА НЕТ. ТОЧНЫЙ БЛОКЕР: sneak НЕ удерживает
  край в агентском контексте (options.sneakKey.setPressed не даёт isSneaking()-
  edge-protection на реальном ClientPlayerEntity) → бот падает с 2-го блока;
  гашение скорости в PLACE запаздывает (снос в STEP). Нужна фокус-сессия:
  position-based edge-clamp в задаче (стоп forward ДО края, не полагаясь на
  sneak) ИЛИ переиспользовать shredder jump-bridge (там backward-бридж решён).
- Остальные примитивы установки (placeBlockAt/defense) — PASS. bridge_test готов.
- ГОТЧА ДЛЯ БУДУЩЕГО: инкрементальная сборка иногда НЕ перекомпилирует изменения
  tungsten-субпроекта → для tungsten-правок надёжнее ./gradlew clean build.

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

---

## 2026-07-22 — Combat void-safety + runAwayPlayer flee (v0.28.0 → v0.29.0)

**Investigate.** User: PVP bot on bedwars falls into the void "constantly" + rarely
hits. Built `deploy/runner/bedwars_combat_test.py` (void islands: flat 13×13, and
two 5×5 + a 1-wide bridge; scoreboard kills/deaths + botY falls). Baseline (bridge
solo): 2–5 self-falls/min. Added a temp in-combat telemetry log — the falls came
from the **combat stage machine**, NOT pursue movement: `DANGER_BATTLE` repositioning
sprinted and `DANGER_IMMINENT` braking JUMPED; next to a rim that brake-jump launched
the bot off. Reactive edge-check (fixed 1.35 lookahead) was overshot by sprint
momentum. Residual post-kill falls traced further to the **punk APPROACH executor**
(re-closing on the respawned victim) which had no void clamp at all.

**Implement (tungsten).**
- `VoidDetector.edgeAhead(...,maxDist)` (speed-scaled) + `voidWithin(radius)`.
- `VoidGuard` — shared final movement clamp: near a rim never sprint; when heading
  (keys OR momentum) points at a drop never jump toward it (longer jump lookahead)
  and plant with vanilla sneak; cancel the drive only when actively steering off
  (keep knockback recovery). Used by SafetySystem (combat) AND after the pathfinder
  executor tick while punk/flee active.
- `PunkPlayerTask`: don't chase a target into the void; release drive keys the instant
  the target dies (kill-moment coast); universal tick sneak-guard.
- Faster aim (WindMouse gravity 2→3.2, maxStep 4→7).
- `RunAwayTask` + `;runAwayPlayer`/py4j/MCP: flee a player to the safest INTERIOR
  point away, void-safe, keeps distance. Mirror of punk, mutually exclusive.

**Result.** Bridge-solo ×3 back-to-back 80s: **0 self-falls each** (kills 1/7/6).
Self-inflicted void fall eliminated. Flee keeps ~8 blocks (avg 8.0), own movement
never self-falls. Mutual PvP still trades knockback-falls (airborne over void —
positioning is future work). Nav regression (swap_test) PASS. Released v0.28.0
(combat) and v0.29.0 (approach-guard completion + flee).

### 2026-07-22 (доп) — @gamer проверка: корень НЕ «нет Movements», а ДРЕЙФ executor'а

Юзер спросил «gamer работает?». Проверил на survival-стенде (seed 12345, спавн на
горе y=148). @gamer стартует, срубает лог, ЕДЕТ (спуск 148→143), затем ползёт/встаёт.
Детерминированный terrain_test поймал точную причину в чистом чате (после гашения
Searchin-спама): **executor drift-abort**. Пасфайндер НАХОДИТ путь (size 133), но
физ-реплей (Agent) расходится с реальностью на рельефе на 5+ блоков → при
drift>driftThreshold (стенд 5.0) `EXECUTOR.stop` (Agent.java:1613). Каждые ~30-90
тиков abort → re-search (пауза) → рывок. Не MobDefense, не punk-утечка, НЕ регрессия
combat-работы (VoidGuard гейтится на punk/flee). #20 развёрнут на реальную причину;
фикс-кандидат №1 — drift-толерантный BlockPathWalker вместо жёсткого стопа. Отдельная
фокус-задача.

### 2026-07-22 (доп2) — @gamer terrain-затык ИСПРАВЛЕН (v0.30.0/v0.30.1)

Корень (уточнён от «нет Movements»): ДРЕЙФ физ-executor'а. Sim расходится с реальной
позицией на ступенях/склонах → drift>threshold hard-stop; плюс поиск отвергает свой
путь (`PathFinder:870` «root far from player» >2 бл) → пасфайндер вечно busy → стоп.
Фикс (директива юзера — робастный tungsten block-путь + drift-иммунное физ-следование,
БЕЗ импорта baritone): altoclef `driveTungstenPrimary` для рельефа ведёт `BlockPathWalker`
(спринт от РЕАЛЬНОЙ позиции по block-пути, прыжки на ступени → без sim → без дрейфа).
Источник пути: cheap `CombatPathfinder` grid BFS (чистый/близкий рельеф) → иначе
робастный elevation-aware путь из async-поиска (`PathFinder.getComputedBlockPath`).
Executor только на финал <=4 бл + вода/паркур. Walker форс-стопит дрейфующий пасфайндер.
Анти-стак-сеть (v0.30.1): 5с без движения → сброс tungsten-состояния (re-plan от факта),
после 3 сбросов → yield на wander (ломает ловушки/stale-rooted-петли).

ВАЖНО (методология): terrain_test сначала бил `gotoXYZ` = tungsten-`;goto` (минует
driveTungstenPrimary!). @gamer идёт через altoclef `@goto/@get` → driveTungstenPrimary.
Исправлено на `@goto`.

Валидация: swap PASS, 12-ступенчатая лесенка @goto доходит доверха drift-free; на РЕАЛЬНОЙ
горе (seed 12345) бот прошёл ~40-100 бл естественного рельефа, спустился, срубил ель/дуб
(held spruce_log/dark_oak_log), hp 20, 0 падений — раньше стоял намертво. Остаётся: паркур
(прыжки-гэпы/2-блочная стена), выживание против мобов (easy — еда/комбат/шелтер), редкие
локальные ловушки (анти-стак смягчает). Speed-pipeline идея юзера — TODO #32.

---

## 2026-07-22 — BUG #29 CRITICAL (frozen camera / hard-stuck aim) — FIXED v0.39.0

**Investigate.** Repro'd the class of the bug, not a one-off: `WindMouseRotation.INSTANCE`
is a static singleton; `applyRenderStep` (called every render frame from
`MixinInGameHud`) steers the mouse toward the stored `(targetYaw,targetPitch)` forever
while `hasTarget`. Every consumer (executor break `tickBreaking`, combat, walker, bow,
bridge, pillar) calls `setTarget` each tick but clears only on its own clean exit. A task
that set a mine/combat aim and then DIED (occluded mine, abrupt combat end, force-stop)
left `hasTarget=true` with no one to clear it → camera locked forever. Static → survived
reconnect (the reported "still frozen after rejoining"). Movement-phase aim uses
`applyNativeRotation` (direct changeLookDirection), so only these task aims can freeze.

**Plan/Implement (durable, no band-aid).**
1. Stale-aim auto-release: `setTarget` stamps `lastRefreshMs`; `applyRenderStep`
   `clearTarget()`s if `now-lastRefreshMs > STALE_MS` (600ms). Live consumers refresh
   every game tick (~50ms) so an active aim is untouched; a dead task's aim clears in
   ~0.6s. No task can leave the camera frozen.
2. `ClientPlayConnectionEvents.DISCONNECT` → `TungstenMod.resetAllState()` wipes aim,
   all tasks (walker/bridge/pillar/punk/runaway/bow), in-progress break, held keys, and
   pf/ex — nothing tungsten survives a re-join.
3. `PathExecutor` stop-branch releases `attackKey` + `clearTarget()` immediately (not on
   the 300-tick timeout) when force-stopped mid-mine.

**Test (all PASS on the 0.39.0 release jar).** `stale_aim_test` (poke a one-shot aim →
auto-releases within 2s), `disconnect_test` (a running punk task cleared after forced
reconnect), `break_test` C_wall/D_sand/E_tool/F_api (mining unaffected — tickBreaking
refreshes setTarget every tick so the aim never expires mid-mine). Bot needs ~90-120s to
settle in-game after a container restart before tests are reliable.

**Levers added:** py4j `pokeStaleAim(dyaw)` / `windMouseHasTarget()` (test the expiry).

Next: block-space move-generation cluster (#28 ran-out-of-nodes, #30 unreal routes into
walls, #31 break-through not completing) — all root in the legacy blind r=8 neighbor gen;
the fix is hardening the flag-gated `SmartMoves` into a robust default.
