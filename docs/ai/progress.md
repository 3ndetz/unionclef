# Progress

Format: Investigate → Plan → Implement. Completed investigation history is preserved in
`docs/ai/archive/15-09-2026-clearance-and-survival.md` (488 lines before archiving).

## Current playthrough status

- Goal remains a complete natural `@gamer` playthrough, with visual observation and regression tests.
- Original one-high opening/low-ceiling ascent defect was fixed in the planner and final walker arrival. Latest audit: one_high 2/2, three head blocks planned and mined, grounded arrival, 20 HP. Flat/staircase/descend 3/3, no invalid runs.
- HEAD before this pass: 828f5a81, including iron-smelting fix 82d5d50e and documentation-only upstream fe28b686. Smelting matrix 6/6: missing output is produced; already-satisfied output leaves raw iron untouched. All videos decoded. No whole-game success claim.
- Natural survival-smelt-fixed reached a water bucket and descended to y28 for gold, but food collection preempted gold mining. No gold appeared in sampled inventory; an earlier commentary saying it had been mined was corrected. Both picks wore out; replacement iron pick was crafted after a temporary placement delay. Then the bot dug a staircase toward surface pigs, reaching y96 with 20 HP in all recorded samples.
- This run ended in a client crash at 17:55:13 UTC. Observer session44767 exited1; last sample707s at120.3,96,-148.3. Container automatically restarted, and the natural world is now disconnected. Do not report later progress from this stale JSON.
- Verified natural world backup before that run: workspace outputs/natural-checkpoints/20260915-173341-iron-kit.tar.gz, 56,062,872 bytes, SHA256 metadata adjacent. No inventory injection or world restoration.
- Open risks: general multi-mob/Enderman/skeleton survival, long surface food hunts from caves, waterfall partial-route termination, coasting/slab arrival, azalea pillar placement. Shield combat experiments failed and were reverted; their complete patch remains in workspace work/shield-combat-experiment.patch. Nether/End/full completion remain unvalidated.
- Push and Telegram publication remain blocked pending previously requested target-specific confirmation. No retry or alternate route.

## 2026-09-15 — Publish navigator results on the client thread

### Investigate
- Crash report: `BlockPathWalker.tickBFS` line523 reads path.size after the route was nulled concurrently. Docker events show normal container exit0/restart, not an OOM event. The application emitted a fatal exception first.
- The same-second log shows FastNavigator-plan entering “walking dead-ends (61.0 -> 58.0) -> physics owns the rest”, while Render thread starts a two-waypoint walk. That worker branch calls BlockPathWalker.stop directly.
- Full plan-result handling currently writes several navigator fields and rendering plans from the worker. It only checks active, so a result from an old route can also publish after stop/start reactivates the navigator. Baritone PathingBehavior serializes calculation publication and path ticks with pathPlanLock.
- Controlled old-jar reproduction: pause the client at a bounded barrier, start a short enclosed corridor route, wait for the actual worker to finish. The walker became inactive and the physics handoff counter increased1 while the client remained paused. This demonstrates the cross-thread mutation without crashing the client. First probe attempt had a Vec3d argument-type error and is not a pass.
- Artifacts: workspace outputs/walker-publication/baseline-probe.json and natural-crash.txt. All six natural run clips decoded.

### Plan
- Keep calculation on the worker; apply the complete result on the client thread so handoffs cannot interrupt a walker tick.
- Identify each calculation with a generation invalidated by cancellation. Discard queued stale results without clearing a newer calculation's planning flag.
- Validate real worker completion while the client is paused, then application after release. Retarget while old publication is queued and require its rejection. Repeat, then broad navigation/mining/placement and original one-high regression before resuming the natural world.

### Implement
- FastNavigator result handling extracted unchanged into applyPlan; worker queues publication through MinecraftClient.execute. Generation and world checks reject stale results. Applied/discarded counters are read by the new fixture. No extra null check in BlockPathWalker masks the race.
- New walker_publication_test uses a client barrier with a ten-second automatic timeout, a real short partial plan, and optional retarget. Fixture setup and restoration are bounded; it does not require a spontaneous crash to validate the handoff.
- Tungsten clean build and deployment succeeded; fixed-fixture and adjacent results are recorded below.

