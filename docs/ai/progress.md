# Progress

## 2026-10-01 — full59 lava stall and bucket-portal continuation (in progress)

### Investigate
- Main and 1.21.11 are synchronized at 2805a570 (report-only head). The original control used the 0.95.53 jar
  built at 01:17; the current candidate binary is identified below. Original full59: 90 minutes,
  zero deaths, final 39 minutes at
  (-290.7,55,965.4), collecting lava through a radius-2 approach.
- Preserved its recording as deploy/runner/artifacts/full59-original.mp4 before
  the runner could overwrite gamer_run1.mp4. Retained checkpoint `last` unchanged.
- Unchanged-code checkpoint controls: lava pickup 13.75 s; portal-context pickup
  112.93 s at 10 FPS, then 15.34 s at 28-30 FPS with botFpsNoIdleThrottle pinned.
  The old persistent stall is not reproduced by restarting these tasks.
- Read player NBT: the retained stone pickaxe has damage 127/131. It breaks during
  the controls. The fresh @gamer's wooden-pickaxe request is therefore not evidence
  of a broken inventory restore. The original task's cached resource history differs.
- Read the original client log (2026-09-30-7.log.gz), goal adapters, FastNavigator,
  FastPlanner, bucket collector/caster and Baritone GoalNear/AStarPathFinder.
  Point planning versus region arrival is a candidate, not a measured root cause.
  Nearby failed-break warnings alone do not prove a regional ban: the original
  breakBanWide counter is zero and bans expire; the stack excludes extra predicates.
- The portal control advances into an upper mould placement and repeatedly wanders.
  Existing G108 already tracks this failure; its course only gates the first cast,
  while portal completion is currently recorded without affecting the verdict.
- Dedicated controls retain a live BFS walker after the builder navigator stops.
  At the stalled upper mould: navigation inactive, walker active at its final
  waypoint, mouse target pitch 0, cobblestone 41. The recorded bot jumps against
  the mould wall. Builder stopWalking cancelled FastNavigator/MovementQueue but
  left its independent BlockPathWalker running; clearQueue forgot ownership.
  Upstream BuilderProcess:622,734-745 clears keys and cancels the route for placement.
- Six original-JAR course controls (`artifacts/20261001-170420`): lit 3/6 at
  117.7/120.5/142.2 s; three unfinished after 420 s. All measured 29.2-29.5 FPS.
  Original-JAR SHA256: 7f6172ed5a68d7aa9de99eceab5071b80a3d81fa432a806176ac93d4f4e1b1a4.

### Plan
- Reproduce the remaining portal construction failure on the dedicated course;
  require a lit portal so that an unfinished upper frame cannot pass the test.
- Use repeated healthy controls, inspect the failing placement's actual stand and
  upstream movement/placement rules, then change only the established mechanism.
- Compile, verify deployed bytecode, repeat the target and adjacent regressions,
  release stable work with an edited Telegram report, audit and continue.

### Implement
- Added ignored checkpoint diagnostics and retained videos, timelines and NBT evidence.
- Added buildWalkStopsWalker for a same-client A/B: end the builder's walker when
  stopWalking hands the aim to placement/pillaring, and clean up before clearQueue
  forgets the walk. Both arms count walker-at-stop; the enabled arm counts cleanup.
- Tagged FastNavigator's waypoint legs so builder cleanup retains an independent
  chase/drive walk that replaced the positioning leg. Queue-clear runs on the client thread.
- The course now gates a lit portal within 420 s and records ownership-counter deltas;
  both timed gates declare load sensitivity. Python syntax check passed.
- Final clean tungsten build and :1.21.11:build passed (8m14s, exit 0). Verified the
  remapped cleanup, route provenance and client-thread API with javap; the nested tungsten
  is byte-identical to its fresh module jar. Deployed only tester1 and checked its SHA256:
  0d2a6d42fac389c778889715273c027752220a74a72965f00a45a621ea590bef.
