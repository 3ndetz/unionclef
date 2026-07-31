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

## WHICH ENGINE CAN DO WHAT — read this before deciding who owns a manoeuvre

Pointed out by the user on 2026-07-30 after a session was spent working around it, and
verified in the code rather than recalled. **The two engines have DISJOINT capabilities, and
every hard course needs both.**

| | swim / dive / enter+exit water / ladder / slime bounce | break / place blocks |
|---|---|---|
| **physics engine** — `PathFinder` + `Node.getChildren` + `path/specialMoves/` | **YES, implemented and live** | **NO — no such move exists** |
| **block engine** — `FastPlanner` + `BlockPathWalker` / `PathExecutor` | badly, bolted onto the waypoint walker | **YES** |
| baritone / shredder (reference) | no physics simulation at all | yes, this is how it reaches anywhere |

The physics side really does swim. `path/specialMoves/` contains `SwimmingMove`,
`DivingMove`, `EnterWaterAndSwimMove`, `ExitWaterMove`, `ClimbALadderMove`,
`JumpToLadderMove` and `SlimeBounceMove`, and they are NOT dead: `Node.java:163` calls
`SwimmingMove.generateMove` from physics move generation, and the move drives a simulated
player through `PathInput` while tracking `agent.swimming` / `agent.isSubmergedInWater`. It
simulates the real body, which is why it can hold itself in water at all.

What it cannot do is BUILD. There is no `PlaceMove`, `BreakMove`, `BridgeMove` or
`PillarMove` in `specialMoves/`, and neither `Node.java` nor any move in that package
mentions `toBreak` or `toPlace`. Breaking and placing exist ONLY on the block side.

### Why this matters more than it sounds

Three of this week's dead ends are the same mistake wearing different clothes — deciding an
executor owns a manoeuvre without checking which engine can actually perform it:

- **Water.** "The real fix is a swimming executor" was written in a commit message here. It
  is wrong: the swimming executor exists. What is missing is a route that swims AND builds,
  and a hand-off between the engines that survives the seam. nav_water passes today by
  walking round the rim.
- **Slime.** The bounce is implemented twice — `SlimeBounceMove` in physics, and
  `SlimeBounceTask` bolted onto the walker (which ships OFF, `slimeCrossing = false`). The
  course needs a bounce or a drop AND a bridge over the gap after it, i.e. both engines.
- **Ladders.** Climbing was moved out of physics into the walker under the slogan "the
  executor that can do it, owns it", while `ClimbALadderMove` sat in physics. That may still
  be the right call for a plain climb, but it was not made with this table in view.

So the question for any new manoeuvre is not "which executor should own this" but: **does
the route need building, physics, or both?** Both means the seam, and the seam is where the
failures are — see the hand-off notes further down this file.

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

⛔ **CORRECTION 2026-07-29: the score is 8/10, not 9/10.** `nav_water` was a FALSE GREEN and
it was my own doing. Fixing the bottomless pool, I filled the shell to floor level across
z=-4..4 and carved only z=-3..3, which left a stone rim on both sides at walking height — the
bot WALKED AROUND the water and the course passed without ever testing a swim. The user
spotted it by watching the clip. The rim is now capped with barriers: the water is held and
there is nowhere to put your feet. With the bypass gone the course fails 3 runs of 3
(25.5 / 7.0 / 25.5, no falls), which is the honest state of swimming.

**Suite score (STALE, see the correction above): 9/10 on per-course runs** (released as 0.64.0). Green: `nav_flat`,
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
- ⚠️ **The FIRST run after recreating the client is unreliable** — nav_gaps failed on it at a
  perfectly healthy 16.4 fps and then passed 3/3 at 16-17. Discard it, or warm up with a
  throwaway run before measuring anything.
- `docker compose restart` does NOT restore fps, but a full `down` + `up` DOES: measured
  8-10 fps before, 13.4-14.7 straight after recreating the tester. The client ages within a
  long-lived container. (An earlier note here said restarting does not help and left it at
  that — it was drawn from `restart` alone and was wrong.)
- The stand shares the host with whatever else is running. During this session that included
  several unrelated containers plus `uctest-mc-tester2` and `uctest-gamer-server` from this
  same project, up for 33 and 37 hours. Check `docker ps` before trusting a marginal verdict.

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

⚠️ **THE OUTCOME OF THIS COURSE IS BIMODAL — either ~8.5 blocks short and safe, or 20.7 with
a fall — AND FPS ONLY PARTLY EXPLAINS IT.** An earlier version of this note claimed fps
decided it outright, on a four-run sample where the correlation looked perfect. More data
killed that: on a freshly recreated client the course failed at 18.3 and 19.3 fps and passed
at 13.6. Low fps makes the bad mode more likely; it is not the whole story. Treat any single
nav_slime run as one sample of a coin, and never conclude from fewer than three.

The original four-run sample, kept because it is still the reason to keep the stand healthy: The same build gave
8.4-8.9 blocks short with zero falls three times running, and later 20.7 with a fall three
times running. After recreating the tester container the correlation was plain:

| run | avg_fps | outcome |
|---|---|---|
| 1 | 14.8 | 8.0 short, no falls |
| 2 | 18.3 | 8.5 short, no falls |
| 3 | 13.1 | 9.2 short, no falls |
| 4 | 9.9 | 20.7 short, fell |

At healthy fps the bot lands on the pad every time; below ~12 it misses. The "two attractors"
were the machine all along. Restore the stand first (`down` + `up`, not `restart`), confirm
fps, and only then read a nav_slime number as evidence.

**THE BOUNCE IS NOW MEASURED, AND TWO IDEAS ARE DEAD.** A tick-rate Y probe was added to the
toolkit (`probeYStart/Stop/Min/Max` over py4j) because sampling position over rcon gives about
three points a second and walks straight past an apex — it read a bounce as 0.15 blocks where
the tick trace says 4.6. Dropping onto a pad from a standstill:

| drop | rise | ratio |
|---|---|---|
| 4.0 | 1.53 | 0.38 |
| 7.0 | 3.07 | 0.44 |
| 10.0 | 4.25 | 0.43 |
| 15.0 | 8.78 | 0.59 |