- First fixed publication test passed: no handoff while the client was paused, one applied handoff after release. Retarget test publication-2 FAILED: the old result was discarded, but stop() had never cleared planning, so start() could not launch the replacement and the stale result no longer cleared it. Added planning=false to cancellation; generation checks keep old completions from clearing newer work. This was a real implementation failure, not a fixture mismatch. Clean rebuild/deploy succeeded; the corrected matrix is recorded below. Final natural clip6 decoded successfully.

- Corrected binary passed 6/6 controlled publications: three ordinary handoffs and three retargets. All kept the walker active and handoff/application counters unchanged while the client was paused; each applied a real handoff after release. Retargets additionally discarded the queued old calculation. All six videos decoded. The broader navigation audit and matched follow-up are recorded below.


### Adjacent audit and newly isolated work
- The initial full nav audit was interrupted during nav_cliff after flat/staircase/descend passed. Steep failed twice at the same lip; gaps fell twice in each of the initial and refreshed runs. All five completed course videos decoded; the interrupted cliff recording was saved separately.
- A clean HEAD828f5a81 baseline and restored publication build were both run through flat, staircase, steep, gaps in the same order. Both passed flat/staircase/gaps and failed steep twice at (6.683,-60,0.5). Baseline gaps had no falls at23.2FPS; restored gaps had no falls at27.6FPS. Earlier publication-build falls remain recorded. No fall-rate improvement or regression is established; some flat/staircase FPS differed, so no timing comparison is claimed.
- Baseline build/deploy logs: /private/tmp/publication-baseline-{build,deploy}.log. Restored publication build/deploy: /private/tmp/publication-restored-{build,deploy}.log. Both clean builds and nested-jar checks succeeded. Current source and deployed binary contain the publication fix again.
- NavCliff had an invalid approach: its start pad stood four blocks below its first platform, without building materials. The fixture now derives both surfaces from start_y; a geometry check confirms both at y-49 and feet at y-48. Live corrected-course audit passed:21.2s,29FPS,20HP,no falls or freezes.
- Harness reporting incorrectly called an arena fall at26FPS a below14FPS result. Added invalid_reason, preserved the arena reason, and removed unsupported load/wear attribution from retries and summary. Replayed the production judging/reporting block with recorded fall samples, low-FPS samples, and an ordinary healthy failure: all three reported the appropriate cause.
- Next root cause, not yet fixed: FastNavigator returns immediately for every result shorter than two waypoints, including an incomplete dead end. Real client-thread FastPlanner.plan at nav_steep's (6,-60,0) toward (22,-54,0) returns one waypoint, complete=false, no physics flag. A bounded worker fixture with a one-cell corridor also applies that result (applied292→293) without any handoff. This differs from a complete one-cell answer where the goal is already satisfied. Fixture now supports --one-cell and --goal-here controls. The short-incomplete fix is pending; do not claim steep repaired.


### Publication fix accepted locally
- Original one-high ascent audit passed2/2, with all three head blocks planned/mined and grounded arrival at20HP. The video decoded and was visually inspected and shown locally.
- Hungry mining ambush passed:6 cobblestone,20HP throughout,145 mining samples,zero sword interruptions during continuous mining. Threat/target entities were gone; video decoded and final image inspected.
- Completed one-cell control applies the result without physics handoff, as required. Incomplete one-cell baseline and retarget controls both reproduce the missing handoff after successful result publication. These follow-up tests establish an existing bug; they are not a claim that it is repaired.
- All matched-prefix videos and the corrected-cliff video decoded. Harness-only fixes committed as58f9254e. The publication race fix is ready for a separate local commit, followed immediately by the short-incomplete-result pass. Full-game completion and general parkour reliability remain open.


## 2026-09-15 — Preserve incomplete one-cell results