- Started 12 interleaved course runs (six per flag arm), with recording and a lit-portal
  gate, in artifacts/20261001-175541. A host-only watcher snapshots timelines before the
  runner truncates them; run 1's timeline was lost, its clip/verdict survive. Snapshots declare
  their capture time and may omit the final poll interval. Verdicts retain counter deltas.
- Original and candidate PlaceBlockTask, PlaceObsidianBucketTask and ConstructNetherPortalBucketTask
  classes are byte-identical. Tungsten instruction differences match this ownership change.
  Same-client control run 5 failed the lit-portal gate after 420 s at 29.65 FPS. Its video
  repeats jumps against the upper mould; live telemetry shows nav inactive, navigatorLeg=true,
  BFS waypoint 4, pitch 0 and 42 cobblestone. Paired enabled run 5 lit at 107.1 s and entered
  the Nether; cleanup counted 26/26 opportunities versus 0/85 in the failing control.
  Final summary: control 5/6, enabled 6/6, 0 invalid; both arms 28.0-29.8 FPS.
  Enabled cleanup fired 24-35 times per run; control cleanup deltas were always zero.
  This is a six-pair completion observation, not proof of universal reliability; time improvement
  remains unclaimed (less than eight runs per arm and no reversed-order replication yet).
- Adjacent nav_flat/staircase/descend/break/wall2/bridge passed 18/18 (three repeats each),
  0 invalid, 28.0-30.0 FPS, zero gated falls/freezes, artifacts/20261001-184205.
  They ran after the valid enabled A/B arm on the unchanged candidate binary.
  Reviewed all six enabled portal sheets, the failed control-5 sheet and all 18 nav clips at
  a three-second cadence. Long stationary tails follow arrival; nav_descend-2 at video 12 s
  explicitly shows FastNavigator: arrived (0.3). No release yet.
- Prepared a live cancellation probe through buildBlocks/buildQueueClear and followPlayer,
  with read-only provenance telemetry. It requires a nonempty builder walk before clearing,
  then checks either owned-walker cleanup or preservation of an independent follow walk.
  First owned cancellation passed. The independent precondition initially failed because the
  diagnostic used ExecuteCommand (AltoClef dispatcher) for a Tungsten command; corrected to
  ChatMessage with the actual prefix and an active-follow/target assertion. Independent case
  then passed: walker retained and Z movement continued. Reusing one target across cancelled
  batches next hit the existing per-cell retry cap before a new walk started; original HEAD
  preserves the same lastWalkCell/walkAttempts fields in clearQueue. Fresh targets isolate the
  six cancellation contracts. Rerun passed owned cancellation 3/3 and independent-follow retention
  3/3; 21 telemetry snapshots measured 28-30 FPS. Both failed-precondition logs are preserved.
  The forced navUsesQueue=false pin is restored on exit, including SIGTERM.
- Prepared the next conditional campaign: require all 18 navigation results to be valid and green,
  recreate only the owned victim, run cancellation contracts, chase_flat three times and mixed
  placement three times. Its resumed controller accepted the corrected six contracts and started
  chase_flat, without redoing nav or recreating clients. Follow-up completed: chase 3/3 valid,
  28.4-29.5 FPS, contact 16.7/20.5/19.5 s, no gated freezes; reviewed all three recordings at
  three-second cadence. Mixed placement passed 3/3, each verifying all four actual blocks,
  with no timeout/deferred cell. Its outcome is load-insensitive and FPS was not separately logged.