- **Holding JUMP through the landing changes NOTHING** — 3.07 either way. There is no
  "boosted bounce" mechanic, so the whole plan of modelling and executing one is dead. That
  was the single piece of work this file named as the next step; it is now closed as a
  dead end rather than left to be attempted.
- **A standing drop is NOT the case routes are planned for.** Entering the pad at a run the
  apex is -55.4 from the same 7-block drop, i.e. 4.6 blocks, about 0.66. The model keeps the
  in-motion figure; the standing table stays as the evidence that killed the jump idea.
- The ledge was briefly lowered on the strength of the standing numbers and then put back:
  height is not the blocker, HORIZONTAL distance is, and weakening the course would have
  hidden that.

**A BOUNCE CHAIN IN THE MODEL WAS BUILT AND MEASURED WORSE.** The parent chain does remember
the route, so the height from the entry fall can be carried across the pad and decayed once
per cell — that was implemented. A/B on one healthy client: without it 8.7 / 10.1 / 8.5 (two
of three with no falls), with it 20.7 / 8.3 / 20.7. Discarded. The decay makes the search
prefer shorter, earlier hops, and the route it then picks is worse than the naive one.

**THE STRUCTURAL LIMIT, and it is in the planner.** A bounce is only offered from a node the
bot FELL onto, because the height comes from the parent edge. Walk one cell along the pad and
that history is gone, so the only bounce available starts where the fall landed — near the
pad's beginning, about 3 blocks of reach, which lands back on the pad. The bot therefore can
never leave the pad upward, and the plan honestly comes out `complete=false`. Representing a
bounce CHAIN — where each hop keeps the horizontal speed and the height decays — is what this
course actually needs, in the planner as well as the executor.

**WHAT WAS THOUGHT TO BE MISSING (now superseded by the measurements above):** With
the measured physics the planner reports `complete=false` — and it is RIGHT to. A passive
bounce cannot cross the gap (the arithmetic is below), so no route to the ledge exists in the
current move set. The course needs a JUMP-BOOSTED bounce, and neither half of that exists:

- the planner models only the passive apex (`BOUNCE_HEIGHT_RETURN = 0.67`, measured);
- nothing performs a jump at the one cell where it would matter. The walker does press jump
  when a waypoint is higher, but that alone does not produce the boost — tested by letting
  the planner offer the ledge (`BOUNCE_HEIGHT_RETURN = 1.00`) now that the drop lands on the
  pad's FAR edge, a combination never tried before: 8.7 / 20.7-with-a-fall / 8.5, and the
  failure came at 17.5 fps, so it was not the stand. Reverted; the measured value stands.

So the next pass is one coherent piece of work: measure the jump-boosted apex on the stand,
put it in the model as a distinct move, and have the executor jump on exactly the cell that
move names — not on every slime contact, which is what killed the bot 5-9 times a run.

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

## VERIFIED SWEEP, warmed stand, end of the 2026-07-29 run — 9/10

flat, staircase, descend, gaps (14-17 fps) · steep, break, wall2, ladder (12.7-16) ·
water PASS 2/2 · slime FAIL at 14.4.

This 9/10 is worth more than the 9/10 the night started with: `nav_water` now passes HONESTLY.
Both ways round it — a walkable rim and minable walls, both of which I had built into the
arena myself while fixing something else — are closed, so the course finally measures a swim.

Swept with a warm-up run first, because the first run after recreating the client is
unreliable (see below).

## State as of the end of the 2026-07-29 run

| course | state |
|---|---|
| flat, staircase, descend, gaps, steep, break, wall2, ladder | GREEN |
| water | GREEN-ish: 5 passes in 6, and now an HONEST pass — two bypasses I had built into the arena (a walkable rim, minable walls) are closed, so it measures swimming |
| slime | RED: 14.4 blocks short, ONE block placed per run |

⚠️ **A number I reported and cannot reproduce:** one slime run placed SEVEN blocks and closed
to 6.3. Repeated measurement gives one placement per run and 14.4 every time. Treat the 6.3 as
an outlier, not as a level that was reached — the honest figure is 14.4.

Search cost was the other big find: 2.2-2.4 ms PER NODE, because every "is this water / is it a
ladder / does a body fit" was a fresh live-world lookup from a background thread. A per-search
memo took water from 2-3 in 4 to 5 in 6. It did NOT help slime, so the bridge loop is limited
by something else.

## Bridging: planned, plumbed, not yet executed (2026-07-29)

The user's correction reset this whole area: **baritone does not build jumps out of physics —
it reaches anywhere by BREAKING AND PLACING.** Tungsten could break and could not place, so a
gap it was unable to jump was a dead end even with a stack of blocks in hand.

Three links, found and fixed in order:

1. **No place move existed.** `breakThrough` had no mirror. `placeAcross` now emits a move
   into a cardinal hole — no floor, body fits once there is one, the cell below empty, a solid
   face to click against — priced at 2.5 walks, deliberately dearer than a jump so the search
   still jumps what it can jump.
2. **The plan was thrown away at the seam.** `Result.toBlockNodes` carried `toBreak` and not
   `toPlace`, so every bridge the planner worked out died on the way to the executor. One
   line. After it the log shows `Path needs bridging: 1 block(s) at segment end`.
0. **CORRECTED: the drop IS planned, and the walker takes it SOMETIMES.** A diagnostic in the
   move itself settles the planner side — `SLIMEDROP from (6,-53,z) reach=3 drop=8 ->
   (9,-60,z)`, 553 times in a run, exactly the intended geometry: off the pad's lip onto the
   slime. So the earlier "the walker parks at the lip" was too absolute; it parks on SOME runs
   (final 14.7-20.7) and crosses on others (8.1). The variance is in EXECUTION of a correctly
   planned drop, not in the plan.
   Note against my own method: I first grepped for this with a pattern that did not match and
   concluded the move was never emitted. Always confirm the diagnostic channel is alive before
   reading an absence as evidence.
