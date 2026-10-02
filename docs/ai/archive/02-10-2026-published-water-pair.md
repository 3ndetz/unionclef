# Published 0.95.56 native water comparison: retained FPS-invalid pair

## Investigate

- Exact published jar: `365e1fdb1615bc85194d48747fb37ec6bb833f8b27e7a2bba0c3b9cc70ee93a9`.
  Both arms restore original `cp1002-1301-t644`, fingerprint
  `582e74bda330ac87f5e28c3ac91322307f78181a61a17818eaa1cd7bc533df64`;
  raw inventory308/cooked mutton3, constant branch clearance=true, tick-only
  observation and owned full-window capture. Original ground remains unchanged.
- Prespecified pair1 ran OFF then ON. Runner147180 terminated naturally with
  exit1 at19:57:12UTC: ON median19FPS fails the unchanged20FPS campaign floor.
  This pair contributes **zero healthy paired observations**. No retry or erased
  outcome, no pooling with earlier092/2b diagnostics. Pairs2-6 remain unexecuted.
- Both traces start already injured atHP9.766668 although restoration initially
  readHP20. Both recover to20, deaths0/lava0/runtime matches[]. The activation
  interval limits any survival attribution; neither water arm receives one.

## Plan

- Preserve the complete two-arm observations, FPS rejection and original gates.
  Inspect every2s page, retaining clip/page hashes and explicit coverage limits.
- Diagnose stand availability before any prospective pair. Read-only post-run
  samples cannot establish the load that caused the past19FPS result.
- Keep the water input default=false. Actual food acquisition on an FPS-invalid
  arm is useful mechanism evidence, never the required healthy paired rate or
  completion of food140, full59 or the full playthrough.

## Implement

- Campaign: `deploy/runner/artifacts/published056-water-food-pair1-20261002-223600/`.
  Durable terminal exit is in `logged-job-20261002-223600/result.json`; the
  healthy-pair `terminal.json` correctly does not exist. Summary retains both arms.
- OFF source `food-bank-trace-20261002-223600`: gamerFAIL/observer0/cleanup[],
  median22FPS over12polls, no new food/rung, cooked mutton3->1. Wet pillar4488ticks,
  4419 JUMP+SNEAK and zero JUMP-only; longest fixed-body hold225.968s.
  Capture326.563s/video333.8s, SHA
  `e80a972c615b68c75d36327ef251bafa9b82379faecdd4d80deaefa8c58a090f`.
- ON source `food-bank-trace-20261002-224627`: gamerPASS/observer0/cleanup[],
  median19FPS over12polls, new cooked porkchop3/cooked chicken3/raw mutton5,
  original cooked mutton3->1. GamerPASS records furnace progress, not completion.
  Wet pillar22ticks,21 JUMP-only/zero JUMP+SNEAK; longest hold22.081s near cooking.
  Capture334.583s/video342.666667s, SHA
  `84d08bf70db2a1cf23e69d4294eeba50dc51231ad84ca1f35bcd4bb8ba67d4c5`.
- Review preparation33768 exits0 in `logged-job-20261002-230336`. Actual inspections
  cover all7 OFF and all8 ON pages at2s. OFF moves at first then remains submerged
  through the long pillar hold. ON escapes around80-95s, hunts/crafts/cooks, then
  returns to lower bank pursuit/tunnelling. Final stop/chain disappearance is
  recorded in both. `sheets.json` and individual `review.json` carry actual review
  journals and hashes. This is sampled-page coverage, not every frame or playback.
- Preview ancestry confirmed: node152636/49320 own Studio3002/3003 and the two
  HyperFrames headless trees. Their8s post-campaign sample totals about0.58% of
  one CPU core; no evidence supports blaming them for the earlier FPS. No browser,
  useful test, Docker service or container was stopped for hiding windows.
- Read-only load151804 exits0: tester created18:09UTC, no CPU/memory quota or cpuset;
  renderDistance5/simulationDistance5/maxFps30. Idle10FPS is the restored default
  inactivity cap, not proof of active-run starvation. Separate stationary probe
 24216 temporarily pins the existing bench throttle flag and reads29/29/29/26/29/28
  FPS, then proves original=false restored. Both samples are explicitly post-run;
  the earlier native19FPS cause remains unresolved. Other projects remain untouched.
