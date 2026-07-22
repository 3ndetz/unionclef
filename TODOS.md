# TODOs

## 🐞 BUGS (from live user testing — each = its own GitHub issue, fix by priority, per checklist)
- [x] BUG #26 (CRASH, DONE 2026-07-22) `PathExecutor.getCurrentNode` did `path.get(-1)` on an
  EMPTY path ("mining without a physics leg") → IndexOutOfBounds in the entity tick → whole
  client crash on a goto that needs a 1-block mine. Fix: guard empty path (return null;
  caller null-checks). Needs build+test (mining goto → no crash).
- [ ] BUG #27 (unreachable goal → infinite search) A goal reachable only by placing/breaking
  (e.g. tree top) makes the pathfinder search FOREVER; it doesn't try place/break though
  allowed. Want: attempt to reach (pillar/bridge/mine) OR fail gracefully as 'unreachable'
  after bounded attempts — never infinite. ROOT CAUSE (audit 2026-07-22): the block-space
  A* plans BREAKING (tryPlanBreakThrough) but has NO PLACE-as-a-move — so it can't pillar
  up / bridge to a place-only goal and never even tries. Two parts: (a) SHORT: bounded
  give-up -> clean 'unreachable' instead of infinite re-plan; (b) REAL FIX: see the
  elevated item below (place-as-a-move in the search).
