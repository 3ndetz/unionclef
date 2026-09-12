# Tungsten vs Baritone — movement/capability gaps and failure predictions

Produced 2026-09-10 from a two-axis comparative audit (baritone `baritone/src` reference vs the
live `tungsten/` engine), verified against the current code, not the older port docs. Purpose:
**stop finding movement gaps one crash at a time** — this is the ranked list of what tungsten
cannot do that baritone can, and the concrete in-game situation each one will break.

## How to read this

The `@gamer`/`@goto` task path (`CustomBaritoneGoalTask.driveTungstenPrimary`) uses several engines:

| Engine | Role | Can break? | Can place? |
|---|---|---|---|
| **E1** `CombatPathfinder` grid BFS | the PRIMARY guide for `@gamer` | no | no |
| **E2** `FastPlanner` (via FastNavigator) | reached only on escalation, and all `;goto` | flat cardinal only | bridge + straight pillar |
| **E3** `BlockSpacePathFinder`/`BlockNode` | robust async fallback | flat cardinal only | bridge only |
| **E4** physics `Node` + `specialMoves/` | final ≤4-block approach | via guide only | via guide only |

**The load-bearing fact:** `@gamer`'s primary guide (E1) is walk-only — flat + a single ±1 step,
no break, no place, no vertical-only move. Every build capability lives in E2/E3, reached only when
E1 returns no route (the escalation, widened 2026-09-10). So anything that needs a *dug* route is
usually never handed to an engine that can dig.

Config defaults (all already ON): `allowBreak`, `allowPlace`, `planPlaceMoves`, `navUsesQueue`,
`queueClimbs`. OFF by default: `queueParkour`, `queueDiagonals`, `smartMoves`, `slimeCrossing`,
`allowBucketMlg`.

---

## RANKED GAPS

### HIGH — these strand the playthrough

**G1. Dig straight down — ABSENT in every engine.**
No `MovementDownward`; no generator prices `(x, y-1, z)`. E2 `breakThrough` is cardinal-horizontal
and requires a floor on the far side (`FastPlanner.java:1089,1100`); E3 bails on any `dy!=0`
(`BlockNode.java:741`).
*Predicts:* the bot cannot descend into the ground to mine iron/diamond/coal-in-stone. **This is the
current frontier** — the playthrough reaches stone tools + surface coal, then dies needing to go
underground. Fix: add a break-down generator to FastPlanner (price like `MovementDownward.cost`).

**G2. Dug staircase, up and down — ABSENT.**
Break-as-a-move is flat cardinal same-Y only; there is no ascend-with-break or descend-with-break.
The `FastPlanner.java:1015-1024` "descend head-clearance" attempt was reverted precisely because
there is no break-and-descend generator to pay for the ceiling.
*Predicts:* can't cut a route up a hillside or down into terrain — the classic baritone way through
the world. The bot walks around or gives up. Fix: ascend/descend-with-break generators.

**G3. Deep descent (>3 blocks) and MLG water-bucket — unplannable.**
`FastPlanner.MAX_FALL = 3`; E1 accepts only a 1-block step-down. The full MLG bucket code is ported
(`MovementFall.java:308`) but DEAD — `willPlaceBucket()` "answers always false" and `allowBucketMlg`
defaults off.
*Predicts:* the bot parks at the lip of any cliff/ravine/cave shaft/drop-into-water >3 and makes no
progress → 14s give-up. Also no void-save for skywars/bedwars. Fix: port `dynamicFallCost`
deep-fall/water-column landing into `FastPlanner.step`, raise MAX_FALL with a bucket/water model.

**G4. Open-water crossing — chase stalls.**
E1 refuses water unless the search STARTS in liquid (`CombatPathfinder.java:388-406`); E2/physics can
swim but CANNOT place, so they can't build across water either.
*Predicts:* bot on a bank returns a 1-cell route, no progress — the documented ten-minute pond stall.
Fix: swim-from-bank entry in E1; a water-aware route that can also build.

**G5. The build engine is gated behind a narrow escalation.**
E1 is walk-only; a partial route that wanders toward the goal but never reaches it has `bfs.size()>=2`,
so it does NOT escalate to E2 — it walks the partial and stalls into the 14s reactive give-up whose
only builds are straight-overhead pillar or straight-gap bridge.
*Predicts:* any leg that needs a *dug* or offset build, but produces a wandering partial route,
stalls instead of building. Fix: widen the escalation to also fire on "no net progress toward goal
for N seconds", not just a <2-cell/stub route.

### MED — will bite specific situations

**G6. Mine a ceiling to pillar through it.** `pillarUp` refuses when `y+2` is occupied
(`FastPlanner.java:1228`); it never mines the block above. A roofed pit / mineshaft / overhang traps
the bot (`pillarEscapePit` needs clear headroom).

**G7. Ascend-with-place-step and parkour-place.** No engine plans placing the step you ascend onto,
or placing a block mid-jump. Can't build up to an offset ledge; wide void gaps only crossable by the
reactive `BridgeTask` at the 14s stall.

**G8. Mining cost uses the HELD item, not the best owned tool (no `ToolSet`).** Cost is priced with
whatever is in hand (`FastPlanner.java:1135`); `COST_INF` if the held item can't break it. The right
tool is only equipped at execution. *Predicts:* refuses reachable obsidian/ore when not currently
holding the pick; over-prices stone held with a sword → 40-block detours.

**G9. Break-and-descend (overhang over a drop).** Planner won't price the ceiling over a step-down;
descending a 2-high stepped tunnel stalls or digs an unpriced ceiling. Common in caves.

**G10. Adjacent-liquid break veto missing.** `BreakRules` only checks fluid at the block itself
(`BreakRules.java:29`), not neighbours — the bot will open a wall with lava/water behind it and flood
its own tunnel. Port baritone `avoidAdjacentBreaking`.

**G11. No-collision blockers are invisible.** Occupancy is decided by collision shape, so cobweb,
fire, tripwire, sweet-berry bush are never seen as walls, never break-planned — the bot walks in and
gets stuck. Port `canWalkThroughBlockState`.

**G12. Doors / fence gates in the chase.** E1 treats a closed door/gate as a solid wall and refuses;
only E2's `MovementTraverse` opens one, and only if the route routes into it — otherwise the bot
shimmies. Village/base nav is flaky. Fix: mark openable door/gate passable in E1.

**G13. Flat 300-tick break abort.** Two hand-mined stone blocks, or obsidian with a stone pick, can
never finish → the plan is dropped and re-searched forever. Fix: derive the budget from the planned
tick estimate, reset per cell.

