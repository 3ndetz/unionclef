# Tungsten: enableHumanizedMovements — Analysis

## What it would do

A setting to constrain pathfinder output to "human-plausible" movements:

1. **Max rotation per tick**: reject nodes where yaw changes by more than ~30 degrees between consecutive ticks (a real player can't flick 90 degrees per tick consistently)
2. **No edge jumps**: reject jumps that start from the very edge of a block (within ~0.1 blocks of the border) — real players jump from the middle-ish area

Both reduce fall risk and make the bot harder to detect.

## Current state

- Yaw is explored in **22.5-degree increments** over a ±90-degree arc from the desired direction (`Node.java:273-279`).
- There is **no per-tick rotation cap** — the pathfinder can produce paths that snap yaw by 90+ degrees between ticks.
- Jump positions are not filtered by distance-from-block-edge. The agent can jump from any position where `onGround == true`, including the very last pixel of a block.

## Implementation plan

### 1. Max yaw delta constraint

**Where**: `Node.java` — `createNode()` and `createAirborneNodes()`

**How**: After creating a new node, check `Math.abs(newNode.agent.yaw - this.agent.yaw)`. If > 30 degrees (configurable), return null / skip.

**Affected code paths**:
- `generateGroundOrWaterNodes()` — the main loop at line 279 already iterates yaw values. Add a filter: `if (Math.abs(yaw - agent.yaw) > maxYawDelta) continue;`
- `generateAirborneNodes()` — line 371, the three candidate yaws. Filter the same way.
- `createNode()` — the inner simulation loop (line 330-339) reuses the same yaw, so no issue there.
- Special moves (`SprintJumpMove`, `LongJump`, `WalkToNode`, `CornerJump`, `NeoJump`) — these set yaw to `desiredYaw` directly. Would need to clamp: `yaw = clamp(desiredYaw, agent.yaw - maxDelta, agent.yaw + maxDelta)`.

**Risk**: If the target is behind the player, 30 degrees/tick means ~3 ticks to turn 90 degrees. The pathfinder would need to generate "turn in place" nodes (forward=false, no movement, just rotating). Currently `generateGroundOrWaterNodes` always includes `forward=true/false` combos, so this should work — the pathfinder will naturally pick "stand + rotate" nodes before "sprint + jump". The cost function already penalizes large yaw changes (`Node.java:365`: `Math.abs(agent.yaw - this.agent.yaw) * 5`).

**Complexity**: **Medium**. The filter itself is trivial, but special moves need individual attention, and the pathfinder will be slower (fewer valid children per node = more nodes explored). May need to increase the node budget.

### 2. No edge-of-block jumps

**Where**: `Node.java` — `createNode()`, plus special moves that initiate jumps.

**How**: When `jump == true && agent.onGround`, check distance from agent position to nearest block edge. If < `minJumpMargin` (e.g. 0.15 blocks), skip/return null.

```java
double dx = agent.posX - Math.floor(agent.posX); // [0, 1)
double dz = agent.posZ - Math.floor(agent.posZ);
double edgeDist = Math.min(Math.min(dx, 1 - dx), Math.min(dz, 1 - dz));
if (edgeDist < minJumpMargin) return null; // too close to edge
```

**Affected code paths**:
- `createNode()` line 306 area — add the check when `jump == true`.
- `SprintJumpMove.generateMove()` — the first node with `onGround` jump.
- `LongJump.generateMove()` — same.
- Other jump-initiating moves.

**Risk**: **Medium-High**. This dramatically shrinks the set of valid jumps. Many parkour jumps in Minecraft are edge-to-edge by design. 1-block gaps, 2-block gaps, neo jumps — all require edge jumps. With this enabled, the pathfinder may fail to find paths that a less constrained search would find.

Mitigation: make `minJumpMargin` configurable and small (0.05-0.1), or only apply it to "optional" jumps (not sprint-jump moves or long jumps where edge proximity is expected).

### 3. Pitch smoothing (already done)

`enablePitchChange` (implemented) handles smooth pitch transitions. Under humanized mode, pitch changes would also be rate-limited to ~30 deg/tick, matching the yaw constraint.

## Will it break the pathfinder?

**Yaw constraint**: Unlikely to break it, but will make it slower and may fail on tight parkour that requires instant 90-degree turns. The cost function already encourages small yaw changes, so in practice most paths already have reasonable yaw deltas. The constraint just hard-caps it.

**Edge jump constraint**: **This is the risky one.** Many block-to-block jumps require the player to be near the edge. A strict constraint (> 0.15 blocks from edge) would make 4-block sprint jumps impossible and many 3-block jumps marginal. Recommendation: only filter "gratuitous" edge jumps where the player walks to the edge unnecessarily, not jumps where edge proximity is required by the move type.

## Estimated effort

| Component | Difficulty | Files to change |
|-----------|-----------|-----------------|
| Yaw delta cap in Node | Easy | `Node.java` |
| Yaw delta cap in special moves | Medium | 6-8 special move files |
| Edge-of-block filter | Easy (code), Hard (tuning) | `Node.java` + special moves |
| Config flags | Easy | `TungstenConfig.java` |
| Testing / tuning | High | — |

Total: **~2-3 days** of work including testing. The tuning is the hardest part — finding margins that feel human without breaking parkour.
