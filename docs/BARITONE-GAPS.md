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
