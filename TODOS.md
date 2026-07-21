# TODOs

## КОГНИТИВНЫЙ АГЕНТ В PVP-ИГРАХ (bedwars и др.) — крупная задача юзера 2026-07-21

Философия (дословно): НЕ скриптовать BedWars, а сделать РЕЖИМ/поверхность, где
когнитивному агенту (Клод по py4j/MCP) УДОБНО играть — он сам выбирает тактику
(застроить кровать, что купить в магазе, когда переть/отступать), а ИГРА/МОД
ПОМОГАЕТ механикой в жёстких битвах (прицел, траектории, пасфайндинг, установка
блоков). Критерий успеха: «чтобы МНЕ САМОМУ (Клоду) было удобно играть и побеждать
за агента». Не всё скриптовать — агент решает, мод исполняет.

- [ ] 6. Поверхность управления PVP для когнитивного агента:
  - [ ] 6.1 ВОСПРИЯТИЕ через py4j/MCP: компактный game-state (враги+позиции+HP+расстояние, своя/чужая кровать, ресурсы, магазин-меню читаемо, таймеры) — чтобы агент «видел» бой без пиксельного скриншота
  - [ ] 6.2 ТАКТИЧЕСКИЕ ПРИМИТИВЫ (мод исполняет, агент командует): attack/aim/shoot(с траекторией)/shield/retreat/chase/goto/placeBlock/buildDefense(застроить кровать)/mineTo — переиспользовать CombatPrimitives + tungsten
  - [ ] 6.3 МАГАЗИН: читать меню магазина (getOpenScreen), покупать по имени (clickUiSlot) — агент решает ЧТО купить, примитив «buy(itemName)» исполняет
  - [ ] 6.4 ЗАСТРОЙКА КРОВАТИ: примитив buildBedDefense — обложить свою кровать блоками (шерсть/дерево/обсидиан из инвентаря), tungsten ставит блоки; агент выбирает материал/паттерн
  - [ ] 6.5 «Игра помогает в жёстких битвах»: авто-ассист прицела/блока щитом/крит-тайминга при активном бою, но СТРАТЕГИЮ (куда идти, кого бить, когда отступать) держит агент
  - [ ] 6.6 BedWarsTask: НЕ выкидывать, но переосмыслить — сделать вариант/режим `@game bedwars cognitive` (или флаг), где вместо скрипта включается tungsten-нападение (;punkPlayer smart) + агент рулит через py4j/MCP. Починить приколы старого таска (устарел)
  - [ ] 6.7 умная стрельба из лука в бою (TrajectorySolver уже есть) — вплести в bedwars-нападение
  - [ ] 6.8 итог: MCP-инструменты (см. блок 5) = именно этот control-surface, с описаниями чтобы агент понимал что делает каждый

## Управляющий интерфейс (py4j + MCP) — задача юзера 2026-07-20

- [ ] 4. Полный слой ввода мыши в py4j (инкапсулированно, БЕЗ дублей):
  - [x] 4.1 interactCrosshairEntity() — right-click сущности под прицелом (рамки/меню). NB: на headless interactionManager через onClientThread виден как «not in game» — надёжнее key-путь (useKey), см. 4.2
  - [ ] 4.2 mouseClick(button) — left/right/middle одним параметризованным методом (переиспользовать InputControls.tryPress CLICK_LEFT/RIGHT; middle = pickItemKey). Мировой клик через КЛАВИШУ (работает headless, в отличие от interactionManager-обёртки)
  - [ ] 4.3 screenClickAt(x,y,button) — клик по ЭКРАННЫМ координатам для GUI-меню (open Screen.mouseClicked/Released); инвентарные слоты уже есть (clickUiSlot) — не дублировать
  - [ ] 4.4 разобраться, почему interactionManager==null в onClientThread-лямбде (player не null: lookAt работает) — либо чинить, либо задокументировать и везде идти через key-путь
