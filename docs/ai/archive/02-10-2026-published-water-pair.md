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
