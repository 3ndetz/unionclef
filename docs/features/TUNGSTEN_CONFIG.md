# Tungsten — configuration (`config/tungsten.json`)

Changes on the fly: `;settings <name> <value>` (bool/numbers), or by editing the JSON
and rejoining. `;settings` with no arguments prints the current values.
IMPORTANT: the saved file overrides the mod's new defaults — after an update,
check the key flags (or delete the file, it will be recreated with defaults).

## Block breaking

| Field | Default | What it does |
|---|---|---|
| `allowBreak` | `true` | Allow the planner to break through breakable walls (`;goto` through obstacles). `false` — go-around only. |
| `breakCostMultiplier` | `1.0` | Cost multiplier for breaking in the planner. Higher — prefers going around more. |
| `breakDenyBlocks` | `[]` | Block ids that can NEVER be broken (e.g. `"minecraft:diamond_block"`). Blocks with a block entity (chests, spawners, furnaces) are always forbidden. |
| `breakDenyZones` | `[]` | Break-denial zones: arrays `[x1,y1,z1,x2,y2,z2]` (inclusive, corner order doesn't matter). |
| `driftThreshold` | `0.8` | Threshold for the replay-vs-simulation mismatch (blocks) before aborting the path. On routes with breakthroughs (squeezing into a 1-block hole), raise to `1.5`. |
| `driftCorrectionEnabled` | `false` | `true` — teleport the player to the simulated position on drift (cheat-style); `false` — stop and recompute. |

Plus altoclef's protections: its break-avoiders (bed protection, task
protected zones) automatically apply to tungsten too via `canBreakHook` — no
separate setting needed.

## Combat

| Field | Default | What it does |
|---|---|---|
| `combatTriggerBotEnabled` | `true` | Auto-hit when the gate passes (reach/LOS/angle/cooldown). |
| `combatRotatesEnabled` | `true` | Auto-aim at the target (WindMouse). |
| `combatMovementsEnabled` | `true` | Combat footwork: closing in, finishing the last half-block, sprinting. `false` = stands still and gets kited. |
| `combatSaverEnabled` | `true` | Survival system: braking at edges, retreating on dangerous knockback. |
| `combatExecutorEnabled` | `false` | Experimental attack-window planner (visualization only for now). |
| `combatWindMouseGravity` | `12.0` | Aim-convergence speed. Higher — faster, lower — more human-like. |
| `combatWindMouseWind` | `0.15` | Random aim jitter. |
| `combatWindMouseMaxStep` | `25.0` | Max degrees of rotation per frame. |
| `combatWindMouseFlickScale` | `3.0` | Flick acceleration for large angles. |
| `combatWindMouseWindDist` / `DoneThreshold` | `15.0` / `0.4` | Jitter decay near the target / snap threshold. |

⛔ CORRECTED 2026-09-01: the gravity/wind/maxStep/doneThreshold values were the old ones (2.0/0.8/4.0/0.5)
— rechecked against `TungstenConfig.java`, where these fields were retuned back on July 24, 2026 after
a user complaint that "users jerk the mouse SHARPLY" (real players jerk the mouse rather than move it smoothly).

## Following

| Field | Default | What it does |
|---|---|---|
| `enableLeap` | `false` | Sprint-jumps to a nearby target without A* (combat/altoclef drives the camera). |
| `enableTrailing` | `false` | Trail mode: follow the target's trail when the gap exceeds >20 blocks. |
| `followBlockPathFinderEnabled` | `true` | BFS-walker for an instant start while A* is thinking. Used to default to `false` — that was the root cause of LIVE-A ("moving target — the bot stands still", `TODOS.md`, fixed in v0.52.0, 2026-07-24); rechecked against `TungstenConfig.java` on 2026-09-01, the flag is now enabled. |
| `followJumpingEnabled` | `true` | Allow jumping while following. |

Re-plan hysteresis is hardcoded: no more than once per 2s, and only when the
target shifts more than max(3 blocks, 25% of remaining distance).

## Paths and execution

| Field | Default | What it does |
|---|---|---|
| `searchTimeoutMs` | `15000` | Physics-search budget (ms). Follow sets its own 120–3000 based on distance; `;goto` returns 15000. |
| `enableParallelStreaming` | `true` | Parallel A* node generation. Disable if you see rare ConcurrentModificationException. |
| ~~`airStrafeMultiplier`~~ | — | ⛔ REMOVED, VERIFIED 2026-09-01: no field with this name exists anywhere in `tungsten/` (grep of the whole module — empty). Either renamed or removed during one of the physics-engine overhauls; the line is left struck through so it isn't confused with an EXISTING lever. |
| `enableNativeRotation` | `true` | Rotations via pixel mouse math (indistinguishable from a mouse to anti-cheats). |
| `enablePitchChange` / `pitchLookAheadNodes` | `true` / `5` | Cosmetic: look at path nodes ahead. |
| `verboseDebugLogging` | `false` | Verbose log (path emissions, trigger gate, timings) — for debugging. |
| `debugTime` | `false` | Pathfinder timings to stdout. |

## Compatibility (ViaVersion servers)

| Field | Default | What it does |
|---|---|---|
| `avoidStuckFence` | `true` | Don't route paths through fences/walls whose connection collisions are invisible to the client. |
| `avoidStuckAnvil` | `true` | Don't approach anvils from the side (collision rotation can differ). |
| `predictDamageFromBlocks` | `true` | Account for damage-slowdown (cactus/fire/bush) in the simulation. |

## Py4j prediction API (external control)

- `canBreakBlock(x, y, z)` → bool — whether the break policy allows this block
  (accounts for config, zones, block entities, and altoclef protections).
- `canReach(x, y, z, withBreaking)` → map: `found`, `pathSize`, `breaks`
  (how many plan blocks will have to be broken), `endDistance` (how close the
  draft route got to the target; a small value = actually reachable),
  `busy=true` if the pathfinder is busy. Heuristic for "can we get there: with breaking / without".
