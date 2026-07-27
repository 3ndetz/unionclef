# Full audit — tungsten in the mod, break/place plumbing, baritone parity, combat bot

Date: 2026-07-27. Method: 7 parallel source readers + 7 adversarial verifiers (every claim
re-checked against source, 88 findings survived), plus independent spot-verification of the
load-bearing claims. Read-only: no build was run.

Scope questions from the user:
1. How is tungsten wired into the mod today, and what are its key problems?
2. Is block **place** and **break** logic actually plumbed into tungsten?
3. Can tungsten fully replace baritone, and why / why not?
4. Is the combat bot OK?

---

## 0. Two facts that reframe everything

**`baritone/` is not compiled.** `settings.gradle.kts`: `// include(":baritone")  // kept as
source reference, not compiled`. The live pathfinder is **`shredder/`**, which occupies the same
`baritone.*` package namespace. So every `import baritone.…` in altoclef resolves to shredder.
`AGENTS.md` ("all source compiled together") is wrong on this point. "Replace baritone" in
practice means **replace shredder**.

**`TungstenHelper` is dead code.** `TungstenHelper.initReflection()`
(`src/main/java/adris/altoclef/util/helpers/TungstenHelper.java:74`) does
`pfClass.getField("searchTimeoutMs")`, but that field was moved out of `PathFinder` into
`TungstenConfig` — `PathFinder.java:83` literally says `// searchTimeoutMs is now in
TungstenConfig (tungsten.json)`. `getField` throws `NoSuchFieldException` → caught →
`reflectionReady = false`, permanently. Therefore `isTungstenLoaded()` **always returns false**,
and every helper guarded by it (`tryPathTo`, `tryPathToEntity`, `stop`, `isActive`, `isLocked`)
is a permanent no-op. The entire documented "tungsten as a fallback when baritone fails" layer in
`CustomBaritoneGoalTask` and `GetToEntityTask` has never run. (A second latent break: `EXECUTOR`
is `public static PathExecutor EXECUTOR;` with no initialiser, so the reflection can also NPE on
`executorInstance.getClass()` depending on init order.)

Consequence: today, by default, **shredder moves the bot**. Tungsten drives only via its own
`;goto` / `;punk` / `;follow` commands, the py4j/MCP levers, and the `setTungstenPathing(true)`
primary mode — which is off by default and reached only from `deploy/runner/*.py`.

---

## 1. Architecture as it actually is

Tungsten plans in two layers and executes with a third, plus a parallel "walker" path.

**Layer 1 — block-space guide.** `PathFinder.findBlockPath` (`PathFinder.java:766-792`) first
tries `FastPlanner` (a real A* with g-accumulation and `PlayerFit` validation) but **discards its
result unless it is COMPLETE within 250 ms** (line 784). On any non-trivial route it therefore
falls through to the legacy `BlockSpacePathFinder`.

**Layer 2 — physics-sim A*.** `PathFinder.search` (183-463) expands `Node.getChildren`, which runs
~192 full `Agent.tick` physics simulations per expansion, guided toward the block path.

**Layer 3 — execution.** Either `PathExecutor` (open-loop replay of a recorded input tape) or
`BlockPathWalker` (closed-loop sprint from the real position toward block waypoints). Handoff
predicates are scattered across three entry points with unrelated magic distances.

### The move-generation fork — the single most important design fact

`BlockNode.getChildren` (`BlockNode.java:286-319`) has two mutually exclusive modes:

| | `smartMoves = false` (**DEFAULT**) | `smartMoves = true` |
|---|---|---|
| generator | blind radius-8 spherical scan, `getNodesIn3DCircule` (359-415) | `SmartMoves.generate` (typed moves) |
| children/expansion | **~1086** (shallow), **~15 000** (deep retry) | **≤8** |
| validation | `shouldRemoveNode` — ~200 lines of `instanceof` special cases + `PlayerFit` at the end | `PlayerFit.standable` up front |
| break-through | **YES** | **NO** |
| place-through (bridge) | **YES** | **NO** |
| ladders / vines / water / slime | YES | **NO** |
| diagonals | YES | **NO** (4 cardinals only) |

