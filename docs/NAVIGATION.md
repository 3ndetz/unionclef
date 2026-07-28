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

**Suite score: 9/10 on per-course runs** (released as 0.64.0). Green: `nav_flat`,
`nav_staircase`, `nav_descend`, `nav_gaps`, `nav_steep`, `nav_break`, `nav_wall2`,
`nav_water`, `nav_ladder`. Red: `nav_slime`.

⚠️⚠️ **THE STAND DECIDES MARGINAL COURSES — ALWAYS A/B ON THE SAME SESSION.**
Over a long session this stand drifts from ~15 fps to ~9, and at 9 fps a marginal course
flakes no matter what the code says. On 2026-07-28 `nav_gaps` fell to 1 pass in 3 and looked
exactly like a regression from the walker changes; those changes were reverted on that
signal. Then the last KNOWN-GOOD build was rebuilt and run on the same stand: it flaked
**identically**, 1 in 3. The code was never the cause, and the revert was wrong — it was
undone once the A/B proved it.

Rules that follow, and they are cheap:
- A suspected regression is not a regression until the previous build is measured in the
  SAME session, on the SAME stand. `git stash` + `git checkout <good> -- <files>` + build.
- Read `avg_fps` on every verdict. Below ~12 treat pass/fail on a marginal course as noise.
- Restarting the containers does NOT restore fps; the drift is on the host.

⚠️ **Per-course runs are the trustworthy measurement right now.** A back-to-back series of
ten degrades the stand: the last full sequential run reported 6/10 with `nav_wall2` INVALID
at 9.8 fps and no build running, while every one of those courses passes on its own. That
is a stand problem, not a bot one — but it means "the suite says N/10" needs the caveat.

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

**Root C — the pool had no bottom.** Reading the builder was not enough; the run had to be
traced. The arena floor is ONE layer thick over the void, so carving three blocks down left
a floating cube of water with no bottom and no walls. With Roots A and B fixed the bot did
enter and swim (`SPECIAL at (15,-61,0): water@feet=true` — mid-pool), then sank out through
the missing bottom: `(12.6,-64,-4.2)` — below the pool floor AND outside its z range — and
fell to y=-169. Fixed by building a solid block and carving the pool inside it.

Note the sequence: "the arena looks fine when you read it" was itself wrong. Three courses
have now turned out to be broken arenas. Read the builder AND trace the positions.

**Root D — the search planned to MINE the ladder it meant to climb.** Ladders carry a real
(thin) collision box, so the break move's occupancy test counted one as an obstruction:
`break-through planned at 9,-60,0 (2 block(s))`, aimed straight down the ladder column, and
the bot fell out of the world at x=9.5. Climbables are now left to `special()`.

**Root E — no move for getting OFF a ladder.** The water branch has an exit clause (step out
onto the bank); the ladder branch never did, so a ladder was a one-way trip. Now it steps
onto a standable cardinal neighbour at our level OR ONE UP — a shelf beside a ladder top
normally sits one above the last rung, so a level-only check finds nothing.

With D and E fixed the PLANNER solves the course: `SPECIAL` fires on every rung
(9,-60)...(9,-56) and the plan comes out `complete=true`. Final distance went 97.7 -> 5.5.
**Still RED: executing the climb.** Special moves are emitted flagged, i.e. delegated to the
physics engine, and physics is not getting the bot up. Next measurement belongs there —
`ClimbALadderMove` (`Node.java:134`) was reworked once already and never verified to run.

⚠️ `nav_ladder` had NO `verboseDebugLogging` in its scenario settings, which is why it
produced no diagnostics at all and the first pass at it was guesswork. Added.

⚠️ Read logs with a window BOUND TO THE RUN (`docker logs | tail -n +$BEFORE`). An unbounded
`tail -600` pulled in a previous course's lines and produced a confident, wrong conclusion
that arenas leak between courses. They do not — `ArenaBuilder.prepare` clears the cube.

### `nav_slime` — RED, and the block-space side is now solved

The move used to be ONE compound edge straight from the lip to the far landing, which left
no waypoint on the slime at all. Physics is guided by those waypoints, so it was handed
"get from x=6.5 to x=18" in one piece and answered `Partial path (goal unreachable)` 208
times in a single run. It is now two ordinary moves — fall ONTO the slime, bounce OFF it —
so the route carries the touch point and each half is short.

Both distances are read off the simulator instead of guessed. `Agent.java:832-836` flips
velY outright, so a slime bounce is LOSSLESS and the apex equals the drop; `Agent.java:849-856`
damps horizontal speed only once you have SETTLED (|velY| < 0.1), so speed carries straight
through. Airtime therefore grows with sqrt(height) and travel is airtime x a preserved
sprint speed — a fall buys HALF of what a bounce does, being one way. The old flat cap of 4
made the ledge unreachable by construction, and bumping it twice moved nothing, which is
what a wrong model looks like from outside.

Measured progress: `flagged` 0 -> 1, plan `complete` false -> TRUE, the guide now contains
the slime touch point, `self_falls` 1 -> 0, final distance 20.7 -> 15.1.