0b. **(superseded) The walker parks at the lip, so every build beyond the drop is discarded.** Measured with
   a distance on the drop counter: `BUILDDROP dist=10.7 at 6,...` — the bot is standing on the
   pad's last block and the build point is 10.7 blocks away, down in the pit. The leg towards
   the work is never walked, so the plan is thrown away 12 times out of 12. It is not the
   bookkeeping and not the arrival check: the walker simply will not go over the edge, and
   every bridge the search wants to lay is on the far side of that edge. THAT is the next
   thing to read — the walker's step logic at a drop — and it is a different place from where
   the last three passes were looking.
3. **The bridge is only ever planned from the WRONG side.** Every bridge in the log sits at
   `8,-61,z` — the slime level, seven blocks below the launch pad. Nothing is planned from the
   pad itself, and the searches that start there report `1 nodes, 1 wp`: one node expanded and
   the open set empty, which is what a search looks like when the bot is AIRBORNE — no
   support, so no moves. In other words the bot leaves the pad before it ever plans to build
   from it, and only starts thinking about bridges once it has already fallen.
   Not a budget problem: the budget is 250 ms and the searches that do run expand 164 nodes,
   so they exhaust the reachable set rather than run out of time.
4. **Execution aborts.** `Bridge place aborted (timeout or out of reach)` — the executor gives
   up when the target is beyond 5.5 blocks or after 200 ticks. The bot is not being delivered
   to the bridge point, so the placement waits and times out. THAT is the next step.

Measured on `nav_slime` along the way: final distance 20.7 -> 13.2-14.4, and self-falls to
ZERO across three runs where the bot used to kill itself. The course is still red.

⚠️ Do not repeat this: I spent many passes proving with physics that the slime bounce cannot
cross that gap and concluded the course was unwinnable. It is unwinnable BY BOUNCING. The
test that settles a course's validity is the user's: **would baritone pass it** — and baritone
would have built across.

## The bridge could only ever be one block long (2026-07-29, root cause)

`nav_slime` was the last red course, and the reason turned out not to be the slime at all.
The course is crossed the way baritone would cross it — a bridge of placed blocks over the
gap — and that bridge was **unplannable by construction**:

```java
BlockPos against = new BlockPos(from.x, from.y - 1, from.z);
if (world.getBlockState(against).getCollisionShape(world, against).isEmpty()) return;
```

Placing needs a face to click against, and `placeAcross` looked for that face **in the
world**. The face for the second plank of a bridge is the first plank — a block that exists
only in the plan at search time — so the search gave up after one placed block. `pillarUp`
had the identical flaw, capping a tower at one block. Any route needing two or more placed
blocks was therefore impossible, which is a whole class of route rather than a corner case:
getting anywhere at all by breaking and placing is precisely what baritone does that this
planner could not.

Fix: nodes carry `placedDepth`, and `branchPlaced(node, x, y, z)` walks the parent chain to
ask whether **this route** has already put a block there. The walk stops at the first
ancestor that placed nothing, so it costs nothing on routes that build nothing. Support is
now "solid in the world OR placed by this branch" in both `placeAcross` and `pillarUp`, and
a plank this branch already laid is walked over rather than placed on twice.

### How the root cause was found, and two dead ends on the way

The bot parked at the launch pad's lip: `minY` stayed at -53 and `maxX` at 6.7-6.9 on three
runs of four. A `WALKSTOP` diagnostic — print the gate's inputs whenever the walker is on
the ground with movement NOT pressed — gave the state directly:

```
WALKSTOP pos=(6.7,0.5) wp=(10,-60,0) dist=0.8 yawErr=51 facing=false
```

Two things came out of that line, one of them a red herring:

- **DEAD END, do not retry: "two owners of the camera".** The reading that the executor's
  place-aim and the walker's waypoint-aim were fighting looked compelling. Letting the
  builder own the aim outright — walker still walking, no longer gating on an aim it may not
  set — changed nothing measurable: 13.6 / 7.6 / 7.8 blocks short against 13.7 / 7.9 before
  it. Reverted rather than kept on faith.
- **Real defect, fixed:** `dist=0.8` was the distance to the waypoint the walker had just
  LEFT — it is not recomputed when the waypoint advances, and it also feeds the ladder
  arrival threshold.

Also measured while chasing this, and worth knowing: **`slimeCrossing` ships OFF**
(`TungstenConfig.slimeCrossing = false`), so `SlimeBounceTask` started **zero** times across
four runs while the planner offered the drop onto the pad 217 times. The bounce path was
never under test. That matters less than it sounds — bouncing is not how this course is
meant to be passed — but "the task never ran" is not the same finding as "the task ran and
failed", and the logs will read as the latter if you do not know this.

## CLOSED: nav_wall2, and the pillar hand-off that never once fired (2026-07-29)

`nav_wall2` went red when the search started preferring chained pillars over a single
2-block climb, and the cause was a test that had been wrong the whole time:

```java
if (jump != null && player.getBlockPos().isWithinDistance(jump, 1.5)) {
    pendingPhysicsTarget = null;   // "already there — nothing for physics to do"
}
```

A pillar target is ONE BLOCK STRAIGHT UP — distance 1.0, inside the radius — so every
pillar hand-off was discarded on the tick it was armed. Measured across one run: 54 of 82
plans flagged a pillar as their first move, `HANDOFF` and `Pillaring up` fired **zero**
times, and the navigator replanned 26 legs while the bot stood 7.5 blocks short at the foot
of its wall. The course had been passing only because a 2-block climb lands 2.2 away and
cleared the test by 0.7 of a block. It was never right, just lucky — which is why it broke
the moment the search gained a cheaper way up.

Arrival is a horizontal question plus a height check: you cannot walk upwards. After the
fix, nav_wall2 is **PASS 3/3** (1.2 / 1.2 / 1.1). The one FAIL seen just after the fix was
the first run following a container recreate — the known cold-start effect, not the code.

How it was isolated, for the next person: the sneak port was stashed and rebuilt (identical
7.5 / 7.5, so not that), the leg-cut experiment was reverted (nav_bridge recovered to PASS
while nav_wall2 stayed broken, so not that either), and only then was the distribution of
`firstPhysics` over a whole run counted. That distribution — 54 ones — was the fact that
pointed straight at the guard.

## Baritone's placement model, read at last (2026-07-29)

Prompted by the user, and it should have been the starting point rather than the fallback.
`MovementTraverse.cost` (baritone/src/main/java/.../movements/MovementTraverse.java) does two
things tungsten did not:

