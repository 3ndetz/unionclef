# Progress

## 2026-10-02 — full59 continuation and live landing feasibility (in progress)

### Investigate
- Earlier full59/casting/Task/physics evidence is preserved verbatim in
  [archive/02-10-2026-full59-landing-investigation.md](archive/02-10-2026-full59-landing-investigation.md).
  This supersedes its final "audit running" entry; historical failures remain retained.
- Published release0.95.54 at main/tag73b78e19; assetSHA25600e866 verified.
  Candidate SHA25622795721788d682aac1e3da06ec55d329f64ce5e973b3b9ec288b30708fe27c0.
- Compiled retained-input checker accepts the recorded green and rejects the fatal
  landing atindex24; max actual pos/vel errors2.79e-8/6.53e-8 after the vanilla fix.
- Navigation34210:42/42 validPASS, gap refusaloff6/6/on6/6, adjacent10courses3/3.
  Enabledgap2 refusedoneplan, then arrived15.1s. BOTH arms contain the vector repair;
  no speed/mortality delta established. All42 fullclips reviewed2s, alltraces
  continuous/error-free, cleanup errors=[]. Evidence: live-landing-ab-20261002-034843.
- Adjacent17179 exited1 atcase8: seven craft/pickup/lava cases validPASS,
  arrow_dodge validFAIL, remaining19/27 NOTRUN.10arrows,400tenths damage,2deaths,
  29.82FPS, searches0/drive0. Entire63s clip reviewed2s, cleanup errors=[].
  Original verdict remains unchanged. Evidence: live-landing-craft-20261002-044154.
- Before resetting counters, readback proves clientDifficulty=PEACEFUL,
  mobDefense=true, dodgeProjectiles=true, mdCalls1170, mdWon0, searches0/drive0.
  MobDefenseChain:716-730 exits on Peaceful before projectile checks. ArrowDodge
  did not normalize difficulty although summoned arrows hurt on Peaceful.
  This establishes why the candidate dodge simulation was never exercised.
- Full59 original column stall and two earlier fatal casting entries remain open.
  Six latest casting probes survive; this does not erase those failures.
- Native full59-before-portal15min replay:0deaths, allOverworld/noportal,
  legitimate thunderstorm shelter, then ~6min no-site wait in water.
  cp1002-0229-t656 has bed/food/health20. Approximate saved geometry finds0 dryshafts
  within ±6/±1; native safe-site predicates/approach remain unmeasured.
  Prepared guarded native probe remains NOTRUN.

### Plan
- Normalize arrow difficulty, verify client settings/active Task before firing,
  and require actual searches/input driving in enabled-arm results.
- Repeat the same mixed27-case adjacent campaign on the unchanged frozen jar.
  Retain the old red separately and stop at the first new red/invalid.
- After meaningful green audits, narrow the callback comment, commit/push tested work,
  including six current-binary portal checkpoint replays: the preceding six casts
  used5516 before the Agent/landing change and cannot validate227957 by inheritance.
  release through :1.21.11:githubRelease and verify the actual asset. Complete the
  edited English video and send through the authorized Telegram launcher.
  Audit and continue without a milestone stop.
- Next: restore the water-shelter checkpoint, measure native safe-site predicates
  and actual @goto/GetToBlockTask approach, then port the established core mechanism.
  Keep flood/hazard predicates; continue the complete @gamer goal from checkpoints.

### Implement
- Uncommitted candidate: ReplayFeasibility ground-boundary check from actual support,
  exact native mouse quantization, vanilla1.21.11 vector deadzone, synchronized path
  mutation and refusal counters/reset. Generic C4.2 public-field races remain open.
- Final38465 build passed after clean22285; nested byteidentity/javap confirmed,
  Task.class unchanged from5516 with its existing9/9 contracts. Tester1-only
  deployment23092 passed. No build/redeploy during either liveaudit.
- First seven adjacent clips reviewed3s; wrapped Task vetoes occurred during actual
  pickaxe/full-pack crafts. Lava escape entered and survived, minHP16;
  the fixture has resistance and does not prove universal casting safety.
