# Progress

## 2026-10-03 — reject gamer progress after survival failure

### Investigate
- End goal remains the full @gamer playthrough on tungsten. Original full59
  lava-column collection, native food140, generated terrain, combat and C4
  world/mining/global-budget thread safety remain open. No controlling
  container/COMPLEX/SWARM or human desktop changes.
- Completed published056 construction/default/video and all six prespecified
  native water pairs are archived in
  [archive/03-10-2026-published056-validation.md](archive/03-10-2026-published056-validation.md).
  Exact native sources and coverage are in
  [archive/02-10-2026-published-water-pair.md](archive/02-10-2026-published-water-pair.md).
  Public0.95.56 binary365e and original food checkpoint582e remain unchanged.
  Water default=false; branch clearance=true.
- Independent cohort014130 verifies all94 actually inspected fixed2s pages.
  Six attempted pairs/twelve arms: five FPS-valid pairs, FOUR successful
  pair terminals. FPS-invalid1 and safety-failed6 retained; campaign safetyRED.
  FPS-valid new endfood observations ON5/5 versusOFF1/5 are NOT safety passes.
  Unequal activation injury prevents survival attribution; food140 remainsopen.
- Pair6 OFF011621 dies to a Drowned trident, loses its pack, then climbs crafting/
  wood tools after respawn and stock gamer reportsPASS. Outer helpera3fe7cee
  correctly rejects it. The common verdict uses reached[] without deaths;
  rung checkpoint machinery already skips postdeath saves.
- Windows console addition is complete/pushed5ec7248d/7a3d64a1/b93fb76d.
  Actual Windows14PASS/1SKIP from detached and hiddenattached parents, Linux
  12PASS/3SKIP; inherited console/pipe/file streams and interaction retained.
  Archive/02-10-2026-quiet-windows-processes.md keeps exact evidence/limits.
- Read-only Drowned follow-up: projectile tracker includes persistent projectiles,
  actual1.21.11 hasTrident reads both hands, MobDefense applies extra danger cost.
  Ordinary ranged/poisonous nuisance classification omits Drowned, but this
  alone is NOT a causal diagnosis of the fatal underwater approach. Baritone
  Avoidance.java provides ordinary mob avoidance, not a matching combat policy.
  No Java repair or survival benefit is inferred from this one retained death.

### Plan
- Reject a run when either the server-log death list or existing independent
  death watch says it died. Do not invalidate a measured death as lowFPS;
  retain ordinary no-death rung/poll/FPS thresholds and cleanup semantics.
- Validate actual production verdict boundaries against the retained falsePASS.
- Run recorded real same-checkpoint fixtures: lethal generic damage (not /kill)
  followed by a new crafting table mustFAIL; no-death new table mustPASS.
  Injected fixtures verify the harness, never natural food or gameplay rates.
  No retries or lowered20FPS fixture floor; every red stays retained.
- Actually inspect all sampled film pages, audit cleanup and original checkpoint
  fingerprints, then commit/push the tested fix and continue original blockers.

### Implement
- gamer_smoke.py now requires no death from either existing source. Death
  prints an explicit survival-failure reason and remainsFAIL even at lowFPS.
  Rung/poll thresholds and ordinary no-death FPS gate are unchanged.
- Actual production verdict AST audit passes13 boundaries, including retained
  trident respawn, each death source independently, lowFPS deathFAIL, original
  lowFPS progressPASS/no-progressINVALID, deeper required rung and poll gates.
  Evidence: artifacts/gamer-verdict-boundaries-proof.json. This block-level
  audit does not replace real whole-run tests.
- Real fixture101436/wrapper123404 isLIVE, logged-job-20261003-014649,
  gamer-death-verdict-20261003-014649. Helper6527a1c7, loaded gamer source315a5af6,
  public365e/cp582e,1.5min recorded arms. Original308item inventory confirmed.
  No terminal result/film inspection claim yet; await actual natural boundary.
- Documentation/cohort sources committed15134f91 and pushed to both canonical
  branches, local1.21.11 fast-forwarded. Latest wiki check still pending.
  Last checkpoint budget10570MB of15GB/free167GB.
- Fixture101436 naturallyTERMINAL0 at22:58:36UTC/log014649. Real generic damage
  confirms ucDeaths9->10, then crafting@48.6s; deaths1/FPS27/4 responsive+busy
  polls, gamerFAIL/exit1. Adjacent same-entry no-death crafting@47.2s/FPS28/
  4 responsive+busy polls, gamerPASS/exit0. Original308item entry in both;
  cleanup[] and originalcp582e fingerprint unchanged. Injected table/damage
  verify only harness semantics, never natural food/crafting rates.
