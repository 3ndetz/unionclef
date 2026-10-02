# Progress

## 2026-10-02 — medium-specific pillar input: native integration and adjacent audit

### Investigate
- End goal remains the full @gamer playthrough on tungsten. Original full59
  lava-column collection, generated terrain and C4 world/mining/global-budget
  thread safety remain open. No controller/COMPLEX/SWARM changes.
- Complete shelter release, wiki repair, food-bank diagnosis and isolated
  experiment history is retained in
  [archive/02-10-2026-shelter-release-and-food-bank-evidence.md](archive/02-10-2026-shelter-release-and-food-bank-evidence.md).
  Older history is in the shelter-snapshot and full59-landing archives.
- Published0.95.55 assetSHAfa9a57f4fb8f35090cd68a4343f957a53bac79eace6cac50ef8140b94278a393
  carries2212 classes byte-identical to the audited shelter candidate. Release
  report delivered asTelegram9619; do not resend. Shelter input is closed.
- Published055 raw food checkpoint controls are intermittent: one diagnostic
  with extra geometry observation acquires/cooks food; a tick-only5min replay
  stays grounded in source water holding JUMP+SNEAK and acquires none.
  Original checkpointcp1002-1301-t644 fingerprint:
  582e74bda330ac87f5e28c3ac91322307f78181a61a17818eaa1cd7bc533df64.
  Never use synthetic healing/inventory for this native comparison.
- Baritone Movement.java125-127 retains JUMP in liquid; MovementPillar187-200
  separates water ascent from land placement. Its land branch also initially
  sneaks. Published055 healthy isolated source water has100 grounded JUMP+SNEAK
  ticks with no rise, justifying a measured difference from that land input.
  Agent.java459,506 apply opposed water sneak/jump impulses.
- Experimental PillarTask uses JUMP without SNEAK while touching water, then
  restores the land pose. Existing real-pose, cell-clearance, crosshair,
  protection, centering and placement-rate gates are retained.
  pillarUsesSwimInputInWater remainsfalse by default pending full validation.

### Plan
- Finish the prospective72 dry interaction/clearance/refusal trials on2b112366:
  six balanced branch-clearance pairs, water=false constant, all six cases in
  each arm. Rebuilt fixtures, exact cells, frozen growth, pin readback, no retry.
  Review every actual recording and retain old-arm reds separately.
- Then run42 navigation gates (water construction, flat, stairs, descend,
  water, wall2, bridge) with both experimental flagsON. First unexpected
  red/invalid/runtime failure stops for diagnosis; do not rerun away a red.
- Native water comparison must use the same branch pin in both arms on the
  new candidate and restore originalcp582e/inventory/ground every time.
  Old092 pair1 is retained but health-invalid; oldpairs2-6 never ran. Do not
  silently pool different candidates. Independently observe input, actual
  food, survival, FPS and recordings with tick-only observation.