- ArrowDodge now sets Normal and verifies actual client difficulty, mobDefense,
  dodgeProjectiles and active runner before arrows. Enabled arms require nonzero
  searches and driving. Python syntax/live replay validation pending.
  No Java defense change guessed. Syntax and diff checks passed; mixed27case
  replay94351 started on unchanged227957, artifactlive-landing-craft-20261002-050402.
  Initial table/mixed-pickaxe/full-pack cases validPASS at29-30FPS; remaining pending.
  The retained red's post-failure-environment.json verifies disk/runtime flags too.
  Fetch of this repository's main/1.21.11 finds both0/0; owner credentials confirmed.
- Normalized repeat1 flat-arrow course validPASS:10arrows/0damage/0deaths,
  searches33/drive78,29.73FPS. Client Normal/defense/dodge/activeTask readback passed.
  First seven mixedcases green and clips reviewed3s; ledge/remaining repeats pending.
  No defense improvement is claimed by comparing different difficulty settings.
- Normalized repeats1-2 completed18/18 validPASS, all eighteen fullclips reviewed:
  craft/pickup/lava3s, both arrowcourses2s including both sheet pages.
  Repeat2 flat arrows:0damage/0deaths, searches28/drive78 at29.67FPS.
  Repeat2 ledge:0damage/0deaths, lava/fallpolls0, searches68/rejected206/drive118
  at29.62FPS. Third repeat remains active; no portal checkpoint restore/build/deploy
  or heavy video render runs alongside this campaign.
- Prepared reports/stories/g108-live-landings.json using reviewed retained clips.
  Split source windows now have equal12s durations, preventing a blank half;
  ffprobe verifies all six selected ranges lie within their actual sources.
  No new render/send yet; draft release remains held pending adjacent validation.
- Mixed campaign94351 completed27/27 validPASS, exit0,29-30FPS,
  cleanup errors=[] and owner lock absent. All27 fullclips reviewed at3s for
  craft/pickup/lava and2s for both arrowcourses, including all pages.
  analyze_active_landing_audit.py verifies per-case verdict agreement, the frozen
  hash, all six protected-craft descendant vetoes, and actual Normal/defense/dodge/
  activeTask preconditions with nonzero searches/drive in all six arrow runs.
  Flat+ledge arrows each3/3, all60 arrows fired,0damage/0deaths; ledge lava/fallpolls0.
- Current-binary portal campaign63444 started with --runs6 --cleanup true --trace,
  logg108-live-landing-portal-audit.log. Each attempt restores the retained identical
  portal entry, verifies inventory/hash, and gates lit portal plus survival.
  The prior six5516 casts remain historical controls; no current portal result yet.
  Release is held for this campaign, not for the now-complete mixed audit.
- Portal campaign63444 completed6/6 validPASS, exit0,29.41-29.71FPS,
  lit portals112.1-121.9s, death/entry deltas0 in every attempt. All six fullclips
  reviewed4s including all pages. Trace analysis:3158/3049/3368/3120/3191/3288
  continuous events, final drains present, no trace errors/lava ticks;54 collector
  handoffs,49 with queue active and1 with walker active. These activity fields do
  not identify the actual final key writer. Cleanup errors=[]; own lock released.
  Frozen outer227957 jar preserved for release bytecode comparison. No release yet.
- Native saved-site probe3088 started only after portal teardown, on the unchanged
  candidate and owned gamer server. This measures actual predicates on
  cp1002-0229-t656; approach and any shelter change remain pending.
- Native3088 completedexit0, artifact shelter-native-site-20261002-060523:
  actual pickSite returnsnull; saved feet fail siteHolds; all five nearby saved
  candidate sites pass the native predicate on the client thread. Closest checked
  (-83,64,1056) is outside both original search bounds. Inventory ids/counts verified,
  original all-file checkpoint hash041ec8 unchanged, cleanup errors=[].
  Actual approach remains unmeasured; no shelter Java fix written.