### Investigate
- Publication fix committed as23d87137. Both original and restored binaries stalled twice on nav_steep at the same lip; explicit planner and worker probes above show the incomplete one-waypoint result is being discarded.
- Completion control passed before this change: one-cell complete result applied without any dead-end handoff. Retargeted incomplete baseline also reproduced the loss after rejecting its stale predecessor.

### Plan
- Return early for empty results and completed single-cell results. Feed an incomplete single-cell result into the existing incomplete-route handler, preserving its below-goal policy and physics ownership.
- Require actual steep-course passage, repeated worker/retarget and completion controls, adjacent navigation, and original clearance regression. Do not confuse a handoff with successful traversal.

### Implement
- FastNavigator now distinguishes complete from incomplete one-cell results. Clean build and verified deploy succeeded (/private/tmp/one-cell-build.log and one-cell-deploy.log).
- Recorded nav_steep trials and adjacent acceptance results are below.

- Actual nav_steep traversal passed3/3 at20.8/25.3/22.8FPS, without falls or freezes. First results now log a real physics handoff and arrival. The runner reuses a repeated course's media path; second/third clips were copied separately, decoded, and the second was visually inspected. Do not claim three distinct retained videos.
- Controlled matrix passed8/8: three incomplete one-cell publications, three retargeted ones (old result discarded), two completed-goal controls (no dead-end handoff). All eight separate videos decoded; control6 image inspected. These barrier tests establish result handling, not traversal rate.
- Adjacent audit passed7/7: flat/staircase/descend/water/break/wall2/bridge,28.0–29.5FPS,no invalid runs. Original one_high passed2/2 with three head blocks planned/mined,grounded arrival,20HP. All eight audit videos decoded; bridge and clearance frames visually inspected.
- Fresh natural-world backup before resume: workspace outputs/natural-checkpoints/20260915-185403-post-crash.tar.gz,56,063,656bytes,80tar entries,SHA256 9bfb283908e5f7537730b021772ba06abe6a8ef52e0e198afa03849d300873dc. Zero natural-server players confirmed, save-off/flush/archive/save-on with finally restoration. No world restore. Fetch completed with no incoming origin commits.

- Short-incomplete fix accepted for local commit. Next action: resume the preserved natural world with a new observer and inspect live progress, especially climbing, food acquisition and route handoffs. No whole-game completion claim.


## 2026-09-15 — Pillaring over interactive supports

### Investigate
- Natural survival-route-handoffs ran538.8s before explicit interruption/disconnect; all five clips decoded. It recovered a stone pick, climbed from y96 to the surface, obtained3pork at243s, crafted/placed a smoker and cooked3pork by325s. One portion was consumed. The earlier table placement delay resolved without intervention.
- Creeper blast at69.8s reduced health20→15.652112. Frames66/70/74 show RunAwayFromCreepers active and the blast; no fall attribution. The bot survived. Creeper retreat in constrained terrain remains an open risk.
- Search visualization coincided with0–2FPS and339–667 render objects at139–165s. FPS recovered26 before renderPathMoves was switched off. Keep trajectory drawing off for natural observation; no measured causal improvement is claimed. Block placement/break overlays remain enabled.
- After cooking, the bot repeatedly tried to pillar over its smoker at(80,116,-155), with body near(80.5,117,-154.3),491blocks unchanged, and the smoker GUI open while GetToEntityTask was active. This persisted for minutes. Final disconnect near(80.6,118.2,-154.2),15.652112HP; server state preserved, no world restore.
- PillarTask unconditionally released sneak before right-clicking. BlockPlaceHelper.tryPlace can return SUCCESS for opening a GUI, so its attempted-placement counter is not a world-placement count. Baritone MovementPillar:232 requests sneak near the apex;:265 waits for isInSneakingPose before CLICK_RIGHT. The already-ported movement contains this too; the live PillarTask primitive did not.
- Real baseline fixture: smoker and crafting_table both opened GUIs, placed0blocks and never arrived. Stone and vine controls both placed3actual blocks, arrived grounded, no GUI. All four valid videos decoded; smoker image inspected. Initial stone attempt hit a reconnect race and measured nothing; fixture now avoids reconnecting an already-connected client and confirms the world plus grounded position before measuring.

