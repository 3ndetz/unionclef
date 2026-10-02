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