- Prepared common-entry checkpoint replays for the flat portal course (reverse the pair order,
  record each arm separately, retain and hash the same world/player files). Started after the
  follow-up released its bench lock; save-flush and restored inventory equality are preconditions.
  It will not be evidence that full59's cached lava-approach state has been reproduced.
  First enabled replay lit at 108.6 s, 29.5 FPS, cleanup 25/25, but the recording and full
  timeline reveal one death at about 53 s. Client log 19:29:52 UTC: tried to swim in lava;
  preceding enclosed escape at (18,-58,2). Keep-inventory and immediate respawn let the task
  continue. Completion is not safe construction; new TODO records this unresolved cast hazard.
  The earlier A/B's eleven surviving partial timelines report zero deaths; run 1 has none.
  First disabled replay also died: client 19:32:20 UTC and its timeline death delta at 19.6 s.
  Death therefore occurs with cleanup disabled too, at a different cast stage. Root cause remains
  unknown; do not attribute it to ownership cleanup or call either replay safe.
  All four arms completed: enabled-1/control-2/control-3/enabled-4 lit at
  108.6/164.9/133.4/100.5 s, 29.4-29.8 FPS. Full-timeline survival scored post hoc:
  FAIL/FAIL/PASS/PASS, hence survival 1/2 per arm despite completion 2/2 per arm.
  All four recordings reviewed at four-second cadence; retained entry hash is unchanged.
  Added ctx.survival_criterion() to the course for future runs; this campaign imported
  the previous completion-only judge. Python syntax and diff checks passed.
  Stable release is deferred while the cast hazard is investigated; mod_version is unchanged.
- High-cadence checkpoint diagnosis (artifacts/g108-cast-probe-20261001-225447): enabled
  cleanup passed 3/3 under the new zero-death gate, 29.5/29.6/29.8 FPS. Recorded complete
  task lists, body, route and lava-entry instrumentation at 3 Hz. No lava entry/death in
  these three replays. Three airborne collector-interaction samples in two runs landed safely;
  this does not establish the cause of either earlier fatal entry. Next probes include the
  local downward block column and the disabled arm; no additional Java patch guessed.
- Edited milestone report rendered at 58 seconds, 14.6 MB for Telegram. Initial visual QC
  found the chosen death cut ended before the recorded fire/respawn; recording time differs
  from scenario time by about five seconds. Corrected the cut to video 52-60 s and rerendering.
  Check passed with 18 inherited structural warnings, one transient wipe occlusion and
  20/20 contrast checks; delivery remains pending final visual QC.
- Final report passed visual review at two-second cadence plus a one-second death-window
  sheet: fire and respawn are present, captions do not hide the health bar. Sent the
  58-second, ~14 MB video through the authorized Telegram launcher: message 9569.
  Local cuts adopted into HyperFrames media ledger; both history turns closed. CLI
  remains 0.8.105. Report states this is not a release and separates completion/survival.
  All three new enabled diagnosis clips reviewed at three-second cadence; enabled probes
  passed the new survival gate, but no hazard fix is claimed. Disabled-arm probes completed:
  completion 2/3, survival 3/3, 29.5/29.7/29.6 FPS. Run 2 hit the original upper-mould
  stall after 420 s; cumulative lava entries/deaths remained unchanged in all three.
- An isolated lifecycle contract compiled the actual Task.java and ITaskCanForce with
  dependency stubs only. Baseline fails when a plain wrapper contains a forcing child:
  canBeInterrupted accepts the first non-forcing node instead of checking every descendant.
  ResourceTask also forces while its required item is on the cursor. The grounded and
  cursor guards must both survive wrappers; explicit stop/chain interruption remains unconditional.
  This defect is established independently of the two fatal lava entries; causality is unknown.
  Artifacts: task-force-contract/baseline.log and src/TaskForceContract.java.
- Report tooling: HyperFrames pin 0.8.30 -> 0.8.105; existing composition check passed (exit 0,
  runtime/layout/contrast clear, 16 structural lint warnings in the old template). The report
  launcher now reads the project pin and gates rendering on check success. Python syntax passed.

Completed and earlier entries: [archive/01-10-2026-completed-progress-history.md](archive/01-10-2026-completed-progress-history.md).
