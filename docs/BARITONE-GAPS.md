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
