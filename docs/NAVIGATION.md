# How the bot moves — the engine map

> This file exists because on 2026-07-27 a whole session was spent reworking "the
> pathfinder" and the bot's behaviour did not change at all: the engine that was reworked
> is not the one that drives the bot. It took an experiment to find that out. If this file
> is out of date, fix it first.

Everything below is **measured**, not assumed. Intermediate hypotheses that later
measurements disproved have been deleted rather than left to mislead.

---

## Short answer

One `;goto` starts **two independent pipelines at once**, and the one that moves the bot is
not the one computing the "real" route.

```
;goto X Y Z
   │
   ├── PIPELINE A (fast — this is what walks)
   │      FastNavigator ──> FastPlanner            (plans a leg)
   │                            │
   │                            └──> BlockPathWalker.startBFS(leg)   ← MOVES THE BOT
   │                                 (walks the cells it is GIVEN;
   │                                  it has no search of its own)
   │
   └── PIPELINE B (slow, the "proper" one)
          PathFinder.findBlockPath
             ├── FastPlanner              (used if it finishes inside 250 ms)
             └── BlockSpacePathFinder     (legacy fallback)
                        │
                        └──> PathFinder (physics A*, simulates the player)
                                 └──> PathExecutor (replays the recorded inputs)
```

Both pipelines write **the same movement keys**. Whoever wrote last, wins.

**Pipeline B no longer races pipeline A.** While the navigator drives, `GotoCommand` does
not start its own search for the final goal: the navigator owns the route and asks physics
only for the segments it cannot walk itself. An earlier attempt at this looked like it
proved the two pipelines were load-bearing for each other, because it broke `nav_gaps` —
but the real cause was that the same edit also skipped the `EXECUTOR.cb` retry callback, so
nothing continued the goto after a segment finished. Skipping only the SEARCH regresses
nothing and is what finally let hand-offs fire.

## Four search engines, not three

| engine | what it is | added | who calls it |
|---|---|---|---|
| `CombatPathfinder` | grid BFS, 800 nodes, radius 25, **no jumps**, runs SYNCHRONOUSLY on the client tick | 2026-03, for combat | `FollowEntityTask` (chase) and altoclef `CustomBaritoneGoalTask`. NOT part of `;goto` |
| `FastPlanner` | block A*: typed moves, real g accumulation, admissible heuristic, `PlayerFit` body checks | 2026-07-25 (PIPE-1) | `FastNavigator`, and as the first attempt inside `PathFinder.findBlockPath` |
| `BlockSpacePathFinder` | block A*: blind radius-8 scan (~1086 candidates), **no cost accumulation**, heuristic in different units | initial commit | fallback inside `findBlockPath` |
| `PathFinder` | physics A*: simulates a real player (~192 sims per expansion) | initial commit | pipeline B |

`FastPlanner` and `BlockSpacePathFinder` do **the same job**; the first is correct, the
second is not, and the second was never removed when the first arrived.

## Log fingerprints — which engine is talking

⚠️ Read `docker logs uctest-mc-tester1`, **not** the in-game chat: the chat overflows and
drops lines silently (`Tungsten: Chat overflow, message dropped`). Several conclusions this
session were wrong because a line was missing from the chat, not from the run.

| log line | engine |
|---|---|
| `Walker: BFS N wp`, `Walker: direct→target` | `BlockPathWalker` |
| `FastNavigator: arrived`, `no progress, handing over` | `FastNavigator` / `FastPlanner` |
| `Found rought path!`, `Ran out of nodes`, `Partial path` | `BlockSpacePathFinder` |
| `Time taken to find path`, `Failed!`, `At the wall — mining` | `PathFinder` (physics) |
| `Mining done — passage open`, `Bridge place aborted` | `PathExecutor` |

### Diagnostics left in the code (all behind `verboseDebugLogging`)

These are what finally located the roots after a string of wrong guesses. Keep them.

| line | where | answers |
|---|---|---|
| `GUIDE bot=(...) n=... END(...)` | `PathFinder.search` | what guide the physics search gets, head **and tail** |
| `CLIMB EMITTED` / `CLIMB rejected at ...` | `FastPlanner.step` | whether a climb is generated, and which check kills it |
| `PLAN n=... complete=... firstPhysics=... flagged=...` | `FastNavigator.planAhead` | is the plan complete, are any waypoints flagged for physics |
| `NAVSTATE walker=... awaiting=... pending=... pfActive=...` | `FastNavigator.tick` | why a hand-off does or does not fire |
| `HANDOFF target=(...) rise=... horiz=...` | `FastNavigator` | the numbers the hand-off branch actually sees |
| `SPECIAL at ...`, `WATER-ENTRY ...` | `FastPlanner.special` | whether ladder/water moves are reached at all |