- [ ] 5. MCP-сервер к моду (юзер хочет подрубать Клода напрямую по MCP):
  - [ ] 5.1 тонкий адаптер поверх СУЩЕСТВУЮЩЕГО py4j (один источник правды — методы Py4jEntryPoint; MCP их оборачивает, НЕ дублирует логику)
  - [ ] 5.2 каждый инструмент с описанием/промптом и JSON-схемой (аннотации на методах либо манифест, чтобы не дублировать сигнатуры)
  - [ ] 5.3 py4j остаётся «ради прикола»/для тестов-раннеров; MCP — основной путь для интерактивного Клода
  - [ ] 5.4 транспорт: MCP-сервер (Node/Python) в контейнере рядом с клиентом, ходит к py4j 25333; или мод хостит SSE-эндпоинт напрямую
  - принцип: максимум инкапсуляции и переиспользования, минимум дублей

- [x] implement Tungsten
  - [x] fixes for autoclef
  - [x] implement
- [x] Create the new merged repo
  - [x] change baritone mojmap to altoclef yarn
    - [x] fix mixins
  - [x] 1.21 runs successfully and working
- [ ] 1. Create a new pathfinder: combination of baritone and Tungsten
  - [x] 1.1 Find a suitable name for the new pathfinder
    - autobots theme: Optimus, Bumblebee, Megatron, Starscream, Soundwave, Ironhide, Ratchet, Jazz, Grimlock, Shockwave?
    - ninja turtle theme: Leonardo, Michelangelo, Donatello, Raphael?
    - Solved: "shredder"
  - [x] 1.2 Copy the codebase of baritone
  - [x] 1.3 Implement into project and replace altoclef's baritone calls with shredder
  - [ ] 1.4 Improve baritone features in shredder
    - [x] Fix stupid debug spam and spam "failed"
    - [x] 1.4.1 Implement ACCELERATION for simple safe paths
      - [x] 1.4.1.1 Implement acceleration for straight line running to run and jump when applicable
      - [ ] 1.4.1.2 Implement diagonal moving acceleration and make diagonal movement instead of horizontal stairs-like movement
        - [ ] 1.4.1.2.1 remove stupid mega-multi-change view path nodes when path is clear and simple without danger and complexity
        - [ ] FAR TODO - unrealizeable. Complex. Can't do normally.
  - [x] 1.5 add safe ENTROPY: HUMAN-like movements
    - [x] 1.5.1 WindMouse camera smoothing in LookBehavior (render-frame, settings: windMouseLook/Gravity/Wind/MaxStep)
    - [x] 1.5.2 TungstenBridge — smart delegation of simple flat segments to tungsten (settings: useTungsten, tungstenMinSegment)
  - [ ] 1.6 Tungsten deep integration — improve pathfinding + reduce drift
    - [ ] 1.6.1 BlockSpace: заменить примитивный BlockSpacePathFinder на baritone-level эвристики
      - BlockNode.getChildren сейчас просто сканирует 3D круг radius=8 — тупой перебор
      - Нужно: адаптировать baritone Movements (Traverse, Ascend, Descend, Parkour, Pillar) для BlockNode
      - Это даст: знание про step-up высоты, gap distance, fence collision, slope — до запуска physics A*
      - Результат: physics A* получает 2-3 умных направления вместо 100+ слепых
    - [ ] 1.6.2 Macro-actions в physics A*: sprint-jump как одна нода вместо 12 тиков
      - Сейчас: каждый тик = нода с 100 вариантами input. 12 тиков прыжка = 12 нод
      - Нужно: "sprint-jump к blocknode X" = одна нода, внутри 12 Agent.tick() без ветвления
      - Результат: дерево A* мельче на порядок, timeout хватает на 20+ прыжков
    - [ ] 1.6.3 Simulation fixes (поштучно, с перетестом pathfinder после каждого)
      - [ ] velocity threshold 1e-5 → 0.003 (vanilla correct) + перетест
      - [ ] AgentInput.normalize → убрать, нормализация в updateVelocity + перетест
      - [ ] airStrafingSpeed 0.06 → 0.02/0.026 + перетест
      - [ ] setSprinting movementSpeed → attribute-like toggle + перетест
      - [ ] fallDistance double → float + перетест
      - Каждый фикс отдельно. Если pathfinder ломается — подстроить costs/heuristic ДО следующего фикса
    - [ ] 1.6.4 Closed-loop executor: yaw-коррекция на основе реальной позиции
      - Сейчас: open-loop, слепо воспроизводит pre-computed input
      - Нужно: каждый тик вычислять posError, корректировать yaw на delta к ожидаемой позиции
      - Результат: drift не накапливается, пути не abort'ятся
    - [ ] 1.6.5 Idle movement: circular path пока pathfinder считает
      - Генератор idle-маршрута от текущей позиции (круг/восьмёрка)
      - Seamless switch idle→real path когда pathfinder досчитал