1. **Side place first, backplace second.** It tries all four horizontals plus down of the cell
   being paved, SKIPPING the direction that would be a backplace, and only if none of them
   can be placed against does it fall back to placing against the block under its own feet —
   at a different price, `SNEAK_ONE_BLOCK_COST`, because a backplace IS a sneak. Tungsten's
   `placeAcross` only ever implemented the backplace.
2. **It sneaks.** `updateState` holds `Input.SNEAK` as soon as it is close to the cell and
   only clicks once `ctx.player().isInSneakingPose()`; if it has come too close it presses
   `MOVE_BACK` first. Tungsten released the movement keys and clicked, and releasing keys does
   not cancel momentum — the bot slid off the lip it was paving from.

`canPlaceAgainst` is also stricter than tungsten's "collision shape is not empty": normal
cubes and glass only, because the check exists to answer "can I look at a side face and place
against it", which carpets and the like fail in practice.

Ported so far: the sneak and the click-only-when-sneaking gate. Measured neutral on both
nav_slime (20.7 / 8.8 / 8.3, unchanged) and nav_wall2 (identical with and without) — kept
because it is upstream's actual behaviour and the failure it addresses is real, but it has
not paid for itself yet and that is not hidden here. NOT yet ported: side-place preference,
the two-tier cost, MOVE_BACK when too close, and the stricter canPlaceAgainst.

## Where to fix things (strategy, not band-aids)

1. **One block planner.** `FastPlanner` is the correct base; move the remaining capabilities
   into it (ladder, water, slime, place/pillar). Delete `BlockSpacePathFinder` afterwards.
2. **`BlockPathWalker` must not own a search.** It should execute the path it is given.
   `CombatPathfinder` belongs to combat.
3. **One pipeline, not two.** Done for `;goto`: physics is now an executor for
   `needsPhysics` segments, not a second router. The other entry points that still start
   their own search (`followPlayer`, altoclef goal tasks) should be moved the same way.
4. **One key owner.** Combat already does this (`CombatMoveIntent`); navigation does not.

## nav_bridge after the verbatim port: green at 15-18 fps, void fall at 10 fps (2026-07-30)

The baritone movement port landed (`62e1108` substrate + MovementTraverse/MovementPillar,
`71254fd` the bridge wiring) and nav_bridge went **PASS 3/3** — 12.5s / ~14s / 18.0s, final_dist
0.4 / 0.6 / 1.0, no self-falls, no freezes, at **avg_fps 14.2-18.3**.

Re-measured independently afterwards on the same commit: **FAIL 3/3, final_dist 22.5, avg_fps
9.9-10.0** — the void-fall signature. And in a full end-to-end sweep, also FAIL, with the suite at
10/12. `uctest-mc-tester2` was already stopped, so it is not that; the host is simply carrying the
user's production containers again.

So the port is correct and the manoeuvre works — three passes prove it — but it is **not tick-rate
robust**: somewhere between 10 and 14 fps it stops surviving. That is the same class of defect as
the aim/stage machines that assume 20 tps, and it is now the thing standing between this course and
a reliable green. It is a real gap, not a stand artefact: a bot that only bridges on a fast client
is not finished.

What NOT to conclude: that the port is wrong. `placeStats` reads `called=0` on the new path, i.e.
the old forged-placement route is genuinely dead and the ported movement is doing the work.

### CORRECTION: the live-trace fix is NOT proven to be what fixed nav_bridge (2026-07-30)

The commit for it says "this is what the fps sensitivity was". That claim is not supported by
its own numbers and is withdrawn here.

| when | isolated nav_bridge | avg_fps |
|---|---|---|
| before the live-trace fix | FAIL 3/3, 22.5 | 9.9-10.0 |
| after it | PASS 3/3, 1.2-1.4 | 20.0-21.7 |
| after it, inside a full 12-course sweep | **FAIL** | ~10 (late-sweep) |

The fps doubled between the two isolated measurements, and the host's load is not something
this session controls — the user's production containers come and go. So the pass may be the
fix, or it may be the machine, and the in-sweep FAIL at ~10 fps points at the machine. Both
readings survive the evidence, which means neither is established.

To settle it, and it is one experiment: pin the two builds against each other in the SAME
window — check out the previous commit, run nav_bridge three times, check out this one, run it
three times, and compare only if both sets report a similar avg_fps. Do not compare across a
gap in wall-clock time on this host.

The live trace is kept regardless: reading a once-per-render cache in a gate that upstream
ray-traces every time is wrong on its own terms (RayTraceUtils.rayTraceTowards), whatever it
turns out to be worth on this stand.

### A/B SETTLED: the port fixed nav_bridge, the live trace did not (2026-07-30)

Both builds run back to back in ONE window, so the host load is the same for both. Only
`RealPlacement.java` differs.

| build | nav_bridge x3 | avg_fps |
|---|---|---|
| A — cached `mc.crosshairTarget` (pre-fix file) | **PASS 3/3** — 1.1 / 1.2 / 1.1 | 18.7-20.3 |
| B — live `RotationHelper.liveHit` | 3.9 FAIL, 1.0 PASS, 1.3 PASS | 18.3-21.5 |

