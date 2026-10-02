# Progress

## 2026-10-02 — shelter and player-input slice released; food-bank investigation continues

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
- Six Java sources implement immutable AnyBlock goals, region ownership,
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
- Prospective series80877 terminalexit0: six validPASS from the original CP,
  exact e03/default primary=true/smartMoves=false, inventory/fingerprint gates,
  sampledHP20, zero deaths/lava, sealed10.01-10.37s holds and clean runtime/
  cleanup. Approach time9.58-13.25s; separate phase means22.48-28.21FPS approach,
  26.01-28.75FPS hold. Complete six22.2/22.9/25.9/23.3/21.5/25.3s recordings
  actually reviewed2s/every page; video-review.json retained in132017 series.
  Prior strict-minimum invalids remain unchanged and outside this denominator.
- Owner docs commite600ff0c pushedatomic main/1.21.11; all four heads identical.
  Seventh actual wiki push36995296019 SUCCESS. Isolated HyperFrames scaffold
  reports/hf/shelter created with pinned0.8.111/initexit0; generated instructions
  read, no custom HTML/assets/render yet. Preserve the delivered landing project.
- Rim trace3067 is live with actual NewlyAvailable fixture/Unstuckoff and exact
  e03, existing continuous movement API. Prepared published00e866 native wrapper
  syntaxPASS/NOTRUN; only missing old scan counters become diagnostic nulls.
  Same CP/defaults/command90s; no-site timeout must stay original nonzero outcome.
  Matched baseline, final build/scoped release/report and subsequent food pass pending.
- Rim3067 terminalexit0, PASS/29.5FPS, sampledHP20/zero deaths,872 trace rows/
  errors[]/gaps[], planning window clean/cleanup[]. Entire54.1s clip reviewed2s
  on both pages. Trace uses its first tick as zero, not recorder zero: region
  refresh13.55s, repeated short routes/jumps by the goal until23.33s first dig,
  bottom25.28s/cap25.58s. FastNavigator already gates arrival on settledBody;
  do not diagnose the jumping as a missing settled-arrival guard from memory.
  Retain approach settling under the existing G65 precise-entry family.
- Frozen e03 JAR copied/hashverified to shelter-candidate-e03ed747.jar before
  replacing the local build artifact with verified published00e866. Canonical
  tester1-only baseline deploy86860 LIVE, deliberate UCTEST_ALLOW_STALE=1,
  GPU0; nested freshness guard reports the intended old payload explicitly.
  No source rollback, manual client-mod copy or controller change.
- Baseline deploy86860 terminalexit0; saved-entry replay55695 is live and
  restores the original CP. Tagv0.95.55 currentlyabsent (GitHub ref404); recheck
  before publication. Report headline catalog searched386items: top3D reveal,
  horizontal line slide and camera pull-back; spring-pop result overshoots.
  None matches the planned quiet vertical cascade/no-overshoot rules exactly;
  use their already-read recipe bodies, not a new effect. No report HTML yet.
- Matched published00e866 native55695 endedexit1 in child135040: no shelter
  top selected in90s/260no-site samples, despitefive nearby cells passing the
  unchanged safety predicate. Same originalCP/defaults/inventory; weighted
  mean27.84FPS, HP20/deaths0/lava0/planning0/cleanup[]. Full92.1s clip reviewed
  at2s on both pages; baseline-analysis.json retains this one old control.
- Published00e866 food replay65325 endedexit1 fromcp1002-1301-t644/raw5min:
  13responsive/13busy polls,13positions, items308 unchanged/cookedmutton3,
  no rung, sampledHP20/deaths0, median27FPS. Same swim-out/pillar pursuit loop
  therefore exists before this shelter candidate; no population/regression-rate
  claim from one run. Preserved308s capture SHA6a66216e9dbb64e8f9ae45299c7dda6605ab9d111257af204a37b65bee116dd3
  in reports/footage/shelter-food-published-baseline-20261002.mp4; full capture
  actually viewed12s on both pages. Densecp1002-1401-t313 and distinct end saved.
