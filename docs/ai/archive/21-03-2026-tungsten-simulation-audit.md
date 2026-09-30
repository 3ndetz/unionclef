# Tungsten Simulation Audit — 21.03.2026

## Investigate

### Comparing tungsten simulation vs vanilla MC 1.21.1

Tungsten was taken from MC 1.21.8 and run on 1.21.1. A byte-by-byte bytecode analysis of vanilla Entity/LivingEntity/PlayerEntity/ClientPlayerEntity vs Agent.java was performed.

**Discrepancies found with vanilla:**

| Discrepancy | Vanilla | Tungsten | Impact |
|---|---|---|---|
| Velocity zeroing X/Z | 0.003 | 1e-5 | Micro-velocities aren't zeroed |
| BlockPos in pushOutOfBlocks | MathHelper.floor() | (int) cast | Wrong block when X<0 |
| Diagonal input normalize | Not in KeyboardInput | Vec2f.normalize() in AgentInput | ~2% difference in diagonal speed |
| airStrafingSpeed init | 0.02/0.026 | 0.06 (hardcoded) | 3x air control |
| Friction block (fences) | getVelocityAffectingPos() with fence check | floor(minY-0.5) without fence check | Wrong friction on fences |
| fallDistance type | float | double | Minor precision drift |
| movementSpeed update | Attribute every tick | setSprinting() recalculation | Timing difference |
| Diagonal normalization (1.21.4+) | applyDirectionalMovementSpeedFactors | Present in the code | Not needed for 1.21.1 |
| Server teleport packets | Processed | ci.cancel() — were being swallowed | Deadlock on teleport |
| Entity collisions in pathfinder | Thread-safety not guaranteed | getEntityCollisions from a parallel thread | ConcurrentModificationException |

### Key finding: simulation = pathfinding

Agent.tick() is used both for per-tick validation and for A* pathfinding. Any physics change affects both. The pathfinder's heuristics (cost function, pruning, node generation) are implicitly tuned to the current physics. Changing the physics requires retuning the heuristics.

### Speed I beacon

A hidden beacon was found near the test zone, giving a Speed I effect. It explained the persistent mismatch of 0.156 vs 0.13 (sprint + Speed I vs sprint).

## Plan

### Safe fixes (don't change Agent.tick physics)

- [x] Fence friction — getVelocityAffectingPos with fence/wall/gate check
- [x] BlockPos flooring — MathHelper.floor() instead of (int) cast
- [x] Server teleport — don't cancel PlayerPositionLookS2CPacket, stop the executor instead
- [x] EntityTrackerUpdate — don't cancel
- [x] ConcurrentModificationException — remove getEntityCollisions from the pathfinder thread
- [x] IOOB in PathFinder.processNodeChildren — bounds clamp
- [x] Diagonal normalization 1.21.4+ — comment out for 1.21.1
- [x] Follow target snap — snapToGround for a sneaking target
- [x] Logging — threshold, aligned format, drift details in chat
- [x] Settings command — overhaul, reload, airStrafe, mismatchThreshold
- [x] MULTIVERSIONING.md — TODO for the tungsten/shredder preprocessor

### Simulation fixes (correct, but break pathfinder heuristics)

- [x] Velocity threshold 0.003 → **REVERTED** back to 1e-5
- [x] AgentInput.normalize() removed → **REVERTED** back
- [x] airStrafingSpeed 0.06→0.02 → **REVERTED** back to 0.06
- [x] setSprinting movementSpeed recalc → **REVERTED** to the original
- [x] fallDistance double→float → **REVERTED** back to double

### TODO: the correct path to an accurate simulation

To apply simulation fixes without breaking pathfinding:

1. **Apply a simulation fix** (e.g. velocity threshold 0.003)
2. **Analyze how the pathfinder's search space changed**
3. **Retune the heuristics/costs** in Node.getChildren, calculateNodeCost, processNodeChildren
4. **Test** that the pathfinder finds paths AND the bot walks them
5. Repeat for the next fix

Each fix is a separate iteration. Don't change everything at once.

### TODO: closed-loop execution

The current executor is open-loop: it blindly replays pre-computed input. Drift accumulates and the path aborts. A closed-loop is needed: correct yaw/input every tick based on the actual position vs the expected one.

### TODO: idle movement (the bot is always moving)

An idle circular-route generator while the pathfinder is computing. Seamless idle→real path switchover.

## Implement

### Commits (kept)

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

### Commits (reverted)

- `a7db22b` → `b1a0449` — velocity threshold 0.003 → reverted to 1e-5
- `e06ac12` → `98c8f29` — fallDistance float → reverted to double
- `9744794` → `98c8f29` — normalize removal → reverted
- `f645c24` → `98c8f29` — airStrafingSpeed 0.02 → reverted to 0.06
- `fcc9c4c..83ad11d` → `98c8f29` — movementSpeed/setSprinting chain → reverted

### Files changed (final state)

- `tungsten/src/main/java/kaptainwutax/tungsten/agent/Agent.java` — fence friction, BlockPos floor, entity collision removal, logging threshold
- `tungsten/src/main/java/kaptainwutax/tungsten/agent/AgentInput.java` — unchanged (normalize restored)
- `tungsten/src/main/java/kaptainwutax/tungsten/mixin/MixinClientPlayNetworkHandler.java` — don't cancel server packets
- `tungsten/src/main/java/kaptainwutax/tungsten/path/PathExecutor.java` — tryReconnect, drift comment
- `tungsten/src/main/java/kaptainwutax/tungsten/path/PathFinder.java` — IOOB clamp
- `tungsten/src/main/java/kaptainwutax/tungsten/task/FollowEntityTask.java` — snapToGround
- `tungsten/src/main/java/kaptainwutax/tungsten/commands/SettingsCommand.java` — full overhaul
- `tungsten/src/main/java/kaptainwutax/tungsten/TungstenConfig.java` — mismatchLogThreshold, airStrafeMultiplier
- `docs/MULTIVERSIONING.md` — tungsten/shredder preprocessor TODO