### Plan
- Request sneak during the real placement window and wait for the player's actual sneaking pose before clicking; retain existing jump/climb logic.
- Score server-side placed blocks, grounded arrival, health and GUI opening. Repeat interactive supports, ordinary stone, vines and blocked-headroom controls; audit navigation/building/one-high clearance before resuming the saved surface position.

### Implement
- PillarTask requests sneak near the placement apex and gates the click on isInSneakingPose. Added sneakWait to the existing failed-pillar diagnostic. New pillar_interaction_test supports stone/smoker/furnace/table, vine and ceiling cases.
- Clean Tungsten build succeeded; deployment is completing. Fixed tests pending; no acceptance/commit claim yet.

- Fixed pillar matrix: twelve valid passes (eight interactive-support trials including two off-center smoker starts; stone, two vine controls and one blocked-ceiling control). All successful climbs placed three server-side blocks, arrived grounded and opened no GUI. The ceiling control refused with zero placements. All thirteen fixed-attempt videos decoded; off-center final frame inspected.
- fixed-7 is retained as an invalid completion measurement: the fixture read body state before separately waiting for the active flag, so its last position predates completion. Server blocks, final screenshot and the grounded Pillar-done log agree. The collector now reads completion before body state; furnace repeat fixed-8 passed. No product change was made in response to this measurement error.
- The clearance fixture now schedules direct planner world reads and navigator startup on the client thread via MethodHandle/FutureTask, matching the live driver's ownership. Its live audit is pending below.

- Adjacent audit passed5/5: wall2/bridge/flat/staircase/descend,21.2–28.3FPS,zero invalid runs,falls or freezes. Original one_high passed2/2: all three obstructing head cells appear in the plan and are mined, grounded goal arrival,20HP. Client-thread clearance fixture executed successfully. All six audit videos decoded; clearance and wall2 final images inspected. Artifacts: deploy/runner/artifacts/20260915-192627 and workspace outputs/pillar-clearance.
- Assessment: formerly reproducible GUI-open/zero-placement failures now complete actual three-block climbs across interactive supports. This removes the isolated smoker stall mechanism with the existing upstream sneak protocol; no general survival or whole-game success claim. Next: resume preserved natural world and verify departure from the smoker position visually.

### Natural continuation after 237074ea
- survival-pillar-fixed completed900s without restarting gameplay between eight clips; all eight decoded. Starting80.6,117,-154.2 (old smoker), ending122.4,6,-182.6,19.8HP during skeleton defence. Minimum sampled HP12.652112; recovered to20 later. No death observed. The next observer survival-diamonds continues the same task without --start.
- The initial task changed to iron collection and left the old smoker without invoking that same pillar, so this is not a natural matched reproduction of the repaired interaction. Controlled fixture evidence remains the acceptance basis. Natural terrain pillars completed later.
- Collected6rawiron, smelted4 and crafted an iron pick. After more hunting, a costly return toward the old smoker gave way to constructing a new smoker at118,116,-153 and cooking5pork. Four cooked portions remained through the subsequent underground route.
- Descended to gold: transient food preemption near699s resolved autonomously by712s, with4cookedpork still present. Collected6rawgold by736.6s, smelted5 and crafted a golden helmet (inventory confirmed). No priority code was changed: a persistent preemption failure was not reproduced in this continuation. Approached diamond ore at129,12,-193; no diamonds yet confirmed at the observer boundary.

## 2026-09-15 — Do not finish a reach route over an unfinished bridge

