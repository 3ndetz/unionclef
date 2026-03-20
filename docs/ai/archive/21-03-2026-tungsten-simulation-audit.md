# Tungsten Simulation Audit — 21.03.2026

## Investigate

### Сравнение tungsten simulation vs vanilla MC 1.21.1

Tungsten взят с MC 1.21.8 и запущен на 1.21.1. Проведён побайтовый анализ bytecode vanilla Entity/LivingEntity/PlayerEntity/ClientPlayerEntity vs Agent.java.

**Найденные расхождения с vanilla:**

| Расхождение | Vanilla | Tungsten | Влияние |
|---|---|---|---|
| Velocity zeroing X/Z | 0.003 | 1e-5 | Микро-скорости не обнуляются |
| BlockPos в pushOutOfBlocks | MathHelper.floor() | (int) cast | Неправильный блок при X<0 |
| Diagonal input normalize | Нет в KeyboardInput | Vec2f.normalize() в AgentInput | ~2% разница диагональной скорости |
| airStrafingSpeed init | 0.02/0.026 | 0.06 (хардкод) | 3x воздушный контроль |
| Friction block (fences) | getVelocityAffectingPos() с fence check | floor(minY-0.5) без fence check | Неправильный friction на заборах |
| fallDistance type | float | double | Мелкий precision drift |
| movementSpeed update | Атрибут каждый тик | setSprinting() пересчёт | Timing разница |
| Diagonal normalization (1.21.4+) | applyDirectionalMovementSpeedFactors | Присутствует в коде | Не нужно для 1.21.1 |
| Server teleport packets | Обрабатываются | ci.cancel() — глотались | Deadlock при телепорте |
| Entity collisions в pathfinder | Thread-safe не гарантирован | getEntityCollisions из parallel thread | ConcurrentModificationException |

### Ключевое открытие: simulation = pathfinding

Agent.tick() используется и для per-tick validation, и для A* pathfinding. Любое изменение физики меняет оба. Эвристики pathfinder'а (cost function, pruning, node generation) неявно настроены на текущую физику. Изменение физики требует перенастройки эвристик.

### Маяк Speed I

Обнаружен скрытый маяк рядом с тестовой зоной, дававший Speed I эффект. Объяснил постоянный mismatch 0.156 vs 0.13 (sprint + Speed I vs sprint).

## Plan

### Безопасные фиксы (не меняют физику Agent.tick)

- [x] Fence friction — getVelocityAffectingPos с fence/wall/gate проверкой
- [x] BlockPos flooring — MathHelper.floor() вместо (int) cast
- [x] Server teleport — не cancel'ить PlayerPositionLookS2CPacket, а стопать executor
- [x] EntityTrackerUpdate — не cancel'ить
- [x] ConcurrentModificationException — убрать getEntityCollisions из pathfinder thread
- [x] IOOB в PathFinder.processNodeChildren — bounds clamp
- [x] Diagonal normalization 1.21.4+ — закомментировать для 1.21.1
- [x] Follow target snap — snapToGround для цели на шифте
- [x] Logging — threshold, aligned format, drift details в чат
- [x] Settings command — overhaul, reload, airStrafe, mismatchThreshold
- [x] MULTIVERSIONING.md — TODO для tungsten/shredder preprocessor

### Фиксы simulation (правильные, но ломают pathfinder эвристики)

- [x] Velocity threshold 0.003 → **ОТКАЧЕНО** обратно на 1e-5
- [x] AgentInput.normalize() убрано → **ОТКАЧЕНО** обратно
- [x] airStrafingSpeed 0.06→0.02 → **ОТКАЧЕНО** обратно на 0.06
- [x] setSprinting movementSpeed recalc → **ОТКАЧЕНО** к оригиналу
- [x] fallDistance double→float → **ОТКАЧЕНО** обратно на double

### TODO: правильный путь к точной симуляции

Чтобы применить simulation фиксы без поломки pathfinding:

1. **Применить фикс simulation** (например velocity threshold 0.003)
2. **Проанализировать как изменился search space** pathfinder'а
3. **Подстроить эвристики/costs** в Node.getChildren, calculateNodeCost, processNodeChildren
4. **Протестировать** что pathfinder находит пути И бот их проходит
5. Повторить для следующего фикса

Каждый фикс — отдельная итерация. Не менять всё сразу.

### TODO: closed-loop execution

Текущий executor — open-loop: слепо воспроизводит pre-computed input. Drift накапливается и path abort'ится. Нужен closed-loop: на каждом тике корректировать yaw/input на основе реальной позиции vs ожидаемой.

### TODO: idle movement (бот всегда в движении)

Генератор circular idle-маршрута пока pathfinder считает. Seamless переключение idle→real path.

## Implement

### Коммиты (сохранённые)

- `3260f2e` — tungsten: disable diagonal normalization for MC 1.21.1
- `2204caf` — tungsten: stop cancelling server packets during execution
- `6ae66fe` — tungsten: fix friction block lookup for fences/walls/gates
- `43b3ead` — tungsten: optimize getVelocityAffectingPos
- `88b3597` — tungsten: snap follow target to ground
- `bc844ef` — tungsten: add path reconnection instead of aborting on drift
- `eec6aae` — tungsten: log drift details to chat when path stops
- `cb80b5a` — tungsten: add mismatch logging threshold and aligned output
- `dac3d92` — tungsten: overhaul settings command
- `62d68b1` — tungsten: add airStrafeMultiplier config
- `fcc9c4c` — tungsten: fix blockPath IndexOutOfBounds (clamped)
- `b94b0f6` — tungsten: fix ConcurrentModificationException in pathfinder
- `38b6f46` — tungsten: suppress diagonal input speed mismatch in verbose debug

### Коммиты (откаченные)

- `a7db22b` → `b1a0449` — velocity threshold 0.003 → reverted to 1e-5
- `e06ac12` → `98c8f29` — fallDistance float → reverted to double
- `9744794` → `98c8f29` — normalize removal → reverted
- `f645c24` → `98c8f29` — airStrafingSpeed 0.02 → reverted to 0.06
- `fcc9c4c..83ad11d` → `98c8f29` — movementSpeed/setSprinting chain → reverted

### Файлы изменённые (финальное состояние)

- `tungsten/src/main/java/kaptainwutax/tungsten/agent/Agent.java` — fence friction, BlockPos floor, entity collision removal, logging threshold
- `tungsten/src/main/java/kaptainwutax/tungsten/agent/AgentInput.java` — без изменений (normalize восстановлен)
- `tungsten/src/main/java/kaptainwutax/tungsten/mixin/MixinClientPlayNetworkHandler.java` — не cancel'ить server packets
- `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java` — tryReconnect, drift comment
- `tungsten/src/main/java/kaptainwutax/tungsten/path/PathFinder.java` — IOOB clamp
- `tungsten/src/main/java/kaptainwutax/tungsten/task/FollowEntityTask.java` — snapToGround
- `tungsten/src/main/java/kaptainwutax/tungsten/commands/SettingsCommand.java` — full overhaul
- `tungsten/src/main/java/kaptainwutax/tungsten/TungstenConfig.java` — mismatchLogThreshold, airStrafeMultiplier
- `docs/MULTIVERSIONING.md` — tungsten/shredder preprocessor TODO