- [ ] 2. PVP: полный аудит и переделка комбата tungsten (smart + fast + effective)
  - [x] 2.1 Аудит: почему боится ударить (чрезмерные пре-условия атаки?), низкий DPS, зависание при взгляде в траву (raycast LOS через tall grass?)
    - Итог: триггер гейтился на ванильный mc.targetedEntity (OUTLINE-пик, блокируется травой), прицел вёл с упреждением по COLLIDER; ESCAPE пол-цикла кулдауна; движение к цели выключено дефолтом. Детали: docs/ai/progress.md
  - [x] 2.2 Переделка по результатам аудита: агрессивность, точность, скорость решений
    - свой гейт (reach+COLLIDER LOS+угол+кулдаун) + прямой attackEntity; без ESCAPE-на-кулдауне; движение в бою включено + дожим последних полблока; крит-окно при падении
  - [x] 2.3 Боевой тест на стенде: PASS — первый удар 4.3с, жертва убита (20.0), 0 зависаний, бой в высокой траве (deploy/runner/pvp_test.py)
  - [ ] 2.4 Полноценный комбат-арсенал (мысли юзера, зафиксировано 2026-07-20):
    - выбор оружия по ситуации: топор/меч/лук; mace-булава с высоты; трезубец (бросок); арбалет; снежки для первой отдачи — примитивы бросков ещё не сделаны
    - расходники: эндер-пёрлы (гэп-клоуз/отступление), золотые яблоки по ХП — сторона altoclef, не начато
    - [x] щит: примитив ShieldBlocker + CombatPrimitives.shieldHold (2026-07-21) — тест shield_test PASS (0/3 урона от стрел при контроле 2/2); тайминги против топора — за мозгом altoclef
    - учёт ХП своего и цели в принятии решений — сторона altoclef, не начато
  - [ ] 2.5 Архитектурный сплит комбата (мысль юзера): tungsten = чистые комбат-ПРИМИТИВЫ с API расширения (удар, прицел, щит, бросок, движение, тайминги); altoclef = мозг боя (анализ поля, ХП, выбор оружия/расходников, стратегия) поверх этого API
  - [ ] 2.6 Стрельба из лука (зафиксировано 2026-07-20):
    - [x] TrajectorySolver на tungsten (2026-07-21): ваниль-баллистика стрелы (drag 0.99, гравитация 0.05), бисекция по питчу через симуляцию полёта, 3-итерационное упреждение по velocity цели. Примитив BowShooter (прицел→заряд→трекинг→выстрел), py4j shootArrowAt/solveArrowAim
    - [x] автотест bow_test.py PASS: 3/5 по стоячей, 2/5 по РЕАЛЬНО бегущей цели с 18 блоков (ваниль-разброс стрел учтён в порогах). NB: упреждение принципиально не работает по телепортирующимся целям (velocity=0)
    - [ ] связать с altoclef-логикой лука (выбор оружия/когда стрелять остаётся там; она должна дергать TrajectorySolver вместо своего прицела)
    - [ ] нейросеть-поправки — пока НЕ нужна: аналитика попадает; вернуться, если реальный бой покажет систематический промах
