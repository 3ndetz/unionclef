# Progress

## 2026-10-01 — full59 lava stall and bucket-portal continuation (in progress)

### Investigate
- Main and 1.21.11 are synchronized at c6e4e7ea (tested builder ownership, no release). The original control used the 0.95.53 jar
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
- Fixed child selection to honor every descendant's veto. When a null child selection is
  deferred, the old subtree continues ticking so its movement/cursor guard can release.
  Explicit stop/chain interruption is unchanged. Added cumulative veto counters and a
  client-thread API for live execution evidence. Baritone safe-handoff references are at the site.
  Actual-source lifecycle contracts passed 9/9: direct/wrapped/ancestor veto, null selection,
  retained progression, candidate override, ordinary replacement/user stop, chain interrupt/resume,
  and stopped-tree restart (only active descendants may veto a fresh selection).
  Runnable contract: python deploy/runner/task_force_contract.py. Clean build passed in 8m08s,
  exit 0, including tungsten:clean/build and :1.21.11:build. The same nine contracts passed
  against Task loaded from the remapped jar (--jar), and javap confirms the active-subtree
  veto loop/counters. Nested tungsten jar is fresh and byte-identical. Candidate SHA256:
  50cd309d601abdeeb78978b4ff01547806d86d72967ccd0a708c85cd194ef62c.
  No claim yet about checkpoint survival or original full59 completion.
  Reviewed the three disabled diagnosis clips at four-second cadence: two finished/entered,
  run 2 repeats the upper-mould jumps through the 420 s timeout. No new fatal entry recorded.
- First new checkpoint replay passed at 29.4 FPS, lit at 112.6 s, zero deaths/entries,
  veto deltas 113 total / 9 below wrappers. Second reached the portal objective but its
  observer failed after ~106 s of recorded observations: getTaskChain streamed TaskChain's concurrently cleared ArrayList from
  py4j and dereferenced a null element. Retained clip/motion/log; second is not counted as
  a verified survival pass. The conditional adjacent controller was stopped before running.
  Fixed the read API to snapshot full descriptions on the client thread. This changes the
  instrument, not task selection; Task guard source remains unchanged. Rebuild/replay next.
- Reader fix build passed in 7m40s, exit 0. Nine contracts passed against the new remapped
  jar; getTaskChain bytecode invokes onClientThread. Task.class is byte-identical to the
  previous 50cd candidate, retained as g108-task-force-before-reader.jar. Nested module
  jars are fresh/identical. New SHA256:
  094ed645aaf8dd633b5b53e72192d570415716597ff55f2995023f4dc2d3eb7d.
- Reader-fixed replays (artifacts/g108-cast-probe-20261002-000144) stopped at the first
  gameplay failure: run 1 PASS/29.4 FPS, run 2 FAIL/29.8 FPS, complete observer exit 0 in both.
  Run 2 enters lava at motion t=84.12 s and dies at 86.14 s. Guard deltas 75/16 confirm it
  executed but did not prevent this entry. No adjacent campaign started and no release.
  Full clip reviewed at two-second cadence; fatal window retains body/velocity/column/tasks.
  Before entry, collection approaches (22,-60,0) from the upper frame, then a two-cell BFS
  route (21,-60,4)->(21,-60,0), maxHop4. Interact becomes the leaf while the body is airborne;
  the guard had vetoed that same handoff at the prior sample. After pickup, construction
  resumes while the body falls into (22,-61,2). A navigator route appears after the airborne
  takeoff, and lava escape runs before the death. Flight/cancellation and route ownership
  must now be traced from this actual entry; Task guard alone is not a cast hazard fix.
- Report tooling: HyperFrames pin 0.8.30 -> 0.8.105; existing composition check passed (exit 0,
  runtime/layout/contrast clear, 16 structural lint warnings in the old template). The report
  launcher now reads the project pin and gates rendering on check success. Python syntax passed.