- Budget after these arms:9360MB of15GB;170GB disk free. All outcomes retained.

## Follow-up: first healthy pair, not a native rate

- Prespecified pair2 ran ON then OFF without changing the helper, jar, source
  checkpoint, branch pin, observation mode or gates. Parent94548 exits0 at
  20:37:07UTC (`logged-job-20261002-231632`); both native instruments finish0,
  gamerFAIL/cleanup[], deaths0/lava0/runtime matches[]. Pair1 remains excluded.
- ON `food-bank-trace-20261002-231633`: median25FPS/13polls; new cooked pork3,
  cooked chicken2, raw mutton3/raw chicken2, original cooked mutton3->1.
  Wet pillar103ticks/96 JUMP-only/zero JUMP+SNEAK, longest exact hold24.592s.
  First traceHP13.466667 on GetToAirTask, then recovers20.
  Capture329.744s/video336.066667s, SHA
  `29d9f707c947c6fd02242dcbad2d86ea09a13fa562c70db6e1cfac42b83f410f`.
- OFF `food-bank-trace-20261002-232637`: median27FPS/13polls; no new food/rung,
  original cooked mutton3 retained. Wet pillar1082ticks/1047grounded/900
  JUMP+SNEAK/zero JUMP-only. First trace/minHP20. Longest exact hold4.759s;
  repeated body jitter around the same bank is not pair1's225s fixed-body hold.
  Capture335.655s/video342.133333s, SHA
  `386e0d3aa655155bfef9e1e7c8b8c544f16f683313076fe584103be680997a04`.
- Review147860 exits0 (`logged-job-20261002-233744`). All8 ON and all8 OFF
  pages actually inspected at2s and hash-journalled. ON leaves the initial bank
  around30-35s, hunts/crafts/cooks; OFF repeatedly tries the original bank.
  Both final stop/chain disappearances remain visible. Unequal initial traceHP
  prevents survival attribution. This is one healthy pair of two attempted,
  never a six-pair success rate or food140/fullgame completion.
- **Retained later input:** ON hunts successfully, then approaches an animal
  below another bank around240-330s without finishing the food goal. End body
  near(-45.5,66,1004.7), not the original water site. Preserve
  `food-bank-trace-20261002-231633-end` and `cp1002-2320-t309`; the cause and
  baseline reproduction are unverified. This limit stays open independently
  of whether the initial water pillar improves. Do not classify it as a new
  water-input regression without measuring the previous behavior there.
- Read-only host observer27936 exits0 naturally when the pair ends.37 retained
  samples,36 CPU intervals (initial interval null), no errors; aggregate Windows
  CPU43.44-82.78%. No extra game API calls, no container/service changes. This
  does not establish per-thread contention or explain the past pair1 FPS19.
- Budget9626MB of15GB,169GB free. Pairs3-6 remain prospective, water default=false.

## Follow-up: second healthy pair, with unfinished later cooking retained

- Prespecified pair3 OFF->ON completes naturally/parent0 at21:07:21UTC,
  `logged-job-20261002-234649`. Original helperSHAa3fe7cee, published365e,
  sourcecp582e, branchtrue, tick-only observation and gates remain unchanged.
  Both gamerFAIL/observer0/cleanup[], deaths0/lava0/runtime matches[].
- OFF `food-bank-trace-20261002-234650`: median28FPS/13polls; no new food/rung,
  original cooked mutton3->1. Wet pillar6014ticks/6012grounded/6011 JUMP+SNEAK,
  zero JUMP-only; longest exact fixed-body hold316.046s. Initial traceHP9.766668
  recovers20. Capture335.688s/video341.736264s, SHA
  `3e9344fc7a08cf2722e2da92b4005ab8d71b117c3775ef25a903a2b1336304f3`.
