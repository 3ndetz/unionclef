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