### 2026-10-02 handoff instrumentation follow-up
- Reopened current Task/grounded guard, interaction click path, drive teardown/orphan
  cleanup, walker jump gate and SafetySystem landing scan. Upstream MovementParkour:244-249
  refuses cancellation after movement starts; the local grounded-only contract cannot
  describe pending jump inputs on the same grounded tick. Interact calls orphan cleanup
  from its parent's onTick before child arbitration. Both are candidate seams, not an
  established explanation of the recorded death.
- Added opt-in bounded TaskMovementTrace: selection/veto, drive stop, reachable interaction
  and actual orphan cancellation events, plus END_CLIENT_TICK observations. Rows record
  precise body/velocity/ground, keys, route provenance, task classes and drive age. All
  recording/reads run on the client thread; off by default. Sequence gaps/errors are
  explicit. The diagnostic changes no movement decision. Full :1.21.11:build passed
  in 8m27s, exit 0; remapped Task contracts 9/9 and nested-module freshness passed.
  javap verifies getMovementTrace(long), snapshot(long) and the enabled setter.
  Diagnostic candidate SHA256:
  269d72e8526a7a0e64c95d86e89f467960b241c58659eace9ee4081501109a38.
  Previous deployed 094ed jar retained as artifacts/g108-handoff-control-094ed.jar.
  Scoped deployment active; live capture pending.
- Deployment succeeded (tester1 only), verified by the probe's loaded-JAR hash gate.
  Six traced replays completed in artifacts/g108-cast-probe-20261002-003746: 6/6 valid
  completion/survival passes, 28.79-29.75 FPS, observer exit 0 in all six. Every captured
  trace has zero errors/gaps (3094/3134/3179/3392/3354/2942 events). Each has nine collector
  GetClose -> Interact handoffs; all 54 are grounded with jump not held at selection.
  Four runs retain an active walker at one such handoff; two runs cancel an orphan route
  while airborne. None of these six enters lava. This sample does not erase the earlier
  fatal replay or establish a safe-casting fix. The final observer poll may omit the last
  fraction of a second after the objective; the capture is not a complete post-objective trace.
  In run 1 a concrete GetClose -> Interact handoff at seq1484 was grounded, yet the
  BFS walker continued advancing from x18.84 to x21.48 over the following 470 ms.
  Another handoff at seq275 was grounded at x19.295/y-59, then forward returned on
  five ticks, the body stepped off at seq286 and orphan cleanup fired while airborne
  at seq287/driveAge312 ms. That entry did not touch lava. These show movement survives
  a concrete click handoff; they do not establish the earlier fatal entry's cause.
  The initial trace does not include MovementQueue/build primitive states. Three idle
  driver fields therefore do NOT prove an idle body: the queue is another key writer.
  Added those missing fields in source for the next build; current269d binary lacks them.
  Do not deploy/rebuild during the active healthy checkpoint campaign.
- First explicit-stop fixture did not observe its airborne-veto precondition within 90 s;
  it is unmeasured, not a stop failure. Revised the fixture to observe inside one persistent
  py4j process, start the real bucket-portal task and issue only @stop on a fresh airborne
  veto. No synthetic lift now. Revised fixture passed 3/3 at 26/26/30 FPS, each
  observing GetWithinRangeOfBlockTask -> InteractWithBlockTask veto while airborne,
  then active=false and an empty chain after @stop. Evidence:
  artifacts/task-force-user-stop-20261002-002729/results.json. This validates user
  cancellation, not safe casting or adjacent crafting/navigation behavior.
- Started the 36-case adjacent campaign on the unchanged 269d candidate after the trace
  campaign released its lock: twelve crafting/pickup/escape/navigation courses, three repeats
  each, retained recordings, survival/veto deltas and FPS floor/drift gates. Loaded-JAR SHA
  is checked before starting. Evidence log: artifacts/g108-task-force-adjacent.log;
  outcomes are pending. No movement-policy patch or release has been made.