- Version prepared0.95.54 and Goto-specific callback comment narrowed without
  changing line count. Final clean Tungsten/1.21.11 build99593 launched only after
  probe teardown. No versions/*/bin found. Compound preparation command was rejected
  before execution; the separate Docker build started successfully. Client not stopped.
  Release payload/publication and the new edited video remain pending.
- Release build99593 passed4m19s exit0. The0.95.54 jarSHA256 is
  00e8666a3095faa92fddb743f4f675af3800d22f9c41de2809d408e9d35efbbf.
  All2209 classes, including every nested jar, are byte-identical to the frozen
  candidate that passed42 navigation,27 mixed and6 portal cases. No added/removed/
  changed classes; fabric metadata reports0.95.54. Nested module freshness passes.
- HF history596a964f started; no intervening human edits recorded. Prior timeline
  inspected. New54s composition built from six reviewed source windows, without
  recursive deletion. Checks/visual review/render/TG delivery pending.
- First HF check passed, exit0; five actual snapshots reviewed. They exposed
  mojibake in generated story strings, not in the UTF-8 storyboard. The owned
  rebuild helper now explicitly decodes/encodes UTF-8; checks run again after this
  correction. Transition-boundary occlusions are inspected separately from the
  middle of each card. Animation-map dependencies are bootstrapped temporarily,
  with lifecycle scripts disabled and both helper packages pinned to0.8.105.
- All2209 rebuilt release classes and nested freshness passed; final remapped
  Task lifecycle contract9/9. No version bins exist. Source ready for narrow
  owner-authored commit/push and canonical release; publication still pending.
- Canonical scoped publication79744 passedexit0 in2m22s. GitHubv0.95.54 points
  to73b78e19, identical main/remote1.21.11; uploaded asset is the correctMC1.21.11
  jar,6368066 bytes, digest00e866 identical to the verified rebuild. Rechecked all
  2209 classes after publication: unchanged from the audited frozen candidate.
- UTF-8 HF check20647 passedexit0. Animation-map20311 passedexit0:36tweens,
  no deadzones;8 offscreen wipe flags and2 overlap flags are the intended cut
  transitions,5 slow zoom/progress flags deliberate. The uneven-stagger heuristic
  combines the bridge label exit with the next text-card entrance; each actual
  text-card step is400ms. Reviewed the corrected split plus all five middle-card
  snapshots, including ladder/title/stat/end. Delivery render54463 active.
  No post-release deployment/audit or Telegram delivery yet.
- Delivery render54463 passedexit0:54.0s/1620frames/1920x1080/30FPS,
  drawElement hardwareGPU,1m59s. Full rendered recording reviewed2s on both
  sheet pages, including actual ladder climb/water exit/bridge continuation.
  Telegram copy4.72MB, same54s, delivered successfully asmessage9570 with the
  actual release link and coverage limits; receiptg108-report-telegram-receipt.json.
  Next: tester1-only canonical deployment, focused release audit, native saved-site
  approach. No shelter change yet; this milestone is not the end goal.
- Tester1-only canonical deployment54166 passed with loaded releaseSHA00e866.
  Post-deployment42784 audit completed4/4 validPASS: gap/flat/stair/descent,
  29-30FPS, zero deaths/falls/freezes, independent cleanup errors=[]. All four
  fullclips viewed2s; analyzer verifies448/252/235/245 continuous trace events.
  Cold-client max landing check12.35ms; the earlier warm maximum is not universal.
  Evidence: live-landing-release-audit-20261002-063648/analysis.json.
  Actual HF history end entry is2ebb1136-1810-4439-bef1-f82ebb1551f4.
- Native approach40445 exited1, artifact shelter-native-site-20261002-064251.
  All91.9s of recording viewed2s on both sheets. Actual @goto reports completion
  about15s, then the runner is inactive; the observer times out at90s. This is
  an observer defect: exact end(-82.699999988,64,1056.983847948) occupies the target
  (-83,64,1056), while getGameState rounds Z to1057.0 before the old judge floors it.
  Passive client-thread native readback confirms the target still passes siteHolds,
  body grounded/dry, air300. No death/new lava entry;270 samples14-29FPS.
  Setup left an idle underwater body too long: entry health20 before settings,
  first motion sample9.4. This is not evidence of damage caused by the route.
  Original result is retained unchanged; checkpoint unchanged and cleanup errors=[].
- Probe now judges unrounded coordinates and performs expensive settings calls
  before checkpoint restoration, without adding water-breathing or healing effects.
  Corrected approach4704 active on the same release/retained entry. No shelter
  Java fix yet. Next validate actual arrival/safety, then implement reachable
  multi-site selection using the existing planner and upstream GoalComposite.
- Corrected4704 exited1, shelter-native-site-20261002-065337. First route sample
  now has20hp; no effects/healing used. The route does not arrive in60s: it climbs
  out, then mines down at(-85,*,1054) toY28.5 despite requestedY64. All61.9s of
  recording viewed2s.180 samples average24.85FPS, minimum11, so this run is not a
  healthy-route comparison. No deaths/lava entries, original checkpoint unchanged,
  cleanup errors=[]. Retain the unexpected descent as evidence, not a proved cause.
- Actual log contains GotoCommand retry callbacks during the unrelated @goto.
  Source audit: TungstenMod.stopNavigation/resetAllState leave executor.cb intact;
  GotoCommand's delayed retry only tests the shared stop flag, which a new search
  resets. This makes stale ownership plausible, not yet established for this descent.
  Also inspect post-mining search target and actual navigator goal before fixing.
  Upstream PathingBehavior:331-370 cancels the owning processes and current/next path.
- Traced same-entry approach95746 active, shelter-native-site-20261002-070326;
  observer records callback class/captured target, global/search/native goal and
  actual client coordinates. No source/build/redeploy alongside the frozen probe.
- Traced95746 completedexit0: exact stable arrival21.1s, health20 throughout,
  no deaths/lava entries,64 samples14-29FPS (average24.22). Native site still safe
  after the approach; originalCP unchanged, cleanup errors=[]. Full23.1s clip
  viewed2s. Callback class isnull throughout, navigator endpoint matches the request.
  This proves this site is reachable, not a rate improvement or the descent's root.
- Candidate shelter pass now implements immutable AnyBlock goals (upstream
  GoalComposite:43-61), searched through the existing FastPlanner movement graph.
  Night discovery covers loaded ±16 in all axes instead of ±6 horizontal/±1 vertical,
  passes the unchanged live safety predicate, and revalidates before the first dig.
  Current safe feet remain the immediate choice; an approach that invalidates its
  destination is rescanned. No-site discovery is refreshed once per20 world ticks.
  Condition routes have caller identity so a danger-region search cannot adopt
  a shelter route. This expands the affected tests to flee and breathable-air ownership.
  Candidate scan counters will measure actual client cost. Build/live tests pending;
  version remains0.95.54; no candidate release or TODO closure.
- Clean candidate build9116 passedexit0 in7m49s, including Tungsten clean/build
  and1.21.11 remap/build. Initial attempt exited1 because an unquoted PowerShell
  JVM option left the Windows JDK override active; the quoted retry succeeded.
  Tester1-only canonical deployment34010 passed: loaded candidateSHA256
  06652cfdb86d9e088ad0a7252c903740b4d09db4c776509370b3678ec4383391,
  nested module freshness verified. Published54SHA00e866 is archived separately.
  Actual saved-entry NightShelterTask probe80495 is active with a sealed10s-hold
  gate and scan/FPS instrumentation. No candidate publication or live result yet.
- Saved-entry80495 exited1 on FPS validity, shelter-native-site-20261002-072502.
  Actual Task/observer exited0: selected(-84,66,1059), capped dry shaft, body at
  bottom, continuous10.279s hold by24.992s.68samples, HP20 throughout, no deaths/
  lava entries; scan61 destinations in22.160ms. FPS4-28/mean20.868, six samples
  below14; this is not a healthy route-rate/timing comparison. Original invalid
  verdict remains unchanged. Full27.1s recording actually viewed at2s cadence.
  motion-analysis.json verifies unchanged originalCP after the run, cleanup[].
  Repeat68771 active on the identical loaded06652c candidate and retained entry.
  Prepared blocked-nearest fixture first verifies actual native predicates:
  safe inaccessible bedrock cavity, reachable shaft, rejected water/lava neighbors.
  Its36-case shelter/exit/air/nav/craft campaign is prepared, NOTRUN.
- Same-entry repeat68771 completedexit0, shelter-native-site-20261002-073041:
  dry sealedshaft(-84,66,1059), continuous10.434s hold by21.363s, HP20 all64samples,
  zero death/lava deltas,23-29FPS/mean27.016, no below14 samples. Discovery76
  loaded candidates in8.630ms; loaded-chunk coverage differs from the cold run.
  OriginalCP unchanged afterrun, cleanup[]. Full23.5s recording viewed2s.
  The trace includes a real GetToAir interruption and shelter continuation.
  One healthy result establishes behavior, not the repeated rate. Campaign79145
  now active: blocked-nearest first, then flat/exit/air/nav/craft, stopfirstred.
- Campaign79145 first6cases validPASS at29-30FPS: blocked-nearest, flat shelter,
  morning exit5.9s, drowned tunnelHP20/air217, flat and staircase. Firstfive fullclips
  actually viewed2s (both drown pages); remaining repeats are still running.
  Native blocked fixture confirms [entryFALSE, enclosedsafeTRUE, accessiblesafeTRUE,
  lavaneighborFALSE, waterneighborFALSE], then seals the accessible shaft, not the
  diagnostic nearest cavity. Generic freeze warnings inside sealed shelter are
  expected waiting, not failed movement gates. Tester2 remains stopped; no victim
  is needed by these courses. No controller/COMPLEX change.
- Adjacent source concern, NOT yet a native failure: siteHolds checks fluids below
  and beside the shaft but not the fluid in top itself. PlayerFit.standable checks
  collision, not dryness; WorldHelper.canBreak only inherits lava exclusion here.
  Upstream MovementHelper.java:96-137 rejects fluid directly above a planned break.
  Prepared check_shelter_fluid_top.py with contained water/lava and a dry control;
  syntax passed, NOTRUN. Measure after campaign teardown before publication; do
  not change the predicate based only on this read or claim a current regression.
- Campaign79145 first round9/9 validPASS at29-30FPS, zero deaths. All nine full
  recordings actually reviewed2s, including both drown sheets. Four navigation
  courses have no self-falls/freezes; mixed-plank craft fires15 descendant vetoes
  and obtains the pickaxe. Repeats remain active; no final36-case verdict yet.
  Checkpoint budget8170MB/15GB, disk free193GB; no cleanup deletion needed.
- Campaign79145 first19 cases validPASS: both complete nine-course rounds and
  the third blocked-nearest repeat. All nineteen full recordings actually viewed
  at2s cadence, including both pages of each drown clip. Third blocked fixture
  again seals the reachable shaft rather than the enclosed nearest cell;
  morning exits in the first two rounds take5.9/5.5s. Mixed craft obtains the
  pickaxe and records15 descendant vetoes in each round. Remaining repeats run
  on the unchanged06652c payload; no build/deploy or concurrent live probe.
- Prepared reports/hf/BRIEF.md from the existing report project and the owner's
  autonomous/report instructions. General-video skill update reports up-to-date.
  The delivered54-second landing report/message9570 is preserved. No new shelter
  composition/render/send yet; final rates and version depend on completed tests.

### Assess
- The retained fatal landing is now rejected by actual-class simulation, and one
  live enabled gap run refused a plan before takeoff, replanned and arrived.
  Balanced current gaps remain6/6 in both arms; no speed or mortality delta proved.
- This advances safe Tungsten execution; the full game is not completed. Night-site
  selection, original lava-column stall and earlier casting deaths remain open.
- One shared vanilla model and a client-thread feasibility check fix the root;
  no special-course coordinates or timeout recovery added. Current failure evidence
  is retained separately from the green rebuilt arenas and fixed portal entry.
- Next unknown is actual travel from the no-site water entry to a native safe site.
  Measure that route before changing site selection or the navigation core.

STOP CONDITION CHECK:
- Is the work actually finished?        -> no
- Is the END GOAL reached?              -> no  (@gamer plays the whole game on
                                           tungsten, baritone deleted)
- Did the customer say to stop?         -> no
VERDICT: if ANY answer is "no", the stop condition has FAILED — work continues
         immediately and the next iteration starts at once.
