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