### Investigate
- survival-diamonds acquired an actual diamond pickaxe, diamond sword and two spare diamonds, then died from a fall at19:49:31UTC. At192.7s body153.1,-23,-189.9 was falling at-1.25 with MLG active; next sample was the respawn. Explicit disconnect followed, observer interrupted, all3clips decoded. Natural world was not restored. Frame65/65.5 of clip2 shows mining at the cavity lip; frame66 shows a movement queue,66.5 the fall,67 MLG waiting with sword still held.
- At19:49:29 MovementQueue accepted151,-11,-191→152,-11,-190; at19:49:30 FastNavigator announced arrived(1.3), followed by a no-floor report at152,-13,-190. Post-run server block checks report destination support152,-12,-190 as air. The chunk was not entity-ticking; no forceload or world edits were used for these reads.
- The reachBlock arrival branch bypasses settledBody entirely. More importantly, a grounded player can be sneaking beyond the floor edge while building: stopping then releases sneak before a support is placed. MovementTraverse.safeToCancel already rejects that state, but MovementQueue exposed no corresponding query and FastNavigator never asked it. Baritone PathingBehavior:153 obtains safeToCancel from PathExecutor; PathExecutor:194 reads movement.safeToCancel.
- Controlled baseline-2 (open gap) ended navigation in midair, placed0 and fell. Roofed baseline-roof-1 forced a bridge, ended while still flagged onGround at3102.4,-29,1040.5 over its missing support, then fell;0blocks placed. First baseline-1 failed before gameplay because reflection chose the wrong start overload; fixture now resolves BlockPos explicitly and prints the subprocess error. Repeats recorded below.

### Plan
- Keep every arrival behind the settled-body condition, including reach goals. Ask the current movement whether its inputs can be released safely, preserving its existing construction contract.
- Require roofed bridges and open-gap landings repeatedly, plus ordinary movement, building, mining/interaction and original one-high regressions. MLG rescue failure remains a separate unresolved issue; this pass prevents premature route completion.

### Implement
- MovementQueue exposes the current movement's existing safeToCancel predicate. FastNavigator includes it in its existing arrivalNeedsSettledBody gate and applies that gate to reach goals too. Build and fixed tests pending.

- Both old-binary variants reproduced twice (open2/open3, roof1/roof2); all4valid baseline clips decoded, roof frame inspected. First fixed binary clean build/deploy succeeded. Roofed fixed-1 passed with1actual support,grounded arrival,no fall. Open fixed-2 still fell, this time while navigation remained active: arrival cancellation was fixed but did not explain that entire path. Failure retained and not accepted as an all-green matrix.
- A client-thread planner probe in open-diagnostic returns the correct3-waypoint reach route with1placement, while the real initial navigator dispatches a walking prefix and falls. Source explains the discrepancy: start(target, block) called default start(target), which immediately invokes planAhead and captures reachBlock=null; only afterward did it assign reachBlock. startExact and predicate starts used the same late-assignment structure. This is deterministic initialization ordering, not a worker timing hypothesis.
- All start variants now delegate to startWithGoal, which cancels the predecessor then assigns every goal field before launching the first plan. Exact-from-drive identity is initialized there too. Second clean build/deploy and complete matrix pending. Fixture now records the reference planner result and can disable the existing arrivalNeedsSettledBody flag for an isolated cancellation control.

- Final goal-initialization binary passed9/9 interleaved trials (3open,6roofed), each with one actual support, grounded reachable arrival and20HP. Disabling arrivalNeedsSettledBody on that same binary reproduces the unsupported fall with0placements. All10matrix videos decoded; final roof frame inspected.
- Adjacent audit passed7/7: bridge/wall2/gaps/flat/staircase/descend/water,17.0–29.3FPS,no invalid runs. Original one_high passed2/2 with all3head obstructions planned/mined and grounded20HP arrival. Buried and open-table controls both opened the preserved table at20HP; client-thread collector executed successfully. Hungry mining threat passed with6cobblestone,20HP and zero pick-to-sword switches during continuous queue mining.
- Retarget barrier passed (stale result discarded, replacement applied), completed one-cell control passed without physics handoff. All13audit videos decoded; clearance frame inspected. Artifacts: workspace outputs/reach-audit and deploy/runner/artifacts/20260915-201049. This pass is accepted locally; next focus is the separate natural MLG rescue failure, with controlled falling trials before more natural play.
