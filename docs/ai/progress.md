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