- [~] PLACE-AS-A-MOVE (user asked "did you add building/bridging to tungsten?"). PRACTICAL GOAL
  DELIVERED: the bot now DOES pillar up (v0.38) and bridge across a gap (v0.41) during @goto —
  validated (pillar_reach_test, bridge_goto_test). Implemented as a give-up-driven move in
  driveTungstenPrimary (stall at a raised/gapped goal + block in inventory -> PillarTask/
  BridgeTask toward the goal). REMAINING REFINEMENT (rule #6 "in core"): integrate Bridge/Pillar
  as FIRST-CLASS moves inside the block-space search (BlockNode.getChildren, like tryPlanBreakThrough)
  so the pathfinder plans them PROACTIVELY mid-route (not only reactively after a ~14s stall, and
  for mid-route gaps not just the final goal). Deferred: core-search edits are regression-prone
  (the SmartMoves epic regressed terrain), so this needs the same careful repro→test→audit cycle.
- [~] BUG #28 ('Ran out of nodes' on hard parkour) Single goto to a reachable parkour target
  often prints 'Ran out of nodes' and fails. PARTLY FIXED v0.40.0 (#34): the walker's grid-BFS
  path source (CombatPathfinder) now generates parkour jump-moves (+2..4 across, flat or +1 up),
  so stepped/gapped terrain (course B) climbs via the walker instead of flailing/stubbing —
  terrain_test B now PASS. REMAINING: the async block-space physics pathfinder still can't route
  parkour at all (diag_b: pure ;goto doesn't move on B), so a pure-;goto over a gap still fails —
  port the parkour move into the async BlockNode move-gen too if we want ;goto parity.
- [x] #34 Tungsten parkour move-gen (jump gaps) — DONE v0.40.0 for the walker path (course B
  climbs, A/D no regression, break_test intact, combat unchanged). Course C (2-block vertical
  wall) still needs place-to-climb (#46 second half), not parkour.
- [x] BUG #29 (CRITICAL, live test 2026-07-22) Camera FREEZES locked on a block forever, bot
  hard-stuck; never recovers, survives reconnect. FIXED v0.39.0. Root: WindMouseRotation is a
  static singleton that steered the mouse toward its stored target every render frame — a task
  that set a mine/combat aim and died without clearTarget() locked the camera forever (static →
  survived reconnect). Durable fix: (a) stale-aim auto-release — setTarget stamps a timestamp,
  applyRenderStep releases if nothing refreshed it for 600ms (live consumers refresh every tick,
  a dead task's aim clears in ~0.6s); (b) DISCONNECT hook wipes all tungsten state (aim/tasks/
  break/keys); (c) executor releases attackKey+aim immediately on stop mid-mine. Tests:
  stale_aim_test, disconnect_test, break_test (mining unaffected) — all PASS on the 0.39.0 jar.
- [ ] BUG #30 (live test) BFS builds PHYSICS-UNEXECUTABLE 'unreal' routes — physics can't work
  out the jumps to pass, or it paths straight INTO A WALL / into the void. Need: reject
  implausible/'stupid' routes in search; when a BFS route is physically unreal (or computing into
  a wall) fall back to a STRICTER baritone-style movement model (real jump reach, collisions).
  Durable fix in search/move-validation.
- [ ] BUG #31 (live test) Pathfinder can't complete simple routes that need BREAKING a block —
  searches forever / 'runs into emptiness' instead of planning+executing the break-through
  (tryPlanBreakThrough exists but the route doesn't reliably complete). Reproduce + durable fix.
  (GitHub issue #31 pending — TLS timeout, retry.)
- [x] BRIDGING/BUILDING in path — DONE. Place-as-a-move complete: pillar-up (v0.38) + bridge-
  across-gap (v0.41). @goto now paves a bridge toward the goal when stalled at the edge of a real
  gap with a block in inventory (bridge_goto_test: crosses a 7-wide sky void). Remaining: a 2-block
  vertical WALL onto a ledge (terrain C) still needs a pillar-beside-wall variant — separate.
- [x] USER BUG (2026-07-22) goal on air / upper 2-tall-grass block -> tungsten computes forever.
  FIXED v0.41.0: (a) GoalSnap snaps non-standable ;goto/click targets to reachable ground; (b)
  PathFinder stall-cap (20s no real progress -> give up, re-roots don't mask it); (c) ;goto stops
  its search the instant the bot arrives. goal_air_test: tungsten goes inactive in 2-4s (was
  forever). NOTE for testers: run terrain_test on a FRESH bot — sky-tp tests leave stale async
  block-path state that makes a following terrain run stall (cross-test artifact, not a regression).

## МЕГА-ЦЕЛЬ 2: TUNGSTEN = ПОЛНОЦЕННАЯ ЗАМЕНА BARITONE + ИНТЕГРАЦИЯ — юзер 2026-07-21 (вечер)

> Дословные идеи юзера (фиксирую чтобы не забыть). ПРИНЦИП прежний: удобный
> ИНСТРУМЕНТАРИЙ, а не хардкод/скрипты; везде хорошие КОНФИГИ + API + ВИЗУАЛИЗАЦИЯ
> по мере реализации. Тестировать грамотно во все стороны.

- [x] 12. MCP-сервер В МОДЕ по LAN (реализовано 2026-07-21): `com.sun.net.httpserver`
  bind 0.0.0.0:mcpPort, Streamable HTTP JSON-RPC (initialize/tools/list/tools/call),
  24 инструмента-рычага поверх Py4jEntryPoint (single source). Настройки mcpEnabled/
  mcpPort, compose публикует 25350. Тест mcp_test PASS: initialize→unionclef,
  getGameState (чтение) + fillSelection (действие) через HTTP → 4/4. Клод рулит по
  http://<lan-ip>:25350/mcp. Осталось: подключить в мой Claude-конфиг, дописывать
  инструменты по мере роста рычагов
- [~] 13. TUNGSTEN как DROP-IN замена baritone (проверить что реально работает):
  - [x] 13.1 тумблер setTungstenPathing(on)/pathingMode (py4j+MCP) — включает
    useTungsten + experimentalPathfinding (shredder делегирует tungsten плоские И
    ascend/descend сегменты через TungstenBridge). Flag-тест PASS (off→on→off).
    @goto/@get/@gamer с ним делегируют сегменты tungsten
  - [x] 13.1b КОРЕНЬ НАЙДЕН И ПОФИКШЕН: НЕ shredder-движение — весь altoclef-task-
    чейн глушился! MobDefenseChain выигрывал КАЖДЫЙ тик (prio 70, ложная run-away
    на PEACEFUL — закомmenченная peaceful-проверка) → UserTaskChain (навигация)
    никогда не тикался. Восстановил peaceful-шорткат MobDefense. Плюс UnstuckChain
    +WorldSurvivalChain defer при tungsten-primary (их shimmy тоже преемптил).
    После фикса: swap_test PASS — @goto доходит (dist 0.6) и с tungsten-primary, И
    baritone (был просто заблокирован, не мёртв). Диагностика: [trtick]-лог чейнов
  - [x] 13.2 swap работает: setTungstenPathing(true) → GetToBlockTask.driveTungsten
    Primary зовёт tungsten PATHFINDER.find напрямую (как ;goto). Тест swap_test PASS
  - [x] 13.3 tungsten ВЕДЁТ реальные altoclef-таски (главный анблок, 2026-07-22).
    ВАЖНО: ранний вывод «tungsten замерзает на рельефе/drift» был ПОСПЕШНЫМ и
    ОПРОВЕРГНУТ тщательным диагнозом (задачи #19/#23):
    · tungsten НОРМАЛЬНО ходит по рельефу — контр-курс step-up/step-down/gap PASS
      (;goto за 4с, no drift). Не рельеф и не drift-порог (frozen при threshold=5).
    · @gamer на gamer-server замерзал по ДВУМ причинам: (1) спавн в ОКЕАНЕ — бот
      утонул; (2) КОНФЛИКТ ВВОДА: shredder InputOverrideHandler при inControl()=true
      ставит PlayerMovementInput (форс-клавиши=0 когда baritone не пасится) →
      обнуляет setPressed tungsten-executor'а → бот стоит, а sim уезжает (drift 5+
      при неподвижном боте). Диагноз: чистый ;goto движет (нет altoclef-таска),
      @get замерзал (таск активен).
    · ФИКС: inControl() возвращает false при TungstenModDataContainer.isExecutor
      Running() → KeyboardInput читает клавиши tungsten. Тест @get log 3 с деревьями
      на tungsten-primary: бот доехал 0.5→8.4 И нарубил 3 лога (blocks 0→3). Регрессия
      swap_test PASS. tungsten теперь ведёт навигацию+майнинг altoclef-тасков.
  - [~] 13.3b @gamer на tungsten-primary — TERRAIN-ЗАТЫК ИСПРАВЛЕН (v0.30.0, #20).
    Проверка 2026-07-22: бот застревал на горном рельефе. Корень — НЕ «нет Movements»,
    а ДРЕЙФ физ-executor'а (sim расходится с реальностью на ступенях/склонах → hard-stop
    + поиск отвергает «root far from player» → вечно busy → стоп). Фикс (директива юзера):
    рельеф ведёт BlockPathWalker (спринт от РЕАЛЬНОЙ позиции по block-пути → без sim →
    без дрейфа); источник — cheap grid BFS, иначе робастный block-space путь
    (PathFinder.getComputedBlockPath); executor только на финал <=4 бл + вода/паркур.
    ПРОВЕРЕНО на РЕАЛЬНОЙ горе (seed 12345): бот прошёл ~40 бл естественного рельефа,
    спустился, нашёл ель, СРУБИЛ (held spruce_log), hp 20, 0 падений — раньше стоял.
    swap PASS. Остаётся: паркур (прыжки-гэпы/2-блочная стена — #20 note), устойчивый
    полный проход (nightly), один end-stall на многократном сборе — доследить
  - [x] 13.3d РОБАСТНОСТЬ КРИВЫХ GOAL (#25) — СДЕЛАНО (v0.31.0). goalToVec снапит
    невалидную цель на ближайшую standable-клетку: цель В БЛОКЕ → стоять сверху; цель
    В ВОЗДУХЕ (клик по траве → воздушная клетка над поверхностью) → спуститься на землю
    снизу. Валидные standable-цели без изменений (нет регрессии — swap/staircase PASS).
    Тест D: @goto на воздух (5,-55,0) → бот дошёл до земли (5.6,-60), reached-ground PASS.
    Остаток «сломать блок-цель если в блоке» — на altoclef-логике (GetToBlockTask уже
    майнит цель), анти-стак (v0.30.1) страхует от вечного залипания у недостижимой клетки
    (adjustOnPathStart/isInGoal). НЕ сломать текущее (перетест).
  - [x] 13.3c ВОДНАЯ НАВИГАЦИЯ tungsten (2026-07-22, #24 DONE): корень — shouldRemove
    Node гнал водные ходы через walk-based StreightMovementHelper, который на
    вертикальном/подводном ходе давал неопр. направление → отвергал swim-up →
    бот тонул на дне (resetSearch size=1). Фикс В ЯДРЕ (не скрипт, откатил костыль
    WaterSafety): в воде соседние водные клетки проходимы плаванием напрямую +
    surfacing air-клетки принимаются → всплывает и вылезает. Тест бассейна PASS:
    со дна -63 → цель (12,-60,3), hp 20, не утонул. Очень сложный рельеф — #20/#21
  - [ ] 13.4 ПОРТ BARITONE-ЭВРИСТИК в tungsten (напоминание юзера): у baritone куча
    важного (эвристики A*, стак-детект, wander, dimension-логика, cost'ы) — портить
    в tungsten по мере. ВАЖНО: делать КРАСИВО, в отдельных потоках, ничего не
    блокировать (tungsten find() уже async — свой поток; держать этот принцип везде)
- [~] 14. ПОЛНАЯ break/place-совместимость tungsten с ограничениями baritone/altoclef:
  - [x] 14.1 BREAK: BreakRules → canBreakHook → shouldAvoidBreaking бриджит (защита
    кроватей, avoid-листы, protected-зоны). Приват-детект «не могу сломать → обхожу
    радиус» течёт через тот же хук. Тест protect_test PASS: приват-зона блокирует
    ломание СОЛИДНОГО блока (canBreakBlock=false; воздух всегда «ломаем»)
  - [x] 14.2 PLACE ГОТОВО: PlaceRules + canPlaceHook (симметрично BreakRules) →
    shouldAvoidPlacingAt. Консультируется в placeBlockAtRaw (весь WorldEdit/build) и
    BridgeTask (годбридж стопается на приватах). Config allowPlace/placeDenyZones.
    Тест PASS: place denied внутри, allowed снаружи, re-enabled после clear
  - [x] 14.3 canPlaceBlock(x,y,z) py4j+MCP (canPlace/policyAllows/replaceable);
    canBreakBlock уже был. Оба как MCP-tools
  - [x] 14.4 markProtectedArea(x,y,z,r)/clearProtectedAreas — агент помечает приват,
    кладётся в ОБА deny-списка (place+break). py4j+MCP. Тест PASS
  - [ ] 14.5 (осталось) авто-детект приватов В САМОМ tungsten при фейле слома (сейчас
    авто-детект на стороне altoclef WorldSurvivalChain; прокинуть/подхватывать в
    tungsten-исполнителе тоже, чтоб при живой игре само помечало)
- [~] 15. ОГРОМНЫЕ ДАЛЬНИЕ МАРШРУТЫ (progressive/receding-horizon pathing):
  - [x] 15.1+15.3 gotoFar(x,y,z,horizon) — рычаг: горизонт-сегменты к далёкой цели,
    каждый вызов = один сегмент <=horizon блоков, агент крутит gotoFar→pathStatus→
    gotoFar до finalSegment. Не просит пасфайндер о всём пути (тот фризит на
    огромных целях). Переиспользует gotoXYZ (tungsten) — без правки ядра A*. Тест
    far_test PASS: 60 блоков за 3 сегмента, финал dist 0.6. MCP-tool есть
  - [ ] 15.2 (осталось) авто-версия внутри ядра: idle-навигация пока считается
    сегмент, seamless-переход; сейчас чанкинг на уровне рычага (агент оркестрирует).
    Для полного baritone-паритета — сегментация в самом BlockSpacePathFinder
- [~] 16. ВИЗУАЛИЗАЦИЯ ПЛАНОВ (правило по умолчанию — всё визуализируем):
  - [x] BREAK_PLAN контейнер есть (подсветка блоков к слому)
  - [x] 16.1 PLACE_PLAN контейнер + гейт renderPlacePlan; годбридж рисует «сюда
    поставим» (зелёный). Регрессия bridge PASS. Осталось: fillSelection/build тоже
    в PLACE_PLAN (сейчас fill показывает через жёлтый SELECTION-бокс)
  - [x] 16.2 виз проверена ВЖИВУЮ демо-захватами (x11grab): слайм-клип показал
    зелёный goal-бокс + цветные ноды пути + красную линию направления; bridge-клип
    показал стройку моста. Пути/цели/бридж рендерятся. (bой/break/place-планы —
    по мере живых сценариев; контейнеры и гейты на месте)
- [~] 17. КОМБАТ-ДВИЖОК: multi-target / avoid-target (интеграция altoclef):
  - [x] 17.1 PunkPlayerTask.startAny(allow, avoid) — бьёт БЛИЖАЙШЕГО из allow
    (пусто=любой), не трогая avoid; tryRediscover авто-ретаргет по политике
    (isAcceptable). Мозг решает кого, tungsten исполняет
  - [x] 17.3 py4j/MCP-рычаги: punk/punkAny/punkAvoid/punkStop/punkStatus. Тест
    multitarget_test PASS: avoid=[t2]→target None, allow=[t2]→target t2, stop→сброс
  - [x] 17.4 УБЕГАНИЕ (#26) — СДЕЛАНО (v0.29.0). RunAwayTask (tungsten-native,
    зеркало PunkPlayerTask): вместо пути К цели — путь к безопасной ВНУТРЕННЕЙ точке
    ПРОЧЬ от неё, ре-план по мере погони; void-aware (flee-точка только не у края) +
    общий VoidGuard на executor (убегание не уносит в бездну своим движением).
    Угловые фолбэки от стен/пропастей. Рычаги: ;runAwayPlayer <name> [dist],
    py4j/MCP runAwayPlayer/runAwayStop/runAwayStatus. Взаимоисключение с punk. Тест
    runaway_test: держит ~8 бл (avg 8.0) на 15x15, своё движение не роняет в void.
    Остаётся avoid-ОБХОД (path around avoid-целей) — отдельная мелкая доработка.
  - [ ] 17.2 (осталось) altoclef-мозг: приоритизация целей (ХП/дистанция/угроза),
    связка с threat-table (attackPlayer/avoidPlayer уже есть отдельно) — свести
- [ ] 18. БОЕВОЕ КРЕЩЕНИЕ = ПОЛНЫЙ ПРОХОД ИГРЫ + tungsten_speedrun таск:
  - идея юзера: @gamer в altoclef юзает baritone по максимуму + куча умной логики
    (учёт СКОЛЬКО РЕСУРСОВ осталось, докопать земли если не хватает при стройке,
    чтоб НЕ ЗАСТРЯТЬ, крафт и пр.) — ВСЁ это надо учесть/не сломать
  - [ ] 18.1 аудит @gamer: какие фичи baritone юзаются, где учёт ресурсов/материалов,
    где может застрять — составить карту зависимостей перед подменой на tungsten
  - [ ] 18.2 tungsten_speedrun таск: спидранит игру на TUNGSTEN-механиках вместо
    baritone (punk на NPC, всё нужное поверх tungsten). Свой таск, не ломая @gamer
  - [~] 18.3 два критерия «крещения»: (a) PVP + строительство на bedwars живьём;
    (b) @gamer/speedrun. ПРОГРЕСС: @gamer стартует на survival, baritone-версия
    рубит дерево (2→22 блоков за 70с) — ранняя игра работает. tungsten-версия
    застревает на рельефе (drift). Полный проход — nightly, после tungsten 1.6
  - [ ] 18.4 учёт ресурсов при стройке (мысль юзера): планировщик знает сколько
    блоков есть, докапывает недостающее (земля/булыжник) — не обещает мост/стену
    длиннее запаса; связка с inventorySpace + altoclef-инвентарём
- [~] 20. ДЕМО-РОЛИКИ/GIF (showcase для Discord/GitHub-релизов):
  - [x] сняты 3 клипа x11grab (внешняя запись экрана — getScreenshot грузил рендер
    и ломал движение): bridge (godbridge мост 14 бл), slime (паркур+виз пути), pvp
    (мили vs tester2). GIF+MP4, доставлены юзеру. Тулинг: capture_demo.py
  - [ ] полировка: спрятать HUD/дебаг-чат, дальше камера, чистые сцены; break-клип
  - [ ] залить на GitHub, вставить в релиз-ноты; авто-отправка в ТГ (нужен chat_id)
- [ ] 21. MLG-МУВЫ: дальние атаки + паркур + баллистика (идея юзера):
  - агент СИМУЛИРУЕТ траектории полёта стрелы и выбирает наиболее вероятные
    попадания, УЧИТЫВАЯ что он сам может сменить позицию / прыгнуть в полёте —
    просчёт «выстрел из лука в прыжке с учётом ИНЕРЦИИ»
  - арбалет: заранее зарядил → паркуром встал в удобную позицию → эпично
    подпрыгнул → в полёте выстрелил по НАВЕСНОЙ траектории → попал
  - связка: TrajectorySolver (баллистика с упреждением уже есть) + tungsten
    паркур/инерция игрока + bow/crossbow примитивы. Просчитывать позицию СТРЕЛКА
    (своя инерция/прыжок) а не только цели. «По красоте»
- [ ] 23. FAR-FAR TODO (user 2026-07-22, AFTER everything — after issues/PRs + main merge):
  make tungsten a FULL player — traversal/vehicle mastery, "по красоте":
  - ELYTRA autonomy: descend mountains / cross gaps on elytra, tracking DURABILITY and
    remaining flight; auto-boost with FIREWORKS (rocket count aware); land safely, avoid
    hazards mid-flight.
    - REFERENCE (we already have it in-repo): baritone has full Nether elytra control —
      `baritone/process/elytra/ElytraBehavior.java`, `baritone/process/ElytraProcess.java`,
      `api/process/IElytraProcess.java`, `command/defaults/ElytraCommand.java`,
      `launch/mixins/MixinFireworkRocketEntity.java`. COPY the mechanic / adapt into
      tungsten, and study the hard problems they already solved (path solver over terrain
      while flying, firework boost timing, pitch/aim control, durability/landing, chunk
      loading ahead of the flight) instead of rediscovering them.
  - VEHICLES: use and (if needed) PLACE minecarts and boats autonomously as part of a route.
  - MLG on vehicles: boat-in-lava tricks — jump across BURNING boats over lava (place boat,
    hop, repeat) as an MLG crossing. Very far future, "по красоте".
  - General: hazard-aware traversal — pick the safe descent/route considering fall damage,
    lava, void, mob threat, item durability/stock.
- [~] 19. Разбор PR/issues — DONE for this session's scope (2026-07-23):
  - CLOSED with fix notes (fixed this session): #29 (frozen camera, v0.39), #26 (crash, v0.34),
    #27 (unreachable forever, v0.35+v0.41), #28 (ran-out-of-nodes parkour, v0.40), #17 (sprint-jump
    loop to unreachable, v0.41 stall-cap), #30 (unreal routes — routes around now), #31 (break-through).
  - COMMENTED + re-test requested (my work likely helps, need repro): #12 (@gamer freeze), #13
    (always stuck), #20 (recalc loop on terrain change).
  - PRs: #10 MERGED (the 1.21.11->main merge itself). #22/#23 (RiaDev1 external bug-fix PRs) NOT
    merged — external contributions targeting main that need human review vs the extensive 1.21.11
    work; flagged for the user.
  - LEFT OPEN (out of this session's pathfinding scope — altoclef crafting/inventory/features):
    #25, #19-craft, #18 (EntityTracker leak), #16, #15, #24, #21 (godbridge sneak), #7, #5, #2.
    Each needs its own repro→core-fix→test pass per the checklist — separate work.
  - СЛЕПОК на 2026-07-22 (3ndetz/unionclef): 3 открытых PR — #23 (misc hidden bugs from
    code audit, RiaDev1), #22 (18 bug fixes: pathfinding/combat/entity-tracking/stuck/
    NPE, RiaDev1), #10 (сама ветка 1.21.11). 14 открытых issues, ключевые (RiaDev1,
    похоже реальные баги): #21 годбридж вечный sneak после фейла установки, #20
    PathingBehavior recalc-loop при смене рельефа, #19 CraftWithMatchingMaterials берёт
    низший тир, #18 EntityTracker blacklist unbounded (memory leak), #17 PathExecutor
    sprint-jump infinite loop к недостижимой цели, #16 PickupFromContainer низший тир;
    плюс #25 крафт-инвентарь (WaluigiDrip), #15 (Guo8410), #13/#12 «always stuck»/freeze
    на @gamer 1.21.11 (FlipperFlopper99), #24/#7/#5/#2 (3ndetz). Разбирать по одному ПО
    ЧЕКЛИСТУ (воспроизвести → чинить в ядре → тест → либо коммент-вопрос).
- [x] 22. МЕРДЖ `1.21.11` → `main` — DONE 2026-07-23 (merge commit 9d8fa96). Promoted the whole
  tested v0.29-v0.41 line to main; conflict was only a stale mod_version (0.21.1 -> kept 0.41.0).
  PR #10 (1.21.11→main) auto-closed as MERGED; main and 1.21.11 now in sync (0 ahead).

## МЕГА-ЦЕЛЬ: ПОРТ ПОЛНОГО BARITONE + WORLDEDIT В ФИЗ-МОДЕЛЬ TUNGSTEN — юзер 2026-07-21

> ПРИНЦИП (напоминание юзера 2026-07-21): рядом ПОЛНЫЙ исходник baritone (baritone/,
> и shredder/ — форк). Для КАЖДОЙ задачи ниже СНАЧАЛА смотреть их реализацию и
> фиксы, внедрять проверенное, НЕ повторять их ошибок. Референсы:
> - бридж/установка как A*-ход: baritone MovementParkourPlace / MovementTraverse (positionsToBreak/ToPlace)
> - схематик-строительство: baritone.process.BuilderProcess (+ ISchematic, палитра, порядок)
> - cost'ы break/place, падающие блоки: baritone.pathing.movement.MovementHelper
> - WorldEdit-like: baritone `sel`/`#set` команды (SelCommand, BuilderProcess selection)
> - A*: baritone.pathing.calc.AStarPathFinder (open set, эвристики) — сравнить с tungsten

Идея: перенести ВЕСЬ функционал baritone (в т.ч. умное schematic-строительство)
+ WorldEdit-подобные команды (`//sel`, `//set`, `//pos1/2`, `//replace`, `//walls`
и пр.) в tungsten и ВСТРОИТЬ в его мега-физдвижок — чтобы, например, в паркуре
он мог ЭПИЧНО ставить блоки (jump-place, бридж через пропасть), а также строить
схемы. tungsten становится единым pathfinder+builder на физике.

- [ ] 7. Block PLACING в tungsten (ФУНДАМЕНТ всего строительства):
  - [x] 7.1 примитив placeBlockAt(x,y,z) — авто-выбор блока, наведение на грань опоры, interactBlock. Тест place_test PASS (4/4 блока: линия+стек). tungsten-подвод в reach — след. шаг
  - [x] 7.2 ГОДБРИДЖ ГОТОВ (2026-07-21): переписал на НЕПРЕРЫВНУЮ pave-ahead модель — БЕЗ sneak, sprint вперёд + мостим до 2 клеток пола вперёд каждый тик (целевая клетка на уровне пола → плоское расширение, падать неоткуда). Ключ: физика точно знает позицию, кладём блок ДО того как нога дойдёт до края. Тест PASS: прошёл ровно N=5 блоков на sprint-скорости, не упал, gap 5/5 (первый прогон без стоп-условия промостил 138 блоков!). Остановка по дистанции. Сломанная sneak+step версия выброшена. Готча: для tungsten-правок нужна ./gradlew clean build (инкрементальная кэширует)
    - [x] bridgeTo(x,y,z) к цели (для bedwars — мост к чужому острову) + команда ;bridge + py4j. Тест PASS: форвард 5.6 + bridgeTo дошёл до x=11
    - [x] визуализация: тумблеры renderVisualization/renderPathMoves/renderBreakPlan/renderCombat (;settings), подсветка клеток бриджа. Регрессия slime/bridge/break PASS — не сломано
    - [ ] ОТЛОЖЕНО (риск ядра A*): глубокая интеграция bridge как block-space move (goto через пропасть авто-мостит). Обоснование: годбридж есть как ;bridge/bridgeTo/py4j-примитив, а по философии block 6 когнитивный АГЕНТ сам решает когда мостить → авто-детект в A* не критичен. Делать отдельной фокус-сессией по образцу baritone MovementTraverse:122-168 (bridge=place at dest.down(), cost=walk+place, side-place/backplace). Диагональный годбридж — туда же
  - [ ] МИНОР: canReach (py4j prediction) флачит — иногда block-space возвращает частичный стаб (found=true/reached=false/breaks=0) вместо полного пути. Захардить ретраем поиска (F_api тест это ловит)
- [ ] 11. АНТИ-ЧИТ ГУМАНИЗАЦИЯ ПОВОРОТОВ (важный поинт юзера 2026-07-21):
  - НИКОГДА не setYaw/setPitch напрямую — античиты палят сразу. Все повороты через mouse-pipeline (changeLookDirection пиксельно-квантованно / WindMouse), «сервер видит как физическую мышь». В боевой ауре (WindMouseRotation) уже настроено грамотно — переиспользовать
  - [x] перевёл ВСЕ мои примитивы с setYaw/setPitch на mouse-pipeline (2026-07-21): BridgeTask+BowShooter→WindMouse (тикаются, сходятся человеко-подобно), майнинг-прицел→WindMouse, placeBlockAt→changeLookDirection (одношот, пиксельно-квантованно). Тесты: bridge PASS с гуманизацией (z-разброс 4.49 естественный), break+place регрессия PASS. Path-replay уже был на changeLookDirection (enableNativeRotation)
  - [ ] humanize-переменные тоньше: пауза на большие углы «поднять мышь», разброс по вкусу (WindMouse-параметры уже дают wind/gravity)
  - [ ] проверить бридж/бой в РЕАЛЬНОМ bedwars против анти-чита — если флагает, крутить humanize-параметры (первая проверка живьём)
  - [x] 7.3 inventorySpace() — свободные слоты + подсчёт блоков по типам (planner не обещает мост длиннее запаса). Тест PASS (free=35, blockCount=64)
  - [ ] 7.4 использование инструментов при ломании (equipToolHook уже есть) + расширить на выбор блока для установки
- [ ] 8. Schematic-строительство (baritone BuilderProcess-аналог на tungsten):
  - [ ] 8.1 загрузка схемы (.schem/.litematic/.nbt), парс палитры и блоков
  - [ ] 8.2 планировщик порядка постройки (снизу вверх, доступность позиций, не замуровать себя), tungsten ведёт к каждой позиции и ставит
  - [ ] 8.3 докупка/добыча недостающих материалов (связка с altoclef-инвентарём)
- [ ] 9. WorldEdit-подобные команды в tungsten (`;` или свой префикс):
  - [x] 9.1 selection: py4j select(x1,y1,z1,x2,y2,z2) — хранит регион, рендерит жёлтую подсветку (SELECTION-контейнер, гейтится renderVisualization), возвращает min/max/volume; clearSelection(). Тест worldedit_test PASS
  - [~] 9.2 операции: //set + //walls ГОТОВЫ. fillSelection(block)=//set (все клетки), wallsSelection(block)=//walls (4 вертикальные стены, полый центр). Общее ядро fillCells(predicate) — без дублей. ЧЕСТНЫЙ blockName: equipHotbarBlock экипирует названный блок из хотбара (не молча ставит что в руке). Снизу вверх (опора у каждой), кап 96/вызов (truncated), возвращает filled/remaining/complete → агент репозиционируется для дальних. Тест PASS: //set cobblestone держа dirt (4/4, доказан equip), //walls кольцо 8/8 + центр air. Осталось: //replace (нужен синхронный break-примитив), //hollow/cyl/sphere (генераторы позиций поверх fillCells)
  - [x] 9.3 "sel set 0" и прочее WorldEdit-like — select+fillSelection как py4j-рычаги для агента (не хардкод сервера, чистые координаты)
  - [x] 9.4 в survival режиме операции идут через РЕАЛЬНУЮ установку (placeBlockAtRaw/interactBlock), НЕ команды сервера — работает в выживании. NB fillSelection де-нест: placeBlockAtRaw (single source, без вложенного onClientThread — тот дедлочил рендер-тред)
- [~] 10. Интеграция: единый tungsten = pathfind + break + place + build + WE-ops. Цикл агента see→move→build ВАЛИДИРОВАН (agent_loop_test PASS): getGameState→gotoXYZ→buildDefenseAround на bedwars-микросценарии, композиция работает как целое. Осталось: break/mine как рычаг, schematic (block 8), baritone-фичи по мере переноса

## КОГНИТИВНЫЙ АГЕНТ В PVP-ИГРАХ (bedwars и др.) — крупная задача юзера 2026-07-21

Философия (дословно): НЕ скриптовать BedWars, а сделать РЕЖИМ/поверхность, где
когнитивному агенту (Клод по py4j/MCP) УДОБНО играть — он сам выбирает тактику
(застроить кровать, что купить в магазе, когда переть/отступать), а ИГРА/МОД
ПОМОГАЕТ механикой в жёстких битвах (прицел, траектории, пасфайндинг, установка
блоков). Критерий успеха: «чтобы МНЕ САМОМУ (Клоду) было удобно играть и побеждать
за агента». Не всё скриптовать — агент решает, мод исполняет.

- [ ] 6. Поверхность управления PVP для когнитивного агента:
  - [x] 6.1 ВОСПРИЯТИЕ: getGameState() py4j — self(hp/pos/blocks/held/armor/onGround) + players[](name/pos/distance/hp/sprinting) + beds[](детект в r=40). Тест PASS. Магазин читается getOpenScreen, покупка clickMenuByName (не дублируем). Осталось (по вкусу): таймеры раунда, свой/чужой цвет команды
  - [~] 6.2 ТАКТИЧЕСКИЕ ПРИМИТИВЫ (мод исполняет, агент командует): ЕСТЬ attack(mouseClick/interactCrosshairEntity), aim+shoot(shootArrowAt/solveArrowAim, траектория), shield(shieldBlock), placeBlock(placeBlockAt), buildDefense(buildDefenseAround), fill/walls(fillSelection/wallsSelection). GOTO-РЫЧАГ ГОТОВ (2026-07-21): gotoXYZ(x,y,z)→tungsten-пасфайндер + pathStatus(busy/pos/distance/arrived) + stopPathing(;stop+@stop) — keystone perception→action, им же агент репозиционируется для дальних fillSelection-клеток. Тест goto_test PASS (дошёл dist 1.5). retreat/chase — НЕ примитивы, агент композит из goto+getGameState (philosophy: agent decides). Осталось: mineTo (нужен break-примитив)
  - [ ] 6.3 МАГАЗИН: читать меню магазина (getOpenScreen), покупать по имени (clickUiSlot) — агент решает ЧТО купить, примитив «buy(itemName)» исполняет
  - [x] 6.4 ЗАСТРОЙКА КРОВАТИ: buildDefenseAround(x,y,z) — защитный панцирь (стороны+крыша), переиспользует placeBlockAt. Тест PASS: кольцо вокруг кровати замкнуто 4/4 при обходе с 4 сторон. NB: «раздутый счётчик 88» из ранней заметки — ЛОЖНАЯ тревога: py4j стрингифицирует Java-List, а тест делал len(строки)=число символов (~88 для ~7-9 клеток), не элементов. Реально ставит 7-9 клеток корректно (agent_loop: placed=7). Тест починен (конвертит списки). Осталось: выбор материала/паттерна агентом
  - [ ] 6.5 «Игра помогает в жёстких битвах»: авто-ассист прицела/блока щитом/крит-тайминга при активном бою, но СТРАТЕГИЮ (куда идти, кого бить, когда отступать) держит агент
  - [ ] 6.6 BedWarsTask: НЕ выкидывать, но переосмыслить — сделать вариант/режим `@game bedwars cognitive` (или флаг), где вместо скрипта включается tungsten-нападение (;punkPlayer smart) + агент рулит через py4j/MCP. Починить приколы старого таска (устарел)
  - [x] 6.7 умная стрельба из лука в бою (2026-07-22): TrajectorySolver вплетён в
    боевой путь altoclef. KillPlayerTask УЖЕ решает melee<10 блоков / лук+пёрл на
    дистанции (политика «когда стрелять» = altoclef), и его ShootArrowSimpleProjectileTask
    теперь целится через TrajectorySolver с упреждением по self-tracked velocity.
    Улучшение прицела распространяется на бой автоматически (bedwars-нападение через
    KillPlayerTask). Валидировано: стоячая цель 5/5 с 24 блоков (v0.32.0).
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
    > НАХОДКИ 2026-07-21 (из @gamer-теста, головной старт для фокус-захода):
    > tungsten-primary @gamer ЗАМЕРЗАЕТ на survival-рельефе. Проверено: НЕ drift-
    > порог (frozen даже с driftThreshold=5.0). updateVelocity уже ВАНИЛЬНО-
    > КОРРЕКТНА (порог 1e-7, normalize при mag>1), airStrafing уже 0.02/0.026 —
    > симуляция на FLAT верна (slime PASS). Значит расхождение на рельефе =
    > КОЛЛИЗИИ/step-up/склоны/тайминг (1.6.1 block-space Movements + 1.6.2 macro-
    > actions), а НЕ пороги 1.6.3. Приоритет: 1.6.1 (baritone Movements для
    > BlockNode: Traverse/Ascend/Descend/Parkour со step-up/gap/slope) — сейчас
    > BlockNode.getChildren слепо сканит круг r=8, на рельефе не находит проход.
    > Driftкоррекция driftCorrectionEnabled(false) снапит позицию (анти-чит-риск);
    > правильный путь — 1.6.4 closed-loop yaw (анти-чит-safe). НЕ торопить —
    > каждый фикс с перетестом slime, иначе ломается рабочий flat.
    - [ ] 1.6.1 BlockSpace: заменить примитивный BlockSpacePathFinder на baritone-level эвристики
      - BlockNode.getChildren сейчас просто сканирует 3D круг radius=8 — тупой перебор
      - Нужно: адаптировать baritone Movements (Traverse, Ascend, Descend, Parkour, Pillar) для BlockNode
      - Это даст: знание про step-up высоты, gap distance, fence collision, slope — до запуска physics A*
      - Результат: physics A* получает 2-3 умных направления вместо 100+ слепых
      - > РАССЛЕДОВАНО (#34a, 2026-07-22, terrain_test): КОРЕНЬ провала курсов B/C — здесь.
      >   B (диагональ +2x+1y через 1-блочные ямы): async BlockSpacePathFinder НЕ находит
      >   маршрут к цели → driveTungstenPrimary получает вырожденный 2-wp stub от
      >   CombatPathfinder → walker лимпит по 2-wp (stop/restart каждый шаг убивает
      >   sprint-моментум для running-прыжка) → падает в яму. C ("Ran out of nodes!") —
      >   вообще без пути. ВЫВОД: чинить НЕ в walker'е и НЕ в выборе пути (пробовал:
      >   edge-timed gap-jump в walker + предпочтение robust-пути для вырожденного stub +
      >   staleness-guard — всё ОТКАЧЕНО, т.к. A-нейтрально но B не решает; корень — SEARCH
      >   не маршрутизирует рельеф). Нужен ЭТОТ пункт (baritone MovementAscend/Parkour в
      >   BlockNode.getChildren) + 1.6.2 macro-actions. A (сплошная лестница) и flat —
      >   работают (walker + executePath), их НЕ трогать.
      - > INVESTIGATED further (2026-07-22): found two REAL bugs in BlockSpacePathFinder —
      >   (1) getDistFromStartSq used start.x for the Y and Z diffs (garbage distances);
      >   (2) bestSoFar had inverted selection logic returning the wrong node. BUT they are
      >   LOAD-BEARING: course A routes via the async BlockSpacePathFinder (branch 2 —
      >   CombatPathfinder returns <2 for the staircase), and the garbage distances made
      >   bestSoFar emit partial paths A depends on mid-climb. Correcting either bug
      >   REPRODUCIBLY stalls A at (10.7,-51) — two clean warm runs identical; reverting
      >   restores A to (13,-48). CONCLUSION: the search cannot be fixed piecemeal — the
      >   distance calc + failing-flag + bestSoFar + move generation must be reworked
      >   TOGETHER, keeping A (the canary) green at every step. Reverted to v0.32.0-stable.
      >   Interim positive signal to reuse in the rework: with the corrected search, course
      >   C returned real "BFS 18/14 wp" paths instead of "Ran out of nodes".
      - > SmartMoves scaffolding BUILT (2026-07-22, flag-gated, DEFAULT OFF = v0.32.0, A safe):
      >   SmartMoves.java (tungsten-native Traverse/Ascend/Descend/Parkour/parkour-ascend
      >   neighbour gen), TungstenConfig.smartMoves, BlockNode.getChildren branch,
      >   py4j setTungstenSmartMoves, terrain_test SMART=1. getDistFromStartSq/bestSoFar
      >   fixes also gated on the flag. RESULT (SMART=1): still FAILS — A stalls at
      >   (10.7,-51) "Ran out of nodes" with the FIXED search regardless of neighbour gen
      >   (blind or SmartMoves) → the real entanglement is the failing-flag/bestSoFar/
      >   isPathComplete/receding-horizon interaction + node budget + cycles from Descend
      >   moves, NOT just neighbour generation. NEXT (focused effort): instrument the search
      >   loop (why "Ran out of nodes" mid-staircase; node count; why fixed bestSoFar stalls
      >   at 5-block failing threshold near goal), rework holistically with A green each step,
      >   then flip smartMoves default. Scaffolding is committed and dormant (safe).
      - EXPERIMENT PLAN (#1.6.1 focused effort, smartMoves flag isolates all of it):
        - [~] E1 near-goal completion: clear `failing` when goal within MIN_DIST_PATH so
          the search completes standing next to the target (fixes (10.7,-51) "ran out of
          nodes"). TESTING NOW.
        - [ ] E2 cycle/budget: Descend moves let the search oscillate up/down; add proper
          closed-set use for smart neighbours + cap/instrument node count; log why "ran out
          of nodes" fires (numNodes at exit, openSet size).
        - [ ] E3 heuristic admissibility with jump/parkour costs (computeHeuristic vs the
          new ActionCosts) so A* is guided, not exhaustive.
        - [ ] E4 walker execution of SmartMoves paths: jump timing for ascend/parkour
          (baritone MovementAscend.updateState model: jump when flatDist<=1.2 && sideDist<=0.2).
        - [ ] E5 diagonals + water/break/ladder parity in SmartMoves (blind scan has them).
        - [ ] E6 once A green + B/C(where possible) route under smartMoves: flip default,
          broad regression (slime/swap/goto/gamer smoke), release.
    - [ ] 1.6.1b (#34b) C-курс «2-блочная вертикальная стена» физически НЕпроходим прыжком
      (ванильный sprint-jump apex ~1.25 блока). Нужен block-placing: пиллар-вверх (ставить
      блок под себя в прыжке) или лестница из блоков. Это отдельная крупная фича (примитив
      установки уже есть — placeBlockAtRaw; нужна pillar-parkour-логика в исполнении).
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
    - [x] 1.6.6 (эксперимент #32, ЗАКРЫТ 2026-07-22) speed-pipeline «идти по BFS пока
      физика считает ноды впереди». ЗАМЕРЕНО reaction_test.py: @goto → первое движение
      за ~0.1с (0.06-0.07с прогретый, 0.26с холодный старт, avg 0.11с, 5 прогонов).
      Вывод: drift-immune walker (BlockPathWalker) СТАРТУЕТ мгновенно и вообще НЕ ждёт
      физику (сам сприентит к BFS-вейпоинтам от реальной позиции). Премис #32 (медленный
      физ-компьют блокирует старт) неактуален — pipeline-оптимизация НЕ нужна. Юзерова
      гипотеза «или у нас всё и так ок» подтверждена.
- [ ] 2. PVP: полный аудит и переделка комбата tungsten (smart + fast + effective)
  - [x] 2.1 Аудит: почему боится ударить (чрезмерные пре-условия атаки?), низкий DPS, зависание при взгляде в траву (raycast LOS через tall grass?)
    - Итог: триггер гейтился на ванильный mc.targetedEntity (OUTLINE-пик, блокируется травой), прицел вёл с упреждением по COLLIDER; ESCAPE пол-цикла кулдауна; движение к цели выключено дефолтом. Детали: docs/ai/progress.md
  - [x] 2.2 Переделка по результатам аудита: агрессивность, точность, скорость решений
    - свой гейт (reach+COLLIDER LOS+угол+кулдаун) + прямой attackEntity; без ESCAPE-на-кулдауне; движение в бою включено + дожим последних полблока; крит-окно при падении
  - [x] 2.3 Боевой тест на стенде: PASS — первый удар 4.3с, жертва убита (20.0), 0 зависаний, бой в высокой траве (deploy/runner/pvp_test.py)
  - [~] 2.10 (URGENT 2026-07-22, user PVP feedback on v0.31.0) DYNAMIC COMBAT MOVEMENT.
    The bot was STATIC: `CombatController.tick` ran ONLY aim(WindMouse)+trigger — zero
    legs — so it rooted, only rotated+clicked; no strafe/jump/kite; didn't handle a
    target occluded by another entity; showed jump trajectories but never jumped.
    (2.2's claim "движение в бою включено" had regressed / never lived in CombatController.)
    FIX: `CombatController.combatMove()` — LOS+safe = circle-strafe + kite to melee reach
    + randomised crit-jumps; no-LOS = walk the pathfinder route to flank the occluder;
    danger = release legs (safety owns motion); every strafe/jump void-checked. Testing.
  - [ ] 2.4 Полноценный комбат-арсенал (мысли юзера, зафиксировано 2026-07-20):
    - выбор оружия по ситуации: топор/меч/лук; mace-булава с высоты; трезубец (бросок); арбалет; снежки для первой отдачи — примитивы бросков ещё не сделаны
    - расходники: эндер-пёрлы (гэп-клоуз/отступление), золотые яблоки по ХП — сторона altoclef, не начато
    - [x] щит: примитив ShieldBlocker + CombatPrimitives.shieldHold (2026-07-21) — тест shield_test PASS (0/3 урона от стрел при контроле 2/2); тайминги против топора — за мозгом altoclef
    - учёт ХП своего и цели в принятии решений — сторона altoclef, не начато
  - [ ] 2.5 Архитектурный сплит комбата (мысль юзера): tungsten = чистые комбат-ПРИМИТИВЫ с API расширения (удар, прицел, щит, бросок, движение, тайминги); altoclef = мозг боя (анализ поля, ХП, выбор оружия/расходников, стратегия) поверх этого API
  - [x] 2.6 Стрельба из лука (ГОТОВО 2026-07-22, v0.32.0 — прицел altoclef на TrajectorySolver):
    - [x] TrajectorySolver на tungsten (2026-07-21): ваниль-баллистика стрелы (drag 0.99, гравитация 0.05), бисекция по питчу через симуляцию полёта, 3-итерационное упреждение по velocity цели. Примитив BowShooter (прицел→заряд→трекинг→выстрел), py4j shootArrowAt/solveArrowAim
    - [x] автотест bow_test.py PASS: 3/5 по стоячей, 2/5 по РЕАЛЬНО бегущей цели с 18 блоков (ваниль-разброс стрел учтён в порогах). NB: упреждение принципиально не работает по телепортирующимся целям (velocity=0)
    - [x] связать с altoclef-логикой лука (2026-07-22): ShootArrowSimpleProjectileTask.calculateThrowLook теперь дергает TrajectorySolver для прямого выстрела (замена старого g=0.006 closed-form). Упреждение — по per-tick position-delta (getVelocity() у чужих игроков ~0, они двигаются position-пакетами → без deltas нет lead). Выбор оружия/когда стрелять остались в altoclef (KillPlayerTask: melee<10 блоков, лук/пёрл на дистанции). High-angle артиллерия + out-of-range = fallback calculateThrowLookLegacy. Тест bow_altoclef_test.py (@shoot путь)
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
  - [x] 2.7.1 BEDWARS: ПАДАЕТ В VOID + 0 КИЛЛОВ (юзер 2026-07-22, тест релиза 0.26 —
    моя задача #28) — ИСПРАВЛЕНО (v0.28.0). Причина (найдена in-combat телеметрией):
    не pursue-движение, а САМА stage-машина боя — DANGER_BATTLE спринтовал,
    DANGER_IMMINENT-торможение ПРЫГАЛО, и у края мелкого острова brake-jump
    выкидывал бота в бездну; reactive edge-check смотрел лишь 1.35 бл (спринт-инерция
    перелетала). Фикс: финальный void-aware clamp движения — не спринтовать/не прыгать
    К обрыву в ЛЮБОЙ стадии, sneak-стоп у края (ванильный) со скорость-масштаб. lookahead,
    нокбэк-recovery сохранён (гасим только когда сам рулю в край), не преследуем цель в
    бездну. Aim ускорен (WindMouse gravity 3.2/maxStep 7). Тест bedwars_combat_test
    BRIDGE SOLO 90с: 11 киллов, 0 смертей, 0 падений (было 2-5/мин). MUTUAL — падения
    только от нокбэка, симметрично с идентичным ботом (обычный PvP, не «сам в бездну»).
    Остаётся (future): позиционирование «край за спиной» для нокбэк-падений в mutual.
- [ ] 2.9 Боевое крещение PVP: skypvp на mlegacy.net (задача юзера 2026-07-20)
  - [x] заход на сервер: mlegacy-капча (rotation-пазл) — клики/повороты РЕШЕНЫ (interactCrosshairEntity/mouseClick), но сам визуал-пазл на дрейфующем рендере не гарантируется; юзер: даже человек через vnc не может → УШЛИ на musteryworld (там анти-бот проходится авто)
  - [x] ЖИВОЙ ЗАХОД musteryworld + НАВИГАЦИЯ ХАБА (2026-07-21): коннект+auth+register, компас→МИНИ-ИГРЫ→BEDWARS по именам через новый clickMenuByName — Я В BEDWARS-ЛОББИ. «Самый сложный» вызов (меню-навигация) решён надёжно
  - [ ] очередь в матч (прыжок «быстро начать») → реальный бой bedwars против живых → тест tungsten-нападения
  - точка входа игры: `@game bedwars` / когнитивная поверхность (блок 6)
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
