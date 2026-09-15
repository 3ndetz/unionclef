# Progress

Format and archiving rule: `docs/ai/readme.md`.

## Archived 2026-09-01

This file had grown to 1916 lines (the archiving threshold in `docs/ai/readme.md` is 500) covering
2026-07-20 through 2026-08-13 without ever being cleared, in violation of its own stated process.
Every block was either explicitly marked done ("СДЕЛАНО") or ended in a handoff to `TODOS.md`
(the last entry, 2026-08-13, explicitly files its open question as TODOS G-1.82 and defers the
decision to the user) — nothing in it read as work still in flight in `progress.md`'s own terms.
Moved verbatim, in the original file's order, into:

- `docs/ai/archive/21-07-2026-foundational-tungsten-features.md` — the 2026-07-20/21 foundational
  packages (drop-in baritone->tungsten swap, MCP server, agent perception loop, movement primitives,
  worldedit-like `//set`, anti-cheat rotation, godbridge + its sneak-bridge prehistory, block
  placement, combat primitives + shield, bow trajectory engine, BreakRules, visible breaking + chase,
  need-fulfiller API, PVP rework, slime parkour, autotesting design, shredder pathfinder v2 including
  the old sprint-speed telly-bridge rewrite).
- `docs/ai/archive/23-07-2026-bugfixes-and-place-as-a-move.md` — 2026-07-22/23 bug fixes (combat
  void-safety, BUG #29 frozen camera, #34 parkour move-gen) and the original place-as-a-move /
  core-bridge work (now superseded by TODOS.md C5.5-C5.14).
- `docs/ai/archive/24-07-2026-pvp-suite-and-live-fixes.md` — the 2026-07-23/24 break primitive,
  LIVE-A follow fix, and the PvP audit that produced suite v1.
- `docs/ai/archive/10-08-2026-pvp-suite-fixes.md` — 2026-08-08/09/10 (ASSESS pass, allround/bow_flee,
  edge_duel knockback, the interleaved-arms methodology fix).
- `docs/ai/archive/12-08-2026-combat-measurement-methodology.md` — 2026-08-11/12 mob_skeleton
  methodology work (the A/B-blocking bug, the floating-island course-validity bug).
- `docs/ai/archive/13-08-2026-mob-skeleton-combat-investigation.md` — 2026-08-13, the four-session
  mob_skeleton/inRange-band investigation that ends by filing TODOS G-1.82.

No active IPI task is currently tracked here. The next one written to this file starts a fresh
`## <Название задачи>` block per the format in `docs/ai/readme.md`.

## 2026-09-14 — Mac pipeline validation

### Investigate
- User request: update checkout, recover context, verify testing and Telegram video delivery.
- Updated cf82a5fc -> 3be654dd (2405 commits). See docs/STATUS-2026-09-14.md for the survival work checkpoint.
- Clients carried 0.60.0 and tester1 was outside the world. No test workflow exists under .github/workflows (only wiki.yml).
- The image lacks pkill; recording continued after the runner copied the MP4, producing a partial file.

### Plan
- Build and deploy current main, run navigation smoke tests with recording, inspect and decode the video, verify Telegram delivery.

### Implement
- :1.21.11:build -x check --offline succeeded with JDK 21. Version 0.94.0 built from 3be654dd is 59 commits newer than the release tag.
- UCTEST_GPU=0 deployment succeeded; nested jars matched, both Py4J endpoints answered.
- Recorder now uses Python /proc inspection scoped to suite ffmpeg processes and waits for exit before copying; launch and stop failures are checked.
- nav_flat/nav_staircase/nav_descend passed before and after this change. Second pass: 28.3/29.0/29.0 FPS, no falls or freezes. This is smoke coverage, not full regression or a reliability estimate.
- Artifacts: deploy/runner/artifacts/20260914-154558 and 20260914-154859. All three corrected recordings decoded without errors; flat-course frames reviewed through the finish.
- Reviewed flat-course video delivered through tg_video.py: ok=true, message 8792, direct connection. Credentials were not printed.
- G89 --nopick and open survival defects were not revalidated. Publication was initially blocked by automatic approval review; the user explicitly authorized continued work and publication on 2026-09-15. No release published yet.


## 2026-09-15 — Survival ascent handoff

### Investigate
- Fast-forwarded main to 6da576e6, built and deployed. Read the current gap triage against live sources before selecting a fix.
- Preflight risks: early tool/drop acquisition; mine/place ownership; deep falls beyond FastPlanner's three-block limit; land-to-water planning differences; low-health survival; unvalidated Nether and End progression. Existing tasks and ports are present, so these are test targets, not claims of missing implementations.
- DefaultGoToDimensionTask's Nether-to-Overworld helper constructs EnterNetherPortalTask(NETHER). This parameter is inconsistent with the helper's purpose, but Task.tick does not gate subtask ticking on isFinished; runtime impact remains unproven.
- Recorded ten-minute default survival baseline: wood, first craft and wooden tools at 113s, no stone tools, no deaths, median 29 FPS. The generic smoke reports PASS because any resource rung satisfies it; this is not a completion acceptance test.
- From ~113s onward: body (109.5,140,-46.7), target stone around (119,137,-52), pending break stand (109,141,-48). Replanning recurs without starting the dig. Screenshot and surrounding blocks show a one-block ascent out of leaves.
- A direct two-cell walker probe on that preserved geometry failed 6/6: inactive after two seconds, unchanged position and full health. BlockPathWalker accepts horizontal distance <1.5 before the jump. FastNavigator's later centering cannot perform a step-up. Baritone MovementAscend checks feet against destination cells.

### Plan
- Require destination-cell occupancy on ascending walker edges, retaining the edge's ascent identity after take-off. Compare the runtime switch off/on on the same geometry, run adjacent navigation gates, then resume survival.

### Implement
- Added walkerAscentNeedsAscent and the ascending-edge completion condition. Clean tungsten build and 1.21.11 build passed and deployed. Same-build control with the switch off failed 6/6; on passed 6/6, followed by another 6/6 with the checked-in probe (destination feet, grounded, no health loss). nav_flat/nav_staircase/nav_descend passed at 29/26/29 FPS; all recordings decode. Artifacts: deploy/runner/artifacts/20260915-065235. Survival continuation is running from the preserved stall position and inventory; no whole-game success claimed.
- Shared recorder finalization now serves both suite and gamer_smoke. The full 608s baseline recording decodes without errors. Reviewed failure excerpt delivered to operator Telegram, message 8812. Artifact: gamer_run1.mp4; retained excerpt in workspace outputs/unionclef-canopy-stall-before.mp4.


Probe replay (requires the recorded world's one-block step to remain intact):

```sh
python3 deploy/runner/walker_step_probe.py --server uctest-gamer-server --start 109.5 140 -46.7 --dest 109 141 -48 --ascent true --output /tmp/ascent-on.json
```

The probe accepts arbitrary start/destination coordinates for another existing fixture. Run `--ascent false` as the control; the runtime switch is restored on exit.


## 2026-09-15 — Reserved crafting material consumed as scaffold

### Investigate
- Ascent fix f307e5ad is committed locally. Automatic approval review rejected pushing to 3ndetz/unionclef:main; an explicit target-specific approval question is pending. No alternate publication mechanism was used.
- The second recorded continuation reached a stone pickaxe at 109.8s, then repeatedly needed more cobblestone while returning to the original table. This is a changed-world continuation, not a paired survival success-rate comparison. It ended with 19 HP, stone pickaxe retained; full video is in workspace outputs/unionclef-survival-after-ascent-2.mp4.
- CraftInTableTask already protects recipe materials through BotBehaviour. BlockPlaceHelper.isScaffold only tested block shape/type, so the planner budget, equipped-stack fast path and restock ignored that reservation. A live protected log still returned scaffold=true.
- Isolated physical test on six separate columns: 2 protected cobblestone in the selected slot, 8 dirt, pillar from -60 to -57. Old build climbed in 2.4–2.5s and consumed BOTH reserved cobblestone in all six trials. This isolates the permission defect from the survival chooser and inventory throwaway issues.

### Plan
- Share the brain's reservation policy with the existing common scaffold predicate. Check reserved and unreserved controls, navigation/build regressions, crafting, then continue survival.

### Implement
- Added canUseScaffoldHook; AltoClef registers !Behaviour.isProtected(item); BlockPlaceHelper gates eligibility before shape classification. All existing planner/selector/restock users already share this predicate.
- Added scaffold_reservation_test.py with a reserved arm and --unprotected control. Clean tungsten + 1.21.11 build passed in 18s. Deployment and post-change tests are in progress; not yet committed.
- Open separate observation: the first continuation stuck at (121.7,142.2,-52.5), even after stopPathing. Server ticks, gravity, abilities and client pose were normal. A backward diagnostic pulse moved the body away and it fell normally; the isolated next slope edge passed 6/6 after teleport. No cause or fix claimed for that contact state.


## 2026-09-15 — Ascent ceiling clearance

### Investigate
- User identified repeated attempts to enter a one-block opening as the primary failure.
- The destination body check exists, but step() only raises the take-off body by 0.6 for any ascent; diagonal corner clearance is checked at the old height only.
- Isolated top-slab take-off ceiling: planner emits the direct two-node ascent with no break plan; the walker repeatedly jumps against the ledge, never entering the landing over six seconds. Destination is standable; the raised transition body intersects the ceiling. A full-block tunnel ending one clear cell before a ledge passed; this negative result prevents attributing every stall to early waypoint advancement.
- This fixture proves a clearance defect, not yet the exact cause of the original natural-world contact stall.

### Plan
- Validate the ascending body envelope at the landing height, including origin and diagonal corners, using the existing shape-aware collision query. Existing breakStair offers priced ceiling removal instead of the invalid walking edge.
- Compare the runtime flag off/on on restored geometry, verify full-block one-high rejection/digging and open ascent controls, then adjacent navigation and natural survival video.

### Implement
- Added planAscentClearance and PlayerFit.ascentClear. Build and live A/B pending.

- Same-build slab control: 0/3 completed in 22s each. New arm initially 2/3 (one airborne-at-floor start kept mining slowly); preserving that failure separately. Requiring a grounded start after a 0.1-block setup drop: new arm 3/3 in ~13s. This is a setup correction, not proof that the natural contact-state issue is fixed.
- Full-block one-high tunnel: all three obstructing head blocks were correctly mined, but the final two-cell walking leg was consumed 1.2 blocks short. Bot remained at (1106.3,-59,304.5), goal (1107,-59,304); no-progress watchdog aborted. This is independent of the ceiling planner predicate.
- Added walkerFinalStandArrival: the final standable endpoint requires horizontal centring within 0.35, while unsupported build endpoints retain existing handling. Pending build and A/B plus bridge regression due the historical arrival/edge interaction.

- Final-stand A/B on the same rebuilt clients: walkerFinalStandArrival=false reproduced the full-block tunnel stall at x=1106.3 (0/1); true completed 3/3 in 7–8s, all at 20 HP. Recorded output: workspace outputs/ceiling-tests/ceiling-one_high-true-arrival-true.mp4. The camera points toward the next, deliberately unmined block beyond the goal; completion is measured by entering the requested goal cell, not the final image alone.
- Checked-in reusable test: deploy/runner/ascent_clearance_test.py, with independently selectable clearance/arrival switches, restored geometry, grounded precondition, settings restoration and configurable artifact directory. Nav flat/staircase/descend/bridge/wall2/gaps/notch regression is running.
- Telegram upload was rejected by automatic approval review despite previous deliveries: destination-specific consent is now pending for the configured operator chat via @mineswarmbot. No workaround used; videos remain local and test work continues.

- Adjacent regression on the first clearance implementation: 6/7 courses passed; nav_wall2 failed twice at healthy FPS. Same-build control disabling ONLY planAscentClearance passed in 9.1s, proving a regression in that predicate (arrival fix remained enabled).
- Cause in the predicate: replacing the lower source-body test with a higher box can hide a collision at the feet. Airborne start snapping can produce such an obstructed source near the ledge. Keep the original lower take-off test AND the new raised envelope; the new gate must only remove invalid edges, never admit an edge the old source test refused. Rebuild/retest pending.

- Corrected lower+raised predicate: seven-course nav recheck passed 7/7, no invalids (wall2 20.0, flat 28.7, staircase 23.3, descend 27.7, bridge 17.8, gaps 25.8, notch 24.3 FPS). Log: /tmp/unionclef-swept-nav.log.
- Final-build ceiling matrix: old slab flag-off control failed as expected; slab, full source ceiling, one-high full-block passage and open ascent all passed with both fixes enabled. Fixtures were restored per run and starts verified grounded. Workspace outputs/ceiling-final contains videos and JSON; no claim of whole-game completion or of the exact natural stall being resolved.
- A fresh fetch found upstream 6388f55d and 11c72a46 (bench fixes, deliberate-blacklist preservation, four stats). Integration is next; the measurements above used the pre-integration binary.

## 2026-09-15 — Flight permission leak and stable exact arrival

### Investigate
- The repaired clearance planner mined the mushroom ceiling at (120,144,-52) and crossed the forest step, but the player then hovered above the goal. Server and client positions agreed; gravity remained normal.
- PathExecutor initialized `allowedFlying=true` when constructed before login. `startBreaking` bypassed `setPath`'s snapshot; finishing an empty mining job restored that stale permission in survival. Live isolated probe: allowFlying false before, true after. Server game mode remained survival.
- After removing the permission leak, the player landed normally, but exact arrival could still finish during a jump. The exactCell branch omitted the settled-body check used by the radius/predicate branches. Adding it yielded 5/6 stable forest arrivals; the last trial coasted into a neighboring cell after input release.

### Implement
- The executor no longer writes or caches server-owned flight permissions. A client input hook suppresses the flight double-tap only while tungsten drives; manual flight permissions remain intact.
- Exact arrival requires a settled body and a release endpoint inside the goal cell. The endpoint sums the current horizontal velocity under vanilla ground drag, using the actual velocity-affecting block's slipperiness. Disabling arrivalNeedsSettledBody restores the previous arrival behavior.

### Validate
- Clean tungsten build and client deployment succeeded. `flight_ability_test.py`: 12/12 checks across six creative/survival pairs; empty mining jobs preserved permissions and completed.
- Restored natural approach (120.5,142.05,-51.5) -> (123,143,-53), mushroom ceiling restored before each run: final 6/6 exact grounded arrivals, 20 HP, no hovering. Times 14.67, 4.70, 4.84, 6.53, 6.82, 6.83 seconds. First run is slow and remains in the evidence.
- Earlier settled-body-only build: 5/6. Same-build settled=false control: 1/1, so the arrival defect is not deterministic on this geometry.
- Final video: workspace outputs/forest-coast-fixed.mp4, full decode passed; screenshots reviewed. Navigation regression audit passed 7/7 (flat, staircase, descend, bridge, gaps, wall2, notch), no falls, freezes, or invalid runs; 23.3–29.3 FPS. Artifacts: deploy/runner/artifacts/20260915-084426. Publication remains pending.

## 2026-09-15 — Preserve recipe materials during scaffolding

### Investigate and implement
- A pillar with two reserved cobblestone and eight dirt consumed both cobblestone in the original 6/6 trials. Scaffold suitability ignored the brain's protected-item reservations.
- AltoClef now supplies a scaffold-spending predicate. BlockPlaceHelper applies it in the shared scaffold gate used by planning budgets, selection and restocking. Standalone tungsten keeps the previous behavior when no predicate is installed.

### Validate
- Final live pillar test: protected 6/6 preserved both cobblestone, used dirt and reached Y=-57 from Y=-60 with 20 HP. Unprotected control 6/6 consumed both cobblestone and reached the same height. Behaviour stack restored after each trial.
- Checked-in regression: deploy/runner/scaffold_reservation_test.py. The deployed build also passed the seven-course navigation audit above.
- Recorded both series in workspace outputs/scaffold-reservation-final.mp4; complete decode and a pillar/inventory frame reviewed. This checks spending policy, not the entire survival crafting chain.

## 2026-09-15 — Inspect existing furnaces before resupplying

### Investigate
- The observed survival continuation reached stone sword/axe, shield and iron pickaxe. At the end of its ten-minute window, inventory held six iron ingots and the furnace at (99,132,-40) held the seventh in its output slot. The active smelting task was instead requesting raw iron. The exact interruption that lost the original furnace state is not yet isolated.
- A new smelting task treated an uninspected closed furnace as empty. Six ingots plus one ready furnace output reproduced the refusal to collect it in 4/4 baseline trials.
- Material accounting subtracted furnace input from the requirement, then compared against a count including furnace input again. Its acquisition target also requested the entire batch rather than the inventory remainder.

### Implement
- Inspect a nearby existing furnace before resupplying. Use the existing walk-versus-rebuild cost to bound the inspection trip, and retain its position for subsequent visits.
- Compare the remaining material requirement with inventory-only materials, request that remaining target, and close the furnace screen before acquiring materials or fuel in the world.
- Add `deploy/runner/smelt_recovery_test.py` with ready-output, full-batch, dropped-material and preloaded-input fixtures. Task startup runs on the Minecraft thread; observations read raw inventory stacks instead of invoking mutating lazy trackers from Py4J. Completion requires the requested items in actual inventory, an empty cursor and a stopped task.

### Validate
- Build and tester1 deployment succeeded; nested module jars matched the build.
- Final binary: ready-output 3/3; full seven-ore batch with four coal 1/1 (71.31 seconds); five ingots plus one furnace input and one dropped raw iron 1/1 (18.97 seconds). The server independently confirmed seven ingots after the latter. These are functional gates, not performance estimates; FPS varied during fixture initialization.
- All three final MP4s decode completely: workspace outputs/smelting/smelt-ready-final.mp4, smelt-batch-seven.mp4 and smelt-loaded-final.mp4. The full-batch furnace/inventory screenshot was inspected during execution.
- The initial preloaded fixture was invalid: injecting items through NBT left cooking_total_time=0. Server inspection confirmed that state; setting the normal 200-tick duration corrected the fixture.
- Separate mine-ore acquisition remained 2/3 on the preceding close-screen build. One trial stalled during mining, before obtaining any raw iron. Closing the furnace screen did not establish a fix for that failure. Retain it as an open mining investigation; do not report the smelting change as resolving mining reliability.
- Final navigation audit passed 3/3 (flat, staircase, descend), 28.7/26.7/25.7 FPS, no invalid runs, falls or freezes. Artifacts: deploy/runner/artifacts/20260915-095158.
- The next 240-second natural-save run crafted two buckets, collected nine coal, recovered the table and reached the surface. It did not verify retrieval of the old furnace output: other tasks took priority and the bot moved away. Nighttime damage reduced health from 19 to 7; the run ended while pursuing a chicken. This is progression evidence, not a completed playthrough or a proven fix for every original furnace interruption.
- The bot died between recording windows (server LastDeathLocation: 179,146,23). The old workspace observer stopped all bot tasks when finishing a video, leaving an unsafe unobserved gap. Exact death cause was not captured. Its replacement must keep gameplay and defence running across recording boundaries.
- No release or remote publication yet.

## 2026-09-15 — Recording must not disable survival

### Investigate and implement
- The workspace observer stopped all tasks in its recording cleanup. In a live survival world this also disabled defence between clips. Replace it with `deploy/runner/watch_survival.py`: observe by default, explicitly opt into starting/connecting, and keep gameplay active both across clip rotation and on observer exit. Stopping the bot now requires `--stop-on-exit`.
- Records bounded MP4 chunks, screenshots, task/inventory/health/FPS timelines, with atomic JSON updates. It never changes inventory, health or time of day.

### Validate
- Observed the same ongoing gamer task across three completed 120-second clip rotations. Gameplay continued without an observer-issued task restart. All finalized clips decode; interrupting the observer also finalized its last clip without stopping the task.
- Another death occurred at 111–117 seconds, before the first clip rotation, while defence was active. Thus fixing observation does not establish a fix for combat survival. Health fell from 16.5 to 3, then the player respawned; exact damage source remains unconfirmed.
- The resumed gamer retained its forced smoker task after losing every item. It spent subsequent minutes seeking three porkchops without rebuilding tools. BeatMinecraftTask keeps lastTask/lastGather across player replacement and onStart only resets timers/protection. This is the next focused investigation.
- A long delay in a flowing-water cave was observed and self-recovered; do not label it an established permanent navigation failure.
- Paused the natural save safely by disconnecting before stopping tasks on the flat stand. Last natural state: (83.3,97,-112.2), 20 HP, empty inventory. Current client is on test-server for the respawn regression.

## 2026-09-15 — Re-evaluate resources after player replacement

### Investigate
- Disposable flat fixture reproduced the natural failure: gamer started smoker/fuel work with stone tools and eight raw porkchops; after death and loss of the kit it kept the same eight-porkchop acquisition task for every post-respawn sample. Ordinary `onStart` only reset unrelated timers, and the forced-resource branch retained the old task.

### Implement
- Tie cached resource decisions to the ClientPlayerEntity instance. Replacement on respawn/reconnect clears lastTask/lastGather, switching history and station pickup commitments before selecting work again. Ordinary task interruptions retain these decisions because the player instance is unchanged. World progression caches (portal/stronghold/dragon) are untouched.
- `deploy/runner/respawn_resource_test.py` first checks that a client-thread interruption retains the identical resource task, then exercises a real inventory-losing respawn. Its gate is the new basic-tool resource tree, not completed crafting; the sealed fixture deliberately contains no resources.

### Validate
- Build/deploy succeeded; deployed nested jars verified by the deployment script.
- Three trials retained the same task/player across ordinary interruption and selected wooden-pickaxe acquisition after respawn (3/3). Sampled FPS20/14/13: these are functional state-transition checks, not performance or reliability estimates.
- Reviewed the resulting screenshot and fully decoded the recorded MP4 in workspace outputs/respawn-resources. Navigation flat/staircase/descend passed3/3 at23.7/25.3/22.3FPS, no invalid runs. No publication.

## 2026-09-15 — Descend under a low ceiling

### Investigate
- Reproduced the missing Baritone MovementDescend head block: source(1600,-59,320), landing(1601,-60,320), stone at(1601,-58,320). Old planner returned a complete walk-only route with no toBreak; the player stayed at x1600.7 against that roof for the full30-second sample. MP4 and screenshot reviewed.
- FastPlanner.step's old-height clearance test was commented out after an earlier nav_gaps regression. breakStair(-1) omitted dest.up(2), and deeper descents had no priced clearance variant.

### Implement
- PlayerFit.descentClear checks the full destination body column from landing feet through departure head using real shapes. Invalid walk-only descents are rejected.
- Extend cardinal breakStair descents through MAX_FALL, include the entire swept head column, price actual fall depth, and retain slab support/overhead shapes outside that vertical envelope. Existing break permissions and mining cost checks remain in force.

### Validate so far
- Current final clean build deployed to tester1. Stone descents1/2/3 each passed with the upper block present in toBreak, removed in game, and20HP. Top-slab descent passed after fixing test setup to heal AFTER teleport (the preceding attempt began at19HP during fixture reconstruction).
- The passage gate now requires the entire body beyond the roof column and a grounded landing, not exact-cell arrival. A previous slab attempt stopped at1602.8 for a1603 target; exact stopping and unnecessary post-mining jumps/turns remain unverified problems, not fixed by this patch.
- Final matrix: stone drops1/2/3, top slab, landing slab, open passage, bedrock roof and breaking-disabled roof passed8/8 functional cases. Negative cases rejected the complete route and retained20HP. Nineclips (including the invalid initial slab fixture) fully decoded; representative frames inspected.
- Navigation flat/staircase/descend/bridge/gaps/wall2/notch ended7/7PASS at18.4–29.3FPS. The first bridge attempt fell outside the arena and was markedINVALID; its automatic retry on recreated clients passed. The invalid attempt is retained in /private/tmp/descent-nav.log; the runner overwrote its per-course timeline on retry, so its cause cannot be attributed. Do not report this as an unqualified first-attempt7/7.
- Recorded the tail of the navigation audit separately; no publication. Exact-cell stopping and repeated jumps remain open.

### Natural survival death evidence clarified
- gamer-server /data/logs/latest.log identifies the two recent deaths:09:58:53UTC blown up by Creeper;10:06:53UTC slain by Enderman. This supersedes the earlier unknown-cause notes only. It does not establish whether the Enderman was provoked by gaze or an attack, or why defence failed.

## 2026-09-15 — Water headroom and supported bank exits

### Investigate
- Natural run after the descent fix lost8HP falling out of a water channel around(80,98,-135); the client's last DamageSource was fall. This does not prove ordinary MovementDescend caused it. The later10:53:14UTC death was shot by Skeleton. Natural save disconnected after respawn and fresh wooden-pickaxe acquisition; no surviving kit is claimed.
- Deep-trough fixture exposed a water-to-air waypoint at(1704,-49,340) with no floor or water below. First water-only patch removed that phantom exit, but the low-roof and waterfall executions still descended into a lower pool. These runs took no fall damage and are not reproductions of the natural8HP loss.
- Pool-bottom nodes also expanded dry ground steps/parkour. Water destinations bypassed bodyFits entirely. The fallback BlockNode guide independently accepted water-to-air exits without headroom:11:23:12UTC guide included(1702,-49,340)->(1703,-48,340) under the roof FastPlanner rejected.

### Implement
- FastPlanner checks the standing body at water destinations and the departure-head sweep on water entry, requires supported horizontal exits, and explicitly offers one-block-up bank exits. Pool-bottom water nodes use strokes/exits instead of dry walking/parkour expansion; planned digging remains available.
- PlayerFit.waterExitClear shares the supported landing and ascent envelope with the fallback BlockNode guide. Its water strokes and entries also check body clearance; the old fluid/air-only early acceptance is removed.
- Added deploy/runner/water_clearance_test.py, with recorded physical gates and route geometry. Water breathing isolates collision testing from idle drowning and is cleared during teardown. This harness does not start gamer/survival chains and is not a survival-policy test.

### Validate so far
- Clean build/deploy succeeded; nested jars verified byte-identical. Full-block bank, slab bank, ordinary entry, low-roof entry and low-roof exit passed5/5 functional fixtures on the final Java. MP4s decoded and representative screenshots reviewed.
- A prior roof run without water breathing correctly refused movement but drowned while idle; its respawn is a FAIL, not a safety pass. The final geometry fixture explicitly excludes this independent survival concern.
- Slab goal uses the planner's upper surface cell(-49), while actual feet are-49.5. A preceding containing-cell goal(-50) physically arrived but returned an incomplete plan; exact-cell representation remains open.
- Waterfall gap remains FAIL: phantom air exit removed, but a partial guide ends in flowing water and execution falls to the lower pool(minY-61). No claim of safe partial-route termination in currents. Do not weaken this gate.
- Standard navigation regression is running before commit. No Telegram send or publication; prior automatic approval blocks remain pending.
- Standard navigation final-build audit passed6/6(flat/staircase/descend/water/gaps/ladder),24.0–29.0FPS, no invalid attempts. Artifacts deploy/runner/artifacts/20260915-113258. Water course reached the far bank in11.2s; viewed the finish frame. This does not close the separate waterfall-gap failure.
- Checked-in water harness replayed low-roof exit successfully at20–30FPS, minHP20 with water breathing; video fully decoded. Fresh fetch found bf2b9c5a(TODOS-only headless registry investigation), reviewed for integration; no Java delta from upstream.

## 2026-09-15 — Natural progression and approach-radius overflow

### Investigate
- Ten-minute natural run after water-clearance fixes retained20HP throughout. Acquired stone tools, coal, iron pickaxe, equipped shield, and two empty buckets. Two cramped station-placement delays recovered without intervention; five120-second video chunks decoded and representative frames reviewed.
- Final natural state(103.3,121,-56.5),20HP,iron pickaxe/shield/two buckets; world disconnected before flat tests. Water approach toward(119,109,-15) then stalled for roughly the final140seconds, with14 samples naming GetWithinRange2147483647.
- GetCloseToBlockTask initializes Integer.MAX_VALUE and squares it as an int in inRange, producing1. The child AltoGoal.Near uses double arithmetic and is already satisfied at any normal distance. Parent never reduces the radius, child never moves.
- Disposable flat baseline reproduced MAX_VALUE and unchanged position20blocks from the target for9seconds. The expected-bug gate passed and video decoded.

### Implement
- Promote the radius before squaring; clamp the decreasing radius to zero when occupying the target. No navigation tolerances changed.
- Added deploy/runner/approach_range_test.py with direct approach, radius-zero, water-bucket and lava-bucket cases. It waits for the fixture teleport/grounding before task startup.

### Validate so far
- Build/deploy succeeded and deployed nested jars verified. Direct approach and radius-zero passed; actual water and lava collection both produced filled buckets with20HP. Videos decoded and both filled-bucket frames viewed.
- The first fixed direct-approach attempt failed its setup grounded/health assertion before starting a task; excluded, then rerun after explicit settle checks. It is not a navigation failure or a pass.
- Water/lava initial FPS samples were12/1, then29/23; these prove inventory outcomes only, not speed/reliability. Checked-in water replay is running before commit. Natural approach through the forest remains to be retried on the new build.
- Checked-in water replay passed and produced a water bucket; no further Java changes. Direct approach and replay videos decoded. Proceeding to the saved natural water approach.

## 2026-09-15 — Hunting must not replace the mining tool

### Investigate
- Natural approach on060e1e44 made progress toward water, then switched to food pursuit. At825.4s it retained18HP,iron pickaxe,shield,two empty buckets at(164.7,115,41.5). All seven video chunks decoded. Water collection was not completed.
- The hunt repeatedly timed out mining nearby stone. Live samples showed executor breaking=true, movement-queue block work=false, and alternating iron pickaxe/stone sword while KillEntityTask remained in the task chain.
- PreEquipItemChain only consulted MovementQueue, missing FastNavigator's direct executor digs. Baritone's reference PathExecutor aggregates remaining break/place cells from its own movement list; tungsten has a separate executor queue that also owns the hand.
- Corrected tunnel baseline reproduced21 sword samples during460 mining samples and failed to traverse the wall in40seconds. The first fixture is excluded: it accidentally mutated the class array retained by KillEntitiesTask and searched for Task.class instead of chicken.

### Implement and validation in progress
- Suppress background weapon pre-equipping while the executor mines/places or the direct controller breaks a block, retaining the movement-queue guard. Actual combat ownership is unchanged.
- Added hunt_tunnel_test.py: a chicken behind a three-cell stone wall in a bedrock tunnel, raw hand/engine samples and recording; open variant checks ordinary hunting.
- Build/deploy passed and nested module jars matched. Fixed functional hunt and navigation audit pending; no publication or Telegram retry.
- First fixed fixture physically crossed and killed the chicken, but its original zero-sword-samples gate failed:7 samples belonged to a new executor queue's settling/start interval, all preceded by a non-breaking sword state. Preserve that as-run FAIL. Refined the mechanism metric to pickaxe-to-sword transitions within a continuous queue: baseline10, first fixed0; second fixed replay passed with0. These are functional observations, not speed comparisons (initialFPS1, later30).
- Final fixture additionally clears prior item drops, asserts a pickaxe start, requires all6 cobblestone from the wall, full health, and the killed chicken. Open case requires observed sword selection. First final wall/open pair passed; repetitions and navigation audit in progress.
- Final harness passed5/5 functional cases: three restored-wall hunts and two open hunts. Every wall returned exactly6 cobblestone; all runs retained20HP, killed the target, and recorded0 pickaxe-to-sword transitions during a continuous mining queue. All five MP4s decoded; mining frame and hunt video reviewed.
- Standard navigation audit passed4/4(flat,staircase,descend,bridge),23.2–29.7 averageFPS,0 invalid attempts. Artifacts deploy/runner/artifacts/20260915-122346. Fresh fetch found no new upstream commits. Returning to the saved natural hunt; food priority logic was not changed.

## 2026-09-15 — Hunger request must not suppress defence

### Investigate
- Natural hunt rose115->129, then died at12:29:40UTC to Zombie. At171.4s the bot had8HP and held its pickaxe while still chasing a distant chicken; the death video shows a zombie touching it. Respawn lost the kit. Observer stopped after326.7s with a new wooden pickaxe; world disconnected before fixtures. Three videos decoded.
- FoodChain refuses eating near enemies. MobDefenseChain, KillAura and AbstractDoToEntityTask instead used needsToEat as a veto on defence/interaction, even when no eating was attempted.
- Hungry wall baseline: initialhunger3,137 pre-death samples needing food but not eating,death at11.47s. Fed wall ALSO died, so there is an additional combat/mining issue; do not attribute all deaths to hunger.
- Open rear-zombie controls escaped and survived, including the hungry arm which found room to eat. That hungry expected-bug gate correctly FAILED; it does not reproduce the circular wait. Initial two-second hunger setup also failed its precondition(hunger20): it consumed saturation only.
- Direct hungry zombie duel isolates the gate:106 samples needing food without eating,0 defence wins,0 committed-combat ticks,death at9.02s. No mining in this case. Video recorded.

### Implement and validation pending
- Defence and interaction now yield to isTryingToEat, not the hunger request. The dteHungry counter follows the active-eating veto it diagnoses. Creeper shielding uses the same ownership distinction.
- Extended hunt_tunnel_test.py with threat,full-hunger control and direct-duel variants; raw hunger/eating/combat counters and a stop at death. Actual post-combat meal is required when hungry; full hunger is not required if FoodChain considers the meal sufficient.
- Build/deploy in progress. No fixed result or natural survival success claimed yet.

### Hunger validation and separate ownership failure
- Build/deploy completed; nested jars verified. Hungry direct duel passed with the zombie active before task startup; fed direct control passed. Both retained20HP. Repeated hungry duel started at19HP/hunger0, finished17HP/hunger20 and killed the zombie. This demonstrates defence and eating, not health recovery or a reliability rate.
- Normal wall control passed after the hunger change:6 cobblestone,20HP,zero pickaxe-to-sword interruptions. Five fixed/control videos fully decoded.
- The first wall threat used a preplaced NoAI zombie and passed, but it does not isolate an attack beginning during mining. Subsequent fixture iterations failed setup (Java Iterable conversion, client-side tags not synchronized, preplaced victim already gone). They are not game passes/failures.
- A spawn-after-mining fixture finally reproduced a separate failure:13:10:58UTC server-confirmed Zombie death. During the attack mdTungsten advanced361->528 but the executor kept its break queue, pickaxe and wall-facing aim;HP20->0 at12.929s. Video inspected. The preceding no-mining timeout remains an unexplained failed setup, not attributed to hunger.
- Source confirms PathExecutor consumes stop=true without cancelling an empty-path mining job, so TungstenHelper.stop on task interruption cannot terminate it. A committed combat takeover needs explicit cancellation of block work, separately from replay drift. Combat audit running before committing the hunger-only fix.

- Hunger-only standard combat audit: meleePASS28.5FPS, weapon-swapPASS29FPS; triofirstFAIL23.4FPS thenINVALID13.1FPS; skeletonFAIL twice16.5/20.7FPS despite killing it (damage gate). These are retained open combat problems, not a green audit. Artifacts20260915-131356.
- Added explicit PathExecutor.cancelBlockWork for committed combat ownership. It drops block queues, replay/callback, attack/use/movement inputs and stale aim synchronously on the client thread. MobDefense stops the old navigator/walker/search before invoking it, only when block work exists and the fight is in engagement range. Drift's empty-path exception remains unchanged. mdBlockWorkCancelled records actual handovers.
- Clean tungsten build succeeded; deploying. Exact ambush now requires a recorded handover, surviving both targets, eating if hungry, and all6 wall blocks collected after resuming. No fixed result claimed yet.

- First exact hungry ambush on cancellation build PASS:handover1,HP20->14,hunger5->20,all6 cobblestone and both targets dead. Video decoded; combat and meal frames inspected. Fed ambush PASS:handover1,minHP20,all6 cobblestone,both dead,video decoded. Further repetitions and navigation audit pending.
- Fresh upstream70a0a127 changes mining watchdog to per-cell planner-sized budgets; fetched and reviewed, not integrated into these measurements. It is compile-verified upstream but has no stand result yet.

- Final ownership fixture matrix passed5/5: two hungry mining ambushes, one fed ambush, normal wall hunt and open hunt. Each ambush recorded one explicit handover and returned6 cobblestone; hungry minHP14/20, fed20. All five videos decoded. One sword transition in the second hungry case occurred after x1848 (wall ended1846), during a retained queue's settling; it is not a cancelled dig.
- Navigation audit ended6/6PASS(flat,stairs,descend,bridge,gaps,notch),23.6–29FPS,no invalid attempts. Bridge first attempt FAILED at healthy28.2FPS: crossed, declared arrival then coasted to25.62 for goal23, outside2.5 tolerance; retry passed. First attempt copied to workspace outputs/hunt-tunnel/bridge-first-failure before overwrite, screenshot inspected. Combat cancellation count stayed3, unchanged from the three ambushes. Do not claim an unqualified first-attempt6/6 or that retry explains the cause.
- This closes the reproduced hunger/queued-mining defence mechanism, not all combat, current-water safety, exact stopping, or game completion. Integrating upstream next; no publication or Telegram retry.

## 2026-09-15 — Preserve the approach before a queued dig

### Investigate
- Natural hunt logs previously showed emitted approach paths immediately followed by out-of-reach mining. Three PathFinder delivery sites set/append a replay and then called startBreaking, which replaces it with an empty path.
- Isolated executor probe:2 replay nodes before startBreaking,0 afterward,queue present. Live far-wall baseline:player remained1902.5,stone at1909 stayed closed,and breakAbortReach incremented within0.212s. Video decoded.

### Implement
- queueBreakingAfterPath initializes the mining job without touching replay/index/arming; the three replay-delivery sites use it. startBreaking remains the immediate at-wall API. Explicit combat cancellation also clears the newly merged per-cell watchdog target.
- Upstream70a0a127 merged as2ceecc63 after ownership fix caac6a79. The joint build is compiling; no fixed physical result yet.
- Added deferred_mining_test.py with far/immediate variants, raw approach/mining/abort samples, restored geometry/settings and recording.

### Validation
- Clean build/deploy succeeded with upstream watchdog included; nested jars matched. First fixed far-wall run approached1908.7 and mined both blocks without any reach abort, but FAILED its full-goal gate because raw physics resumeUsesSearchTarget was false. Preserve this as-run failure; it is not full arrival.
- The fixture now explicitly enables/restores resumeUsesSearchTarget for its raw PathFinder entry. With it, full far-wall traversal passed, and the checked-in replay passed4.42s with2 separately sized mining cells,20HP,zero reach/timeout aborts. Immediate-wall control passed3.24s.
- Two obsidian blocks with a diamond pickaxe passed21.29s,20HP,zero timeouts; budgetCells advanced1 at0.11s and2 at9.864s. This exercises a combined dig longer than the old queue-wide300-tick watchdog. All five fixed/control videos decoded.
- Isolated executor probe confirmed deferred delivery preserves2 nodes and tick1, while immediate start resets to0 nodes/tick0. Initial probe used public-field reflection for a private tick and failed before assertion; corrected to declared-field access.
- One-block-high ascent clearance replay passed2/2 on the joint build,20HP,three planned/removed head blocks. Video decoded and mining frame inspected. Merged hungry ambush and final navigation audit pending.

- Final joint-build hungry ambush passed:one explicit handover,minHP17,hunger8->20,6cobblestone,both targets dead;video decoded.
- Final navigation audit passed3/3(nav_break,nav_gaps,nav_wall2),24.8/26.6/28.8FPS,no invalid attempts. Artifacts20260915-134546. Proceeding to natural survival; complex partial approaches remain unverified.

## 2026-09-15 — Buried container false adjacency

### Investigate
- Natural run on245214b1 reached stone tools, then stalled crafting a stone sword at a capped table(103,131,-19). Saved after182.9s at(102.5,132,-18.6),20HP,18blocks; disconnected before fixtures. Two videos decoded.
- Live target reach=false; the cell above the table and the block below the player's feet were dirt. The approach task nevertheless repeatedly reported completion.
- FastPlanner.adjacentToBlock accepted horizontal neighbours at dy=1, unlike reference baritone/api/pathing/goals/GoalGetToBlock.java. The original uses Manhattan distance after folding the player's head cell into the feet cell; horizontal adjacency is only dy=0/-1. Our widened predicate accepted a diagonally elevated cell behind solid ground.
- Buried-table fixture baseline reproduced25s with adjacent=true,reach=false,unchangedposition,and the default player inventory handler throughout. Table remained intact. Video decoded and frame inspected. Initial fixture failed public-field access from Py4J; corrected to get_field before the measured baseline.

### Implement
- Match the Baritone predicate exactly, restoring overlapping cells for non-colliding targets and excluding diagonally elevated neighbours. AltoGoal shares the predicate; distant visible reach still uses its ray check. Clean build running.
- Added buried_container_test.py: capped/open table variants, real InteractWithBlockTask, physical crafting-handler gate,table preservation and20HP; restored settings/geometry. No fixed result yet.

### Validation so far
- Clean build/deploy succeeded,nested jars matched. Capped table opened in2.40s;repeat1.57s;open-cap control0.47s. All retained20HP and the table. Videos decoded; the fixed frame shows the planned cap removal. Functional outcomes only, not speed comparison: repeat inherited the shovel in hand.
- All125 offsets in[-2,2]^3 matched the12 explicitly enumerated Baritone goal positions, with no missing/extra cells.
- Adjacent craft audit(mine_stone,craft_at_distant_table) running before natural replay.

- Craft audit passed2/2(mine_stone,craft_at_distant_table),19.2/23.7FPS,no invalid attempts;artifacts20260915-140020. Natural replay next.
- Fresh upstreamd9bbc3b0 raises only the watchdog cap6000->12000 plus documentation. Reviewed for integration; ordinary stone/diamond-obsidian budgets stay below either cap. Its long wrong-tool margin has not been timed end-to-end here.

## 2026-09-15 — Deep descent must land before mining below its launch reach

### Investigate
- Natural replay on99d707e5 passed the buried-table blocker, made stone sword,shield,iron pickaxe. Saved/disconnected at283.4s,20HP,48blocks,(107.5,127,-11.7). Three videos decoded.
- A descent queued four cells down to(107,124,-11); after upper cells cleared, its lowest dig was hidden behind the own support(107,126,-12). Replans repeated the same unavailable action.
- Baritone MovementDescend.cost mines source y+1,y,y-1; dynamicFallCost requires deeper cells already open. Our breakStair mined the whole fall column, including blocks too low to reach around the launch support.
- New deep_dig_step_test: first fixture used an unsupported four-level destination and failed to reproduce (no route). Corrected geometry passed at cell center, including a filled column; these are controls, not reproductions. Offset -0.2 within the same source cell reproduced60 occlusion aborts in25s,unchanged position,lower stone intact.

### Implement
- Reject a descending edge if it needs to mine below source y-1; retain lower open fall cells and landing shapes. Search can land on the blocking cell and use a subsequent dig. Clean build passed; deploying before fixed measurements.

### Validation
- Fixed deep-column matrix passed6/6: four offset-0.2 starts,one centered,one offset+0.2. All reached the bottom with20HP,zero occlusion aborts,and preserved launch support. All six videos decoded; descent frame inspected. Plan now includes the intermediate landing and separate downward digs. Final five fixtures also removed old arena item drops before kit setup.
- Existing descent-clearance and one-high ascent regressions running, followed by navigation baselines and saved natural replay.
- Clearance audit passed6/6: stone overhang descents1/2/3,depth2 landing slab preserved,depth3 bedrock/disabled-breaking routes refused. All20HP. Sampled meanFPS17–26.7 (brief lows10/12 on two short courses); these are functional clearance checks,not timing comparisons. One-high ascent repeat passed2/2 with three head blocks planned/removed. Seven videos decoded and ascent frame inspected.
- Navigation baseline audit passed3/3(flat,staircase,descend),17.3/19.3/27.7FPS,no invalid attempts. No new upstream after fetch. Committing and returning to the saved natural world; game completion remains open.

## 2026-09-15 — Mining parent must yield to placement approach

### Investigate
- Natural8f825ef9 replay descended from its former blocked lip, then repeatedly failed to pillar back toward elevated iron. Pillar aimed upward at nearby coal instead of down; PlayerInteractionFixChain swapped dirt->ironpick hundreds of times. Saved/disconnected at143s,(107.5,127.2,-10.7),20HP,49blocks (one tower block eventually placed). Two videos decoded; client-stall.log copied into outputs/survival-deep-fixed.
- DestroyBlockTask's nearby-occluder clearing ran while its own approach was pillaring. PlayerInteractionFixChain's placement veto only covered PathExecutor,not PillarTask/BridgeTask.
- mining_pillar_test reproduces actual DestroyBlockTask approaching ore from a sealed shaft: pillar active,221 tool swaps,upward pitch,target intact after35s. Reference Baritone MovementPillar owns centering,jump,place and the hand as one movement; mining cannot be a concurrent writer.

### Implement
- Shared builderOwnsInputs covers active executor placement,PillarTask and BridgeTask. DestroyBlockTask keeps its same approach child and releases mining input while it is building; foreground mining resumes afterward. Both background tool and weapon pre-equipping respect this ownership.
- dbBuilderYield recorded/reset in Py4J stats; fixture records per-run handovers. Clean build passed,stats follow-up build/deploy running. No fixed result yet.

### Validation in progress
- First fixed run FAILED with pillar=false,yieldcounter0: pending placement confirmation from arena restoration immediately banned the pillar cell. It did not execute the changed branch. Fixture now clears old placement/break confirmations and temporary avoidance while the task runner is stopped, before scheduling the new task.
- Corrected fixed run passed7.841s,target mined,pillar observed,28 yield ticks,0 tool swaps,20HP. MP4 decoded,frame inspected. placeStats returned dbBuilderYield correctly (format/reset wiring checked). Repetitions and normal/open hunt audit running.
- Final normalized pillar/mining matrix passed5/5:target mined,20HP,zero tool swaps,26–34 builder-yield ticks. All videos decoded. Wall/open hunt controls passed2/2;wall returned6cobblestone with0pick-to-sword transitions during a continuous mining queue,both targets killed,20HP. Navigation bridge/pillar/baseline audit running.
- Navigation audit passed5/5(bridge,wall2,flat,staircase,descend),22.6–29.7FPS,no invalid attempts;artifacts20260915-143742. Fetch found no newer upstream. Returning to saved natural pillar attempt after commit.

## 2026-09-15 — Replace tasks bound to re-tracked entity instances

### Investigate
- Natural176cdb43 replay passed the pillar, fled a creeper, smelted7iron and made2emptybuckets; descended to y26 with20HP and199blocks. Saved/disconnected after540.9s at(130.7,26,0.6) when the food hunt repeatedly approached a DISCARDED chicken. Five videos decoded; client-stall.log retained in outputs/survival-builder-fixed.
- Live reload probe confirmed oldEntity.isAlive=false,isRemoved=true, but oldEntity.equals(freshEntity)=true because the network ID is reused. The closest-entity parent rejects the old target, yet Task.tick preserves its old child because KillEntityTask compares Entity.equals. Its final target remains removed, disabling the long-haul approach. Baritone FollowProcess instead scans current world entities each tick.
- entity_reload_hunt_test pauses task evaluation across a real server re-track, retains the old child, moves the fresh target20blocks farther and opens the corridor. Baseline reproduced25s stationary at2122.5 with fresh target alive and task equality=true. Initial reflective pause helper failed caller-sensitive access; lookup corrected before baseline.

### Implement
- Bound KillEntityTask,GetToEntityTask,GetNearEntityTask and isFor use object identity, preserving equality for the same live instance and forcing replacement for a new one with the same ID. No tracker or timeout policy changes. Build and deploy passed,nested jars verified.
- First two fixed runs killed the fresh target with20HP; all three task comparisons and isFor rejected the old instance while same-instance equality remained true. Third attempt failed in the fixture's concurrent world iterator (null entity), before result; guard added. Repetitions and ordinary hunt audit in progress.

### Validation
- Entity reload fixed matrix passed6/6 (runs1,2,4–7),target killed,20HP. Each confirmed removed/dead old entity,equal network-ID identity,different client instances,all old/new task bindings unequal,and same-instance KillEntityTask equality intact. Run3 remains a fixture failure,not a pass. Videos decoded; approach frame inspected.
- Wall/open hunt controls passed2/2:6cobblestone through the wall,zero pick-to-sword interruptions while mining,both targets killed,20HP. One-high ascent replay passed2/2 with three head blocks explicitly planned and removed,20HP. All videos decoded; low-ceiling mining frame inspected. Final flat/staircase navigation audit running.
- Final navigation audit passed2/2(flat,staircase),28.3/24.7FPS,zero invalid attempts;artifacts20260915-150643. Fetch found no newer upstream. Committing locally and resuming saved natural survival. Full game completion remains open.

## 2026-09-15 — Passable plants must not latch collision recovery

### Investigate
- Natural59b396b5 resumed old food hunt and progressed from y26 to54,then crossed a lush cave. Iron pick remained at damage221; stone pick handled ordinary digging. Water bucket present. Old removed-entity lock did not recur during346.7s. Three videos decoded and frames inspected.
- Creeper defence entered SafeRandomShimmyTask at(203.5,60,40.1),remained nearly stationary for over30s in tall grass beside a step and ledge. World capture: feet/head tall_grass,solid moss below,step/clay to south,air/ledge north. Later moved before disconnect;last sampled(198,56,37.5),17.5HP,285blocks. No attribution of damage to a particular source yet.
- Six task isAnnoying/stuckInBlock copies include tall/short grass,flowers,vines and neighbouring blocks without collision tests. Active shimmy stays latched while any such block remains; sneak-forward cannot jump the step.
- grass_unstuck_test reproduced on an isolated temporary-door course: after door opened at15s,the bot stayed in grass at2162.3,-60,820.3 through39.26s,shimmy active,20HP. It uses the real RunAwayFromPositionTask and naturally reaches the failed-progress branch;no task-state injection.

### Implement
- Shared WorldHelper.intersectsPlayerCollision checks live entity-context collision boxes against the slightly contracted player body. Six legacy annoying-block predicates now require real penetration before activating/latching recovery. Route planning retains ordinary contact and passable plants. Build succeeded,deploy running. Fixed fixture matrix and actual fence overlap controls pending.

### Validation in progress
- Fixed temporary-door matrix passed6/6: four tall-grass,one dandelion,one air control. All resumed across the step after the door opened,20HP,no SafeRandomShimmyTask. Baseline remained trapped after40s. All seven videos decoded;final escape screenshot inspected.
- Live collision probe passed6/6:air,short_grass,dandelion and open gate do not overlap;embedded fence and closed gate do. Adjacent fence rejected in all six;player coordinates verified so displacement cannot fake a negative. No claim about every fence escape outcome. Mining/pillar,buried-table and wall-hunt audit running.
- Adjacent audit passed3/3:mining/pillar target mined with26 builder-yield ticks,zero tool swaps;buried crafting table opened and preserved;wall hunt killed target after6cobblestone withzero mining tool interruptions. All20HP,three videos decoded. No Tungsten movement code changed in this pass. Committing locally and returning to natural survival.