- Captures106.066667/103.266667s cover confirmed100.116/97.282s windows,
  SHA317dfb0b2bf388aab0ccc01313d814315ee1b2d479ebe85b3b233469fd050a2c
  and a3a0390101f27abdb972b2780d6276a2905565f38787ca75eed436e7eee21fc3.
  Review157100 naturally0 at22:59:14UTC/log015901; all6 fixed2s pages actually
  inspected/hash-journalled. Death arm transitions to bare-handed tree respawn
  between28.5-30.5s, then table/wood/craft/dig; no sampled death-screen claim.
  Zero-death arm retains pack and submerged bank hold; no natural food closure.
  Final chain disappearance inspected in both. Not moving/everyframe coverage.
- ASSESS:retained natural falsePASS becomesFAIL under the actual production
  verdict; real injected death/new-rung FAIL and zero-death new-rung PASS prove
  whole-run dispatch without relaxing gates. This advances trustworthy full-game
  measurement, not combat or food completion. The common verdict consumes the
  existing observations; no reactive gameplay script or Java change.
- No mod release/video for this invisible Python harness correction; public056
  and its delivered report9676 remain unchanged. Water default remainsfalse.
  Native cohort safetyRED and original falsePASS source remain retained.
  Next focus returns to medium-specific water input/default validation and
  original playthrough blockers. Latest a1ae58f1 Wiki37074785036 actuallySUCCESS.

STOP CONDITION CHECK:
- Is the work actually finished?        -> no
- Is the END GOAL reached?              -> no  (@gamer plays the whole game on
                                           tungsten, baritone deleted)
- Did the customer say to stop?         -> no
VERDICT: if ANY answer is "no", the stop condition has FAILED — work continues
         immediately and the next iteration starts at once.

## 2026-10-03 — validate medium-specific water input as the packaged default

### Investigate
- Isolated wet construction and dry policy/placement checks already passed
  with this exact implementation. All five FPS-valid published056 ON native
  observations gain food and avoid contradictory wet JUMP+SNEAK; ordinary OFF
  gains food once. Native pair1 FPS-invalid and pair6 OFF trident death remain
  retained; campaign safetyRED, no survival attribution or food140 closure.
- Assessment supports the input mechanism only: use liquid ascent without
  opposed sinking input, retain real sneak-pose/body/ray/policy placement gates
  and original land behavior. This does not repair Drowned combat or later
  smoker routes. Existing Baritone source references are at the implementation.
- Host Java clients are ordinary TLauncher processes with no JDWP or unionclef
  source reference; no live project debugging found. Own native fixtures ended
  naturally with cleanup[] before any Gradle. Version0.95.57 is currently free.

### Plan
- Clean tungsten and scoped1.21.11 packaging offline, no cache/rerun tasks,
  no stale versions/*/bin. Verify final recursive class payload against public056:
  only TungstenConfig may differ, actual constructor defaults bothtrue.
- Deploy only tester1 via canonical script, then six repeated actual-default
  water-construction/neighbor gates, with unchanged healthy20FPS floor, actual
  reset/pin readback, full recordings, every sampled page reviewed.
- Recheck dry interactive placement/refusal with real packaged defaults, then
  original-checkpoint native integration. Retain every red/instrument failure
  and keep food140/full59/fullgame/combat separately open.
- Only after required gates pass, commit/sync/release via scoped Gradle,
  verify actual public asset and produce/send an edited measured video report.

### Implement
- Candidate only: version57 and water default=true; source movement behavior
  otherwise unchanged. No release/post-build/default test result claimed yet.
- Clean150716 naturally0 at23:14:51UTC/log020421: tungsten clean1m24s/one
  executed task, scoped build8m59s/18 executed tasks, offline/no-cache/rerun,
  no versions/*/bin. Existing remap/deprecation warnings retained; buildexit0.
- Payload156748 naturally0 at23:15:42UTC/log021539. Frozen final candidate
  SHA f7922c214448af8b50d05b56cde587fbe1be982c033268b7f4685965981c78ba;
  all2212 recursive class names match public056 and onlyTungstenConfig bytes
  differ. Actual javap constructor branch=true/water=true; metadata57 confirmed.
  Evidence: artifacts/water-default-candidate-f7922c21/proof.json.
- NavWaterPillar promoted into normal SCENARIOS with unchanged physical/material/
  survival gates; normal CLI --list actually exposes it. Prepared actual-default
  helpers retain42 wet/neighbor and36 dry placement/refusal gates, no flag pins.
  Canonical tester1-only deployment99012 ended0 at23:17:37UTC/log021640;
  actual loaded hash equals frozenf792. No public release yet.