- Reviewed all six trace-series recordings at four-second cadence: casting, mould
  repositioning, clearing the inside and lighting/entering the portal are visible. No
  recorded fire/death during construction; phase changes agree with the retained timelines.
  Prepared repro_checkpoint_task_safe.py for the original survival checkpoint: ownership
  lock, SIGTERM/finally cleanup covers setup, loaded-JAR/world-hash/position gates, full
  task-list snapshot, FPS floor and separate recordings. It never clears/re-kits inventory.
  Syntax/help passed; NOT RUN yet. The older repro_checkpoint_task.py remains unsafe and
  must not be used. Driver-trace source also records queue movement/from/to and navigator
  goal/exact/live-walker state for the next build; none of these additions is deployed yet.
- Adjacent repeats 1 and 2 completed 24/24 valid PASS on269d at29-30 FPS; repeat3 is
  active. Reviewed every first/second-repeat recording atfour-second cadence. Crafting
  actually uses the table, goto_then_mine digs at the new position, lava escape starts
  in lava, gaps use physics and water crosses underwater before arrival. Full-pack craft
  contains recipe reset messages but finishes with the requested pickaxe; no new hang
  is established by that message. Direct tungsten nav courses do not exercise Task vetoes.
- Strengthened the prepared survival-checkpoint diagnostic: offline tester1 UUID is
  verified against the saved player file; compare the complete restored Inventory NBT
  (including components/damage) and selected slot after save-all flush, then compare
  client slot item/count data. `last` contains25 stacks/549 items and stone-pickaxe
  damage127/131. Retained world hash and candidate must match again on exit. Optional
  trace rejects row gaps/errors and drains the tail before teardown. Syntax/help passed,
  but the replay remains NOT RUN. The portal observer's independent teardown now attempts
  every cleanup action and releases its lock even when an observer/recorder action fails;
  this revised tail/cleanup path is also not exercised yet.
- Adjacent campaign stopped at case35:34 valid PASS,1 arena INVALID; final repeat3
  nav_water was not run. nav_gaps-3 at29.4 FPS missed the last gap, fell toY=-230.3,
  died once, respawned and eventually reached the goal. Its self-fall/death gates are
  red even though the harness classifies leaving the arena as INVALID. Do not hide
  the fall or call this36/36. All35 recordings reviewed, the failing clip at2s.
  The nav course's own source documents a pre-existing fall rate (2026-08-12), but that
  history is not a contemporary control. Direct goto uses no Task chain/vetoes here;
  attribution to the Task fix is unestablished. Next: refresh the client through the
  required deploy script, repeat gaps/water with expanded driver observations, then
  resume the portal checkpoint diagnosis. No movement-policy patch guessed.