`SmartMoves` returns early at line 300, so `shouldRemoveNode` — and with it *both*
`tryPlanBreakThrough` and `tryPlanPlaceThrough` — is never reached. **The clean move generator and
the build/mine capability are mutually exclusive.** Neither mode is complete.

### Cost model is decorative

`BlockSpacePathFinder.updateNode` (345-364) sets `child.cost = child.cost + 1` — it uses the
child's own cost, not `current.cost + step`. **There is no g accumulation.** The `cost` argument
of the `BlockNode` constructor is discarded (162-168). So every cost the code carefully computes —
the mining-ticks break penalty (`BlockNode.java:675`), the bridge penalty (718), `ActionCosts` —
is a one-off local bump that never propagates. The search is effectively greedy best-first on the
heuristic alone.

### Knowingly-broken distance math on the default path

`getDistFromStartSq` (366-377) computes the Y and Z differences from `start.x`. The comment admits
it: *"copy-paste bug… garbage distances. Correcting it alone regresses the blind-scan search
(course A depends on the garbage-driven partial paths), so the correct form is gated behind
smartMoves."* Default is `smartMoves = false` → **the default path runs on the garbage version**,
and that function gates every partial-path emission and the `failing` flag that arms the timeout.

Downstream: `bestSoFar`'s legacy branch (313-328) `continue`s whenever a node is the furthest so
far, so it can only ever return a node that is *not* the best — an inverted selection, called out
as such in the comment at line 298.

### Why it is slow (PERF-1)

- Blind scan: ~1086 `new BlockNode` + ~10 `world.getBlockState` each ≈ **10 000+ world reads per
  A\* expansion**. Baritone generates ~10-15 neighbours.
- `PathFinder.java:1091-1125`: when `children.size() > 5`, children are chunked and each chunk's
  Callable does `return null;` on the first rejected child — **aborting the whole chunk**. Most
  branching is silently discarded, non-deterministically (depends on ForkJoin scheduling).
- `PathFinder.java:538-590`: the closed set is quantised to 0.01 blocks and keyed on inputs/yaw —
  **essentially no state dedup**, so the search re-expands near-identical states forever.
- The `MIN_PRIORITY` search thread farms its real work onto NORM-priority pools including the
  shared `ForkJoinPool.commonPool` — the "never win CPU against the client thread" comment
  (`BlockSpacePathFinder.java:48-51`) is not what the code does.
- `TungstenModRenderContainer.*.clear()` is called from the search loop **ungated by the render
  config** (`BlockSpacePathFinder.java:209`, `BlockNode.java:315`, `wasCleared` line 328 — the last
  runs per *candidate child*). These are `Collections.synchronizedCollection`, so multiple
  ForkJoinPool threads contend on one lock thousands of times per second. `RenderHelper` has a
  producer-side gate and a 20 Hz throttle; **these direct call sites bypass both.**

### Thread safety

All searches read the **live `ClientWorld` off-thread**, from two worker pools. There is no
`BlockStateInterface` equivalent and no chunk-loaded guard. `VoxelWorld` — the would-be cache — is
never populated and never read (dead stub). Baritone has this abstraction precisely because
pathing runs off-thread; tungsten does not.

---

## 2. Is break / place logic plumbed in?

**Both exist as first-class search moves, and both are far more limited than the docs suggest.**

### Break — `allowBreak = true` (ON by default)

`tryPlanBreakThrough` (`BlockNode.java:638-677`) → `PathFinder.pendingBreaks` →
`PathExecutor.tickBreaking`. Real: it costs mining ticks, honours `BreakRules` (deny lists, zones,
protected areas, block entities), and pays for gravity blocks above the passage.