- ON `food-bank-trace-20261002-235718`: median26FPS/13polls; new cooked chicken6
  and cooked porkchop3, original cooked mutton3->2. Wet pillar31ticks/29 JUMP-only,
  zero JUMP+SNEAK/grounded; initial/min traceHP20. Unequal activationHP prevents
  survival attribution. Capture333.332s/video340.066667s, SHA
  `a3030da026bf90adb73cf1af2f9489bd42e87430648104a516c2a01ea91605f3`.
- Review100688 exits0 at21:08:21UTC (`logged-job-20261003-000736`). All8 OFF and
  all8 ON pages actually inspected at2s and hash-journalled. OFF approaches then
  stays submerged from roughly22s through336s. ON leaves around30-33s, gathers
  wood/climbs, kills a pig66-68s and crafts/cooks72-96s; further animal kills and
  clearing114-226s. No sampled death/fire; both final stop/chain disappearances
  retained. Coverage is every sampled page, not every frame or moving playback.
- ON later pauses25.789s during the smoker approach, resumes the route, then
  holds42.173s near(-48.964,99,1078.887) in the smoker interface. Actual trace
  chain includes SmeltInSmokerTask/DoSmeltInSmokerTask/MoveItemToSlotFromInventoryTask;
  measured movement drivers are inactive there. Retained diagnostic chat includes
  MOVEMISMATCH holding cooked_chicken versus requested coal. Output changes near
  the end, so this is an unfinished cooking/transfer interval, not proof of an
  unconditional permanent freeze or a new water regression. Preserve
  `food-bank-trace-20261002-235718-end` and `cp1003-0001-t309` for investigation;
  causal/baseline reproduction remains unverified. Food140 stays open.
- Host observer146684 exits0 naturally at21:07:31UTC:37samples/no errors,
  aggregate CPU34.87-82.64%. These aggregate samples cannot establish per-thread
  contention. No services, useful tests or human windows stopped/hidden.
- Two healthy pairs of three attempted; original pair1 FPS-invalid outcome still
  excluded. Pairs4-6 remain prospective, water default=false. Budget9867MB of15GB,
 169GB free. Wiki37062623387 succeeded on prior exact head8d566680.

## Follow-up: third healthy pair includes a food-positive control

- Prespecified pair4 ON->OFF finishes naturally/parent0 at21:33:54UTC,
  `logged-job-20261003-001242`. Same helper a3fe7cee/published365e/cp582e,
  branchtrue, tick-only observation, full capture and unchanged gates. Both
  gamerPASS for furnace progress, observer0/cleanup[], deaths0/lava0/runtime[].
  Neither standard PASS means food140/full59/fullgame completion.
- ON `food-bank-trace-20261003-001242`: median23FPS/13polls; new raw mutton11,
  cooked porkchop3/cooked chicken4/raw chicken3, original cooked mutton3->1.
  Wet pillar25ticks/24 JUMP-only/zero grounded/JUMP+SNEAK. Initial traceHP9.766668
  recovers20. Capture342.747s/video349.866667s, SHA
  `e12e2feacf8a229ce48101fd4873fa237cd4e453acc3682af6ef5feb5da6c796`.
- OFF `food-bank-trace-20261003-002326`: median25FPS/13polls; new cooked pork3
  and cooked chicken4, original cooked mutton3->2. Wet pillar455ticks/430grounded/
  400 JUMP+SNEAK/zero JUMP-only; initial/min traceHP17.366667. Unequal activationHP
  again prevents survival attribution. Capture337.234s/video343.933333s, SHA
  `1be0c1603312b937bb833ef2b362d7622875c7e75e41d28f41a7cee3a621b12b`.
- Review7724 exits0 at21:35:20UTC (`logged-job-20261003-003432`). All16 actual
  2s pages inspected/hash-journalled. ON leaves water14-20s, kills pig32-34s,
  crafts/cooks38-82s; subsequent hunting continues. OFF has repeated wet
  attempts, descent and surfacing, then approaches the bank92-110s, kills a
  pig112-114s, gathers wood/crafts118-160s/cooks164-178s, and later hunts/cooks
  again. Both final chain disappearances recorded; no sampled death/fire.