- Eighth actual wiki push36997941027 SUCCESS on3e0ba0ed. Six-scene60s report
  authored in the isolated pinned111 root; five verified captures adopted and
  local GSAP/font dependencies frozen with hashes/licenses. Firstcheckexit0
  (runtime/layout clean, contrast29/29) with12 initial-state lint warnings;
  corrected title/context initialopacity in CSS and secondcheck haszero lint
  warnings. All six midpoint scenes and the old10-66s refresh cut actually
  viewed. Animation-map first attempt lacks helper packages; temporary pinned
  dependency bootstrap running. No render/TG yet. Corrected CalculationContext
  citation to97-108; final055 build91543 LIVE, no debug/hotswap detected and
  no versions/*/bin present. Exactcandidate/class proof and publication pending.
- Final055 build91543 terminalexit0/BUILD SUCCESSFUL in4m31s. Recursive payload
  proof:2212 candidate/release classes, no added/removed/changed classes,
  byte-identical to audited e03; finalJAR6374438bytes/SHAfa9a57f4fb8f35090cd68a4343f957a53bac79eace6cac50ef8140b94278a393,
  metadata055. Pinned animation-map bootstrap58072 exit0;20/36 mapped tweens,
  fast entries match the read waterfall recipe; held text while actual footage
  runs explains the map's dead zones. One empty closing badge diagnosed and
  replaced with a visible Next pass label. Preview3002 HTTP200 confirmed.
  Publication/scoped final rebuild, post-deploy audit and reportdelivery pending.
- Published0.95.55 with scoped githubRelease88087 exit0; tagb247dd54 and
  correct1.21.11 asset605450387 verified. Downloaded JAR6374438B/SHAfa9a57
  matches all2212 audited e03 classes. The canonical deployment initially
  stopped before mutation on a nested-JAR mtime guard after the citation-only
  source edit. Forced fresh packaging60072 exit0/11m25s/18 tasks executed;
  whole JAR remains byte-identical to the actual published asset. Guard retained.
- Final report60782 check exit0/zero lint/runtime/layout issues/contrast29/29.
  Render54187 exit0:60s H2641080p30, deliberately silent,45268423B/SHA451e5c.
  Compact export71960 exit0:7957285B/SHA28557e, identical duration/frame rate.
  Complete master and compact review sheets viewed at2s on every page; source
  cap additionally verified at33.5s. HAPI compact display succeeded; master
  exceeded upload limit. Telegram actually acknowledged message9619; receipt
  retained. Post-publication tester1 deployment/audit remains pending.
- Fresh canonical tester1 deployment57438 exit0: nested module freshness and
  byte identity passed, actually loaded SHAfa9a57 matches the published asset.
  Final motion map38538 exit0,20/36 mapped/16 micro-tweens/no degenerate targets;
  eight fast entries follow the selected recipes, text holds accompany footage.
  History end exit0 reports the previous transaction already closed. Ninth
  actual wiki sync37000315997 SUCCESS onb247dd54. Published audit66754 is active;
  first three checks passed at29.5-30FPS/HP20/clean runtime. Next food-bank
  observer prepared/syntax checked only, not executed during the shelter audit.
- Published audit66754 exit0,5/5 valid PASS,29.5-30FPS, no planner failure,
  owned fixture count0 before each case and cleanup errors[]. Native40181
  exit0 from originalcp1002-0229-t656/SHA041ec8dd with original inventory and
  default pathing: actual sealed10.35s hold at(-84,66,1059), sampledHP20,
  zero deaths/lava entries, approach11.20s/FPS27.36 and holdFPS29.04.
  The stopped dry arena was warmed before restoring the original checkpoint;
  no healing, inventory replacement or underwater warm-up of the measured entry.
  All5 arena clips plus23.4s native recording actually reviewed at2s cadence,
  every page. The specific no-site shelter TODO is closed; rim movement and
  wider C4 debt remain open. Tenth wiki sync37003048571 SUCCESS ona5c48393.
- ASSESS: matched previous-release control waits90s without a site; repeated
  candidate6/6 and published saved-entry audit hold a sealed dry shelter. This
  advances one night-survival step toward the full game, not the whole game.
  Loaded geometry, reusable exact destinations and immutable player input are
  core changes; no server-specific policy or hazard relaxation. Food acquisition
  remains red on both published versions. Next pass observes the retained
  food-bank input before selecting one core repair, rather than changing flags.
- Native food-observer preflight initially failed on missing inventory slots:
  an extra client FutureTask wrapped APIs that already dispatch internally,
  making their nested supplier time out. No gamer run was launched. Corrected
  observer calls those existing safe APIs directly, dispatches only raw getters;
  corrected preflight exit0/inGame true/567 blocks/20 entities/43 slots.
  Retain this instrument failure separately from any bot failure. The observer
  changes no movement decisions; its geometry snapshots are separate from ticks.
- Food observation95260 exited0 in food-bank-trace-20261002-145821, using the
  original cp1002-1301-t644 fingerprint582e74bd and exact published055 asset.
  Parent and observer both exited0; cleanup errors[], checkpoint unchanged.
  Full sequence1-8149 contains7273 client ticks and51 separately timed geometry
  snapshots, with no gaps/errors. Actual trace minHP19 (polls/endHP20), no lava
  ticks/deaths, damage counter1.0; measured client-log window12:02:33-12:08:37 UTC
  has no exceptions/planning failures. Median22FPS over13 parent samples.
- This replay is a successful counterexample, not a food repair: no movement
  code changed. After repeated pillar attempts at(-94.5,62,1099.7), a swim
  queue at trace48.71s moves around the bank to(-95.68,63,1102.48) at54.99s;
  pillar reachesY66 by58.12s, then ordinary ascends reachY75 by63.83s. The
  chain starts cooking at73.78s; final inventory has5 cooked chicken,5 cooked
  mutton and3 cooked porkchop, compared with3 cooked mutton initially. The
  parent reports furnace@98.4s and PASS; it does not establish140 food units.
- Actual308s clip SHA8c0e83904a3c6914bd48b2d1efef2638dde38ced92b1d2f327e21673d8c0db21
  reviewed over its full length at8s cadence and first80s at2s, all four sheets
  viewed. Trace window363.47s includes parent/save overhead beyond recording.
  Preserve food-bank-trace-end63MB; checkpoint budget8593MB/free170GB. Previous
  candidate15min and published054 five-minute stalls remain valid retained reds.
  Different FPS and additional client-thread sampling prevent a speed comparison;
  instrumentation may alter timing. No target/entity identity or fluid block-state
  capture proves why this particular replay escapes. Food TODO stays open.
- Next focused experiment15438 is live: same original checkpoint, published055,
  shipped defaults, raw5min, recording/dense saves; only existing tick trace is
  observed, without the extra geometry/state API sampling. Its end checkpoint
  has a unique run name. No build/render or other bench runs during measurement.
- Corrected the runtime-window reader's timezone: Minecraft logs use UTC,
  while the initial helper used host Moscow time and matched an empty window.
  Re-read actual12:02:33-12:08:37 UTC lines; no runtime matches. Retain this
  instrument correction explicitly rather than trusting the earlier empty scan.
- Tick-only replay15438 finished: diagnostic wrapper exit0, gamer exit1,
  observer exit0, cleanup[], unchanged original checkpoint fingerprint582e74bd.
  Retained food-bank-trace-20261002-151535 contains7142 continuous sequence
  rows/7122 ticks/zero geometry snapshots/no gaps or errors. Measured runtime
  window12:19:44-12:25:40 UTC has1122 actual log lines and no exceptions or
  planner failures; minHP20/no lava ticks/zero deaths. Median22FPS/12 polls,
  no new food/rung,12 responsive/busy polls but only5 distinct positions. Bot
  remains at(-94.5,62,1099.4) after155s. Parent FAIL is a retained food failure,
  not an observer failure. Unique end checkpoint preserves that stopped entry.
- Actual308s negative clip SHA643ebe952ee7d999e5320d156e748e13d0f1530a5c23062862573a2ae5087144
  fully viewed at8s cadence, both sheets; long stationary bank hold is visible.
  Separately captured216 immutable block states AFTER stop, before switching
  worlds; cells-after-stop.json is not simultaneous with the movement trace.
  Published055 now has one positive and one negative diagnostic replay, with
  different observer sampling; do not pool these into a claimed success rate.
- Re-read the complete PillarTask and upstream MovementPillar cost/update paths.
  The successful trace still records four pillar failures: air0/placeAt0/
  jumpStolen0, onGround true. PillarTask holds sneak throughout; Agent water
  physics subtracts0.04 for sneak and adds0.04 for submerged jump. This suggests
  an input/medium mismatch, not failed placement rays. It remains a hypothesis
  until isolated from navigation and measured. MovementQueue already dispatches
  water before land/pillar classes; FastNavigator's separate build handoff must
  be inspected with that fact, rather than duplicating an existing water rule.
- Initial synthetic diagnostic27078 exited1 before any cases: the harness
  rejected obsolete gamerule doMobSpawning on1.21.11. Cleanup errors[], no
  bot outcome measured. Changed only the ignored probe to the existing arena
  helper's spawn_monsters spelling and retained the first instrument failure.
  Next compare existing PillarTask with queue execution on rebuilt dry/wet
  one-cell basins, recording each12s window. Synthetic mechanism isolation
  is not an original-checkpoint test or a food fix.
- Completed probe31796 on the published055 asset, four12s dry/wet task/queue
  cases. All ran at10FPS because the probe omitted the stand's idle-throttle
  pin; dry placement happened, but these are not comparable healthy runs.
  Its first dry queue cancelled on stale pre-teleport lastTickFeet, so that
  case says nothing about dry pillar execution. Cleanup errors[].
- Corrected diagnostic12273 in water-pillar-probe-20261002-153429 exited0:
  botFpsNoIdleThrottle pinned/restored, first queue explicitly excluded as
  world-transition warm-up. Four measured cases ran at30FPS,249-250 ticks,
  HP20; cleanup errors[]. Dry task and dry queue both placed cobblestone,
  spent one block and rested atY-59. Wet task stayed exactlyY-60 throughout,
  holding JUMP+SNEAK for all100 active pillar ticks; no cell placement.
  Wet queue uses existing MovementSwim, reachesY-58.879 transiently, then
  returns toY-60 with all64 blocks and source water still present. This is
  ascent without construction, not completed standing arrival. All five
  actual13.3-13.5s recordings, including warm-up, viewed at2s on every page.
- Native tick-only failed replay has2895 consecutive ticks at the exact
  fixed body(-94.49930889108592,62,1099.4219606931506), all touching water
  and grounded:2704 pillar ticks with JUMP+SNEAK,191 idle ticks. Separate
  post-stop immutable cells show dirtY60/61, source water[level=0]Y62,
  airY63-65 in column(-95,1099); not simultaneous tick geometry. This
  supports testing medium-specific pillar input before changing the planner.
  Upstream Movement.update125-127 holds submerged JUMP; MovementPillar
  187-200 separates swimming from its land SNEAK/actual-pose placement path.
  No production change or food fix has yet been validated.

## 2026-10-02 — medium-specific pillar input experiment (not yet validated)

### Investigate
- The healthy isolated source-water task repeats the native fixed-body
  JUMP+SNEAK hold. Dry construction and existing swimming are separately live.
  Upstream swimming holds JUMP; its normal land placement branch initially
  sneaks. The measured grounded source-water hold justifies separating ascent
  input by the actual body's water contact while retaining placement checks.

### Plan
- Compare false/true on one deployed build with rebuilt dry/wet fixtures,
  balanced repeated order and read-back pins. Require actual blocks and rested
  height, then multi-rung/interactive-support/roof/vine/bridge regressions.
- Repeat the original food checkpoint with unchanged inventory and ground;
  no food-coverage closure from a synthetic ascent. Audit and release only
  stable measured behavior, with the required edited English video report.

### Implement
- Added experimental false-default pillarUsesSwimInputInWater: PillarTask
  holds JUMP without SNEAK while touching water, then restores land input.
  Actual-pose, clearance, crosshair, protection and placement-rate gates remain.
- Added nav_water_pillar, a full15s source-water construction test checking
  actual server block, one spent block, grounded final height and survival.
  Python syntax and git diff checks pass; no behavioral verdict yet.
- Build17162 exit0/7m43s, scoped1.21.11 build,9 actual tasks/9 up-to-date.
  Recursive payload proof:2212 classes, only PillarTask/TungstenConfig changed,
  added[]/removed[]. Nested freshness/byte identity guard passes. Frozen
  experimental09232aab2ab8fecbed2c6632337b639fc871ea8d3a4664d03647f5900ad22feb
  retained separately from the published055 asset. Canonical tester1-only
  deployment90782 is live; no measurement runs during deployment.
- Fourteenth actual wiki sync37006969095 SUCCESS onaef8c9bd.
- Canonical deployment90782 finished exit0, actual loaded09232aab verified.
  Smoke29828 exited0 in water-pillar-probe-20261002-155713, cleanup[]. Both
  settings read back exactly. Wet-off holds100 pillar ticks JUMP+SNEAK at
  Y-60, no placement/all64 blocks; wet-on has13 active pillar ticks/10 wet
  ticks, no JUMP+SNEAK, places cobblestone and restsY-59 with63 blocks.
  Both dry arms also build and restY-59. All measured cases29-30FPS/HP20;
  excluded queue warm-up29.5FPS. Actual five13.7-15.7s recordings fully
  viewed at2s/every page. One pair supports the mechanism, not a success rate.
- Corrected the source comment's Agent citation to459,506 and qualified land
  sneak-pose wording after this build; functional classes are unchanged, but
  final packaging must satisfy freshness again. Repeated balanced diagnostic
  34523 is live: six pairs in each medium, rebuilt fixtures/read-back flags,
  stop first unexpected outcome or unhealthy FPS, cleanup in finally.
  No build or rendering during its measured windows. Food remains open.
- Balanced six-pair diagnostic34523 finished exit0/cleanup[] in
  water-pillar-probe-20261002-160351:24 measured cases plus excluded warm-up.
  All measured medians30FPS/minHP20; exact pins and rebuilt source cells.
  Wet-off0/6 constructed (100 active pillar JC ticks every time, Y-60/all64
  blocks), wet-on6/6 constructed/restedY-59/63 blocks (13 pillar ticks,
  9-10 touching-water ticks, zero JC). Dry off6/6/on6/6 constructed/rested.
  Both wet orders agree. All25 actual13.4-13.7s films fully viewed at2s,
  every page; review.json records actual reviewed hashes. Fifty actual client
  log lines across the25 UTC windows, zero runtime failure matches.
  This establishes the isolated source-water construction mechanism only;
  natural food acquisition and broader adjacent coverage remain unvalidated.
- Fifteenth actual wiki sync37010991593 SUCCESS onb07ed289. The durable new
  nav_water_pillar is registered and listed by the real runner. Next experiment
  rebuilds a three-rung column above a smoker, comparing both settings in
  each medium; require all three actual cells, spent materials and no GUI.
- Three-rung smoker diagnostic31209 finished exit0/cleanup[] in
  water-pillar-probe-20261002-162126:12 measured cases plus excluded warm-up,
  all30FPS/minHP20. Wet-on3/3 filled all three cobblestone cells, spent three
  blocks and restedY-57; wet-off0/3 stayedY-60 with64 blocks. Both dry arms
  constructed3/3. All13 actual13.6-14.1s films viewed at2s/every page;
  actual UTC runtime windows contain26 lines, zero failure matches.
  GUI was closed in final snapshots and sampled frames; these observations
  do not establish absence of brief intermediate opening. Next balanced
  off-center/low-roof crafting-table experiment adds separate half-second
  GUI polls to both arms, with timeout/error treated as instrument failure.
- Off-center low-roof crafting-table45127 finished exit0/cleanup[] in
  water-pillar-probe-20261002-163915:12 measured cases30FPS/minHP20 plus warm-up.
  Wet-on3/3 constructed/restedY-59/63blocks, wet-off0/3 stayed below; dry3/3
  in each arm. All22-23 separate GUI observations per measured case were closed,
  no API errors. All13 actual13.1-15.4s films viewed at2s/every page;25 actual
  UTC log lines/zero runtime failure matches. GUI coverage remains sampled.
- First true-pin candidate raw food replay10796 is live, original checkpoint
  cp1002-1301-t644/fingerprint582e74bda330ac87f5e28c3ac91322307f78181a61a17818eaa1cd7bc533df64,
  tick-only observation/original inventory/unique end save. Full36 interaction
  and42 navigation campaigns are prepared, not yet executed. Default remainsfalse.
