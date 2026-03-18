# Progress

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