**THE DROP NOW LANDS ON THE FAR EDGE, AND THE DEATHS ARE GONE.** Worked out from the trace,
not from taste: a bounce leaves the pad at about +1.05 blocks/tick, which under vanilla
gravity keeps the bot above the ledge's level for ~17 ticks; at the measured 0.26 blocks/tick
that is 4.4 blocks of travel. The ledge starts at x=17, so the bounce has to begin at
x >= 12.6 — the pad's LAST cell. When every landing on the pad was offered, the search took a
near one and spent the height on hops that each shed speed. Only the furthest reachable slime
cell is emitted now (descending scan, first hit wins).

Measured: `nav_slime` went from one landing in three with a void death on the other two, to
**8.4-8.9 blocks short with ZERO falls, 3 runs of 3**. It also retro-explains an earlier
result — charging for horizontal air travel, which biased the search towards the NEAR edge,
measured worse, and now it is clear why.

**A PASSIVE BOUNCE CANNOT CROSS THIS GAP — arithmetic, not opinion.** Traced: the bot leaves
the pad's end at x=13.4 with its apex at y=-55.5. Reaching the ledge from there means 4
blocks of horizontal travel while descending 0.6 — about four ticks, or 0.8 blocks at the
measured speed. It is short by a factor of five, so NO throttle policy over a passive bounce
can ever do it. The pad also cannot be entered at its far edge directly: the fall from the
lip carries about 3.7 blocks, and the pad's far edge is 6.6 away, so the crossing necessarily
starts near the pad's beginning. What is left is a jump-boosted bounce, aimed, on the last
pad cell — tried, and it still killed the bot 6-8 times a run.

⚠️ **AND THE COURSE IS NOT RELIABLY MEASURABLE AT THIS STAND'S CURRENT ~9 fps.** The same
build, with no planner change between the runs, produced 8.4-8.9 blocks short with zero falls
three times in a row, and later 20.7 with a fall three times in a row. Two attractors, and
which one you get is not decided by the code. Conclusions about nav_slime drawn from a single
series here are not evidence — restore the stand's performance first, then measure.

**A DEDICATED EXECUTOR EXISTS AND IS OFF BY DEFAULT (`slimeCrossing`).** `SlimeBounceTask`
is the right architecture — a crossing is ONE manoeuvre, which is what the walker rules below
could never express — and it is verified to run (starts and bounces counted over py4j, not
read off the chat, which drops messages here). Its POLICY is unfinished and the numbers say
so: constant sprint at the exit gives 5-7 void deaths per run, against 8.6 blocks short and
zero deaths with it off. Everything tried on top:

| crossing policy tried | effect |
|---|---|
| full sprint + jump on the slime (launched bounce) | 5-6 deaths per run — the launch clears the pad entirely |
| passive bounce, no jump | 5-7 deaths |
| release the throttle only over the FINAL landing | still 5-6 deaths, best distance 6.7 |
| exit = first non-slime cell in the route | aimed at x=14, one step past the pad and over the VOID — traced closing to horiz 0.3 while falling to y=-88 |
| exit must have a real floor under it | still 6-7 deaths |
| retried after the far-edge landing fix, so the bounce starts where the maths says it can reach | still 9 deaths — the constant sprint is the problem, not the launch point |
| jump-boost on the LAST pad cell only (a passive bounce provably cannot cross) | still 6-8 deaths |

The remaining suspicion, and where the next pass starts: the executor is doing what it is
told, so the doubt now falls on the PLAN it is told to follow — the reach model may still be
optimistic under the executor's real conditions, i.e. the ledge may not be reachable from
where the route starts the bounce. That is a planner question, not a policy one, and it is
answered by tracing one crossing against the model's own prediction.

**THE EARLIER CONCLUSION, after four walker rules were tried and measured:** a bounce chain needs
its OWN executor, the way pillaring has `PillarTask`. The generic walker treats a bouncing
surface as ordinary walking, and every rule bolted onto it fixes one phase and breaks
another — each of these was built, run and measured, and the numbers are the reason each
verdict is what it is:

| rule tried | effect |
|---|---|
| hold the landing waypoint while airborne above it | 20.7 with a void fall every run -> ~8.4, no falls, but only ~1 run in 3 |
| release that hold once we have flown PAST the waypoint | WORSE — 3 failures in 3; the bot needs to keep chasing it |
| cut the throttle over the landing | needed, or the arc overshoots — but it also bleeds a bounce chain from 0.25 to 0.00 blocks/tick |
| exempt bouncy landings from that cut | no measurable change |
| charge for horizontal air travel, to prefer the near edge | WORSE — 1 landing in 4 against 1 in 3 |

What such an executor has to own, and what none of these rules can express: keep the planned
heading and full sprint across an ENTIRE chain of bounces, count them, and cut the throttle
only above the FINAL landing. Until it exists the course stays red, and the honest number is
one landing in three, ~8-9 blocks short of the ledge, no falls on the runs that land.

**Where it is stuck, measured:** the bot walks to the lip and stops there.
`NAVSTATE walker=false awaiting=true pending=- next=-` — the navigator has handed the drop
to physics and is WAITING, the walker is switched off, and physics returns neither a path
nor a failure. A deadlock at the lip: same CLASS as the starved hand-off fixed for
`nav_wall2`, different instance. That is where the next pass starts.

### `nav_slime` — the arena

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