So the live trace is NOT what made the course pass — the old file passes 3/3 at the same fps,
and if anything B is marginally worse (one FAIL, within this stand's noise at n=3). What made
nav_bridge pass is the **verbatim movement port itself** (`71254fd`), and the 22.5 failures
measured earlier were the host at ~10 fps, exactly as the withdrawn claim feared.

The live trace is KEPT anyway, on its own terms rather than on a result it did not produce:
upstream ray-traces in this gate every time (`RayTraceUtils.rayTraceTowards`) and a
once-per-render cache is stale by 1-2 ticks at 10 fps. It costs nothing measurable here.

**What this leaves as the real open problem:** nav_bridge passes at 18-21 fps and fails at ~10,
i.e. the ported manoeuvre is not tick-rate robust — which is also why it is red inside a full
12-course sweep, where fps sags by the eleventh course. That is the next target, and it is a
genuine defect: a bot that only bridges on a fast client is not finished.

#### Tick-rate robustness: what has already been ruled out

Checked so the next pass does not re-check it:

- **Injection point is correct.** `MovementQueue.tick` runs inside
  `MixinClientPlayerEntity`'s `@Inject(method = "tick", at = @At("HEAD"))`, so the movement's
  inputs are set BEFORE the player's own movement for that tick. A one-tick input lag — which
  at 10 fps would be 100 ms of walking, easily a step off a lip — is not the mechanism.
- **Key ownership is enforced.** The same mixin skips `BlockPathWalker`, the build primitives,
  the crossing and the physics executor entirely while the queue runs, so it is not contention.
- **The placement gate is not the discriminator.** Settled by A/B above: the cached-crosshair
  build passes 3/3 at the same fps.

So what remains to investigate is inside the manoeuvre's own timing: the 4-tick
`BlockPlaceHelper` gate (upstream `rightClickSpeed`), and how many ticks the bot spends
between leaving support and the block existing. The measurement to take first is a tick trace
of ONE crossing at ~10 fps against one at ~20: where do the extra ticks go, and is the bot
airborne during them.

#### FOUND: the queue aborts on OFF-PATH DRIFT, not on place rate (2026-07-30)

The tick trace of a failing sweep run, which is what the fps sensitivity actually is:

```
MovementQueue: too far from path (3.4)
MovementQueue: too far from path (3.3)
MovementQueue: rewound 7 -> 5
MovementQueue: rewound 13 -> 11
MovementQueue: 16 traverse(s) 0,-53,0 -> 16,-53,0
MovementQueue: 14 traverse(s) 0,-60,0 -> 14,-60,0
```

The manoeuvre is not too slow and the placement gate is not starving — the bot DRIFTS 3.3-3.4
blocks off its path and the queue gives up. The 4-tick `BlockPlaceHelper` hypothesis is refuted.

Two things follow, and both are upstream behaviour we did not carry over:

1. **The tolerance is tuned for a 20 tps client.** Baritone's `MAX_DIST_FROM_PATH` (2.0) and
   `MAX_MAX_DIST_FROM_PATH` (3.0) assume ticks arrive on time. At ~10 fps each tick moves the
   body further, so the same walk overshoots past a threshold that was never meant to be a
   fps-dependent quantity.
2. **Upstream does not ABORT on off-path — it RE-PLANS.** `PathingBehavior` re-searches on the
   same tick a segment fails; the audit already recorded this as tungsten's biggest execution
   gap ("its watchdog hands over to a caller that does not exist"). Our queue rewinds and then
   gives up, so a recoverable drift ends the whole crossing.

Next fix, precisely: on `too far from path`, re-plan from the bot's ACTUAL position and continue,
the way `PathingBehavior` does — instead of rewinding twice and abandoning. That is a closed loop
and it removes the fps dependence, because a drift becomes a re-plan rather than a failure.

#### CLOSED: the drift was the bot walking BACKWARDS, and the suite is 12/12 (2026-07-30)

The drift above is real but it is a symptom, not a cause, and the cause is one line of upstream we
never ported. A per-tick trace of the ported movement — body, camera and keys on the same line —
caught it at the seam where the sneak-backplace hands over to the step after it:

```
MV 12,-60,0->13,-60,0 pos=13.30 yaw=90/90   err=0    keys=Su   <- plank placed, facing BACK
MV 13,-60,0->14,-60,0 pos=13.30 yaw=90/-90  err=-180 keys=F    <- "forward" pressed...
MV 13,-60,0->14,-60,0 pos=13.20 yaw=81/-91  err=-171 keys=F    <- ...runs the bot BACKWARDS
MV 13,-60,0->14,-60,0 pos=12.87 ...
MovementQueue: off path (3.1) at 10.96,-60.00,2.13 ground=true
```

A backplace deliberately faces backwards down the bridge (`MovementTraverse.updateState`, the
`dist2 < 0.29` branch), so the movement that follows it asks for a 180 turn AND presses MOVE_FORWARD
in the same tick. Baritone may do that because its camera is instant: `LookBehavior.onPlayerUpdate`
PRE calls `player.setYaw(...)` on that very tick, and `MixinEntity` (baritone .../launch/mixins/
MixinEntity.java:43-66, identical file in `shredder/`) swaps the yaw around `Entity.updateVelocity`
so the input vector is resolved in the REQUESTED facing whatever the camera is doing. Tungsten aims
through `WindMouseRotation`, stepped once per RENDER FRAME, so every direction key was resolved in
the previous facing. At 25 fps the turn costs half a block and the course still passes; at the 9 fps
of a full sweep it costs three, the queue calls that off-path, the rewind re-arms the sprint guard,
and the bot sprints into the void — 22.5 blocks short, self_falls=1.

**The fix is that mixin, ported:** `Movement` publishes the rotation it asked for as a per-tick
motion frame (`motionYaw`/`motionPitch`), `MixinEntityMotionYaw` swaps it in around
`Entity.updateVelocity` and back out, and it is cleared at the RETURN of `ClientPlayerEntity.tick`
— upstream's "the target is done being used for this game tick". Scope: the client player, on ticks
a ported movement declared a rotation, i.e. only while `MovementQueue` runs.

**MEASURED AND REVERTED — do not retry:** holding the direction keys back until the CAMERA reached
a FORCED target. No change at all (22.5 before, 22.5 after, avg_fps 8.8 both). The branch that walks
the bot backwards is the `MovementHelper.moveTowards` fall-through, whose target is UNFORCED, so the
gate never saw it; and widening it to every target would stall the bot through every heading change,
which is not what upstream does. Upstream STEERS.

**Second defect, found by the first one's fix and fixed with it.** With the bridge working, the run
still failed at `final_dist 3.5`: the bot bridged the lip, handed the last gap to physics
(`physics owns the jump -> 19,-60,0`), and three seconds later `FastNavigator: no progress, handing
over`. The stall watchdog counts a stationary bot as failure, and `awaitingPhysics` — this
navigator having deliberately stopped and asked another engine to own the next piece — was not in
its list of things that count as progress, though the build queues already were. The jump then
landed at x=19.57 with nobody left to plan the last 3.4 blocks and the bot stood there for 104 of
the 120 seconds. One clause, same shape as the BUILDING-IS-PROGRESS fix beside it.

##### The bench that made this measurable

`docker update --cpus 1.2 uctest-mc-tester1` pins the client at 5-10 fps deterministically, which is
the condition a full sweep reaches by its eleventh course. Before this, low fps could only be got by
waiting for the host to be busy, and every A/B was contaminated by that. (`maxFps` in options.txt is
NOT a lever: `startapp.sh` inside the image rewrites it to 30 on every boot.) Undo with a
`--force-recreate`, which `deploy/deploy_jar.sh` does anyway.

##### Numbers, all on that bench

| build | nav_bridge | final_dist | self-falls |
|---|---|---|---|
| before (2c51266) | FAIL 3/3 | 22.5 | 1 |
| + camera gate on forced targets | FAIL | 22.5 | 1 | (reverted)
| + motion frame | FAIL (goal only) | 3.5 | 0 |
| + physics wait counts as progress | **PASS 3/3** | 1.6 / 0.8 / 1.3 | 0 |

Baselines on the same bench: nav_flat 1.0, nav_wall2 0.9, nav_hazard 1.6, nav_gaps 0.7, all PASS.

**Full sweep: 12/12**, at avg_fps 5.3-9.0 — i.e. green under conditions HARSHER than the ~10 fps
that used to score 10/12. `nav_slime` came with it (t=29.0s, final_dist 1.3) exactly as expected:
it needed the same bridging.

## 12/12 — the whole nav suite green in a full sweep (2026-07-30)

```
nav_flat nav_staircase nav_steep nav_gaps nav_descend nav_water
nav_ladder nav_slime nav_break nav_wall2 nav_bridge nav_hazard   all PASS
12/12 ok, gate failures: 0, invalid (host starved): 0
```

`MovementQueue` reports two chains and both finish — `16 traverse(s) 0,-53,0 -> 16,-53,0`,
`chain complete`, `14 traverse(s) 0,-60,0 -> 14,-60,0`, `chain complete` — with no off-path
aborts at all. Bridging is done by the ported baritone movements end to end.

Getting here took, in order: the search's own logging out of its inner loop (164 nodes in
204 ms -> 202 in 1.7 ms), the search remembering blocks it places, a hazard predicate the
planner never had, an arrival test that mistook a cell overhead for one underfoot, the
verbatim movement port, and finally treating off-path drift as a re-plan rather than a
failure. Every step of that is above, including the parts that measured worse and were
reverted.