- The food-positive OFF observation is retained. The native defect is intermittent,
  not an unconditional failure with ordinary input. Mechanism exposure is still
  present in both arms (400 opposed wet inputs versus24 JUMP-only); a successful
  alternative exit must not be deleted or replaced to exaggerate the change.
- **Fixed-body is not a task duration:** ON's longest47.507s exact hold starts
  in MineAndCollect/DestroyBlockTask, but actual film134-182s spans wood, crafting
  and furnace cooking. The first signature describes only the first phase; it
  does not prove48s of failed mining. OFF longest31.976s lies in furnace cooking.
  Earlier wood/craft delays and later route retries remain visible, not disguised
  as water-only delays or claims of uniformly smooth gameplay.
- Host156836 naturally exits0 at21:34:15UTC:39samples/no errors/aggregate
  CPU46.39-76.58%; no per-thread causal claim or unrelated service changes.
  Budget10129MB of15GB/free168GB. Three healthy pairs of four attempted;
  pair1 FPS-invalid result retained/excluded, pairs5-6 prospective. Waterdefault
  remainsfalse. Wiki37065363489 succeeds on fa9bf261 before this append.

## Follow-up: fourth healthy pair retains the long smoker return

- Prespecified pair5 OFF->ON finishes naturally/parent0 at22:00:17UTC,
  `logged-job-20261003-004013`. Same helper a3fe7cee/published365e/cp582e,
  constantbranchtrue, tick-only observation, full capture and unchanged gates.
  OFF gamerFAIL and ON gamerPASS for furnace; both observer0/cleanup[],
  deaths0/lava0/runtime[]. Food140/full59/fullgame remain open.
- OFF `food-bank-trace-20261003-004014`: median24FPS/13polls, no new endfood,
  original cooked mutton3->1; wetpillar5716ticks/5714grounded/5713 JUMP+SNEAK,
  zero JUMP-only. Exact-body hold305.713s; initial traceHP9.766668 recovers20.
  Capture338.926s/video346.133333s, SHA
  `52bb3f419693fc22d063edf7a009af658a1e5859f9d6eef957b542f4e45f5237`.
- ON `food-bank-trace-20261003-005036`: median25FPS/13polls, new cooked pork3
  and chicken3, original cooked mutton3->1; wetpillar68ticks/zero grounded,
  63 JUMP-only/zero JUMP+SNEAK. Initial/min traceHP13.566668 recovers20;
  unequal activation injury prevents survival attribution. Capture332.467s/
  video338.466667s, SHA
  `f6b1e2f52ca9c678e7dbeceb6cffe38d1abab3469a80655c6555395e8cdbd910`.
- Review147564 naturally0 at22:01:47UTC (`logged-job-20261003-010100`), all16
  actual2s pages inspected/hash-journalled. OFF cycles submerged from roughly36s
  through338s. ON leaves24-32s, kills pig38-40s, gathers wood/crafts44-64s,
  smoker cooks64-82s, then hunts chickens102-120s. No sampled death/fire; both
  final chain disappearances retained. This is not every-frame/moving coverage.
- Actual ON film120-284s is a long return to the smoker, with route searches,
  wandering, refused takeoffs and terrain detours, NOT continuous hunting.
  It eventually cooks chicken286-300s, then resumes wood/animal approaches.
  The longest20.867s fixed-body hold overlaps the pig-to-wood/craft phase;
  next18.636s overlaps smoker cooking. Neither duration proves a new water
  regression. Retain `food-bank-trace-20261003-005036-end` and
  `cp1003-0054-t313`; later-return mechanism/baseline verification stays open.
- Read-only host157068 naturally0 at22:00:36UTC:37samples/no errors.
  The first immediate interval reads0%;36 subsequent30s intervals range
  50.04-79.70% aggregate CPU. No per-thread contention or past19FPS cause
  established; no services/useful tests/human windows stopped or hidden.
- Four healthy pairs of five attempted; pair1 invalid and pair4 food-positive
  control remain retained. Pair6 is prospective; water default=false.
  Budget10370MB of15GB/free168GB. Wiki37068107613 succeeds on0b52397c.
