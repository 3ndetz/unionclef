# Progress

## 2026-10-02 — shelter reachability and player snapshot (in progress)

### Investigate
- Full chronological landing/shelter/failure evidence is preserved in
  [archive/02-10-2026-shelter-snapshot-investigation.md](archive/02-10-2026-shelter-snapshot-investigation.md).
  Earlier full59 investigation: [archive/02-10-2026-full59-landing-investigation.md](archive/02-10-2026-full59-landing-investigation.md).
  Historical pending/live statements in those archives are superseded below.
- Release0.95.54 is published at73b78e19, assetSHA00e866. Its byte-identical
  candidate passed42 navigation,27 mixed and6 same-entry portal checks; edited
  English54s video delivered asTelegram9570, deployed landing audit4/4PASS.
- Native cp1002-0229-t656 proves nearby safe sites outside the old search bounds
  are reachable. Initial06652c candidate passed36 arena cases and one healthy
  saved-entry sealed10s hold, but isolated discovery with general Unstuck off
  retained an obsolete single destination after a new safe site appeared.
- Refreshedf8caef passed that matched discovery input. Its first campaign retained
  a Flee fixture zombie in the only air shaft, producing one drowning death.
  Clean isolation then stopped at finalAir199 despiteHP20/no deaths and visible
  refills; keep both original reds. Round3 Flee also raised a live-player worker
  NPE in isClimbing. These are separate findings, not a universal air repair.

### Plan
- Validate the frozen unpublished e03ed747 snapshot candidate with51 repeated
  shelter/exit/Flee/air/nav/craft checks, stop first red/invalid/runtime failure.
- Then six healthy saved-entry repeats, exact inventory/checkpoint preserved,
  and raw15min @gamer integration from cp1002-0229-t656 with dense checkpoints.
- Publish stable work only after final-payload validation, through scoped
  :1.21.11:githubRelease; verify real asset, edited English HyperFrames Telegram
  report, canonical tester1-only deployment and audit, then next focused pass.
- Original full59 lava-column stall/casting deaths, entity-obstructed air escape,
  stale executor-callback ownership and complete-game coverage remain open.
  Generic world/mining/global-budget snapshot debt C4.1/C4.3 remains separate.

### Implement
- Six Java sources remain uncommitted: immutable AnyBlock goals, region ownership,
  loaded3D shelter discovery, live safety revalidation, bounded destination refresh,
  and client-thread immutable player StartState before worker dispatch. Flood/
  hazard checks, three-block shaft and morning policy are unchanged.
- Exact final-candidate deployment/contracts/current campaign are recorded below.
  No controller/COMPLEX/SWARM changes; workflow-center tracked files untouched.

## 2026-10-02 — recurring wiki sync failure (fixed and verified)

### Investigate
- Owner asked why wiki sync repeatedly fails. Latest36975493590 and historical
  35376440063 both fail on duplicate Progress.md; first also reports git rm
  treating ----.md as an option. Upstream cmbrose/github-docs-to-wiki v0.20
  (63b291ccf8b66be4233493eae236329a8c43343e) reads the input as a string and
  tests its truthiness. Default "false" is nonempty, so header naming stays on
  despite commit76232701 removing the input. Progress archives share that header.

### Plan
- Use deterministic relative-path page names, preserving headings, Home mapping,
  local doc links/anchors and source links. Validate the export before changing
  the cloned wiki; separate Git options from paths and serialize publishing.

### Implement
- Commite4d5c0bd adds the repository-owned PowerShell exporter and regression
  checks; workflow no longer invokes the broken upstream action. Passed local
  duplicate-header, Home/archive/source links, code, leading-dash Git cleanup,
  idempotence and collision-rejection checks. All174 docs export successfully.
- Pushed atomic main+1.21.11; local version branch fast-forwarded after sole
  worktree/divergence checks. Actual push run36978862343 SUCCESS,174 pages,
  wiki master6f4c4d89b8d052c64b23e81d907cb5bf8d762c5d. Explicit second run
  36979076017 SUCCESS and "Wiki already matches docs." No token was printed.
- Owner's subsequent highlight question was read-only: source defaults for all
  visuals/mining/break/place are true. MixinDebugRenderer:135-152 gates only the
  master switch; mining events are globally subscribed by PlayerExtraController
  and include manual breaking. WorldEdit selection stays until clearSelection/
  //desel. No rendering/default changes made; disclosed exact disable commands.
  Reentry also confirms the green goal box is unconditional under renderPathMoves;
  disclosed that separate switch. Vanilla crosshair outline was not investigated.

## 2026-10-02 — final snapshot payload deployed, repeated validation in progress

### Implement
- Snapshot candidate SHA256 e03ed74770ce620e12809b76e2868ce9cb6b545d8bd17224dbebaeeae21910c5
  is unpublished. Final nested freshness/bytecode checks pass: only FastPlanner,
  FastNavigator and unchanged-disassembly nest members Heap/NodeMap differ from
  archived f8caef; StartState is added. Canonical tester1-only deployment12444
  exited0 and the actual loaded JAR hash matches. No controller changes.