Standing caveat, so this is not read as more than it is: the stand's fps varies with the
host's other containers, and nav_bridge has passed at 22-24 fps and failed at ~10. A green
sweep is a green sweep, but the low-fps behaviour is not yet proven and AC-1 in TODOS.md
still stands.

## THE CHASE DOES NOT USE THE FAST PLANNER AT ALL (2026-07-30) — AC-1 root cause

The user's complaint, verbatim: *while the enemy runs away we recompute the whole route and end
up 100+ blocks behind*. Reproduced on the bench and traced, and the cause is not a tuning
problem — the chase runs on the wrong engine.

`chase_terrain`: **FAIL — contact=None, kills=0** over 120 s of pursuit. `chase_flat` passes
(contact 12.3 s, avg dist 4.64), so the failure needs terrain to show.

The decisive measurement is what is NOT in the log. Across a whole failing run:

| fingerprint | count |
|---|---|
| `FastPlanner:` | **0** |
| `Walker: BFS` | **0** |
| `MovementQueue:` | **0** |
| `physics owns` | 0 |

Zero. The block planner never runs during a chase. `PunkPlayerTask` hands the approach to
`FollowEntityTask`, which steers with `BlockPathWalker.steerLive(...)` — a beeline at the target
— and whose "primary pathfinder" (`FollowEntityTask.java:279`, `startFind`) is
`TungstenModDataContainer.PATHFINDER`, the **physics** A\*. So the pursuit is: beeline, and when
that is not enough, run the slow simulation search.

That is exactly backwards from the agreed engagement order (`TODOS.md`, AC-2.1: block route
first, always; physics LAST, only when nothing else reaches). It also explains the shape of the
complaint precisely: the physics search is the one that takes real time, and it is being asked
to keep up with a runner.

Note the bench's own asymmetry, which makes it a fair test of exactly this: the RUNNER flees
with `@goto`, i.e. on baritone/shredder, while the CHASER pursues on tungsten. Baritone's block
route outruns our physics search — which is the whole reason the user asked for baritone's speed
as well as its building.

Next: give the chase the fast block route (plan with `FastPlanner`, extend rather than replan)
and leave physics as the last resort, per AC-2.

### CORRECTION and the real chase evidence (2026-07-30)

The section above concluded "the block planner never runs during a chase" from an absence of
log lines. That inference was WRONG and is withdrawn: `chase_terrain` does not set
`verboseDebugLogging`, and it defaults to false, so the planner's summary line was gated off.
The channel was dead, not the code. (This file already warns about exactly that mistake; I made
it anyway. The scenario now sets the flag, so the next reader gets real evidence.)

What the source DID establish, and what stands: the branch used `CombatPathfinder`, capped at
`MAX_RADIUS = 25` (CombatPathfinder.java:29), while the bench sends the runner 140 blocks. A
25-block search cannot route to a target 140 blocks away. That is now `FastPlanner`, which has
no radius cap.

With logging on, the honest picture of a failing `chase_terrain` (contact=None, kills=0):

| fingerprint | count over ~180 s |
|---|---|
| `FastPlanner:` | 4 |
| `Walker: direct` | 3 |
| `Walker: BFS` | **0** |
| `MovementQueue:` | 0 |

So the planner does run now — but only FOUR times in three minutes, and its route is never
walked. Two causes, both visible in the code:

1. `startFind` is only reached when the physics engine is idle
   (`!pathfinderActive && !executorRunning && !stopRequested`, FollowEntityTask.java:279), and
   the physics search occupies most of the time — so the block plan is computed rarely.
2. `BlockPathWalker.start(target, bfsPath)` begins in DIRECT mode with the route only as a
   fallback ("Start with direct-sprint toward target. BFS path is fallback",
   BlockPathWalker.java:87). So even a computed route is not followed; the bot beelines.