**Printing only the HEAD of a path hid the answer for four attempts.** Print the tail too.

---

## Rules learned the hard way

1. **Before fixing anything, prove by experiment that the code runs.** Breaking this cost a
   whole session (the wrong engine was reworked) and three off-by-one bugs.
2. **Open the function before calling it.** Three bugs in one day came from assuming a
   signature: an inverted coordinate convention, a support check one level too low, and
   `passableAt(cell, 0.1)` — whose third argument is an ABSOLUTE world height, so it asked
   "does the body fit at y=0.1" (open sky, always true) and made a whole capability
   unreachable.
3. **A dead flag is a missing feature, not a detail.** Ten of the eleven roots found this
   session were code that looks alive and never executes: a flag written and never read, a
   message describing an action that does not happen, a task handed to itself, two waits
   deadlocking each other, an unexecutable move, a silently discarded queue.
4. **Changing approach is not changing target.** A half-done course gets finished. What
   changes when you are stuck is HOW: stop patching, re-read the sources end to end until
   you can explain the mechanism, then make one correct fix.

---

## Course status

**Suite score: 6/10.** Green and stable: `nav_flat`, `nav_staircase`, `nav_descend`,
`nav_gaps`, `nav_steep`, `nav_break`.

### `nav_break` — GREEN (previously never passed)

Breaking through a wall works end to end. Roots fixed, in order:

- `FastPlanner` had **no notion of breaking at all** (grep for `allowBreak`/`BreakRules`/
  `toBreak` returned nothing), so a wall across the only corridor was an unreachable goal.
  The receiving half already existed: `BlockNode.toBreak` → `truncateAtBreaks` →
  `PathExecutor.tickBreaking`. Only the producer and the channel were missing.
- The cell-occupancy test used `passableAt(cell, 0.1)` — see rule 2 above — so every wall
  block counted as already open and the move could never fire once.
- The planner preferred to **climb over** a 2-block wall (~30) rather than mine it (~34.6),
  but above jump height the only way up is to pillar, which this planner cannot emit. An
  unexecutable move is worse than no move; such climbs are now only offered when pillaring
  is actually available.
- `PathExecutor` **silently wiped the mining queue** on the `stop` flag. A mining segment
  runs with an EMPTY path, so a drift abort — a statement about a *replay* — has nothing to
  say about it. Narrowed on the executor side; weakening the abort itself regressed
  `nav_gaps` from a stable 6/6 to failing.
- The physics search was aimed at the goal *behind* the wall and burned its full 20 s budget
  (180 attempts per run). It is now aimed at the **approach point** — the end of the
  truncated guide — so physics delivers the bot to the obstacle and the mining machinery
  takes over.

### `nav_wall2` (2-block ledge, needs pillaring) — GREEN

A chain of three, where each link was invisible until the one before it was fixed.

1. **The hand-off was starved.** Everything around it was already correct — the climb was
   generated (`CLIMB EMITTED ... rise 2.00`), the route reached the ledge top, the leg was
   cut at the right waypoint (`PLAN complete=true flagged=1`) — and the hand-off was still
   refused on every single tick, because there is ONE physics search engine and `;goto` was
   running a second search on it for the final goal. That goal sits on top of the ledge,
   which physics cannot climb, so the search never succeeded: full 20 s budget, restart,
   repeat, engine busy forever (`pending=set pfActive=TRUE`).
2. **The first attempt to free the engine broke `nav_gaps`** and was reverted, which made
   the two pipelines look mutually load-bearing. They are not. Re-reading
   `GotoCommand.startWithRetry` end to end showed the edit had returned early and skipped
   the `EXECUTOR.cb` retry callback a few lines below — so after the first executor segment
   nothing continued the goto. Skipping only the search keeps every course green.
3. **With the engine free, the hand-off fired** (`HANDOFF target=(12,-58,0) rise=1.48
   horiz=2.18`) and physics was asked to climb 1.48 blocks, which no jump clears. A target
   above you and nearly overhead is a WALL, not a jump: the only way up is to place a block
   under yourself. `PillarTask` already implemented exactly that — centre, jump, place while
   airborne — ticked from the client mixin and exposed over py4j. Navigation had simply
   never asked for it. It is now asked at the hand-off point.