- Native contract69437 exited0: gateway-worker and native client-thread captures
  both return immutable StartState, valid geometry and place count2 at30FPS,
  with all movement drivers inactive. Initial no-player assertion was a missing
  test precondition; retained separately in the no-player stderr artifact.
- Campaign17649 started with persistent shelter-start-snapshot-audit-console.log,
  planned51: six each new-site/blocked/flat/exit/Flee/air, three each navigation
  flat/stair/descent/water and mixed pickaxe craft. Every case checks fixture
  absence and its own client-log window for FastNavigator planning failures;
  stop on the first behavioral/runtime red or invalid FPS. No prior incomplete
  isolation reused. Native saved-entry repeats and full @gamer remain pending.
- First three exact-payload cases validPASS: new-site/blocked/flat at28.7-29.5FPS,
  runtime windows clean; all three full clips reviewed2s (both new-site pages).
  Prepared ignored six-run saved-entry driver, syntaxPASS/NOTRUN: same retained
  inventory/checkpoint, HP20 throughout, minFPS14, sealed10s, runtime/cleanup gates.
- First full eleven-course round is validPASS, runtime windows clean. All11
  complete recordings reviewed2s, including both air/new-site pages. First air
  HP20/deaths0/finalAir300,27.14FPS; navigation and protected mixed craft pass.
  The51-case series remains live; no completed repeated rate claimed. Fresh
  fetch confirms main/1.21.11 and both remotes0/0 atc0f74803, sole worktree,
  owner3ndetz credentials. Chronological426-line evidence archived exactly.
- Reentry confirms wiki run36983163446 SUCCESS on60c6a8fa. Exact-e03 campaign
  first37 validPASS, all37 complete recordings actually viewed at2s. Round4
  new-site reaches a sealed shaft but circles beside the started shaft before
  descending: timeline moves around the rim at25.5-43.2s, bottom at50.1s,
  HP20 throughout. Preserve this outcome and investigate alignment/approach
  separately; do not claim a speed improvement from behavioral gates alone.
- Actual Minecraft1.21.11 named bytecode retained in shelter-minecraft-entity-
  javap.txt and shelter-minecraft-living-entity-javap.txt: isClimbing uses
  getBlockStateAtPos, which conditionally fills stateAtPos then rereads it;
  baseTick and block-position changes invalidate that field to null. A client
  tick between the check/fill and final read can produce the retained worker
  NPE. This supports the snapshot fix; the exact interleaving was not captured.
  Correct the CalculationContext citation to97-108 before the final build.
- Exact-e03 campaign17649 completed51/51 validPASS, independent analyzer exit0:
  six each new-site/blocked/flat/exit/Flee/air, three each flat/stair/descent/water
  navigation and protected mixed pickaxe craft. All51 complete recordings
  actually viewed at2s, every sheet page; review ledger retained separately.
  Per-case planning-error windows clean, fixture counts zero, cleanup[]. Prior
  finalAir199 and entity-obstructed fatal remain retained, not retrospectively green.
- Saved-entry series31386 is live with persistent snapshot-saved-console.log:
  six exact-payload native shelter holds from cp1002-0229-t656, original inventory
  and checkpoint fingerprint. Stop first red/invalid; raw15min gamer remains pending.
  Prepared ignored existing-API rim trace wrapper, syntaxPASS/NOTRUN; no motion fix.
- Saved-entry31386 stopped after its first child, exit1/cleanup[]: sealed hold
  reached withHP20, but minFPS13 (mean22.19) fails the unchanged14FPS floor.
  Full recording reviewed2s. Native helper also enabled experimental smartMoves
  through setTungstenPathing(true), so it is not shipped-default coverage. Removed
  that toggle; before restore assert primary=true/readFlag smartMoves="false",
  retain settings in the result and enforce them in the six-run judge. SyntaxPASS.
  Prior healthy066 saved entry has the same experimental-setting limitation.
  Attempted parent-stop identity check found it already gone; no process was killed.
- Corrected shipped-default series93712 is live, directory
  shelter-saved-repeats-20261002-122513. First child122514 validPASS: primary=true/
  smartMoves=false, original inventory/checkpoint, HP20, zero deaths/lava entries,
  minFPS15/mean26.75, sealed10.05s hold, runtime window clean/cleanup[]. Complete
  recording viewed2s. Remaining five native repeats and raw gamer pending.
- Preserved the122,510,827-byte original gamer_run1.mp4 before integration in
  reports/footage/full59-before-shelter-snapshot-20261002.mp4; source/copy SHA256
  2019038de6c7f29e184aad61a085f869fa6f10cce0f25e48d0cc090574ca3f83 match.
- Corrected default series93712 stopped on child122909, exit1/cleanup[]:
  actual sealed hold10.17s and HP20, but three samples at4.22-4.80s read13FPS.
  Mean21.42; unchanged14FPS floor invalidates the run. Whole22.5s recording
  reviewed2s. Preserve first validPASS and the invalid second, not a six-run rate.
  Canonical tester1-only refresh68232 exited0 with the same exact e03 payload;
  fresh six-run series started separately. No controller/peer changes.