- Before a release default change, clean final tungsten/scoped packaging,
  prove actual payload, recheck human debug sessions/stale versions/*/bin.
  Never override/bless a stale nested jar.
- Publish only stable measured behavior through scoped :1.21.11:githubRelease,
  verify branch/tag/assets/payload, edited English HyperFrames report and
  Telegram delivery, then canonical deployment/audit and the next focused pass.
  Do not close broad natural food or complete-game coverage from arena ascent.
- Keep milestone scope independent: if the dry branch-clearance and adjacent
  gates validate, release that stable planning fix while the water-input flag
  remainsfalse/experimental. Native repeated water acquisition is the separate
  gate before changing the water default. Final candidate hashes/pins must be
  documented again after any default/version rebuild; pending2b native plans
  are not results on a later released binary.

### Implement
- Candidate build17162 exit0/7m43s; canonical tester1-only deploy90782 exit0.
  Frozen wholeSHA09232aab2ab8fecbed2c6632337b639fc871ea8d3a4664d03647f5900ad22feb.
  Recursive2212-class proof:only PillarTask/TungstenConfig changed; added[]/
  removed[]. Candidate is not the published055 asset despite its filename.
- Durable nav_water_pillar is registered/listed by the real runner. It requires
  actual server cobblestone, one spent block, grounded final height and survival
  over15s. Syntax/diff checks pass; actual runner campaign is still pending.
- Smoke29828 exit0/cleanup[]:wet-on builds/restsY-59, wet-off staysY-60/all64
  blocks; dry both build. Healthy29-30FPS/HP20. All5 actual films reviewed2s.
- Six balanced pairs per medium34523 exit0/cleanup[] in
  water-pillar-probe-20261002-160351:wet-on6/6 actual construction/restedY-59/
  63blocks, wet-off0/6 with100 JC ticks each; dry6/6 each. All30FPS/HP20.
  All25 actual13.4-13.7s films viewed2s/every page;50 actual UTC log lines,
  zero runtime matches. Synthetic mechanism, not a natural food success rate.
- Three-rung smoker31209 exit0/cleanup[] in
  water-pillar-probe-20261002-162126:wet-on3/3 builds all three cells/restsY-57/
  61blocks, wet-off0/3;dry3/3 each, all30FPS/HP20. All13 actual13.6-14.1s
  films viewed2s/every page;26 actual UTC log lines/zero runtime matches.
  GUI was closed only in final snapshots and sampled frames.
- Off-center low-roof table45127 exit0/cleanup[] in
  water-pillar-probe-20261002-163915:wet-on3/3 constructs/restsY-59/63blocks,
  wet-off0/3;dry3/3 each, measured30FPS/HP20. All22-23 separate GUI polls per
  measured case closed/noerrors. All13 actual13.1-15.4s films viewed2s/every
  page;25 actual UTC log lines/zero runtime matches. GUI coverage is sampled.
- Raw true-pin food10796 LIVE in food-bank-trace-20261002-164841. Original
  restored position/HP20/food20; start308items/cooked_mutton3. Parent and native
  observer verify exact pintrue. Partial observations:25sY68.2,50sY81.4,
  73sY89 withporkchop3/smoker cooking selected. No terminal verdict or rate yet.
  Trace begins after gamer activation; end-checkpoint work may extend its
  window. Exact fixed-body durations alone are not active-game stall scores.
- Isolated evidence committed63018b3e, atomically pushedmain/1.21.11 and local
  version branch fast-forwarded. Three remaining source/scenario edits are OWN.
  Wiki37015549344 actualSUCCESS on63018b3e, verified green16. Only two specific
  historical failing logs were reviewed; not the entire historical action log.
- First true-pin native10796 terminal:wrapper0/observer0/gamer1/cleanup[],
  original checkpoint fingerprint unchanged. Median24FPS/13 polls, sampledHP20,
  trace minHP19/zero deaths/lava ticks. Final cooked_mutton7/porkchop3/chicken3
  versus initial cooked_mutton3. General gamer verdict staysFAIL: no new rung.
  7174 ticks/8043 continuous sequence records/zero geometry snapshots or gaps;
  water-pillar36 ticks, grounded5, JUMP+SNEAK0, JUMP without SNEAK30. Actual UTC
  13:52:26-13:58:25 has2241 log lines/zero runtime matches. Actual308s capture
  SHAdfda1d565a04938a096223ec790208791b484c66610a5bbd82a5b8708c81ce67 reviewed
  at8s/all2 pages, first80s at2s/all2 pages,78-98s and260-308s at2s/all3 pages.
  Both dense holds show crafting/cooking; fixed308s capture does not cover the
  entire358.628s observer window. No visual claim about that missing tail.
  Matched false-pin native95373 is LIVE; no rate or broad food closure yet.
- Archive090ae2fb atomically pushedmain/1.21.11; real export177 unique pages.
  Wiki37016377063 actualSUCCESS on090ae2fb, verified green17. Checkpoint budget
  after this native run8858MB/15GB, disk free169GB; no evidence deleted.
- Matched false-pin95373 terminal:wrapper0/observer0/gamer1/cleanup[], original
  checkpoint unchanged; final cooked_mutton1 versus initial3, no newfood/rung,
  zero actual deaths/lava ticks. All6184 wetPillar ticks grounded JUMP+SNEAK;
  no JUMP-only ticks. Continuous7092 sequence records/7031 ticks/no geometry or
  gaps; actual UTC14:06:38-14:12:29 has566 log lines/zero runtime matches.
  Median19FPS/12 polls fails the prospective comparison floor20: retain this
  as-run failure, do not count pair1 as healthy or quote a natural success rate.
  PollsHP20 miss initial traceHP9.766668; the early injury predates the long
  pillar hold and requires attribution separate from water input.
  Actual308s filmSHA7d8da6c7f543b613c76a659fa3de843e3fcc85925310093e5ba8c0f121b94992
  viewed8s/all2 pages,0-52s/22-308s/260-308s at2s/all10 pages. Fixed body in
  source water is visible through the capture end; no food construction/kill/
  cooking during that hold. Missing43.449s observer tail has no visual claim.
  Host83% sample, only tester1 up; no unrelated container stopped. Check arena
  regressions/health before resuming the remaining prospective balanced pairs.
- Interaction88652 stopped at first unexpected red:3/4 executed gates pass,
 32 planned cases unexecuted, cleanup[]. Furnace/vine/ceiling29-30FPS; cave-tip1
 fails29FPS/HP20/no GUI. All4 actual8.9-23.5s films viewed2s/every page. The
 navigator chooses a partial detour and builds away from the requested column;
 its tested column has0 actual rungs. Do not call this a water-input regression.
- Same-prefix false-pin control64934 also stops at cave-tip1:3/4 pass29FPS,
 cleanup[]/zero runtime matches. All4 actual8.9-15.2s films viewed2s/every page.
 The second navigator reports arrival while a separate pillar still runs; the
 test's exact requested column remains unbuilt. Preserve both as-run reds.
- Read-only post-stop client-thread policy probe finds cave vines in the FEET
 cellY-60, although the fixture originally placed its tipY-59. All plants are
 breakable/outline-obstructing at2ticks; water=false, break/place allowed.
 This late snapshot proves growth occurred, not when it occurred relative to
 either plan. Inspect exact pre-plan cells and separate random growth from
 the controlled head/jump-eye clearance case before assigning the mechanism.
 Water input remains false-default experimental; no new release/report yet.
- Frozen false-prefix71994 also fails cave-tip1:3/4 pass,29-30FPS/HP20,
 cleanup[]/zero runtime matches. Pre-plan feetY-60 are air, tipY-59 age0;
 fixture_valid=true. Random ticks restored to3. All4 actual films viewed2s/
 every page. Plant growth does not sufficiently explain this retained red.
- The original completion sampler watched only FastNavigator. It now journals
 both navigator and PillarTask and waits until both finish, without relaxing
 the actual requested-column gate. Same frozen false-prefix70931 remains3/4:
 tip1 has0 requested rungs after both finish, body restsY-57 inZ979,28-30FPS,
 HP20/noGUI/runtime matches, cleanup[]. All4 actual9.2-17.8s films viewed2s/
 every page. SIGTERM is catchable in the helper; forced OS kill remains a gap.
- Loaded092 isolated generator39776 terminal0: fresh frozen air makes all3
 pillar children/complete plan; tip1 emits the first clearance(-59,-58), then
 refuses the next source at-59 solely on unchanged-world replaceability with
 branch support=true, body clearance=true and policies=true. Tip2 emits two
 steps, charges removal of-58 twice and refuses the third. These are direct
 loaded generator facts, not a natural acquisition rate. Earlier diagnostic
 invocations8706/27190 stopped on probe boxing/private-field errors; no motion
 verdict came from them. Their cleanup ran before the corrected probe.
- Experimental pillarUsesBranchClearance=false now carries explicit ancestral
 removals into later interaction-only pillar steps and skips duplicate removal
 cost. Solid-body clearance remains actual-world based. Virtual placement keeps
 policy/world-border/recent-failure checks through PlaceRules. Baritone source
 MovementPillar258-266 clears a non-replaceable source before placing.
 No stable behavior claim yet; fresh clean tungsten/scoped packaging38352 is
 LIVE. Water/default/branch flags remainfalse. Dry/native/adjacent audits and
 release/report remain pending; remaining native pairs2-6 are not executed.
- Build38352 terminal0: tungsten clean1m34s, fresh scoped build8m29s/all18
 tasks executed, no build cache. Candidate2b11236656542ecaf2eb46c527c45242e95a007494696a16d46c7a9588c49506
 frozen separately. Recursive2212 classes, added[]/removed[];8 differ from055,
 7 fromwater092. Heap/NodeMap/StartState normalized method bytecode matches092
 exactly (actual javap16697 terminal0); their hashes reflect debug line shifts.
 Nested freshness guard passes without override. Canonical tester1-only deploy
12549 is LIVE. No motion trial on this new candidate yet. Docs/harness83734a79
 atomically pushedmain/1.21.11; Wiki37023169592 actualSUCCESS, verified green19.
- Canonical deploy12549 terminal0/exact loaded2b112366. Frozen loaded generator
54286 terminal0:air/tip1/tip2 all complete and emit all3 children; tip1 removals
 are4 unique cells, cost52.236543;tip2 are3 unique cells, cost50.236543. Actual
 world source plants stay non-replaceable; the later child now uses ancestral
 clearance rather than rewriting the world. This is a generator probe, not rate.
- First new-candidate dry81928 stops at sixth case:5/6 gates pass (both plant
 heights actual3 requested rungs, HP20/noGUI); protected-vine is red despite
 intact plant and0 requested rungs. Final bodyX2701.5/Y-57/Z980.5 is a neighbour,
 not the exact goal2700,-57,980; navigator is still active in the final sample
 and later gives up. Existing helper calls ANY grounded body at target height
 arrived, ignoringX/Z. Preserve this as-run red and compare old branchfalse
 full six-case prefix before changing the criterion. Films not yet reviewed.
- Actual virtual-placement policy probe terminal0: after explicit clearance
 allows the non-replaceable source, but placement disabled, external deny hook,
 actual world border and recent failure all deny. Exact hook/config/refusal
 state restored; no driver or placement started. Seven predicate checks are
  specific guards, not an arena success-rate claim. No release/report yet.
- Same-prefix old-arm84841 terminal1/cleanup[]:4/6 gates pass; tip1 and
  protected-vine are red. Tip2 actually builds3 requested rungs through runtime
  clearing/replanning, so the old arm does not always fail both plant heights.
  Protected plant is intact,0 requested rungs, body2701.5,-57,980.5 again:
  the old height-only arrival predicate is the test defect. Both six-case
  pilot/control recordings were actually viewed at2s/every page (12 films),
  with review journals retained; runtime matches[]/HP20/noGUI in both arms.
- Corrected the helper arrival to grounded target height AND requested feet
  cell (floorX2700, floor(Y+.1251)==target, floorZ980), matching the navigator.
  Retained the old height observable and every original red JSON. Protected
  refusal additionally requires0 requested rungs; complete pre-plan plant
  stems/stone are checked. Syntax/diff checks pass; prospective bench validation
  is now LIVE5287, balanced6 OFF/ON pairs x6cases=72planned on exact2b112366,
  constant water=false, frozen random ticks, rebuilt geometry per case. Odd
  pairs OFF->ON, even ON->OFF. No automatic retry; only healthy normal old-arm
  tip reds may continue, retaining their actual verdicts. Other red/invalid
  stops the campaign. Planned trials are not completed outcomes.
- Last checkpoint budget8977MB/15GB, diskfree176GB. Native pairs2-6 and42nav
  remain unexecuted. Both source flags are false-default; no new release/TG.
- First corrected-helper protected control on5287 actually passes29FPS:
  height=true, exact cell=false, arrived=false, plant intact/0requested rungs.
  Earlier furnace/vine/ceiling positive/refusal gates pass and tip1 retains
  its actual red. This checks the correction on the bench, not the full72
  campaign or its recordings, which remain in progress/unreviewed.
- Campaign5287 remainsLIVE after the first3 complete pairs:36 executed,
  ON18/18, OFF15/18 with tip1 red3/3 and tip2 green3/3. All other gates
  pass, minimum sampledFPS28-30, runtime matches[]. These are partial as-run
  counts; all new films are still unreviewed. Remaining36 are prospective.
  Wiki37027617770 actualSUCCESS on3d627b8b (verified20); helper/docs committed
  and atomically pushedmain/1.21.11. Experimental Java/scenario stay OWN WIP.
- Campaign5287 TERMINAL0/cleanup[]:all72 executed. ON36/36, OFF30/36;
  tip1 OFF0/6 versus ON6/6 actual3 requested rungs. Tip2 is6/6 in both arms,
  but ON initial plans complete with3 unique removals (tip1 has4). Other
  positive/refusal gates all6/6 per arm. Minimum sampledFPS25-30, HP20/noGUI,
  runtime matches[]; terminal analysis verifies all clip hashes/fixtures/
  exact-cell outcomes/no duplicate ON removals. New films not yet reviewed.
- Extended actual policy probe now passes8 loaded predicate checks including
  configured place-deny zones; exact prior hook/config/list/refusal restored.
  First extended invocation190534 failed on diagnostic int[][] versus actual
  List<int[]> type mismatch; preservedstderr, finally restored state. Corrected
  diagnostic terminal0; not a production movement failure or a rate.
- All72 actual dry campaign recordings now reviewed at2s/every contact-sheet
  page, including all protected detours and terminal holds; individual journals
  and sheets.json mark actual review complete. OFFtip1 leaves the requested
  column unbuilt and builds beside the intact plant; ON clears and constructs
  the requested column. OFFtip2 runtime clearing still succeeds. Protected
  plants remain intact; neighbour construction is not arrival. No death/fire
  or opened interface seen at the sampled cadence; not every-frame coverage.
- Adjacent campaign32534 is LIVE in water-pillar-adjacent-20261002-191942:
  prospective42 gates on exact2b112366, both flagsON/readback, full15s water
  construction first, then six neighbouring navigation cases, six repeats.
  Stop first unexpected red/invalid/runtime. No build/render/extraction runs
  concurrently. Native2b cohort remains prospective/unexecuted; no release yet.
- Adjacent32534 has completed the firstfive full repeats35/35 plus sixth water
  construction, all valid/healthy without runtime matches. The sixth remaining
  navigation prefix is LIVE; no terminal42 or actual film-review claim yet.
  Source-water post-completion rest is not an unfinished climb. Pre-recording
  recovery after removing a preceding arena is separate from measured falls;
  inspect actual films before accepting that distinction. Jar remains exact2b.
- Fresh source review found a native coverage defect: record_start's300+8s cap
  ends before slow final polls/diagnostics/end save while the gamer is still
  active. Old092 recordings are308s versus~350s observer windows; their unseen
  tails remain explicitly unreviewed. Standalone cleanup did not finalize the
  recorder. Do not use those recordings as full-window visual evidence.
- OWN gamer_smoke.py WIP now starts an explicitly owned recording before task
  activation, confirms stop through a later client-thread FutureTask immediately
  after the observation loop, and finalizes driver/recorder from main'sfinally
  on failed/catchably terminated exits. Other short callers retain their finite
  duration. Syntax/diff checks ONLY: live ownership and native-boundary tests
  remain pending after the adjacent campaign. Forced OS termination is a gap.
- Ignored native observer now stops/drains at the timestamped end marker; its
  helper verifies required capture span against actual clip duration and trace
  start. Analyzer retains/audits every sequence, excluding post-marker drain
  ticks from measured metrics. These additions are syntax-only/unexecuted too.
- Prospective actual lifecycle fixture uses a live idle task and recorder for
  normal-return/catchableSystemExit cleanup plus finite short-caller expiry;
  it is not a gamer-progress test. Prepared post-build proof requires all2212
  tested2b classes identical except the validated branch-default bytecode, with
  waterfalse. Prepared36 neighbouring-course audit reads actual reset defaults
  without branch/water overrides. None of these planned gates has run yet.
- Report outline targets50s of actual events, silentEnglish/local frozen assets,
  six scenes inline. Re-opened prespecified pair1 tip1 sheets for trim selection:
  OFF17.2s/ON14.3s recordings, common action8.2..14.2 supports6s real-time split.
  Cached words-tier catalog/386items finds animated-bar-chart; installation,
  fresh project, final checks/render/report are pending. No extra GPU/container
  changes. Checkpoint budget8977MB/15GB, diskfree173GB; no evidence deleted.
- Adjacent32534 is TERMINAL0 with all42/42 executed/valid, runtime matches[]
  and cleanup errors[]. Extraction40387 terminal0 independently checks the
  exact2b payload, readback, clip hashes/durations and sampledHP20/no deaths.
  All42 recordings now actually inspected at2s/every contact-sheet page and
  individually journalled. Stairs/descent/water/ledge/bridge end supported;
  water construction replaces the source and holds the completed rung. Setup
  recovery text predates the measured courses; no death/fire/opened interface
  seen at sampled cadence. This is not every-frame coverage or native food
  closure. Both flags stay experimental false-default; no new release/report.
- Actual gamer lifecycle fixture77035 is LIVE: normal return, catchable
  SystemExit and finite short-caller expiry. No terminal outcome yet. Its
  idle driver tests ownership/cleanup; native capture boundaries remain a
  separate prospective original-checkpoint gamer test.
- Lifecycle77035 TERMINAL0/cleanup[]:all3 actual checks pass. Owned recorders
  are still live after9s without a cap; normal return and catchableSystemExit
  leave runner active=false and no recorder. Actual clips12.33/12.20s exceed
  the hold and both pages inspected at2s. The idle task is preemptible by
  Unstuck; this is an ownership check, not an idle-policy/progress result.
  Finite0s caller still expires at its8s cap. Forced OS kill remains uncovered.
- New native boundary diagnostic10310 is LIVE on exact2b, originalcp1002-1301
  fingerprint582e, raw resume/tick-only, constant branchtrue/waterfalse. Its
  five-minute observation is prospective; no progress or coverage verdict yet.
- Native10310 TERMINAL0 wrapper/observer0, gamer1FAIL, cleanup[]. Capture
  spans321.313s to confirmed active=false before final diagnostics/end save;
  actual video329.267s, all7 pages now inspected at2s (not every frame).
  Stop chat/chain disappearance visible near322.5s. Trace6431 sequences is
  continuous,6389 measured ticks and21 post-window ticks retained separately,
  runtime matches[] over523 UTC-window lines. MedianFPS23/12polls, initial
  sampledHP17.27 recovers20/deaths0/lava0; original kit308->307/mutton3->2.
  Gamer remains red:290.232s fixed-body hold in water,5500 wet JUMP+SNEAK
  ticks, zero wet JUMP-only, no new rung/food. This confirms capture ownership
  and the known false-water control, not a food fix or paired native rate.
- Removed experimental NavWaterPillar from ordinary SCENARIOS while the water
  input defaultsfalse; explicit ignored helper imports the same measured class
  and validates jar-name. Syntax/diff checks pass; ordinary six-course default
  audit remains prospective after the final branchtrue/waterfalse build.
- Operator's additional Windows desktop request completed separately in5ec7248d:
  all157 bench subprocess imports use the shared quiet adapter,55 ignored active
  helpers updated too. Actual Windows12pass/Linux11pass and Session1 gamer.sh
  Docker chain verify preserved output/exit7 with no new visible console window.
  Both canonical branches pushed/synced; detail in the quiet-process archive.
  Native benchmark had already terminated naturally; no Docker/test stopped.
- Gamer recording lifetime code now has actual idle ownership/normal-return/
  SystemExit/finite-caller and native boundary evidence above. Forced OS kill
  remains outside coverage. Scope remains capture ownership, not native progress.
- Branch clearance default nowtrue and mod_version056 prepared; water input
  remainsfalse. Final clean build/payload proof/default36/video/release still
  prospective, with no experimental water course in the ordinary suite.
- Final build12334 is TERMINAL0:clean tungsten and scoped056 build,10m7s/
  18 executed tasks. Frozen365e1fdb candidate has2212 recursive classes matching
  tested2b exceptTungstenConfig. Actual constructor defaults branchtrue/waterfalse
  verified with javap. Canonical captured deployment exits0; loaded SHA matches.
- First default audit72737 retained its nav_flatPASS30FPS but stopped on the
  voicechat missing headless audio-device error from initial server join. Its
  exact log and film remain; actual film reviewed at2s. Fresh44763 completes
  server join before measured course windows, with unchanged runtime gates.
  Currently14/36 pass, minimum average28.5FPS; LIVE/no terminal rate yet.
- Prepared50s English silent report reports/hf/pillar-clearance, CLI0.8.112:
  same rebuilt pair1 ground/one binary OFF-ON, real-time trims, precise rates,
  guards and limits. Six frozen clips/fonts/GSAP sources and hashes recorded.
  Final check0/runtime-layout0/contrast42 of42; reviewed montage track warning.
  Both comparison panels corrected after actual sheet inspection; all7 final
  snapshot cells viewed. Animation helper0/43tweens/13mapped, reviewed moving
  proof and deliberate reading holds. Wrapper Unicode-print failure retained,
  wrapper corrected toUTF8. Managed Studio3003 returnsHTTP200. Final render,
  full rendered-motion inspection/publication/Telegram stillpending, and render
  waits for the FPS-sensitive campaign to finish. No prospective36 rate in film.
- Original default44763 subsequently terminated after25 validPASS rows. The
  next repeat5 staircase retains OSError22Invalidargument instead of a full
  verdict; cleanup[]/originalPID absent, original exit/traceback unavailable.
  Actual14.9s film inspected at2s shows supported arrival, but that does not
  supply the missing criteria/log/default verdict. Never score it asPASS.
- Isolated Windows output-reader closure reproduces that exact errno atprint;
  an explicitly duplicated output file remains writable afterparent closes its
  handle. This supports the output-lifetime diagnosis, not a confirmed original
  instruction address. No Docker/game state was changed by the reproducer.
- Eleven explicitly missing trials are now a separate same365e/default/gate
  continuation, with durable file stdout/stderr and retained source25/error.
  Session82376/childPID129668/loglogged-job-20261002-220303 isLIVE. Final cohort
  must disclose two phases and the extra original instrument failure. All18
  first-three-repeat films actually inspected and journalled with hashes; final
  metadata and the remaining films are stillpending. Budget9095MB/free172GB.
- Durable continuation129668 finished naturally:11/11 validPASS, exit0 and
  cleanup[]. Terminal two-phase audit222254 independently verifies36 unique
  scored trials (6courses x6repeats) on the exact365e candidate, defaultbranch
  true/waterfalse, unchanged gates, minimum average28.5FPS, sampledHP20/deaths0,
  no measured runtime matches. Original extra staircase OSError22 remains
  unscored:37 attempts,36 full verdicts, not an uninterrupted36-run campaign.
  All remaining18 films actually inspected at2s/every page and journalled;
  sheets.json now records all36 complete. No death/fire/opened interface seen
  at this cadence; not every-frame coverage or native food closure. Release
  notes updated to actual evidence; publication/render/Telegram remain pending.
- Milestone delivered:scoped Gradle githubRelease143576 exits0,17tasks/one
  executed, publicv0.95.56. Actual downloadedassetSHA365e1fdb and all2212
  recursiveclasses equal the fully audited final candidate; tagtargetb72b2364
  matches syncedcanonicalbranches. Benchalreadyruns this exactSHA, so a fresh
  recreate solely for an unchanged archive would not add binary coverage.
- Final50.0s silent1920x1080/30FPS report rendered in90.8s, hardwareGPU/
  drawelementcapture/oneworker,32,978,112bytes, SHA426706745f339730657d13aecb549dd7038dd2c2b617ad8ec0c598a8a208cda2.
  Both whole-film2s pages and both dense sceneentrance pages actually inspected;
  hashes/journal retained. HAPI rejects the full file for size; separately
  encoded720p preview displayed. Full report delivered asTelegram9676; caption
  corrected in place to distinguish72 comparison trials from42/36 passed gates.
  No duplicate video sent. Waterdefaultfalse/nativefood/full59 remainopen.
- ASSESS:single-tip requestedcolumn score0/6->6/6, two-tip6/6unchanged. The
  route now reuses scheduled removals without duplicate price or policy bypass;
  coreplacement semantics advance realterrain construction, not a speed claim.
  Stablebranchclearance milestone closed; nextpass is a new balanced native
  waterOFF/ON comparison on exactpublished056, constantbranchtrue, original
  cp582e/rawinventory restored everyarm, tick-only observation and healthyFPS.
  Old092/2b diagnostics are not pooled. Budget9095MB/free171GB; no evidence
  deleted. END GOAL not reached, no stop requested; autonomous work continues.
- Native published056 pair1 is actuallyLIVE147180 (OFF->ON), originalcp582e,
  constantbranchtrue, tick-only and owned full-window capture. OFF observer is
  active; no terminal paired score or review claim yet. Only this first pair
  launched; remaining five are prospective and old092/2b stay separate.
- A real hidden noninteractive parent console exposed another bench contract:
  CREATE_NO_WINDOW loses console-buffer stdout/stderr despite successful writes.
  Inherited-pipe/file proofs do not cover that. Adapter now inherits an already
  attached console normally (no new window); detachedparents still add the flag.
  Real run/call/Popen streams/exit7/sameHWND and partial captured/console streams
  pass. Full15-case suite passes actualWindows14/one skip from both detached and
  attachedhidden parents; actualLinux12/three skips. Current native process
  continues with its already loaded policy; no test/container stopped for hiding.
  Archive retains exact evidence and original observation coverage limit.