Result: `Pillaring up to y=-58`, PASS in ~6 s. The pillar itself is clean — all four
attempts across the repeat runs logged `Pillar done ... (placed 1)`, with no `stuck` and no
`no block in hand`. One repeat run of three took 16.5 s and tripped the 6 s freeze
assertion; since the pillar logs are clean that stall is BEFORE the hand-off, not in the
pillar. Not chased without evidence — watch item. Note the shape of this bug — three complete,
working mechanisms in a row, none of them reachable, each hidden behind the previous one.

### The dead special moves (`nav_water`, `nav_slime`, `nav_ladder`)

All three fail in the PLANNER, not the hand-off: the route never reaches the goal and
carries no flagged waypoint at all (`complete=FALSE flagged=0`), i.e. the swim/ladder/bounce
moves never make it into a plan.

**Coordinate convention, measured, because it decides every check here:** `node.y` is the
cell the player's FEET are in. `FastNavigator` plans from `player.getBlockPos()`, which is
the floored entity position, and `PlayerFit.bodyFits(world, x, feetY, z)` takes an ABSOLUTE
feet height. A diagnostic in `special()` claimed `feetY = node.y + 1` — that was wrong and
has been corrected; `node.y + 1` is head height.

**Root A — the search loop deleted every supportless node.** `plan()` popped a node, called
`PlayerFit.supportTop`, and `continue`d on NaN. Water and ladder cells are supportless BY
DEFINITION, so `special()` emitted them and the next line threw them away before they could
expand even once. Fixed: a supportless cell that is water or ladder expands through
`special()` (which needs no floor); anything else is still genuinely unstandable.

**Root B — water entry looked at the wrong level.** You STEP DOWN into a pool: its surface
normally sits one block below the bank. Entry only tested `isWater` at our own foot level
and above, and the cell beside a pool at foot level is the AIR over the water — so a normal
pool was never entered. Fixed: entry also tests one below, like the ordinary walk-down move.

Course geometry was checked first, per the rule below, and this time the arena is FINE:
`nav_water` carves y=FLOOR_Y-2..FLOOR_Y and fills it, i.e. a real 3-deep pool whose surface
is one below the bank. The earlier note blaming the arena was wrong and is withdrawn.

**Known still-missing move — getting OFF a ladder.** The ladder branch climbs the column and
steps onto an adjacent ladder, but has no move from a ladder cell onto an adjacent STANDABLE
cell. On `nav_ladder` the shelf top is beside the ladder top, so even with Root A fixed the
bot can climb and then has nowhere to go. Not yet implemented — measure after Root A lands.

### `nav_slime` — the arena is a bounce puzzle

Reading the builder: the bot spawns on a pad at `FLOOR_Y+7` and must FALL ~8 blocks onto a
slime pad at `FLOOR_Y`, bounce, and land on a ledge at `FLOOR_Y+4`. That is a genuinely
harder problem than swimming — do it after water.

### `nav_water` original failure notes

```
nav_wall2:  PLAN n=19 complete=true  firstPhysics=12 flagged=1
nav_slime:  PLAN n=7  complete=FALSE firstPhysics=-1 flagged=0
nav_water:  PLAN n=12 complete=FALSE firstPhysics=-1 flagged=0
```

These fail in the **planner**, not the hand-off: the route never reaches the goal and
contains no flagged waypoint at all, i.e. the swim and bounce moves added to
`FastPlanner.special()` never make it into a plan.

Known arena caveat for `nav_water`: the course fills water from `FLOOR_Y-2` to `FLOOR_Y`,
i.e. **below** the walking level, so the bot faces a hole with water at the bottom rather
than water at its own level. Check the course geometry before blaming the engine — two
courses have already turned out to be broken arenas rather than broken code.

### `nav_ladder` — RED

`ClimbALadderMove` exists and is wired (`Node.java:134`). It used to press **jump only**,
with the agent's current yaw; a ladder is climbed by holding **forward into it**, which is
what produces the `horizontalCollision` that `Agent.java:719` needs to grant climbing speed.
Fixed to face the ladder and hold forward+jump — the course is still red, so something
further along the chain remains.

---

## Where to fix things (strategy, not band-aids)

1. **One block planner.** `FastPlanner` is the correct base; move the remaining capabilities
   into it (ladder, water, slime, place/pillar). Delete `BlockSpacePathFinder` afterwards.
2. **`BlockPathWalker` must not own a search.** It should execute the path it is given.
   `CombatPathfinder` belongs to combat.
3. **One pipeline, not two.** Done for `;goto`: physics is now an executor for
   `needsPhysics` segments, not a second router. The other entry points that still start
   their own search (`followPlayer`, altoclef goal tasks) should be moved the same way.
4. **One key owner.** Combat already does this (`CombatMoveIntent`); navigation does not.