**G14. Block-entity = hard break deny.** One chest/sign/bed in a wall column aborts the whole tunnel
plan instead of being merely expensive. Fix: soft cost tier (baritone's `avoidBreakingMultiplier`).

**G15. Throwaway budget counts shulkers/beds.** `countPlaceable` counts every `BlockItem` the equip
hook will never place → over-promised bridge → executor aborts "no block in hand" after walking to
the gap. Fix: whitelist to the equip-hook set.

### LOW — cosmetic or rare

- **G16. Diagonals not executed by the queue** (`queueDiagonals=false`) → rewritten to two cardinal
  steps: slower nav, extra corners, no hard failure.
- **G17. Slime-bounce shortcut off by default** (`slimeCrossing=false`) → builds around instead.
- **G18. Soul-sand / honey not priced** → detours or refusals; cobweb treated as a permanent wall.
- **G19. No elytra / boat / nether-portal travel** → only matters if the playthrough needs elytra
  flight or cross-dimension travel (then HIGH).
- **G20. Cost model mis-tuned** (`JUMP_PENALTY=6.5` vs 2, `PLACE=11.58` vs 20, flat `FALL=1.0`,
  `octile` scales raw dy → mildly dive-biased) → longer routes, occasional refusal of a legit build.

---

## What to fix, in order, to get the playthrough through the ground

1. **G1 dig-down** + **G2 dug staircase** — the ore phase is where it dies now.
2. **G3 deep descent** (raise MAX_FALL + a fall/water model) — cliffs, ravines, caves.
3. **G5 widen escalation** — so a wandering partial route actually reaches the build engine.
4. **G4 water** and **G8 best-tool pricing** — the next two most common real-terrain stalls.

Everything below G8 is polish that removes intermittent "the bot did something weird" faults but is
not a hard playthrough blocker on its own.

---

## 2026-09-11 — anatomy of ONE recorded playthrough: every micro-fault, its root cause, the principle

Source: `gamer_smoke.py 10 --record` on the survival stand, main @ `ee3b5809` (all of 2026-09-10's
fixes + SwimOutTask), client chat log 07:52–08:02 UTC, freeze dumps `stall_run1*.txt`, the 6x video
sent to the operator. Ladder: first craft 43 s, wood tools 65 s, stone tools 217 s, then 6 minutes
motionless in a 1x1 pit. The operator's verdict: the bot only got past the FIRST stall by luck
("shimmy dug the right way"), and in 90% of spawns it will not. So this section records not the two
stalls but every fault that fired on the way, with the line of code that produced it. Rule going
forward: **a random walk is never a recovery. Every recovery is a plan.**

### The chain of faults, in the order they fired

**G25. A block to be MINED is approached as a cell to be STOOD IN → zero-length plans every tick.**
Log 07:52:19–07:54:11: `Time taken to execute: 0 minutes, 0 seconds, ~100 milliseconds` every 0.6 s,
`Failed to mine block. Suggesting it may be unreachable` + blacklist for SEVEN consecutive stone
targets at y=59 while the bot stood on the surface at y=64; freeze dump: `snap=2034/1860/174/self1833`,
`atGoal=182(ex182,ytol0)@GetToBlockTask`, `pdPillar=0`, `mqSteps=0`. Chain: `DestroyBlockTask` →
`GetToBlockTask(pos)` (`breakGoalIsReach` is off, measured-and-not-shipped) → `AltoGoal.block(pos)`
("occupy the cell") → `CustomBaritoneGoalTask.snapGoalToStandable` finds no standable neighbour
underground and takes the "stand on top" column search → the SURFACE cell → the bot walks there,
arrival is unsatisfiable (exact-cell test on a solid block) → asks again next tick → FastPlanner
`planStartIsGoal` (one-cell path) → PathExecutor "finishes" in 100 ms → loop. **Nothing ever asks
for a dig**, because the snap moved the goal to the surface BEFORE the only engine that can dig
(FastPlanner.breakDown, G1) saw it. Root: no GoalGetToBlock. Baritone's miner never stands in the
ore; it goes ADJACENT, and its search breaks blocks on the way. Principle: **a mining target is a
REACH goal (`AltoGoal.Adjacent`, the GoalGetToBlock predicate), planned by FastPlanner with a
reach-goal test, dig allowed. No snapping.** Fix: `AltoGoal.Adjacent` + `GetAdjacentToBlockTask` +
`FastPlanner.plan(..., reachBlock)` + `FastNavigator.start(target, reachBlock)` +
`CustomBaritoneGoalTask.driveReach`; `DestroyBlockTask` uses it (`mineGoalIsAdjacent`).
*Refinement after the first run on it:* adjacency alone is too strict for anything TALL — a log
five up a trunk is mined from the ground in vanilla, and with an empty pocket the planner could
not become "adjacent" to it (150 s under a spruce: "walking dead-ends → physics owns the rest →
Pillaring up → out of blocks"). The planner's goal test is now `FastPlanner.reachGoalSatisfied`:
adjacent, OR eye within 4.0 of the block centre with an unobstructed raycast to it — the same
thing the miner's own arrival (`LookHelper.getReach`) asks. The "Wall too high — pillaring"
hand-off in FastNavigator also requires blocks in the pocket now. Benches: `dig_reach_test.py`
(6 blocks down through dirt: 12 s, 0 shimmies), `tree_reach_test.py` (6-high trunk, empty
pocket: log taken from the ground in 12 s, 0 pillar attempts).

**G26. The "unstuck" is a random dig.** `SafeRandomShimmyTask` holds SNEAK + FORWARD + **CLICK_LEFT**
and turns at random — that is the "digs the ground back and forth" at 0:22 of the video. Six shimmy
detections in two minutes (07:52:44 … 07:54:34) are what carried the bot 5 blocks down to the stone
it wanted; on another spawn the same six shimmies dig into nothing and the run is over. Principle:
**unstuck = a planned escape (FastNavigator with break/place to the live goal, else to the nearest
open cell / the surface), and a shimmy never holds the attack key.** Fix: CLICK_LEFT removed from the
shimmy; `PlannedEscape` + `PlannedEscapeTask` under UnstuckChain (`unstuckPlansEscape`): FastNavigator
to the live goal / the surface above / the nearest open cell, up to 20 s, shimmy (no dig) only when
no plan can be made or the plan went nowhere.

**G27. "Place a crafting table nearby" picks the cell the bot is standing in.**
Chat 07:55:29 `Failed placing` at `1215,58,368` with the bot at `1215.7,58.0,368.3`; then `1217,59,369`
with the bot at `1217.7,59.0,368.7`. `PlaceBlockNearbyTask.locateClosePlacePos` scores `isInsidePlayer`
(+3) instead of EXCLUDING it — and `WorldHelper.isInsidePlayer` is `isWithinDistance(player, 2)`, a
radius, not a hitbox test — while `WorldHelper.canPlace` never checks the cell is air. In a 1x1 pit
every neighbour is stone (+4) so the bot's own feet (distance 0.6, +3) win. Then `PlaceBlockTask` →
`BlockPlaceHelper.placementPlausible` refuses it (that port already knows a player is a solid object)
→ progress fails → `Wander for 5 blocks` → the wander cannot move in a pit (G29) → 6 minutes.
Principle: **a placement candidate must (a) be replaceable, (b) have a face to click, (c) not
intersect any entity's box — and when no such cell exists, the task MAKES ROOM: break a wall cell at
foot level and place there (carve a niche).** Fix: `WorldHelper.wouldIntersectAnEntity`,
`locateClosePlacePos` exclusions, `locateNicheToCarve` → `DestroyBlockTask` (`placeNearbyCarvesNiche`).

**G28. A PILLAR waypoint executed as a BRIDGE.** Every 30 s from 07:55:41: `Path needs bridging: 1
block(s) at segment end` → `At the gap — bridging without a physics leg` → `Bridge place aborted
(TIMEOUT) dist=1.16 ticks=202 target=1217, 59, 368` — the target is the bot's own feet cell. A plan
whose `toPlace` is the cell the body occupies means "put a block under yourself" (a pillar); the
"At the gap" shortcut in `PathFinder` hands ANY pending place to `PathExecutor.tickPlacing`, which
aims at a side face of the target and clicks — impossible for a cell you are standing in. Vanilla
refuses, 200 ticks, abort, replan the same plan. Principle: **never click-place into your own hitbox;
a place cell that holds the body is a pillar and goes to PillarTask.** Fix: `ownCellPlaceIsPillar`
guard in both the shortcut and `tickPlacing`; `BlockSpacePathFinder.snapToSupport` no longer moves a
search start ABOVE the player (a start above the body implies exactly this plan).

**G29. Wander in a confined space is a no-op.** `Failed exploring.` x40; `wanderDenied=6351`,
`wanderTargetUnstandable=11`, `BFS stuck at 1217,59,368 — 8 neighbours feetBlocked`.
`TimeoutWanderTask` picks spiral targets at the ANCHOR's Y (`wanderTargetFollowsTheGround` is off),
`tryPathTo` runs the physics/block-space search which cannot dig, so a walled-in bot has nowhere to
wander and the task that ordered the wander (G27) waits forever. Principle: **a wander that cannot
route is an escape, and an escape is planned with the build engine.** Fix: `TimeoutWanderTask` asks
`PlannedEscape.enclosed` first (four cardinal feet neighbours solid) and hands the body to the same
`PlannedEscape` as G26 (`wanderEscapesWhenEnclosed`), keeping the tick while it drives.

**G30. `Time taken to execute` on every path completion.** `PathExecutor` logs it unconditionally,
so the G25 loop floods the chat once per plan. Fix: log only for paths ≥ 2 nodes or ≥ 1 s, or in
verbose mode. (Symptom, but also noise on every video.)

**G31. The executor mines cells it cannot see.** `Mining aborted: ticks=302 dist=1.06 target=1214,
59, 367 eye=(1215.18,59.62,368.30)` — a DIAGONAL neighbour, no visible face from inside the pit;
`target=1216, 61, 368 … dist=1.50` x4 — above-adjacent, occluded by `1216,60,368`. `breakMissWhy=1/244`,
`breakAim=3204/345/245/100`. The "At the wall" shortcut fires on eye distance < 4 regardless of line
of sight, then the flat 300-tick abort (G13) ends it and the same plan is found again. Principle:
**a break target needs a visible face; if the plan's own earlier cell occludes it, mine that first;
if a foreign block does, the shortcut must not fire and the physics leg must deliver a cell with
LOS.** Open (batch 2/3).

**G32. "Unreachable" is declared by a timer, not by a search.** `Blacklist … Try 2/4` fired on every
G25 no-op approach after ~8 s, condemning reachable stone. Principle: **a block is unreachable when
the dig-capable planner says so (incomplete result), never because an approach that never planned a
dig took too long.** Follows from G25; verify the blacklist stays silent on the dig bench.

**G33. The attack key is released every tick, so a PLANNED dig never breaks anything.** Found by the
G25 bench, not the recording: with the reach route working end to end (`FastPlanner: 416 nodes, 7 wp,
complete`, `At the wall — mining without a physics leg`, aim on the planned cell 2990 ticks), the
block still stood after 302 ticks: `mine=6/2978`, `attackThief=[MobDefenseChain:663 x2982]`.
`MobDefenseChain` "stop putting out fire" releases CLICK_LEFT on every tick the bot is NOT in fire;
the fix existed behind `fireReleaseNeedsFire`, default false, "until a paired A/B" — and that A/B
is this bench (six hits in 2978 ticks with it off). Every G1/G2/G25 dig move was dead on the stand
for as long as this stayed off. Principle: **a per-tick writer that undoes another owner's key is a
theft, not a policy; a release needs a reason.** Fix: default true; benches pin it because the
stand's saved `tungsten.json` can carry the old value. Cascade it caused, also fixed: the miner's
approach clock and MineAndCollectTask's progress checker now HOLD while the executor has a
break/place queue or a pillar is up (a dig is progress) — that is the G32 fix, `dbBuildHeld`.

**G34. A planned BREAK run was handed to the physics engine, which does not deliver the body on
real terrain.** Found on the first recorded run of the fixed build (12:03): iron ore nine blocks
under the feet on a slope. The plan had the digs; the executor mined ONE block; the next break cell
was handed over from five blocks away (`Mining aborted: ticks=1 dist=5.19`), then `walking
dead-ends -> physics owns the rest` x4 while the miner's far give-up condemned the ore 5/4 four
times over — two minutes. The dig bench passed only because its first break was right under the
feet, where the "At the wall" shortcut fires. Previous sessions named this seam and deferred it
("the place plan only reaches the executor THROUGH the physics path ... giving the block planner
its own route to the executor is the real fix, and it is a bigger job"). Principle: **the engine
that planned the dig executes the dig.** Fix: `FastNavigator` owns break runs (`navOwnsBreakRuns`):
walk to the cell before the first break waypoint, start the executor's mining on that waypoint's
cells, wait for "Mining done", re-plan from wherever the dig left the body; `PathExecutor`'s
post-mining resume stands down while the navigator owns the run.
*And the body must be IN the plan's cell before the dig starts.* The terrace bench showed the
second half: a breakDown mines the floor of the node it was planned from, the walker declares a
leg done from up to a block away, so the bot at z=299.7 (plan: z=300) mined a neat three-deep
shaft in the neighbouring column without ever dropping, and the next cell was "out of reach".
Baritone's MovementDownward centres on `src` first; FastNavigator now steers the body to the
plan's stand cell (mouse pipeline, forward, sneak for the last block) before starting the mining
(`navBreakCentered/CenterTimeout`).

**G35. Every ore within reach is blacklisted as "dangerous" the moment a hostile is near.** Same
run, 09:09:32: fifty `Blacklisting dangerous Block{coal_ore / iron_ore}` lines in one second.
`BeatMinecraftTask.blackListDangerousBlock` condemns the nearest ore permanently
(`requestBlockUnreachable(pos, 0)`) for every hostile within 12 blocks of the bot and 30 of the ore,
every tick, per ore type — and the iron phase is abandoned for wood. Open: a combat decision (fight
or wait), not a navigation one.

**G36. "FastNavigator: no progress, handing over" handed the body to nobody.** The stall watchdog
stopped the navigator and nothing took over ("a fallback is not a fix; the navigator must never get
stuck"). Now: no progress → re-plan from the cell the body is in, with the full move set; a second
stall from the same cell → the honest verdict "unreachable from here", said out loud
(`navStall=replans/gaveUp`), for the caller to blacklist with a reason.

**G37. Camera thief while mining with a route live** (6x video: "the camera jerks madly UP while
blocks break BELOW"). Diagnosed long ago in `TungstenConfig.executorYieldsAimToMiner` — the
executor re-aims at its waypoint in the same tick the miner aims at the block — and left off until
an A/B. Default on; the run script pins it because the stand's `tungsten.json` carries false.

**G38. The miner runs back and forth between targets.** The log target changed every ~20 s in the
first phase of the 12:03 run (`-299,118,-230` → `-296,116,-227` → `-298,113,-254`, 25 blocks away
→ `-296,115,-221`), `AbstractDoToClosestObjectTask` cycling "Retrying old heuristic!" / "Trying out
NEW pursuit" / "Moving towards closest...", and "Waiting for calculations I think (wandering)"
whenever the scanner momentarily had no candidate. Principle: a target you are closing on is kept
until reached or proven unreachable; a scanner hiccup is a tick to wait, not a wander. Open.

**G39. A drop on a ledge is chased through the physics engine for 200 s.** The 13:00 run (build
d488e65b, the first to reach iron tools) lost t=87–290 s to one raw iron lying two blocks up a
ledge. Chain: `Pickup Dropped Items [[raw_iron]] → Approach entity entity.minecraft.item → Walking
straight at it (navigation would not)`; log: "Failed to pick up drop, suggesting it's unreachable"
at 15 s, "Failed exploring" ×12, a random dig, a pillar under the ledge ("Pillar stuck at
y=100.8"), "Drop has cost more than its budget" at 200 s. Root: `GetToEntityTask` has exactly one
engine, `TungstenHelper.tryPathToEntity` → the physics `PathFinder` (E4), which walks and jumps
and can neither place nor break; when it refuses, the task holds MOVE_FORWARD into the ledge face
(`entityCloseRangeWalk`) and then wanders. FastPlanner — the engine with `pillarUp` and
`breakStair` — is reached only through `CustomBaritoneGoalTask.driveTungstenPrimary`, and no
entity approach goes through the drive. So the user's question "does FastPlanner die on a two-block
ledge?" has the answer: it was never asked. Same disease the ores had in G25, one layer up.
Principle: **a drop that has come to rest is a place, and a place is reached by the build
engine.** `PickupDroppedItemTask` now returns `GetToDropTask` (a block goal on the drop's cell
through the drive, escalation to FastNavigator included) for a settled drop — on the ground, out
of water, not moving — and keeps the entity chase for a moving one; the give-up clock is held while
the navigator digs / places / pillars toward it (G32 applied here too); a failed pickup blacklists
and re-targets instead of wandering. Flags `settledDropIsABlockGoal`, `pickupFailureRetargets`;
counters `dropBlock=goal/held/retarget`; bench `deploy/runner/drop_ledge_test.py` (stair phase
with a pickaxe and no blocks, pillar phase with cobblestone and no pickaxe).
Found while benching it: a resting item's client-side velocity is NOT zero — `ItemEntity.tick` adds
gravity every tick and only calls `move()` (which zeroes it) every fourth tick while the item lies
still, so `y` cycles −0.04, −0.08, −0.12, 0. A "settled" test on the whole vector flipped the pickup
between the block goal and the entity chase on three ticks of four (chain alternating every few
seconds, body never moving, 2.4 blocks from the drop). The test uses the horizontal component only.

**G40. The last four blocks belong to an engine that cannot dig, and the snap can point at the bot's
own feet.** Two benches, one disease. `dig_down` in the 13:10 regression: FastNavigator mined six
blocks (`at the dig — mining … -52 … -57`), then at y=−57 with the goal at −62 the client log turns
into `Found rought path!` / `Time taken to find path: 2 ms` / `Finished!` every 0.5 s for 140 s while
the bot looks at the floor block. `drop_ledge` phase B: the bot on the ledge top, the drop 2.4
blocks away on the same flat top, `Tungsten (primary) pathfinding...` for 70 s, `Drop not getting
closer for 25s`. Roots, in `CustomBaritoneGoalTask.driveTungstenPrimary`: (a) `snapGoalToStandable`
walks a solid goal's column up to five cells for somewhere to stand, and from the bottom of the
bot's own shaft that cell IS the bot's feet — the snapped goal became "here", the `goal moved`
guard (25 > 16) stopped the navigator without a word, and the drive fell into (b); (b) inside the
4-block radius the physics executor is the only driver ("final precise approach") and it can neither
dig nor climb, so a goal five blocks down through stone or a body hanging off a ledge edge is
searched every 600 ms and never moved; (c) `twFnGoal` is per task instance, the pickup rebuilds its
task on every target flip, and `TungstenHelper.stop()` never touches FastNavigator — so a route the
previous instance armed kept running underneath the new instance, which refused to escalate
("navigator active") and spun physics. Principles: **a goal that cannot be stood in is reached by
the engine that digs — the snap may never land on the bot's own cell**; **within reach is not within
walking — a near goal the physics approach is not closing goes to the build engine** (at once when
the goal cell is unstandable, after 2.5 s still otherwise); **one navigator, one owner — a running
route that serves our goal is adopted, a stale one is stopped, an escape or a builder's exact
positioning is left alone**. Flags `snapNeverLandsOnSelf`, `nearGoalEscalatesToBuild`; counters
`snapSelfRefused`, `pdNearBuild`, `pdFnOrphan=adopted/stale`; benches `dig_down_test.py` (33 s,
12 blocks) and `drop_ledge_test.py`.

**G41. Arrival declared mid-air.** `nav_bridge` in the same regression, twice at identical
coordinates: the physics engine sprint-jumped the gap, the body passed within 2.0 of the goal in the
air, `FastNavigator: arrived (2.0)`, the navigator stopped — and the executor's replay, still
running, walked the body back to x=18.84, 4.2 blocks short, `nav=false path=-1` for the rest of the
course. Principle: **arrival is a state, not a moment** — the position test also requires the body
on the ground (or in water / on a ladder) and not sprinting, and stops a still-running replay when
it fires. Flag `arrivalNeedsSettledBody`.

**G42. A tower built one MovementPillar step at a time does not go up.** The 14:00 run (build
58d51b55): a log lay on a spruce canopy four blocks up; the pickup's block goal escalated to
FastNavigator, whose leg went to the MovementQueue as `9 movement(s) -302,110,-213 -> -302,115,-214
CLIMB+5`, and the ported `MovementPillar` reported `step 2 has taken too long (126 ticks, expected
25) MovementPillar (-302,111,-211)->(-302,112,-211)` eleven times in four minutes — under OPEN SKY
(the column at z=−211 is air from 112 up, rcon-checked) — two blocks placed in all, the chain
dropped and the identical plan re-issued every 14 s. `PillarTask` (jump, place while airborne,
stay centred) is the tower primitive that clears pit_escape, nav_wall2 and drop_ledge; the per-step
port with its sneak-pose click window through the mouse pipeline is not. Principle: **a tower is one
manoeuvre with one owner** — a planned pillar run is cut out of the queue leg at the tower's foot
and the top of the vertical run goes through the wall hand-off to PillarTask; the navigator keeps
its hands off while the tower (or a swim-out) is going up, and counts it as building for the stall
watchdog. Flag `pillarRunsGoToPillarTask`; counter `navPillarRuns`; bench `canopy_drop_test.py`.
The bench then exposed the tower primitive's own hole: `PillarTask` "stays centred" by releasing
the keys and never moves the body to the centre, so a hand-off with the body left at x=764.0 —
exactly on a cell boundary by the previous manoeuvre — read `Pillar stuck at y=-59.0` every 12 s
(`navPillarRuns=16`, the crosshair straight down lands on the neighbouring column and
`RealPlacement` predicts the wrong cell). Baritone's pillar centres before it jumps (the 0.17
test); so does the navigator before a dig (G34). PillarTask now walks to the cell centre, sneaking,
before its first jump (`pillarCenterTimeout` counts the towers that had to start off-centre).
With the tower's "stuck" verdict made to name its reason, the real hole showed on pit_escape:
`air=81 placeAt=81 readyNull=0 tryFalse=81 placed=0` — airborne, a cell to fill, the ray on the
support's top face every time, every click refused. The click was attempted from the first tick off
the ground, feet at +0.42, still inside the cell the block goes into; vanilla refuses a cube that
intersects an entity, and `BlockPlaceHelper`'s rate gate is armed by the attempt regardless, so the
next click came after the apex. Baritone's `MovementPillar` clicks only at `player.y > dest.y + 0.1`;
PillarTask now clicks only once the feet are above the cell's top (`insideCell` in the verdict).

**G43. Killed by a creeper it was pursuing.** Same run, 11:08:45 UTC: `COMBAT: → DANGER_BATTLE`
→ `NARROW_BATTLE` → `PURSUE` → `DANGER_BATTLE` → `tester1 был взорван Крипер`, hp 20 → 4.5 in
40 s, respawn with an empty inventory. Root: `MobDefenseChain` put the creeper in its fight list
like any hostile, `canDealWith >= dangerousness` held with an iron sword, and `KillEntitiesTask` +
the duelling controller closed to striking distance — which is the fuse distance; the existing
creeper branch only fires once the hiss has started. Principle: **a creeper is never engaged at
melee range** — one within 10 blocks that sees the bot is fled (`RunAwayFromCreepersTask`, run
out to 15, priority 66 above the fight's 65), a farther one is ignored. Six blocks was measured
too late on the bench (a creeper walking at the bot closes 0.45 blocks a tick; the turn-around
alone let it fuse, hp 20 → 11.8). Round 7 then showed the other edge: fleeing to 15 "finished"
with the creeper still targeting (its follow range is 16), the task walked straight back into it
and the bot was blown up three seconds after the flee ended — so the flee runs past the follow
range (20) and starts at 12. Still open underneath: a bot with a sword should kill a creeper the
way a player does, hit-and-back-off (the duelling controller's hold-at-striking-distance is the
wrong shape for a mob whose weapon is proximity); avoidance is the safe half. Flag
`neverMeleeCreepers`; counter `mdCreeperAvoid`; bench `creeper_avoid_test.py`.

**G44. A goal 94 blocks below is handed to the physics engine.** Same run, 11:07: with iron tools
the next target was deep (`physics owns the jump -> -343,6,-203`); FastPlanner's budgeted plan
was incomplete with no progress (`walking dead-ends (94.2 -> 94.0) -> physics owns the rest`), the
physics search `Ran out of nodes` / `Failed!`. Baritone mines a staircase toward such a goal.
Root: when the 250 ms budget runs out, FastPlanner handed back the path to the lowest-heuristic
node it had POPPED. Every dig costs ~23 ticks against a walk's 4.6, so A* opens a widening disc of
surface cells first, and the dug cells — generated, never popped — were invisible to the choice:
the partial ends on a surface neighbour, "no progress", dead end. Baritone does not have this
problem because it judges every GENERATED node against seven coefficients that discount the cost
travelled (`h + cost / coef`, `AStarPathFinder.COEFFICIENTS`) and walks the first candidate, from
the least greedy up, at least 5 blocks from the start — the greedier coefficients are exactly what
make a dug cell win — then re-plans from there, which is how it reaches diamond level in legs.
Principle: **a partial plan is chosen by progress per cost, over generated nodes, and walked in
legs**. Ported as `PartialTracker` (flag `planPartialLikeBaritone`, counter `planPartialCoef`);
bench `deep_goal_test.py` (25 down, 6 aside, through solid stone).

**G45. The start snap walked the start down the hole onto the drop.** Round-4 playthrough (14:50,
build with G42/G43): the bot stood on the rim of a 1×1 hole three deep with cobblestone at the
bottom, hitbox half over the edge, for the whole run. `GetToDropTask@block(-322,71,-542)`,
`atGoal=434 (ex434)`, `navRes=434 short`, `navStall=16/15`, `pdNearBuild=13`: every plan was
one cell. `FastPlanner.snapStartToSupport` — meant for a body in the air about to land — found no
support under the feet cell's centre and walked the start down the column to the first floor: the
bottom of the hole, i.e. the goal. Principle: **a body on the ground plans from its feet cell**;
the snap runs only while airborne (`startSnapOnlyAirborne`, counter `planStartSnapRefusedOnGround`).
From the rim the plan is then a one-step fall into the hole, which the walker performs. Bench
`hole_drop_test.py` (PASS 6 s, `startSnapRefused=1`).

**G46. The client crashed from its own status overlay.** Round-5 benches, 12:10:24 UTC:
`java.util.ConcurrentModificationException` at `CommandStatusOverlay.drawTaskChain:111` →
"Unreported exception thrown!" → the client JVM died, the container restarted, and every bench
after it read "cannot connect to the Java server". Two older crash reports (08-23, 08-27) carry
the same trace. Root: the overlay iterates the task chain's live `ArrayList` on the render thread
while the task runner rewrites it on the tick thread. Principle: **a renderer draws a snapshot**.
`render()` now copies the list (and draws nothing for a frame if the copy itself races).

**G47. Stone punched by hand with a pickaxe in the hotbar** (operator, on the 14:00 recording).
`DestroyBlockTask` never equips a tool — its own comment reads "Tool equip is handled in
PlayerInteractionFixChain. Oof." — and that chain refuses a tool INSIDE THE HOTBAR while
`Nav.isPathing()` is true ("Baritone will take care of tools inside the hotbar"), a clause that
outlived the engine it trusted: navigation is live on nearly every mining tick of the tungsten
drive, and tungsten's own tool hook serves only its executor's break queue. The chain's "Found
better tool in inventory, equipping." lines in the log are it catching up late (only when
navigation happened to be idle). A second hole underneath: `shouldSaveStack` keeps a worn iron
pickaxe for diamond-grade blocks even when it is the only pickaxe, so the "best tool" was NOTHING and
stone was punched (7.5 s, no drop). Principle: **the miner equips its own tool before it swings; a
tool being saved still beats bare hands.** `DestroyBlockTask.equipBestToolFor` (counter
`dbToolEquipped`), the fix chain takes any slot, `getBestToolSlot` falls back to the saved tool.

**G48. A mob 45 blocks away is chased with a 30-second physics lock that moves the body zero.**
Round-6 playthrough (15:38, the first on G42–G46): stone tools at 305 s, then five minutes
motionless at (−288.7,75,−771.6) on `Collect 220 units of food → Killing chicken → Approach entity
→ Failed to get to target, wandering for a bit → Wander for 5 blocks`; `lock=chicken:45.1>45.1,
m0.0` (twice), `wanderDenied=4014`, `pdEnter+0` for the whole stall — the drive was never entered.
`GetToEntityTask` has one engine, the physics search behind `tryPathToEntity`, and it is
short-range: 45 blocks of terrain defeat it, and every recovery below it (close walk, wander) is
short-range too. Same disease as the drops (G39) and the ores (G25), one entity type further.
Principle: **the long haul belongs to the drive; the physics chase is for the last blocks** —
beyond `CLOSE_WALK_RANGE` (8) the approach is a `GetNearEntityTask` (a live near-goal on the
entity's current cell through `driveTungstenPrimary`, so walking, the ported movements and
FastNavigator's dig/pillar all apply), handed back to the entity task inside 3.5 blocks. The first
cut handed over at 8: on the bench the drive delivered the body to four blocks in eight seconds
(three MovementQueue legs and a two-block pillar onto the ledge) and the physics chase then held
it motionless for forty — "Approaching target", the same m0.0 — so the last strides are the
drive's too. Flag `entityLongHaulViaDrive`; counter `entLongHaul`; bench `far_mob_test.py`
(chicken 40 blocks away on a two-block ledge).

**G49. A five-block partial thrown away as "no progress", and the clock blacklisting logs while a
route was being walked.** Round-8 playthrough (16:26): five minutes on `walking dead-ends (9.1 ->
8.1) -> physics owns the rest` / `physics owns the jump -> -315,71,-1409` with the goal nine blocks
BELOW — the coefficient partial (G44) was a real leg, `FastNavigator` judged it by "did the
straight-line distance shrink by four", refused it, and handed the goal to the one engine that
cannot dig. Baritone walks that partial and re-plans from its tail; that is the whole point of the
coefficient rule. Same recording, 13:31:52–13:32:04: `Failed to mine block. Suggesting it may be
unreachable` ×5 while the navigator was walking legs and arriving at them — `MineAndCollectTask`'s
progress clock reads a body standing at a leg's end as stuck (G32 again, one driver further).
Principles: **a partial at least five blocks long is a leg, walked and re-planned, whatever the
straight-line gain; a goal below with no such partial is given up out loud, never handed to
physics; the unreachable clock waits while any driver owns the route.** Counters
`navPartial=walked/noneBelow`; `MineAndCollectTask` holds its clock while FastNavigator, the walker
or the queue is running.

**G50. Seven minutes for six cobblestone: the miner swings before the crosshair is on the block.**
Round-9 playthrough (17:00): from wood tools at 153 s to the end the bot stood at (16.7, y, −1385.7)
on `Collect cobblestone x3 → Destroy block at 17,88,−1387 → Block in range, mining...`, its y
dropping one block every ~66 s (91 → 85), `dbBlockedSelfFloor=130`, `blockedBy=stone`,
`breakAim=93/17/13/4`. The target stone lies diagonally below; `DestroyBlockTask` turns toward the
reach rotation and holds CLICK_LEFT in the same tick — while the camera is still travelling the
crosshair sits on the bot's OWN FLOOR, the swing lands there, the floor breaks, the bot drops one,
the target is re-chosen, and so on down. The executor already learned this (G31: aim at a visible
face, click only on target); the altoclef miner never did. Principle: **no swing until the live
ray is on the block** — `CLICK_LEFT` is held only while `LookHelper.isLookingAt(mod, pos)` (a live
ray trace, not the stale crosshair) says so; counter `dbAimWait`.

**G51. The tower is started where the body stopped, not in the column the plan chose.** Round 10,
canopy_drop ×2 (the same bench passed ×2 on round 9): the planner picked the open-sky column
beside the canopy, the walk left the body one cell over at x=763.5 — under the canopy's edge leaf
— the wall hand-off started `PillarTask` right there, and the jump was capped by the leaf two
above the feet: `Pillar stuck … insideCell=80 placed=0 … center=0/60 at=(763.55,300.49)`,
sixteen restarts, "not getting closer", the drop abandoned. Principles: **a tower is built in the
plan's column** (the hand-off steers the body onto the jump's x,z first, the way a dig is centred
in G34) and **a column without headroom is refused at once** (PillarTask stops with "no headroom"
when a block sits two above the feet, so the navigator re-plans instead of jumping into it for the
stuck window). Flag `pillarInPlannedColumn`; counters `navPillarSteered`, `pillarNoHeadroom`.

**G52. A route outlives the drive that owned it.** `CustomBaritoneGoalTask.onStop` stopped only
the physics search (`TungstenHelper.stop`); the navigator, the walker, the queue and the building
primitives ran on under whatever task came next. The 17:22 recording, in a pit under the bot's
own crafting table (the canopy it stood on decayed and dropped it six blocks): the cobblestone
approach escalated to the navigator ("Path needs mining: 1 block(s)"), the task tree switched to
the table three blocks overhead, the click leaf aimed UP at it while the orphaned navigator handed
off to a tower ("Pillaring up to y=59") that aimed DOWN — `pitch=25`, no jump in eighty ticks,
`Pillar stuck … air=0`, and the stone beside the bot "failed to break" three times as the
crosshair swung between the two owners. G40 caught an orphan only when the NEXT drive started; a
leaf that does not drive (a click, a mine in reach) never did. Principle: **the route dies with its
drive** — a drive's `onStop` stops every route engine (`TungstenMod.stopNavigation`) unless the
interrupting task is another drive (adoption, G40), an escape is armed, or the builder holds an
exact cell. Flag `routeDiesWithItsDrive`; counter `pdRouteStopped`. PillarTask now also reports
`jumpStolen` in its stuck line: the jump it pressed found released before the game sampled it.
*Refined on round 12:* the first cut called `TungstenMod.stopNavigation()` — every engine, the
physics stop flags, the goto marker — and far_mob, green four times before it, went red: the
long haul hands over at 3.5 blocks, the entity task starts its close walk in the same tick, and
the drive's `onStop` ran after it and killed the LIVE walk the chase had just started; the body
stood at four blocks until the chicken was blacklisted four times. The drive stops only what it
owns — the navigator it armed (`twFnGoal`) with the towers/bridges/swim-outs it handed off to,
the grid queue, and the non-live waypoint walker. *Round 14, A/B with the flag:* still red with
that cut — off took the chicken in 13 s, on stood at four blocks for two minutes
(`pdRouteStopped=2`, `dc=…/none1337`). The task tree above a drive blinks for a tick now and
then (the chooser reads nothing for one tick, the unstuck chain cuts in, a parent returns null)
and the same drive is back on the next, so any stop in `onStop` restarts the route from scratch
every time the tree blinks. Final shape: **a route nobody has driven for a third of a second is
an orphan, and the leaf that holds the body stops it** — the drive stamps `lastDriveTickMs`
every tick it drives; `DestroyBlockTask` in reach, `InteractWithBlockTask` clicking and
`AbstractDoToEntityTask` striking call `stopOrphanRoute()`, which stops the navigator (with the
tower/bridge/swim-out it handed off to), the grid queue and the non-live walker only when the
stamp is stale.

**G53. A drop inside the tree the bot stands on, five below, is never reached.** Same recording,
14:23–14:27: the bot on the crown of the spruce it had just felled, a stick and a plank five
blocks below on the lower layers; grid BFS found no walking route, the navigator planned, made no
progress at its own cell, re-planned, gave the route up — "MovementQueue: 1 movement(s)
1234,65,-1406 -> 1235,65,-1406" over and over — and the pickup blacklisted both drops after three
tries each. Bench `tree_drop_test.py` rebuilds the tree (7x7 skirt, 5x5 body, 3x3 crown, the
stick on the skirt five below and two aside) and reproduced it on round 12: the navigator stepped
the bot onto the body layer, dug one leaf, and the body came to rest at `(803.1, -54)` — its
centre already over the air column above the stick, its hitbox still on the edge of the body
block behind it. From there: `Failed! No block path` fifty times, `no progress at 803,-54 —
re-planning` → `after a re-plan — goal unreachable from here, giving the route up`, the drop
blacklisted after three tries. Root: **the walker's arrival is horizontal only** — a waypoint
three blocks below, 0.4 blocks aside, counted as reached while the body stood on the lip above
it, so every leg ended without a step and every re-plan produced the same leg. Principle: **a
waypoint below the feet is reached by going down, not by standing over it** — while the body is
on the ground and the waypoint is clearly lower, the walker keeps walking to its centre until the
hitbox leaves the lip and the body drops; the airborne rule then holds the waypoint until landing.
Flag `walkerDescentNeedsDescent`; counter `walkerHeldAbove`. *Round 13, with that fix in:* still
red, one layer up — the body at `(803.1, -53)` on the lip of the body layer, the stick four below
in the very column its centre hangs over, `primDrive NO ROUTE (d4.0)` and the physics search
`Ran out of nodes` for sixty seconds. A four-block fall is beyond every engine's limit (rightly),
and the dig down through the leaves next to it was never planned, because **every planner
started from the centre cell, which has no floor**: the grid BFS finds nothing standable, the
fast planner rescues the start at the player's level and then can neither dig a floor that is
not there nor fall in place, and the leg it hands to physics dies. Second principle: **the
body's cell is the cell that holds it up** — planning starts from the nearest hitbox-overlapped
cell with a solid block under it (`FastNavigator.supportedFeet`, the test PillarTask already
uses), so the route begins on the block the body actually rests on and the dig down is its
first move. Flag `planFromSupportedCell`; counter `navStartSupport`. *Round 14, with that in:*
the start moved (`navStartSupport=1109`, `NO ROUTE: at 802,-53`) and the search still died
childless, `noSup=653` of 657 plans, `resc=0` — the start's support read as none and the rescue
(`startCellTrustsThePlayer`) is deliberately off because it also trusted swimming starts. Third
piece: **on dry ground the body's own level is its support** — a start with the body on the
ground, not in water and not on a ladder, is expanded from the body's level whatever
`supportTop` makes of the cell under it (`startOnGroundTrustsThePlayer`), and a childless start
now names its cell and the block under it in chat.

**G54. A new pursuit is given up on its first tick and banned for ninety seconds.** Round 11,
canopy_drop on a freshly recreated client, right after five other benches: `@get raw_iron 1` →
"Waiting for calculations I think (wandering) → Wander for Infinity blocks / Exploring" from the
first sample, `RTGATE targets=[[raw_iron]] dropped=true` on every line, the raw iron lying six
blocks away on the canopy and never approached; the same bench had passed twice on round 9 in a
different order. The closest-object chooser's idle give-up clock (`budgetIdleSinceMs`, 30 s of
not closing on the target) is a static, and unlike every other clock in that block it was NOT
restarted when the target changed — so the new target inherited the moment the previous pursuit
last closed on anything. Thirty seconds after that (a walk, a fight, the gap between two benches)
the first tick of the next pursuit read "idle", gave the target up, and `giveUpTargetStaysGivenUp`
refused it for ninety seconds: the chooser had nothing left and wandered. On the recording this
is a "Failed exploring" every time the bot picks a new thing to go for after half a minute of not
closing on the old one. Principle: **a clock belongs to the pursuit it measures** — the idle clock
is re-armed when the target changes. Flag `pursuitIdleClockPerTarget`; counter `dcIdleRearm`.

**G55. A solid block goal is "reached" by standing on it — by one layer, and never by the other.**
The 17:56 recording (G50+G51 build): nine of ten minutes on one spot, `(1465.7, 61, -1414.7)`,
chain `Performing an action: Getting to block (1465,60,-1415)` — BeatMinecraft's loot action asks
to stand in `chest.up()`, and here the chest is buried: the cell is sand, the bot stands on it.
Counters: `snap=2230/147/1830` (the snap wanted the bot's own cell and G40 refused it 1830 times),
`plan=… zero52 … atGoal=52(ex0,ytol52)@GetToBlockTask@block(1465,60,-1415)`, `pdNearBuild=8`,
`FastNavigator: arrived (1.0)` every twelve seconds, `Failed! No block path` every two. The planner
completes on a cell within one block of the goal's height and the navigator arrives within 2.0,
so both said "there"; `AltoGoal.Block.reached` is exact and said "not there"; the task asked again
every tick. Upstream altoclef leaned on baritone's `GoalBlock`, which would have MINED the sand and
stood on the chest. Principle: **a solid block goal is dug into, not stood on** — a breakable
solid goal cell (not bedrock, not a block entity) goes straight to the navigator as an EXACT cell
(`FastNavigator.startExactForDrive`), the planner completes only in that cell (`exactGoal`: no
height tolerance), and arrival is the exact cell, the same test `isFinished` uses. Flag
`blockGoalDigsIntoSolid`; counters `pdDig=armed/held/onTop`. *Round 13, bench `buried_goal`:*
phase one dug the sand and finished on the chest — and read as red only because both the bench
and the arrival tests floored the body's y on a 7/8-high chest top (the cell below); the feet
cell is now baritone's `playerFeet` (+0.1251) in `FastNavigator`'s exact arrival and in
`isFinished`. Phase two re-placed the sand where the bot had just broken it, the break-failure
detector read that as a claim and protected the cell, the dig branch was rightly refused, and the
old loop came back. **A solid goal that may not be dug is reached by standing on it** — when the
cell can neither be entered nor dug, on top of it is as far as any engine goes, and `isFinished`
says so (`pdDigOnTop`). The bench gives each phase its own column.

**G56. A tower is started under a ceiling the plan meant to mine.** pit_escape on round 14 (the
first red since round 10): the bench's goal is the surface pad block itself, so with G55 the
route climbs INTO it — the plan carries a break above the head — and the wall hand-off started
`PillarTask` under that pad: `Pillar stopped: no headroom, stone at 204,-53,200` (G51),
re-plan, the same hand-off, sixty seconds at y=-55. The hand-off dropped the plan's breaks.
Principle: **a tower through rock is a dig first** — the hand-off mines the solid cells above
the body (from two above the feet to the jump target) through the navigator's own dig ("at the
dig", the executor's break run) before it starts the tower; a ceiling that cannot be broken
gives the route up. Flag `towerMinesItsCeiling`; counters `navCeiling=mined/refused`.

**G57. The tool gate is skipped for a drop and the chooser mines a block bare-handed.** The
19:08 recording: two minutes at `(1820, 62, 683)`, chain `Collecting resource: wooden_pickaxe →
… → Collect cobblestone → Destroy block`, `dbToolEquipped=0`, eleven stone targets tried and
none broken (`dbTargets=11/11`, `dbUnreachMove=9` all near). The pack had no pickaxe (the
stone one lay six blocks below, blacklisted), a cobblestone DROP existed somewhere, so the
mining-requirement gate was skipped ("a drop on the floor needs no pickaxe") — and the chooser
then picked the nearest STONE because it scored closer than the drop: 7.5 s of bare-hand mining
against a five-second give-up, blacklist, next stone, repeat. Principle: **with the requirement
unmet, the drop and only the drop** — the chooser's blocks are off the table (`dropsOnly`), and
when no drop can be chosen either (banned, blacklisted) the tool is the job after all
(`SatisfyMiningRequirementTask`). Counter `dropsOnlyNoDrop`.

**G58. A search that spent its whole budget is read as "unreachable".** The 19:34 recording
(the first broadly green build: far_mob 2/2, tree_drop 2/2, buried_goal, canopy, hole_drop,
dig_reach, pit_table, nav 3/3) stood ninety seconds on a cliff above a drop at `(60, 98, -123)`:
`FastNavigator: no leg from here toward a goal 5 below (5.7 -> 3.0) — giving the route up` every
two seconds, `plan=540/7232/251ms` — each search 7000 nodes in 251 ms of a 250 ms budget, the
best partial inside five blocks because the dig moves round a cliff are dear and the frontier
never got past them, so G49's honest give-up fired where digging down was the answer. Baritone
plans for half a second and two on failure. Principle: **a budget that ran out is not a verdict**
— before the give-up, a search that hit its budget runs once more at four times the budget
(`planBudgetBoostBeforeGiveUp`; `navBudgetBoost`).

**G59 (open). A target under a one-block cover.** Same recording, 1:05–2:40 at `(81, 124, -44)`:
the miner in reach of stone under grass beside its feet, the reach ray through its own floor
(`dbBlocked=69/0/0` self-floor, `dbUnreachMove=17`, `dbTargets=46/16`). The approach's adjacency
accepts a cell from which the block cannot be struck; the plan should dig the cover and stand in
it (the block then under the feet), as baritone's GoalGetToBlock does from above.

**G61. The bot faces an animal, aimed at it, and neither walks nor strikes.** The 19:57
recording, and the user's loudest complaint of the day. Two dead bands, one on each side of the
approach/strike seam in `AbstractDoToEntityTask` / `AbstractKillEntityTask`: (1) the entity
approach's long haul handed over at a fixed 3.5 blocks, and inside that the only movers were a
straight walk gated behind six seconds of "the body has stalled" and a thirty-second physics lock
that on a slope moved it zero — so a kill task asking for 0.5 or 1.0 blocks (a target above, an
edge nearby) got a body that stood; (2) `canHitEntity` says yes from 4.5 blocks with a line of
sight, the strike branch hands the fight to the combat controller, and the controller drives
nothing until its own close quarters at ~3.4 — between 4.5 and 3.4 nobody moves the body and the
sword (3.0) reaches nothing; the earlier attempt to pull the strike branch back to 3.0
(`combatCloseToReach`) measured worse because it took the controller out of the zone it does
close in. Principles: **the haul ends where the caller's distance begins** (the drive runs until
the target is inside the caller's own distance, never tighter than one block, and inside that the
straight walk runs at once — `entityHaulToCallerDistance`, `entityCloseWalkImmediate`), and
**inside "can hit" but beyond the sword, the strike branch itself sprints in** until the
controller's range (`combatClosesInsideCanHit`, `kaClose`). Bench `pig_stare_test.py`: a pig four
blocks away on open ground, then the same pig with an eight-deep pit two blocks behind it.

Second reading, round 18: the bench still failed with the body motionless, `dte=621/621` (in range
every gate tick) and `kaTung=0/0/0/0` (the closing above never ran). The kill task's strike branch
is split by target type, and everything that moves the body lived in the PLAYER half; the mob half
was one instant aim and one click. The click lands only when the crosshair is on the hitbox inside
the sword's 3.0, the gate says "in range" from 4.5 — so a pig four blocks away was clicked at from
where no click can land, fifteen counted clicks blacklisted it as "no damage", the blacklist ran
out and it was aimed at again (`Blacklist ... Try 1 / 3` eight seconds after the task started, then
every eight seconds). Principle, the same one, now in the branch an animal reaches: **the strike
branch owns the legs inside "can hit"** — face the target, sprint until the eye-to-hitbox
distance is half a block inside the sword, then swing; a target above, at a drop, or behind a
straight line that stopped shrinking the gap goes to the entity approach (`kaMob`, the bench also
fails on any `Blacklist:` line).

Also seen, already tracked: `Pillar: out of blocks — nothing placeable in the hotbar` at 07:52:11 with
planks in the pack (G15, the throwaway whitelist); `Error when getting tasks! Something is broken!`
once at t≈30 s (an exception in `getTaskChainString`, cosmetic).

### Is baritone's move set fully ported into FastPlanner? No.

| Baritone move | What baritone does | FastPlanner today | Gap |
|---|---|---|---|
| TRAVERSE | walk; break the 2 body cells; place a floor when missing (bridge) | `step` + `breakThrough` + `placeAcross` | doors/gates (G12), non-collision blockers (G11) |
| ASCEND | step up; break head cells; **place the step block if missing** | `step` (clear body only) + `breakStair(+1)` | ascend-with-place (G7) |
| DESCEND / FALL | step down; break; fall any depth with water/bucket landing | `step` (fall ≤3) + `breakStair(-1)` | deep fall + MLG (G3), break-and-descend pricing (G9) |
| DIAGONAL | diagonal walk | `step` diagonals (planner) | not executed by the queue (G16) |
| DOWNWARD | mine the floor, drop one | `breakDown` (G1, done) | — |
| PILLAR | jump, place under; **mines the block above if needed** | `pillarUp` (refuses when y+2 occupied) | pillar-through-ceiling (G6) |
| PARKOUR | jump gaps; place a block at the far end | `parkour` (no place) | parkour-place (G7) |
| goals | GoalBlock, GoalXZ, GoalYLevel, **GoalGetToBlock**, GoalTwoBlocks, GoalNear, GoalComposite | exact cell (±1 y) only | **reach goal (G25) — the root of the mining stall** |
| costs | ToolSet best-tool pricing; avoidBreaking neighbours; break/place multipliers | held-tool pricing | G8, G10, G13, G14, G15 |
| unreachable | search reports "no path" and the process re-plans | one-cell / incomplete results, callers infer | G32 |

The load-bearing missing piece is not a move, it is the GOAL TYPE: without a reach goal no amount of
dig moves helps, because the request never reaches the planner in a form it can dig toward.
