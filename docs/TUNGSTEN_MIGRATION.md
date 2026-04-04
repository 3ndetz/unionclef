# Tungsten Migration: upstream server-side branch

Upstream: `Hackerokuz/Tungsten` branch `server-side`
Target: MC 1.21.11, yarn 1.21.11+build.3, Fabric API 0.140.0+1.21.11, Loom 1.15-SNAPSHOT

## New features and improvements to port

### 1. Water/swimming overhaul (HIGH PRIORITY)

3+ commits dedicated to water mechanics. Changes across:
- `EnterWaterAndSwimMove.java` -- new water entry logic
- `ExitWaterMove.java` -- exit mechanics
- `SwimmingMove.java` -- swimming path calculation
- `Agent.java` -- water physics simulation

Currently our tungsten has basic water handling; upstream has iterated heavily on getting this right.

### 2. BlockSpacePathFinder improvements

- Fixed bug where "most of the nodes were never considered" (hashcode collision issue)
- Improved closed-node hashcodes
- Improved heuristics with water/proximity weighting
- Better `bestSoFar()` function for partial paths
- String-pulling path simplification

### 3. Retry system

New retry mechanism for pathfinding -- when initial search fails or times out, the pathfinder can retry with adjusted parameters. This is new in upstream.

### 4. Block state checker expansion

New utility methods:
- `isSlab()`, `isDoubleSlab()`, `isBottomSlab()`, `isTopSlab()`
- `isTrapdoor()`, `isOpenTrapdoor()`, `isClosedBottomTrapdoor()`
- `isWater()`, `isFlowingWater()`, `isAnyWater()`, `isWaterLogged()`
- `isLava()`, `isFlowingLava()`, `isAnyLava()`
- `isConnected()` -- fence/wall/pane connectivity checks
- Added more blocks to `fullyPassableBlockState` check

### 5. Jump cost increases

Jump moves now cost more in pathfinding, which should prefer ground-level paths when available. Affects `ActionCosts` and move cost calculations.

### 6. Command system refactor

New `commandsystem/` package with:
- `Filtering.java` -- suggestion filtering API (STRICT, SLIGHTLY_LOOSE, LOOSE modes)
- Cleaner command/argument separation
- Pipe-separated command chaining improvements

### 7. Server-side path execution

`PathExecutor` now supports `ServerPlayerEntity` via `PlayerInput` (MC 1.21.4+ API). Uses `player.setPlayerInput()` instead of key presses. We disabled this in our version (comment on line 93-94) because `PlayerInput` record doesn't exist in 1.21.1.

### 8. Rendering improvements

- Frustum culling for debug renderers (only render visible cubes)
- Max 500 renderers per category (performance cap)
- `RenderLayer.getDebugLineStrip(2).draw()` for line rendering

## DANGER: Position correction in upstream

**The upstream executor cheats.** In `Agent.compare()`, the upstream version:
1. Detects position mismatch between simulation and real player
2. Calls `player.setVelocity()` and/or `setPosition()` to force-sync

This is exactly what anti-cheat detects. Our modifications:
- **Stop executor** on drift instead of correcting position
- **Re-simulate from real position** (same inputs, different start) -- legitimate
- **Try reconnect** by scanning ahead for matching nodes -- no position writes
- **Never call** `setPosition()`/`setVelocity()` on the real player

When porting upstream changes, DO NOT bring in any position/velocity correction code.

### Our custom features to preserve

| Feature | File | Description |
|---------|------|-------------|
| Native rotation | `PathExecutor.applyNativeRotation()` | Pixel-quantized via `changeLookDirection` |
| Pitch look-ahead | `PathExecutor.calculateLookAheadPitch()` | Cosmetic pitch toward future nodes |
| Re-simulation | `PathExecutor.resimulateFromRealPosition()` | Fix stale start without cheating |
| Reconnection | `PathExecutor.tryReconnect()` | Scan-ahead path recovery |
| Drift detection | `Agent.compare()` | Stop executor on mismatch, never correct |
| TungstenPlayerInput | `agent/TungstenPlayerInput.java` | Replaces MC 1.21.4+ PlayerInput record |
| WindMouse rotation | `MixinInGameHud`, `MixinMouse` | Human-like camera in render frames |
| ChatHud fix | `MixinChatHud` | Off-by-one vanilla bug fix |
| Teleport handling | `MixinClientPlayNetworkHandler` | Let vanilla handle teleports, stop executor |
| Drift config | `TungstenConfig` | driftThreshold, driftCorrectionEnabled, etc. |

## Ported changes (status)

| Change | Status | Notes |
|--------|--------|-------|
| processingTasks bug (nodes never considered) | DONE | `tasks.add()` → `processingTasks.add()` |
| Node hashCode (remove velocity) | DONE | Less closed-set pollution |
| sortNodesByYaw removal | DONE | Unbiased search |
| Jump node color (cyan → mauve) | DONE | `Color(150, 55, 85)` for jumps |
| BlockNode isDoingJump +6.5 cost | DONE | Prefer ground paths |
| DivingMove cost 0.00002 → 0.2 | DONE | Avoid diving |
| RunToNode/SprintJumpMove water-break | DONE | Cost penalty when entering water |
| SwimmingMove non-swimming penalty | DONE | Prefer staying in swim state |
| SprintJumpMove horizontal collision cost | DONE | +0.00004 penalty |
| ExitWater: skip if target is water | DONE | |
| RunToNode NPE fix (parent chain) | DONE | Null-check in while loop |
| Walk/Run distance conditions | DONE (gated) | Preprocessor gate: `MC >= 12111` uses upstream (walk >12, run <14), else no distance threshold. Was REVERTED before because upstream thresholds broke 1.21.1 |
| SprintJumpMove loop colors → purple | DONE | `(0, 255, 150)` → `(147, 17, 222)` main loop, `(255, 0, 0)` → `(24, 17, 222)` fall damage |
| RunToNode loop colors → pink | DONE | `(0, 255, 150)` → `(222, 17, 186)` main loop, `(255, 0, 0)` → `(24, 17, 222)` fall damage |
| DivingMove colors → uniform blue | DONE | Mixed blues `(0, 25/85/125/105, 150)` → uniform `(0, 0, 150)` |
| Retry system (resetSearch) | NOT PORTED | Adds complexity, test separately |
| Water/swimming full rewrite | NOT PORTED | Major refactor, risk of regression |
| Command system refactor | NOT PORTED | We have our own command handling |
| Server-side PathExecutor | NOT PORTED | No server-side use |
| calculateNodeCost base 1→4.358 + penalties | NOT PORTED | Upstream has velocity stall +15, horizontalCollision +0.0004, water +0.2, lava +2e6, removed yaw penalty. Needs careful testing |
| Node jump extra cost +2.4 | NOT PORTED | Upstream adds +2.4 to jump node cost in createNode(). Physics-dependent |

## Physics version notes

We now target MC 1.21.11 (same as upstream), which has diagonal movement
normalization (MC-271065, added in 1.21.4+). The preprocessor gate in
`Agent.java:1364-1368` handles this: `//#if MC >= 12104` enables
`applyDirectionalMovementSpeedFactors()`.

Version-dependent numerical constants (costs, distance thresholds) are
gated with `//#if MC >= 12111` preprocessor directives where needed
(e.g. walk/run distance thresholds in `Node.java`).

### Files NOT to port (upstream-only)

- `fakeplayerapi` dependency -- server-side fake player, not needed for client bot
- `ServerSideTungstenMod` entrypoint changes -- we don't run server-side
- Any `player.setPosition()`/`setVelocity()` calls in compare/executor