- Prepared rim trace now inherits the actual NewlyAvailable scenario and pins
  general idle-goal rescue off, matching the retained rim detour; syntax check
  follows, not yet run on the bench. Wiki push run36989256438 SUCCESS oned9019af.
- Fresh-client series14844 stopped at first child124318, exit1/cleanup[]:
  hold10s reached/HP20, but first recorded FPS2/4 and min2/mean13.76; not valid.
  Whole39s recording reviewed2s. Canonical refresh alone did not warm the client.
  Native helper now waits for six consecutive >=20FPS readings in the stopped
  prior world BEFORE restoring the water checkpoint (no healing/teleport/time
  change). The measured14FPS floor remains unchanged. Retain both invalid series.
- HyperFrames skills update is current/noop. Read-only pin probe105->111 followed
  by upgrade and browsercheck20416 exit0 on the existing landing composition:
  runtime/layout zero errors/warnings, contrast12/12; structural lint warnings
  remain in the old monolith. No new rendering/sending, no bench overlap. New
  six-scene outline recorded with source/count gates and local-font decisions;
  footage assembly waits for native/integration evidence. Warmed series44450
  started separately in shelter-saved-repeats-20261002-125023.
- Warmed series44450 stopped first child125024, exit1/cleanup[]: before restore
  six consecutive20-25FPS readings, but actual movement min9/mean22.42;
  sealed hold reached/HP20. Full recording reviewed2s, unchanged14FPS gate.
  Pre-restore warmup did not eliminate restore/movement contention; no six-run
  default rate claimed. Reprioritized raw15min @gamer integration on the same
  CP, with its existing per-run reference/median diagnostics, then return to the
  native measurement problem. No floor lowered, no source repair inferred.
  Persistent shelter-snapshot-raw-gamer-console.log; dense checkpoints and
  distinct shelter-refresh-gamer-end. Original full59 recording already preserved.
- Measurement correction before any further native repeats: the ignored helper
  incorrectly required every FPS sample >=14, whereas CHECKLIST4d and run_suite
  judge average FPS. Future runs require time-weighted average >=14 separately
  for approach/dig and sealed hold, positive samples, and >=75% of the best
  preceding series average for each phase. Retain minima diagnostically. All
  original directories/verdicts stay unchanged; no old run enters the new rate.
  Offline phase analysis only:122514 approach24.03/hold29.27;12290919.86/23.20;
  12431811.86/16.88 (cold approach still invalid);12502418.05/26.34. Both revised
  ignored helpers syntaxPASS; six fresh repeats remain NOTRUN.
- Raw integration88994 is still live: fresh gamer first spends its sleep budget
  wandering in water with a bed, then food pursuit moves it. At123s the main
  task selects NightShelter and holds at(-89.7,60,1076.3), HP20 through382s.
  After morning it resumes food pursuit and moves by432s, HP20. This is actual
  main-task routing, not forced @test shelter; final recording/runtime review
  and complete15min outcome remain pending.
- Sixth actual wiki push run36992331585 SUCCESS on3edaee5f. Source reentry
  confirms the renewed food chase is a separate vertical-routing input: pig
  around(-63.76,84,1099.71), body near(-92.6,61,1092.5), repeated one-step
  swim queue, swim-out failure and pillar center stalls. Existing vertical food
  TODO is marked closed; retain this new input and check the baseline before
  attributing it to the shelter/snapshot change or choosing a repair.
- Raw88994 terminalexit1: sampledHP20/zero measured deaths, medianFPS25 over36
  samples,21 distinct positions, no new rung/items gained; GAMER_SMOKE FAIL.
  Watch phase936s with periodic checkpoint pauses; preserved908s capture,
  124308497bytes/SHA4325192b28e4a6dff0e07593af830334b03ec11402e1f7c01eba5452f353804a
  in reports/footage/shelter-refresh-gamer-e03-20261002.mp4, copy hash verified.
  Complete capture viewed36s cadence and every page, plus2s entry90-145 and
  morning380-450 windows. Video shows digging/cap, held shelter, morning exit
  and subsequent water/bank pursuit loops. Polls are not continuous health:
  damage counter4.5 is nonzero; do not claim no damage over the whole replay.
  From actual gamer-start client line687: no planning errors/Exception lines.
  Earlier reconnect/voicechat errors are setup noise, not a clean-all-log claim.
- The stopped body drowned AFTER the measured end/checkpoint (client10:17:04);
  respawn returned an empty inventory. Retain this outside-run loss separately.
  Prior-world warm fixture: first fill failed because chunks were unloaded;
  teleport before retry allowed a fall. Retried fill succeeded,25 stone blocks
  atY99; body at(-89.5,100,1076.5),HP9.33/onGround/runnerinactive. No healing,
  and this altered prior world is discarded by original checkpoint restore;
  the measured checkpoint/fingerprint/inventory/HP20 assertions stay unchanged.
- Prospective average-gated six native repeats80877 are live in
  shelter-saved-repeats-20261002-132017. Raw recording review completed before
  launch; no browser/build/render/deploy overlaps. Runtime and sealed-hold gates
  unchanged; stop first red/invalid. Rim trace/final build/release/report pending.