- [ ] 3. Tungsten block break/place: научить ломать (и в идеале ставить) блоки
  - [x] 3.1 Block-space поиск с учётом ломания — v1: tryPlanBreakThrough (соседняя клетка, ваниль-тики через calcBlockBreakingDelta); NB: A* не аккумулирует cost — в открытом мире обход выигрывает у пролома, честная аккумуляция = следующий шаг
  - [x] 3.2 Физическое исполнение — v1: майнинг в конце сегмента (aim + зажатый attackKey, ваниль майнит), retry гонит следующий лег; отдельный BreakBlockMove с паузой replay не понадобился
  - [x] 3.3 Гравитационные блоки: cost-надбавка за FallingBlock-стек + доломка упавшего в проход (курс D с песком — PASS)
  - [ ] 3.4 (далёкое будущее) редстоун/поршни в модели мира; аккумуляция cost в block-space A*
  - [x] 3.5 Автотест-курсы: запечатанные бедрок-коробки с dirt-дверью (C) и песком над дверью (D) — оба PASS (deploy/runner/break_test.py)
  - [ ] 3.6 Инвентарь и инструменты — сторона ALTOCLEF (мысли юзера, 2026-07-20):
    - та же логика сплита, что в комбате: взаимодействие с инвентарём = altoclef, примитивы исполнения = tungsten
    - [x] научить брать и ИСПОЛЬЗОВАТЬ инструменты — Ступень 1 СДЕЛАНА: equipToolHook (tungsten объявляет «ломаю блок», altoclef экипирует лучший инструмент через getBestToolSlot/forceEquipItem). Автотест E_tool PASS: deepslate-дверь, кирка вне хотбара, курс в бюджете времени
    - [ ] следом: cost в block-space от ЛУЧШЕГО ДОСТУПНОГО инструмента (второй хук bestBreakTicks), не от текущей руки
    - block PLACING: брать блоки из инвентаря, отличать МУСОРНЫЕ блоки от ценных, строить предпочтительно из дешёвого (сначала земля/булыжник)
    - учёт КОЛИЧЕСТВА: понимать «у меня 10 блоков земли или нет» — планировщик не должен обещать мост из 20 блоков при 10 в инвентаре
    - интерфейс tungsten↔altoclef: tungsten объявляет потребность (нужен инструмент X / нужно N блоков), altoclef решает чем платить из инвентаря
    - переменных много: hardness×инструмент×зачарования, мусор/не мусор, резерв блоков, порядок трат — двигаться инкрементально, каждая ступень с автотестом
  - [ ] 3.7 ВИЗУАЛИЗАЦИЯ ломания (фидбек юзера 2026-07-20: «ломается блок, но он даже не показан»):
    - [ ] подсветка блоков, запланированных к слому (из toBreak плана) — рендер-бокс до и во время майнинга
    - [ ] прогресс ломания на подсвеченном блоке (стадии/цвет по breaking progress)
    - [ ] общий принцип: КАЖДУЮ механику стараться красиво визуализировать (как рендерятся пути/цели) — это правило по умолчанию для всех будущих фич
  - [ ] 3.8 БАГ: после слома блока задача завершается, а не продолжает путь до цели — довести «goto сквозь стену» до бесшовного (слом → продолжение без видимой «смерти» задачи; retry-цепочка должна быть незаметной)
  - [x] 3.9 Конфиги — СДЕЛАНО (2026-07-21): docs/features/TUNGSTEN_CONFIG.md — полный справочник всех полей tungsten.json (ломание/комбат/follow/пути/совместимость) с дефолтами и «когда менять»; про переопределение сохранённым файлом предупреждено; в ноты следующего релиза — раздел конфигурации
  - [x] 3.10 Богатое API — СДЕЛАНО (2026-07-21), автотест F_api PASS:
    - [x] BreakRules — единая политика «можно ли ломать»: config deny-список блоков, deny-ЗОНЫ [x1,y1,z1,x2,y2,z2], block entities всегда запрещены; применяется в планировщике, исполнителе (перепроверка каждый тик) и API
    - [x] связка с altoclef: canBreakHook → AltoClefSettings.shouldAvoidBreaking (break-avoiders, защита кроватей, protected-зоны тасков — один источник правды)
    - [x] py4j: canBreakBlock(x,y,z) и canReach(x,y,z,withBreaking) → reached/pathSize/breaks/endDistance — эвристика «дойдём ли: с ломанием (reached=true, breaks=2) / без (found=false)» проверена тестом