Limits, all confirmed:
- **Cardinal, same-Y, one cell only** (line 641: `if (dy != 0 || |dx|+|dz| != 1) return false`).
  No break-to-ascend, no break-to-descend, **no digging down, no digging up**. `@gamer` mining
  strategies are not expressible.
- One breakable cell per full re-search, executed standing still.
- The cost is priced with the **currently held item** while the executor swaps to the best tool —
  roughly a 20× mismatch.
- The executor **mines whatever the crosshair hits**, so `BreakRules` is enforced on the *intended*
  block, not the one vanilla actually breaks.
- `mineBlocks()` silently no-ops on any block with an empty collision shape and still reports
  "Mining done".
- Dead in `smartMoves` mode.

### Place — `planPlaceMoves = false` (**OFF by default**)

`tryPlanPlaceThrough` (`BlockNode.java:692-720`) is a genuine mirror of the break move, including
the chaining trick that lets one search plan a multi-cell bridge. But:

- **It ships off**, and nothing in the default path turns it on. Only `;settings planPlaceMoves
  true` or the py4j lever does.
- **One shape only**: horizontal bridge, cardinal, same-Y. **No pillar-up as a search move**, no
  place-beside-wall, no sneak-place, no downward scaffold. Pillaring/godbridging exist only as
  separate reactive tasks (`PillarTask`, `BridgeTask`) bolted on beside the pathfinder.
- **`stringPull` deletes the very nodes carrying the bridge plan** before anything reads it
  (`BlockSpacePathFinder.java:412-429` has no `hasPlaces()`/`hasBreaks()` guard) — so even when
  enabled, the plan can be destroyed between search and execution.
- The progress guard at line 697 compares `estimatedCostToGoal` values produced by two different
  heuristic functions.
- Place-move children bypass the `PlayerFit` body-fit check the same file calls "the last word".

### The user's "placement looks cheaty / instant" complaint — confirmed in code

- `BridgeTask` and `PillarTask` place with a **fabricated `BlockHitResult`** and **no aim
  convergence check** — the interaction packet goes out regardless of where the camera actually
  points. That is exactly "places blocks without looking at them".
- WorldEdit-style fills emit **up to 96 placements inside one client-thread task with no
  throttle** — that is the "6 glass appeared at once, as if replaced by a command" clip.
- The shipped `@goto` bridging behaviour is still the **reactive 14-second-stall patch**, i.e. the
  band-aid the project rules explicitly forbid, because the core version ships off.
- `BridgeTask` has no re-equip and no fallback when the stack empties mid-bridge.
- Build material is a hardcoded 8-item list duplicated in two files, with a third policy elsewhere.

---

## 3. Can tungsten fully replace baritone (= shredder)? No — and here is the ranked why

**Structural coupling.** 78 of 561 altoclef files import `baritone.*` (→ shredder); 7 import
tungsten. The imports are not just pathfinding: `Input` (44 files), `Goal` (23), `Rotation` (16),
and `baritone.altoclef.AltoClefSettings` — **altoclef's own settings class lives inside the
shredder module**. Baritone is not a pluggable backend here, it is a load-bearing type library.

**Capability gaps, ranked by depth of work:**