- Actual-default gate48696/wrapper47356 isLIVE/log021838, campaign
  water-release-defaults-20261003-021839. Source7df9df04, planned42 (seven
  cases x six), ONLY idle-throttle pin; both new defaults read back true.
  First14 original gates passed at29-30FPS. No terminal or whole-score claim.
  Wait for its natural boundary; no render/extraction/Gradle duringFPS sampling.
- Prepared audit_water_actual_defaults.py independently checks terminal ownership,
  source/payload/defaults/unique planned trials, original gates, sampledHP/deaths,
  cleanup and clip hashes, then prepares2s sheets. It is NOT running yet; actual
  visual inspection is separate. Prepared36 dry and originalfull59 integration
  wait for current terminal/audit. Native wrapper reuses frozen original1f0af692,
  checks actual defaults before @gamer, forbids automatic recreation/retry and
  preserves any other active bench client. Native result is not yet measured.
- HyperFrames skills refresh24364/log022707 exited0/up-to-date. Report scaffold
  140820/log023119 ended0, new reports/hf/water-ascent pinned0.8.113. Recorded
  automation brief, palette/type design and six-scene outline; no final footage,
  prospective gate rates or release claims. Both catalog searches use word-tier;
  animated-bar-chart matches the observational comparison. No media extraction,
  render, preview server or new Telegram delivery yet; prior reports unchanged.
- Static report authoring only: registry component adapted to the independently
  audited1/5 versus5/5 endfood observations, explicit native safety-red/control
  death/unequalhealth/fullgame limits. Partial index mounts ONLY the stat scene.
  Initial hidden-heading warning retained024658, then corrected by the recipe's
  CSS initial state. Static lint112480/log024807 naturally0: zero errors/warnings.
  Full browser/layout/motion/contrast/render remain pending after allFPS gates.
- Existing native pair3 first OFF/ON pages and ON6 pages3/4 plusON5 pages5/6
  actually re-read for cut selection, no new extraction. ON3 exit30..34s; ON6
  repeated entry retries until170s then exits/works; ON5 route192..284s then
  smoker GUI286.5s. Proposed source cuts recorded in report SOURCE_CUTS.md;
  no moving/retimed-cut/every-frame review claimed. Fonts/runtime/licences copied
  byte-identically from the prior report; registry install58876/log023914 ended0.
  Actual checkpoint budget10678MB/15GB/free167GB, list exit0; no deletion needed.

- Gate48696 ended naturally with exit1 at00:01:13.729347UTC/log021838:
  first34 retained rows are green, min29FPS. Case35/nav_bridge passes original
  physical gates at29.33FPS, but daily00:00 Log4j rollover invalidates the
  wrapper's line-count window. Original failure/summary remain untouched.
- Independent recovery157344/log030735 exited0 at00:07:36.493UTC: closed
  2026-10-02-7 archive decompressed bytes exactly equal the previous case's
  saved log, so complete frozen midnight latest.log conservatively covers all
  of case35 including setup. Runtime matches[]; original source's two setting
  assertions passed before its rollover assertion. recovered-summary.json
  retains35 original verdicts; seven scheduled cases remain unexecuted.
  This is retained-evidence recovery, not a retry, original job success, gate
  waiver or actual film review. Recovery proof retains every boundary hash.
- Partial metadata/2s-sheet audit78540/log030905 isLIVE. No complete42 score,
  film inspection, native integration, release or new video delivery claimed.
  Prepared continue_water_release_defaults.py runs ONLY the seven originally
  unexecuted repeat6 cases, with unchanged gates/defaults; it is not yet running.
- Audit78540 ended0 at00:09:49.293UTC: all35 retained cases satisfy original
  gates, min29FPS, sampledHP20, no deaths/runtime matches, actual defaults,
  complete recordings and cleanup[]. All35 one-page2s sheets actually viewed
  in five explicit seven-image batches, hashes journalled actual-review.json.
  Wet course constructs one real rung by4.5s then holds through its normal15s
  scenario; neighboring routes visibly advance then settle at their goals.
  Not moving/every-frame or native-playthrough coverage.
- Continuation140776/wrapper53684/log031157 started00:11:57.991535UTC,
  helperb95a7541; ONLY the seven original unexecuted repeat6 gates are nowLIVE.
  No complete42 or prospective dry/native/release result. Source/JAR unchanged;
  log collector preserves the original measured error-window gate.

## 2026-10-03 — retain client errors across midnight log rotation