- Saved and hash-verified the deployed269d candidate asg108-handoff-control-269d.jar.
  Full :1.21.11:build of expanded driver-trace source active (session80737,
  artifacts/g108-complete-driver-trace-build.log). No live benchmark now; three host
  javaw processes expose neither debug nor IDE markers and versions/*/bin is absent.
  mod_version remains0.95.53; release0.95.54 remains a draft pending this audit.
- Expanded trace build completed in 6m56s, exit 0. The remapped jar changes only
  TaskMovementTrace.class versus the retained269d binary; Task.class and all nested
  modules are byte-identical. Remapped lifecycle contracts passed9/9, nested freshness
  passed, and javap confirms queue movement/from/to/safe-cancel, navigator goal/exact,
  live walker and build/follow driver fields. SHA256:
  5516d6ea910caab60c0b4bdb0192f7314e5e6c6376560ff5afa9b2f5a88324f2.
  Required deploy succeeded for tester1 only; tester2 remains stopped. Started the
  private nav-tail campaign (session43423): water3 then gaps6, retain every run,
  reject trace errors/gaps or an active Task runner, and stop at the first non-green.
  This separate campaign cannot erase the preceding nav_gaps-3 death. Results pending.
- Reopened full59's pre-stall timeline and checkpoint metadata. Only its last four
  periodic checkpoints survive, all after the stall, but rung-bucket is from this
  same run at2432s, before the portal approach/casting at2607-3060s. Retained a
  byte-identical private copy asfull59-before-portal (all-file hash
  cc4c6c3b368c3c90a1dce9f519c42bb2a9f9e8686fed68115f017970ec7f4801).
  This avoids a later playthrough overwriting the original rung entry. Its resume
  has not run yet; it may reconstruct the transition history that restarting at
  `last` loses. Original `last` and full59-original.mp4 remain unchanged.
  Nav-tail water passed3/3 at28.3/29.7/29.7 FPS; all three clips reviewed at3s,
  showing underwater crossing then arrival. The first gap replay also passed and
  was reviewed at2s. Campaign remains active; no repaired gap-fall claim.

### 2026-10-02 nav-tail result and expanded portal capture
- Nav-tail session43423 finished exit1 at the first non-green. Exact results:
  water3/3 valid PASS, gaps3/4 valid PASS with one valid FAIL; gaps5/6 NOT RUN.
  Failed gap average29.5FPS, minY=-249.7293, one fall/death followed by respawn and
  eventual arrival21.4s. All seven clips reviewed (water3s, gaps2s, fatalgap1s).
  Inactive Task-runner gate and continuous trace assertions passed in every case;
  cleanup errors=[] and lock released. Direct physics replay, not Task arbitration,
  owns the failed hop. This cannot erase the preceding35-case campaign's fall.
- Failed hop ages316-331: executor starts atx17.5426 with residualvx0.0036;
  jumps without sprint, then sprints forward. Atage327 x18.3068/y-59.8787,
  the body no longer overlaps the platform ending atx18. It falls belowy-60
  atage328 and presses the next jump atage329 while airborne. Drift abort at
  tick15 expects19.22/-59.25/1.09 versus18.83/-61.42/1.12. Successful repeats
  land the first hop at the lip and then jump. The difference is millimetres at
  the edge; residual root velocity/rotation precision are hypotheses, not a fix.
  Existing Agent.compare diagnostics can print simulated/actual states through
  verboseDebugLogging; use that instrument before adding more Java tracing.
- Expanded portal campaign session52830 started on unchanged5516 candidate:
  artifacts/g108-cast-probe-20261002-020451. First two runs validPASS, observers
  exit0, zero lava entries/deaths,28.84/29.57FPS; both clips reviewed at4s.
  Run1 trace3169 events, zero gaps/errors. Atseq187 the task selects interaction
  while MovementTraverse remains active; seq189-200 show the queue continuing
  forward through later task changes. The previously unrecorded key writer is
  now visible. This is a successful-run observation, not causality for the fatal
  lava entry. Remaining runs and final independent cleanup are pending.
- Expanded portal campaign finished exit0:6/6 valid completion/survival PASS,
  FPS28.84/29.57/29.78/29.67/29.76/29.55, all observers exit0. Traces contain
  3169/3440/2976/2943/2876/3215 events with zero errors/gaps, including explicit
  final drains of4/4/4/5/3/4 events. Independent cleanup errors=[], lock released.
  All six clips reviewed at4s. Across55 collector-to-interaction handoffs, the
  queue remains active at49 and the walker at3; one orphan stop is airborne.
  These are recorded ownership states; activity alone does not prove which driver
  wrote each key. No safe-casting fix or comparison with the old fatal entry is claimed.
- Prepared a guarded native15min replay from full59-before-portal: raw resume,
  native5min/rung checkpoints, unique end save, retained originallast. It verifies
  loaded jar, original all-file hash, saved inventory components, client slots
  and horizontal position before @gamer. A first invocation stopped before any
  game writes because it compared a world-only digest to the all-file digest;
  corrected the scope to include meta.json. The corrected invocation is active
  (session53422), logfull59-before-portal-native-resume-verified.log; entry gates
  and playthrough outcomes are pending. The native task-string reader still reads
  the mutable list off-thread; this fixture uses the verifiedgetTaskChain snapshot.
  Private verbose-gap fixture syntax passed; not run yet.
- Native entry verified on5516 at30FPS: original inventory components and client
  slots match, iron pick damage35/stone109,218items in17stacks. Collected iron
  at73.7s, then shield resources. Held a shelter at172-366s with20hp; this is
  not evidence of a daytime-condition bug. Original level.dat has raining=1,
  thundering=1, DayTime50031; periodiccp1002-0229-t322 still has both weather
  flags and DayTime56999. WorldHelper.canSleep explicitly includes thunderstorms.
  The shelter ended by391s and portal approach started by415s. A drowned reduced
  hp to15 at488s; food restored20hp by514s. At538s the task requests another
  shelter at(-89.3,61.3,1062.3), reports no acceptable site and remains there.
  The15min run is still active; no actual Nether transition or fix is claimed.
- Reopened physics root creation: PathFinder.find starts a worker; Agent.of(player)
  occurs later in search/initializeStartNode, not atomically at the publicfind call.
  The pfRootMoving counter therefore need not describe the eventual root state.
  Compare existing exactsim/real logs before attributing the gap fall to residual
  velocity or native rotation quantization. Prepared verbose-gap run stillNOTRUN.
- Read-only no-site snapshot confirms30FPS, water in feet/head cells(-90,61/62,1062),
  20hp, no active walker/pathfinder/executor. This is a real exposed-water hold,
  not an idle-client or daytime false alarm. Retainedno-site-snapshot.json and
  periodiccp1002-0229-t656; added the corresponding general openTODO.
- Native replay finished exit0 with verified entry/checkpointunchanged and cleanup
  errors=[]. Actual outcome:0deaths, iron/bed/shield acquired, allOverworld,
  no portal or Nether entry. The native smokePassed=true only means its weak
  progress gate passed; the last~6min no-site water hold remains red. Original
  checkpoint andlast protected, uniqueendCP retained. Copied recording under the
  private run directory and reviewed completeclip at36s cadence (908s real-time).
  Shelter hold and later water hold visibly match the task records. Budget8170MB
  of15GB, diskfree194GB. After cleanup/lockrelease started verbose-gap campaign
  session66100 on unchanged5516, logg108-gap-verbose.log; outcomes pending.
- Verbose-gap66100 finished exit0:6/6 validPASS,29.67FPS each, no trace gaps/errors,
  inactiveTask runner and independent cleanup errors=[]. All six clips reviewed2s.
  Exactstdout vectors joined85/87/83/87/87/87 executor ticks to body age/position
  within1e-7. Last-gap initialvx simulation0.010407/0.006506 versus actual0.001694/
  0.001940 in examples; the historical failed hop was not reproduced. Successful
  repeats retain root/rotation errors too, so these errors alone are not a cause.
  Verbose logging can alter search timing; no physics-handoff fix is claimed.
- Added a null-by-default Agent.compare observer feeding the existing boundedtrace
  with exact expected position/velocity/ground/yaw/sprint/jump cooldown/input and
  actual post-vanilla state. It observes the same executednode the stdout diagnostic
  uses, avoiding a second worker-racing path read or next-node indexing. No movement
  policy changed. Full clean-tungsten/:1.21.11:build active(session40455); notdeployed.
  Private one-case verbose calibration and six quiet-gap replay modes prepared;
  syntaxpassed, notrun. Calibration must match new vectors to existing exactstdout
  before interpreting a quiet failure. Validator also requires nonempty/contiguous
  trace and uniquephysics ages; the old fatal failures remain retained.
- Full clean-tungsten/:1.21.11:build passed6m38s exit0. Remapped observer and
  expected-vector trace fields verified withjavap; nestedmodulefreshness passed.
  Task.class is byte-identical to5516, so its existing9/9 contracts still apply.
  Changed classes areAgent andTaskMovementTrace; their syntheticinner classes
  also differ due source line tables (Agent$1 instructions are byte-for-byte
  equivalent in javap output). New candidateSHA256:
  77b547b6d6267bf4aed25ecbd69860a818339ce07ec4efae35a4cc955a868556.
  Required deployment started for tester1only; no runtime calibration yet.
  The no-site checkpoint contains a whitebed. The parent's exhausted-sleep-budget
  branch can request shelter while carrying a bed; its label does not prove an
  inventory failure. Keep site selection/approach distinct from that timer policy.
- Deployment39674 finished exit0. Single-case calibration44110 passed29.67FPS,
  trace432events/86physics comparisons, noerrors/gaps, cleanupempty. All342logged
  actual/expected vectors match new post-vanilla rows within1e-7; zero ground/sprint
  disagreements, maxposition error0.03836. Fullclip reviewed2s. This establishes
  the observer's sampling point, not a movement fix. Started six --quiet-gaps
  repeats on77b547 with verboseDebugLogging=false; each pins/readbacks the flag,
  requires nonempty physics rows and retains everyfailure.
- Quiet60381 stopped exit1 on the second run:1/2 validPASS, one validFAIL,
  repeats3-6 NOTRUN. FPS30.0/29.25, cleanup errors=[]; both fullclips reviewed
  (green2s/fatal1s). Traces426/636events and87/173physics rows are contiguous,
  error-free and Taskinactive. Validator finds zero sprint disagreements. The
  fatal lastleg starts atage306 with simvx0.019043 versus real0.003100; its
  root position/velocity match different phases of the earlier clienttick.
  The preparatory hop lands, but atage329 realx21.648819 trails sim21.725477.
  Atage330 vanilla collides with the next platform's side atx21.7/y-60.323529,
  while the plan lands atx22.009595/y-60. Ground disagrees; the next jump fails.
  This differs from the preceding fatal which missed the preparatory landing.
  Both expose stale-root replay across a support boundary. Baritone's executor
  recalculates current/future movement feasibility before starting it (lines197-218).
  Next pass: validate the recorded trajectory from the actual client-thread state
  before takeoff, including native yaw quantization. Do not patch the settle speed
  or weaken collision/fall guards. No navigation repair or release claimed.
- Detached counterfactual replay on the retained arena exactly reproduces every
  old simulated position/velocity (maxerror0.0). Substituting the actual pre-input
  root while keeping the old exact yaw predicts the same missed landing; using
  actual recorded yaw also misses. No real player was moved by this experiment.
  The model still snaps small velocity components individually; recorded vanilla
  can retain a small component when its other horizontal component is moving.
  That secondary simulation difference is not patched in this focused pass.
- Added a live-root landing feasibility check before fresh grounded replays,
  following Baritone PathExecutor:197-218. Re-simulates existing Agent inputs,
  including the root's idle tick and native mouse quantization; refuses an input
  schedule whose recorded landing is no longer possible, before releasing the
  body from its platform. The real body is never snapped to the old plan.
  Observation counters run in both arms; replayChecksLiveLandings gates only
  refusal. Path-mutating methods share the tick monitor during this check;
  this does not close external multi-field reads/direct public-field C4.2 races.
  Water/ladder/airborne and aim-owned entries are not cancelled by this ground
  boundary check. Full clean build72715 active; behaviour remains unverified.
- Reopened vanilla1.21.11 Entity.changeLookDirection and LivingEntity.tickMovement
  with the named cached jar's javap. The former casts mouse delta tofloat before
  multiplying0.15F; adjusted the shared forecast accordingly. The latter explicitly
  tests PLAYER horizontalLengthSquared<9e-6, while other entities use per-axis
  thresholds. Ported that player branch behind MC>=12111; older versions keep their
  axis behaviour. This is needed for faithful live feasibility, not a guessed margin.
  Added actual collision support to cancellation eligibility: onGround can remain
  true for one tick after moving beyond a lip, when the next jump must continue
  (Baritone MovementParkour:244-248). Rebuild required after these calibration edits;
  no live outcome yet. Prepared balanced-order six-pair gap A/B plus30 adjacent
  nav runs, and detached retained green/fatal input controls for the compiled checker.
- Build72715 and clean rebuild22285 both passed, but were superseded by review
  edits before any deploy/test. Callback completion now notifies a direct physics
  caller on refusal; input-free splices retain the last keys rather than the
  entry keys. Source frozen; final incremental38465 follows the clean build,
  logg108-live-landing-frozen-build.log. Only this final jar may be deployed.
- Final38465 build passed3m39s, nestedfreshness/byteidentity passed, javap confirms
  landingcheck, callback notification, synchronizedtick and9e-6 horizontal vector
  branch in the shipped nestedmodule. Task.class remains byte-identical to5516.
  Candidate22795721788d682aac1e3da06ec55d329f64ce5e973b3b9ec288b30708fe27c0.
  Required deployment23092 active, tester1only/GPU0; no runtime outcome yet.
- Deployment23092 passed. Rcon verifies tester1 on the flat server; Taskinactive,
  no executor/queue/search activity. Compiled retained-input fixture PASS: green
  trajectory accepted(-1), fatal missedlanding caught atindex24 before replay.
  Detached Agent now matches real recorded pos/vel at maxerrors2.79e-8/6.53e-8,
  including actual yaw, proving the vanilla deadzone port's relevant behaviour.
  Bench sensitivity read0.5. Started balanced six-pair A/B plus30adjacent nav on
  frozen227957, logg108-live-landing-ab.log; no navigation outcome yet.

- Live A/B remains active(session34210), candidate227957 unchanged. First five
  balanced pairs allvalidPASS at29.33-30FPS. Enabledrepeat2 observes4checks,
  1unsafe/1refused, then arrival15.1s withzero deaths/falls: the actuator and
  continuation fired in a real course, not only the detached fixture. Full clips
  throughpair3 andenabled4 reviewed2s; remaining reviews/results pending.
  No speed or mortality-rate delta claimed; horizontaldeadzone repair is present
  in BOTH arms, so this pair isolates only live refusal.
- Prepared private check_live_landing_craft.py for21 active-Task crafting/pickup/
  lava regressions and6 existing arrow-dodge safety runs. Agent.tick is shared
  with ProjectileDodge.plan, so the latter are relevant adjacent guards. Syntax
  andnine scenario IDs passed; NOTRUN. It requires completed42-case nav audit,
  green enabled/valid arms andclean teardown before acquiring its exclusive lock.
- Read-only cp1002-0229-t656 region scan findszero approximate dry shafts in
  NightShelterTask's dx/dz±6,dy±1 bounds. Nearest conservative full-cube site is
  (-83,64,1056),9.70blocks away andoutsideboth horizontal/vertical bounds.
  Source water61/62/dirt60 matches the retainedlive snapshot. Output
  artifacts/shelter-saved-geometry.json declares native collision/canBreak and
  path reachability UNVERIFIED. No checkpoint restore, player move or shelter
  source edit during the nav campaign. Next native probe must inspect the same
  saved geometry and existing safety predicates before choosing a search fix.

- Navigation34210 completed exit0:42/42 validPASS, cleanup errors=[] and lock released.
  Gap refusaloff6/6/on6/6; enabled2 refusedoneplan thenreplanned/arrived.
  Adjacent10courses each3/3, total30/30,29-30FPS,zero gated deaths/falls/freezes.
  All42 fullclips actuallyreviewed2s. Read-only analysis verifies alltraces
  continuous/error-free and count inequalities; no speed/mortalitydelta claimed.
  Started27active-Task/dodge audit17179 on unchanged227957,
  logg108-live-landing-craft.log; no result yet. SharedAgent caller audit confirms
  CombatExecutor renders predictionsonly, whileProjectileDodge actuallychooseskeys.

Completed and earlier entries: [archive/01-10-2026-completed-progress-history.md](archive/01-10-2026-completed-progress-history.md).