- [ ] 2.7 PVP: доводка по реальному использованию (фидбек юзера 2026-07-20 — «работает ужасно»):
  - [x] «ждёт вечно чего-то» — главный источник убит: вечные пере-планы follow (см. 2.8); pvp_moving_test: 0 фризов за 120с погони с боем
  - [x] «телепортирует взгляд» при майнинге — плавный поворот 16°/тик, атака только при доведённом прицеле; (в бою прицел и так через WindMouse)
  - [x] прогнать против ДВИЖУЩЕЙСЯ цели — pvp_moving_test.py PASS: первый урон 6.8с (с погоней), 18.0 урона, 0 фризов
  - [ ] реальный бой с человеком — остаётся финальной проверкой (skypvp-крещение, 2.9)
  - [ ] «левый клик плохо/редко жмётся» (live-наблюдение юзера) — диагностика: кулдаун-гейт 0.95 слишком строгий? LOS/угол-гейты режут чаще, чем нужно? свинг не виден (attackEntity без нажатия клавиши)? добавить видимый клик/свинг и трассировку частоты атак
  - НИ ОДНА ветка не считается завершённой, пока не работает в реальной игре гладко — критерий юзера
- [ ] 2.9 Боевое крещение PVP: skypvp на mlegacy.net (задача юзера 2026-07-20)
  - [ ] заход на сервер: капча при входе (визуальная — «повернуть рамки») — разобраться через getScreenshot/визуальный анализ; NB: первый заход можно сделать вручную через noVNC (капча — раз на сессию), бот дальше воюет сам
  - [ ] выжить и драться в реальном skypvp против живых игроков — финальный критерий ветки 2
  - точка входа: существующий altoclef-таск `@game skypvp` (уже совмещён с punkPlayer) — дорабатывать его по необходимости
- [x] 2.8 FollowEntityTask: преследование сломано на движущейся цели — ИСПРАВЛЕНО (2026-07-21):
  - причина: пере-план каждые 0.75с при смещении цели >1.5 блока — поиск (бюджет 0.5-3с) убивался вечно, путь не эмитился
  - [x] гистерезис: мин. 2с между пере-планами + порог max(3.0, 25% остаточной дистанции)
  - [x] автотест follow_test.py: жертва бежит по прямоугольнику ~3 бл/с 90с — средняя дистанция 2.0 (лимит 10), финальная 0.6, 0 фризов — PASS
  - [ ] (запас на будущее, пока не нужно) инкрементальное достраивание хвоста и прямой charge при LOS
  - [x] 1.8 Tungsten слайм-паркур: автономное использование slime blocks (bounce routing)
    - [x] физика/роутинг: падение на слайм без урона, bounce-дети в block-space, SlimeBounceMove
    - [x] автотест-стенд фазы 0 (deploy/, мак): оба слайм-курса PASS
  - [x] 1.7 Fix jump bridging (bridgingMode jump/back_jump)
    - [x] 1.7.1 Rewrite state machine: sprint-speed telly bridge (FJ_SPRINT → FJ_AIRBORNE continuous)
    - [x] 1.7.2 Fix placement: processRightClickBlock bypasses crosshair (objectMouseOver MISS at 86°+)
    - [x] 1.7.3 Fix sprint: setSprinting(true) forces sprint at entity level
    - [x] 1.7.4 Fix TestBridgingCommand: GoalBlock at player Y (was GoalXZ → pathfinder descended)
    - [x] 1.7.5 Optimize: debug flag, drift correction, cooldown, path-end graceful exit
<!-- Верхнеуровневые задачи. Пишет юзер, AI отмечает выполнение. -->
<!-- Формат: - [ ] задача / - [x] задача -->