| Gap | Depth |
|---|---|
| No process layer. `BuilderProcess` / `MineProcess` are live functional dependencies of altoclef; tungsten has no analogue. | core |
| `Goal` is a single `BlockPos`. `GoalNear`/`GoalXZ`/`GoalYLevel`/`GoalRunAway`/`GoalComposite` cannot be expressed even with tungsten-primary on. | core |
| No world cache / no `BlockStateInterface`. Baritone paths through unloaded cached chunks and scans a whole world; tungsten reads the live world off-thread with no snapshot and no chunk guard. | core |
| Move generation: must pick between "has break/place but 1086 children and garbage distances" and "8 clean children but no break/place/ladder/water/diagonal". | core |
| No g-cost accumulation → costs are decorative → no meaningful route optimisation. | core |
| No dimension handling, no portals, no waypoints, no elytra. | bounded feature each |
| Break is one cardinal same-Y cell; no dig-down/up. | bounded |
| Place is one bridge shape, off by default. | bounded |
| Input arbitration: **14 tungsten classes write movement keys across 202 `setPressed` sites**, plus shredder's `InputOverrideHandler`, with no arbitration — resolved only by undocumented call order. `InputOverrideHandler` yields for tungsten's `EXECUTOR` but **not** for its `BlockPathWalker`, so it can mute every walker key press. | core |
| `TungstenHelper` fallback layer dead (see §0). | small fix, big unblock |

**Verdict:** tungsten cannot replace shredder today. The honest sequence is: fix `TungstenHelper`
(one line) → unify move generation so break/place survive the clean generator → add g-cost →
add a world snapshot → build the goal/process abstraction. The first three are the ones that gate
everything the user is actually asking for.

---

## 4. The combat bot — not OK. Structural, not tuning.

### The "stands still when the target is near" root — found, provable

Chain, all verified in source:

1. `PunkPlayerTask.enterCombat()` (`PunkPlayerTask.java:214-220`) **hard-stops all navigation**:
   `PATHFINDER.stop.set(true)`, `EXECUTOR.stop = true`, `FollowEntityTask.stop()`. In COMBAT mode
   the only thing that can move the bot is `CombatController.combatMove`.
2. `combatMove` (`CombatController.java:138-142`) presses forward only when `dist > 3.4` and back
   only when `dist < 2.0`. **Between 2.0 and 3.4 — melee range — neither is pressed.**