That is AC-1.4 inverted: we are supposed to run the exact block route immediately and refine
while running. Next step is to walk the ROUTE in a chase and stop gating planning on the physics
engine being idle — measured against both chase courses and the nav sweep, since chase_flat
passes today WITH the beeline.

### chase_terrain: the bot gets stuck EARLY, pushing into terrain it cannot leave (2026-07-30)

Five iterations improved the pursuit's numbers without touching the gate, because the framing
was wrong. The chase does not fall behind gradually — it stops.

| run | freeze position | start |
|---|---|---|
| A | (-270.30, 114.02, 284.50) | (-288, 117, 288) |
| B | (-270.30, 114.02, 284.50) | same |
| C | (-252.68, 107.00, 286.18), three windows | same |

**Correction to an earlier claim in this file:** two runs freezing at an identical position led
me to call the point deterministic. Run C froze somewhere else entirely, 18 blocks further on
and 7 blocks lower. It is not one cursed cell; it is terrain the bot cannot get out of, wherever
it first meets it.

What the bot is doing there, measured: `WALKSTOP` — the diagnostic that fires when the walker is
on the ground with movement NOT pressed — printed ZERO times. So the walker is pressing forward
the whole time and the body does not move. It is pushing into something. `Walker: danger` also
appears once per run, and the walker's own comment says a 2-block wall reads as danger and it
cannot climb.

Elevation says the same: start y=117, freezes at y=114 and y=107. The route descends and then
the bot cannot climb back out.

So the open question is NOT "why are we slower than the runner" but: **what does the block route
do when the terrain requires a climb the walker refuses, and why does nothing recover?** The
physics engine is supposed to own exactly that (it has the jump moves), and per AC-2.3 it is the
last resort — but here it is the case that matters.

Numbers moved by the five pursuit iterations, for the record, none of which took the gate:
plans 4 -> 15 -> 97, one-waypoint stumps 93 -> 1, routes walked 2 -> 8, chase_flat 4.64 -> 3.74.

### chase_terrain: 59% of the pursuit's active ticks think it has ARRIVED (2026-07-30)

Six iterations went into pursuit logic — planner choice, planning frequency, walking the route,
pathStart, mid-walk replanning, driving through the navigator — and none took the gate. The
counters the code already carried answer why in one line:

```
chaseStats: called=10786 inactive=7771 active=3015
            | reached=1781 steer=615 leap=0 cooldown=195 losBlocked=981
punkStats:  called=10797 inactive=9614 noTarget=239
```

Of 3015 ACTIVE ticks, **1781 — 59% — are spent in the "reached" branch**, i.e. the follow task
believes it has arrived and does nothing. The runner is 140 blocks away and never stops moving.
Steering gets 615 ticks; line of sight blocks 981.

That is the mechanism behind every symptom recorded above: the bot standing still while the
walker reports itself running, the freeze windows, the pursuit ending 18 blocks in. It was never
about being slower than the runner, nor about which engine plans — the chase simply stops
because something says it is already there.

Next pass starts at that branch: what `effectiveDist` and `closeEnough` actually are on those
1781 ticks. Print them; do not reason about them.

Also worth noting for whoever picks this up: `punkStats` shows the punk task itself inactive on
9614 of 10797 calls, with noTarget on 239. The two counters together say the chase is idle far
more than it is chasing.

#### RETRACTION: "59% of ticks think it has arrived" was a misread label (2026-07-30)

The section immediately above is wrong and is withdrawn. The `reached=` field in `chaseStats`
is `FollowEntityTask.followTicks`, incremented at FollowEntityTask.java:245, and the comment
beside its declaration says exactly what it means: *"the first version of this counter sat deep
in the method behind several early returns and so measured 'reached the steering decision', not
'was called'"*. It counts reaching the steering DECISION, not arriving at the target. 1781 of
those is healthy, not a defect.

The correct reading of the same numbers:

```
active=3015 | reached(=decision point)=1781  steer=615  losBlocked=981  cooldown=195
```

Steering requires line of sight (`hasLineOfSight(effectiveTarget.add(0,1,0))`), and **981 active
ticks — about a third — have it blocked**, against 615 that actually steer. That fits the two
courses exactly: `chase_flat` is open ground and passes; `chase_terrain` is broken ground where
LOS is lost constantly, and there the pursuit depends entirely on the fallback route path.

So the question for the next pass is what happens on the 981 LOS-blocked ticks — not whether
the bot thinks it has arrived. Print the state there.

Recorded as a retraction rather than an edit because misreading one's own instrument is exactly
the failure this file exists to make expensive.

### chase_terrain: steering barely happens on real terrain (2026-07-31)

Two runs of the same course, same build, measured with `chaseStats`:

| run | active | steer | losBlocked | cooldown |
|---|---|---|---|---|
| A | 3015 | 615 | 981 | 195 |
| B | 3635 | **7** | 441 | 273 |

Seven steering ticks out of 3635. Live-steer is the chase's PRIMARY mode and it is gated on
line of sight, so on broken ground the pursuit runs almost entirely on the fallback block route —
the path that has no climb hand-off, which is where the bot gets stuck. Run A and run B differ
only in the terrain the generator handed them, which is why the two courses split so cleanly:
`chase_flat` is open ground where steering works and it passes; `chase_terrain` is not.

Also recorded, because it cost a pass: a diagnostic attempt that added counters inside
`FollowEntityTask` took the task's `active` ticks from 3015 to **ZERO** — the chase never
activated at all. Reverted; `active` came back at 3635. A counter added to an activation path is
not free, and "it only adds logging" is not a safe assumption there.

### The BFS walker has NO bail signal at all (2026-07-31)

Tried giving the chase the climb hand-off that `FastNavigator` has: on
`BlockPathWalker.wasStoppedByBail()`, if the current waypoint is above jump height and roughly
overhead, hand it to `PillarTask`. It fired **zero** times, and the reason is exact:

`stoppedByBail` is set in ONE place — `BlockPathWalker.tickDirect` (:283) — and on bail that
code calls `switchToBFS()` and keeps running. So the flag means "direct mode gave up, now
walking the route", not "the walker is stuck". **The BFS mode sets it never.** A caller watching
`!isRunning() && wasStoppedByBail()` therefore cannot see a BFS-mode obstruction at all, which
is why the chase presses into a wall until the run ends with nobody asked to solve it.