### Investigate
- Actual candidate gate crosses midnight. Client latest.log changes from4889
  lines to the new daily file; old line-index collection raises even though
  nav_bridge passes. Matching the same number of lines would also silently
  omit errors if a new file grew past the old count. Client/container remains
  healthy; restarting either would not repair the log boundary.

### Plan
- Identify the starting file by exact byte prefix, keep newly rolled gzip
  archives plus frozen end snapshot, and fail on missing/ambiguous history.
  Retain any incomplete starting line in full, preserving boundary errors.
- Use the quiet subprocess adapter for read-only Docker calls; preserve clients,
  original failed evidence, gates, sampling and Linux compatibility.

### Implement
- New uctest/client_logs.py provides a conservative retained ClientLogWindow;
  the actual-default continuation and prepared dry fixtures now use it.
  Original frozen failed helper stays unchanged for provenance.
- Nine meaningful boundary tests pass on actual Windows Python3.14 and Linux
  Python3.11: append, real midnight shape/errors on both sides, numeric size
  rotations, partial error lines, equal-count replacement, missing/ambiguous
  history, missing intermediate archive and rollover during collection.
  Linux/read-only live client check16452/log030827 ended0 at00:08:34.698UTC.
  Live snapshot prefix/window retained without altering the client; retained
  real midnight archive independently validates the production byte collector.
- ASSESS: gameplay score is unchanged; trustworthy cross-midnight error
  measurement advances the playthrough process. This fixes evidence collection,
  never gameplay or FPS. Native food140/full59/combat remain open; continue the
  pending actual-default audit and originally unexecuted cases immediately.

### Candidate validation continuation
- Collector/test/progress commit05e44c5a pushed atomically to both canonical
  branches; local1.21.11 fast-forwarded. Wiki37081099635 actuallySUCCESS.
- Remaining-seven gate140776 ended0 at00:20:38.339818UTC/log031157,
  water-release-defaults-tail-20261003-031158. Independent combined audit153736/
  log032149-139480 ended0 at00:22:00.220596UTC: exact42 unique scheduled trials,
  min29FPS, sampledHP20/deaths0/runtime[]/cleanup[], actual defaults/JARf792.
  All42 fixed2s pages now actually inspected/hash-journalled. Original midnight
  exit1 remains retained; recovered case35 plus seven previously unexecuted
  trials complete the original schedule without retrying a gameplay failure.
- Dry actual-default fixture106964/wrapper150588/log033006-150588 isLIVE,
  helper97740fa0,36 planned trials. New exact-prefix log collector and peer
  preservation guard, original material/GUI/policy/FPS gates. No dry overall
  score or native/release result yet; await its natural terminal/audit.
- Report resume153372/log031851 ended0 at00:19:13.646418UTC: read-only CLI
  freshness confirms0.8.113 current; history shows only our prior source-cut/
  storyboard edits. Three action scenes authored and mounted alongside stat
  scene (four of six). Two final scenes and browser/render checks remain.
- First footage staging148824/log032109 ended1: original normal-speed ON bank
  slice is valid/adopted, then compressed OFF cut fails exact-duration audit.
  Output-side duration was applied after speed-up; rejected output is retained
  as unmounted off-bank.mp4/failed-output.json, never adopted or used in film.
- Correct input-bound source ranges107796/log032625-98232 ended0 at
  00:26:55.371170UTC. Five source-SHA-verified silent854x480/30FPS cuts retained:
  ON12s/1x, OFF12s/4x, rung8s/1x, slower entry12s/8x, smoker return12s/8x.
  Off-bank-source-window.mp4 is the corrected mounted asset. Local media ledger
  contains exactly these five approved assets; source/hash/time/speed in cuts.json.
- Static lint147360/log032752-139124 ended0 at00:28:00.290965UTC: zero errors,
  one retained missing-host-coverage warning on incomplete58s root. Do not
  extend the opening scene over later slots to silence it; finish the timeline.
  Cut-sheet preparation149204/log032752-155096 ended0 at00:28:20.254141UTC.
  All9 half-second pages actually viewed/journalled: bank exit then wood, OFF
  submerged hold, one real constructed rung, slower entry retries then exit,
  smoker detours then GUI. This is exact retimed-cut sampling, not moving,
  every-frame, composed/browser/render proof. New report not delivered yet.
- Parallel audit-launch attempt collided with the ignored durable wrapper's
  second-resolution directory name before starting any audit child. Retained
  tool error; wrapper now appends itsPID for unique ownership. Audit153736 is
  the sole actual combined audit. No benchmark retry or overwritten job directory.