3. The only remaining motion is the circle-strafe, and it is suppressed entirely near a drop:
   `strafeSafe == false` → **both** `leftKey` and `rightKey` set false (159-160). On a 1-wide
   bridge (the user's own test scenario) the direction flips every tick and it never strafes at all.
4. So the bot parks at ~3.4 blocks pressing nothing but an occasional hop. That is the reported
   symptom exactly.

### And it parks outside its own reach

`combatMove` is content at `dist > 3.4` measured **centre-to-centre**. `TriggerBot` requires
`REACH = 3.0` measured **eye → closest hitbox point** (`TriggerBot.java:30, 59-63, 80`). At 3.4
centre-to-centre the eye-to-hitbox distance is ≈3.1 > 3.0 → `gateReach` fails. **The movement
controller's "close enough" is looser than the attack gate's reach**, so in steady state the bot
neither closes nor hits. This is a hard logic bug, not an aim-feel issue.

### More, all verified

- **`SafetySystem`'s entire WASD/sprint output is overwritten every tick** by `combatMove`, which
  runs after it in `CombatController.tick` (line 36 then line 94). 49 `setPressed` calls in
  `SafetySystem` are dead on arrival. The code written to fix the range-hold symptom sits in that
  dead writer.
- In the hold band the bot walk-strafes only — MC will not sprint without forward input.
- `VoidGuard` runs *after* `combatMove` and zeroes all four WASD keys whenever the pressed heading
  points at a 4+ block drop. Third writer, same tick.
- **No health input at all** in the tungsten combat engine — two of six declared stages are
  unreachable. No retreat, no eat, no gap-apple, no potion, no totem.
- **No w-tap / sprint-reset / crit timing.** `AttackTiming.canAttack` and `isCritState` have **zero
  callers**. Crit jumps fire on a 280-600 ms random cadence, so crits are accidental.
- **Shield is never raised by the combat engine.** `ShieldBlocker` is reachable only from py4j /
  `CombatPrimitives` — i.e. only if the cognitive agent drives it manually. Directly contradicts
  FIGHT-1 "уметь пользоваться щитом". The primitive also presses `useKey` without checking what is
  in hand.
- `WeaponSelector`: hotbar-only, **enchantment-blind** (plain netherite 100 > Sharpness V iron 75),
  rescans once per 21 ticks, called from **exactly one place** (`PunkPlayerTask.java:202`, COMBAT
  mode only), and its `reset()` is dead. No offhand, no bow/crossbow-by-range logic.
- **Aim and the whole stage machine run per RENDER FRAME with no delta-time term** — every tuning
  constant is framerate-dependent. On the low-FPS stand the tuning means something different than
  on the user's machine. This alone invalidates a lot of past "combat feel" tuning work.
- `WindMouse` keeps accumulating pixel deltas while any `Screen` is open (including chat) and dumps
  the whole pile in one frame when it closes.
- `PunkPlayerTask`'s "no hits for 5 s → re-approach" is a self-perpetuating 5-second interrupt
  cycle, not a recovery — it tears down combat, re-approaches, re-enters, and repeats.
- `KnockbackEstimator`'s enchantment read is a permanent zero and `simulateKnockback` has no
  terrain collision, so the DANGER_BATTLE trigger fires on a physically impossible point.
- `CombatExecutor` burns a 30-tick full physics simulation per 10 ticks purely for a debug overlay,
  and `combatExecutorEnabled` is **read nowhere** — the flag gates nothing.
- **`UnstuckChain` preempts and tears down tungsten follow/punk and throws the aim to a random
  angle** (URG-2 confirmed); `SafeRandomShimmyTask`'s forced baritone inputs nullify tungsten's key
  presses. `MobDefenseChain` is completely tungsten-unaware and preempts tungsten combat at HP≤10.

**Verdict:** the combat bot is structurally missing what a PvP bot needs (health logic, shield
logic, hit-timed crits/w-tap, a single input owner, tick-rate-independent timing), and on top of
that has a movement dead band and a reach mismatch that produce the exact "stands and stares"
behaviour reported. It is not a tuning problem.

---

## 5. Cross-cutting

- **Config persistence poisons defaults.** `TungstenConfig.load()` (`TungstenConfig.java:250-262`)
  unconditionally re-`save()`s the whole object, so once `tungsten.json` exists **every future
  shipped default is permanently shadowed** on that machine. `;settings reset` /
  `resetTungstenConfig()` are the only escape. Any test result from a stand with an old
  `tungsten.json` is suspect.
- **MCP server binds `0.0.0.0` with no authentication and wildcard CORS, enabled by default.**
- `gotoXYZ` / `gotoFar` / `stopPathing` — the primary agent movement levers — are routed through
  the **human chat anti-spam rate limiter**.
- Reactive wall-clock timeouts stand in for missing core capabilities throughout the pipeline
  (the pattern the project rules ban).
- Server-specific data hardcoded in Java source (`ButlerConfig` chat formats).

---

## 6. Suggested order of attack

1. **`TungstenHelper.initReflection`** — delete the three stale field lookups. One line, unblocks
   the whole documented fallback layer. (Verify `EXECUTOR` null-init too.)
2. **Unify move generation** — make `SmartMoves` the single generator and move
   `tryPlanBreakThrough`/`tryPlanPlaceThrough` into it, so break/place survive the clean generator.
   Fix `getDistFromStartSq` at the same time and delete the legacy blind scan. This is REAL-1 +
   PIPE-1 + the perf blocker in one core fix.
3. **Add g-cost accumulation** to the block-space A* so costs stop being decorative.
4. **Fix the chunked child filter** (`PathFinder.java:1111/1118` `return` → `continue`) and the
   1 cm closed-set quantisation.
5. **Combat**: close the 2.0-3.4 dead band, align the hold distance with `TriggerBot.REACH`, pick
   ONE key writer, add delta-time to the aim, add health + shield logic.
6. **Input arbitration layer** — one owner for the KeyBindings, everything else requests through it.