That is the shape of the real fix, and it is not another hand-off: **the BFS walk needs a stuck
signal of its own** — it currently pushes forward forever with no notion of failing. Baritone's
executor has exactly this (per-move cost-proportional timeout, graduated off-path distance, live
cost re-verification) and `docs/BARITONE-PORT.md` already lists it as tungsten's biggest
execution gap: "one failure detector where baritone has five".

The inert hand-off was reverted rather than left in place.

### A stuck signal for the BFS walk: right idea, wrong alone (2026-07-31)

Gave `tickBFS` what it has never had — a way to say "I am pressing forward and not moving":
40 ticks of `move` pressed with the body under 0.05 blocks of travel, exempting climbing, being
airborne, and any tick where the executor's place/break queue or `MovementQueue` is running
(standing still IS the job while building).

Measured: the nav suite went **12/12 -> 11/12 with a real gate failure** — so the definition
catches at least one legitimate pause that none of those exemptions cover. Reverted.

Two things to carry forward:

1. The signal is still the right target. `stoppedByBail` is set only in `tickDirect` (:283) and
   means "direct mode gave up", never "the route is blocked", so no caller can see a BFS-mode
   obstruction — which is why the chase presses into a wall until the run ends.
2. It must land WITH its consumer and with a better definition of stuck. A signal nobody reads
   cannot pay for a regression, and "did not move for 2 s" is too blunt: the bot legitimately
   waits on physics hand-offs and on slow water. Baritone's answer is not a timer but
   cost-proportional per-move budgets plus graduated off-path distance — five detectors, each
   converting a specific failure into a re-plan (docs/BARITONE-PORT.md, execution section).

### Why placement-ON breaks nav_water: not bridging into water (2026-07-31)

Switching `planPlaceMoves` on by default takes `nav_water` from 3 passes of 3 to **3 failures of
3 at final_dist 25.5** — a signature nothing like the course's known 1-in-3 flake at ~8.2, and
the bot never leaves the start area.

First hypothesis, and it is REFUTED: that the search bridges INTO the pool. It is plausible on
paper — water has no collision shape, so `supportTop` is NaN ("no floor") and the cell below
reads as empty, i.e. every water cell looks like a hole to pave, while the placement cannot
execute because the ray trace passes THROUGH water and the crosshair never lands on a face.
Guarding `placeAcross` against water destinations changed nothing: still 25.5, 3 of 3.

Next hypothesis, grounded in the source rather than in taste: the flag also unlocks CLIMB moves
above jump height. `climb` is explicitly refused while the flag is off — the generator logs
"CLIMB rejected: planPlaceMoves is OFF" — and when it is on, such a climb is emitted FLAGGED,
which cuts the walked leg and hands the rest to the physics engine. On this course that hand-off
would leave the bot at the start, which is exactly what 25.5 looks like. Measure that before
changing anything: count flagged waypoints and hand-offs on nav_water with the flag on.

Both the flag and the (unmeasured) water guard were reverted rather than kept.

**CONFIRMED by measurement.** With the flag on, `nav_water` reports:

```
PLAN n=10 complete=true firstPhysics=1 flagged=1   x102
HANDOFF = 24     physics owns = 0
bridge planned = 0     pillar planned = 0
```

The flag unlocks NO placement at all on this course — zero bridges, zero pillars. What it
unlocks is a CLIMB, emitted flagged at index 1, so the walked leg collapses to a single cell
immediately; the hand-off then fires 24 times and the physics engine never takes it. Hence 25.5:
the bot never leaves the start.

This is the same family as the nav_wall2 defect fixed earlier — a cut at index 1 produces a leg
of one cell, which `startBFS` refuses (it needs two), so nothing walks and nothing hands over.
The fix belongs there, in how a flagged FIRST move is handled, not in the water course and not
in the flag.

### The 12/12 with placement shipping ON — the honest rate (2026-07-31)

One sweep is not a result, so here are three on the same build (`planPlaceMoves` now true by
default):

| sweep | gate failures | note |
|---|---|---|
| unrecorded #1 | **0** | 12/12 |
| unrecorded #2 | **0** | 11/12, the one non-pass marked INVALID = host starved |
| **recorded** (`--record`) | **2** | `nav_bridge` and `nav_water` |

So: zero gate failures whenever the machine is not loaded, and under the extra load of
per-course ffmpeg exactly the two courses already documented as fragile fail — `nav_water` with
its long-standing 1-in-3 flake, and `nav_bridge` with its known fps sensitivity (passes at
18-24 fps, fails at ~10).

That is the honest reading and it is also the argument for the open AC-1 item: the suite is
green on a quiet machine and not proven on a busy one. Recording is itself a load, which makes
`--record` sweeps a rough low-fps probe — but a deliberate one (`docker update --cpus N`) is
what actually settles it.

### AC-1 SETTLED: the suite holds at a FORCED 10 fps (2026-07-31)

The low-fps question has hung over this work all session — `nav_bridge` passed at 18-24 fps and
failed at ~10, and every attempt to judge it waited on the host being busy, which produced one
withdrawn claim. It is now deterministic:

```
docker update --cpus 2 uctest-mc-tester1      # -> avg_fps 10.0, reproducibly
python deploy/runner/run_suite.py nav
```

Result at that limit, with `planPlaceMoves` shipping ON:

```
nav_flat nav_staircase nav_steep nav_gaps nav_descend nav_water
nav_ladder nav_slime nav_break nav_wall2 nav_bridge nav_hazard   all PASS
12/12 ok, gate failures: 0, invalid (host starved): 0
```

So the fps sensitivity recorded earlier is GONE — and it went away with the work that landed
since, not by luck: the verbatim movement port, the placement gate going through the real ray
trace, and the block-budget guard on climbs. `nav_bridge` in particular now passes at the exact
fps at which it used to fall into the void.

Method note worth keeping: `--record` sweeps are a rough low-fps probe (per-course ffmpeg is
itself load), but `docker update --cpus N` is the deliberate one. Judge low-fps behaviour with
the limit, never by waiting for the machine to be busy.
