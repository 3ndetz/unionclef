# Progress

Format: Investigate → Plan → Implement. Completed investigation history is preserved in
`docs/ai/archive/15-09-2026-clearance-and-survival.md` (488 lines before archiving).

## 2026-09-17/18 — the bread deadlock fixed from a checkpoint (G103), G104 found

- **G103 (the 46-minute bread/crafting-table loop)** root-caused by resuming the `loop-bread`
  checkpoint and reading the live task tree: `BeatMinecraftTask`'s "Picking up the crafting table
  while we are at it" (line 1688) returns `MineAndCollectTask(CRAFTING_TABLE)` every tick when the
  bot lacks a table item and a placed one is on the way, preempting the flow that would craft a
  fresh table; the break false-fails on the survival world, so the reclaim never gets the table and
  the run churns ("Blacklisting extra crafting table" ×971) with four logs in the pack. Fix: reclaim
  only when the pack cannot cheaply make a table (four planks / a log), and drop the reclaim flag so
  it stops re-arming — `DoStuffInContainerTask` crafts a fresh one (G80's "a table is four planks").
  Verified from the checkpoint (the point of having one): before, 971 blacklists over the loop;
  after, two 5-minute resumes read 0, and the bot forages + mines with items 63 → 104. Commit
  `fce9331b`. (Correction: the "reclaim=0" figure first used was invalid — the reclaim's debug-state
  string is not written to `latest.log`; the valid log-based metric is `blacklistExtra`, 971 → 0.)
- **G104 (found by the regression run, not a regression of G103):** a fresh opening reached stone
  tools at 66 s then spent eight minutes on a stone-sword craft frozen at (885,62,648),
  `cb=0/1/53532/0` (the block scanner rejecting every cobblestone candidate), self-clearing at
  t=554. Shield bench passes, so the craft-grid carousel itself is fine; this is a survival-world
  interaction of the same family, occasional and self-resolving. Recorded, `run4-end` near it.
- Checkpoint tooling now the standard debug loop: `checkpoint.py save/restore/list`,
  `gamer_smoke.py --from NAME` / `--checkpoint-every N` / `--save-end`. On disk: post-run2,
  cp0916-2241-t900/t1849/t2800, run3-end, loop-bread, run4-end.

## 2026-09-18 — G100 fixed (dig up for air), closed-loop run

- **G100 (drowned mining diamonds at y=0):** `GetToAirTask` only searched for the nearest EXISTING
  reachable air; in a stone-capped flooded pocket there is none, so it did nothing and the bot
  drowned. Fix: while submerged, scan the column upward and DIG the first solid cap over the water
  (a `DestroyBlockTask` aimed up), climbing to the surface a block at a time; bedrock/sideways-only
  falls back to the old lateral search. Commit 4d215874, release 0.95.5.
- Verified from a purpose-built bench, `air_pocket_test.py` — and the bench itself taught two
  lessons (both now guarded): the survival chain only ticks while AltoClef is RUNNING, so the
  faithful test runs `@gamer` (an idle `@stop` bench never fires the air-seek and looked like the
  fix was dead — onTick=0); and a drown+respawn reads as "reached air" unless arrival is checked
  near the shaft. Under `@gamer` the bot dug both cap blocks (dig=2, cap computed at the lid) and
  surfaced with 16/20 hp in ~14 s; before, it drowned every time. Diagnostic method: the debug-state
  string ("Reaching breathable air") is NOT logged, so a real `Debug.logMessage` was needed to
  measure the dig firing — the same "grep the log for a debug-state string" trap paid for in G92/G103.
- Regression: nav_water PASS (baseline); the change is isolated to the submerged-no-air branch, which
  no nav course exercises. Full nav suite run as confirmation (nav_gaps hit its known void-fall flake,
  retried) — nav_water PASS and every gate course green through 9/14 (only nav_gaps's known void-fall flake, which retries); confirms the submerged-only change is regression-free.
- **G93 (a buried pickaxe DROP beat crafting one):** FIXED same day, v0.95.6, commit 1b2ff693.
  `CraftInTableTask` inherited `ResourceTask`'s pickup-before-craft with the default
  `getPickupRange()==-1` (unlimited): `ResourceTask.onTick` (line 184) returns a
  `PickupDroppedItemTask` for ANY drop of the craft target whenever `range < 0`, no cost check, so a
  copy buried ten blocks down outranked a craft from planks in hand (the third 60-min run opened with
  seven minutes of digging for it). Its sibling `CraftInInventoryTask` already returns 0 there ("this
  shouldnt pickup items"); the table craft never got the override. Added it — a table craft crafts, it
  does not detour to scavenge a distant/buried copy; ordinary pickup paths still collect drops when a
  pickup is the goal. Verified `deploy/runner/buried_drop_vs_craft_test.py` PASS: two logs + two sticks
  in the pack, a `wooden_pickaxe` dropped in a sealed pocket 8 blocks down, `@get wooden_pickaxe 1` →
  crafted in 7.9s, min_y=-60 (surface, floor top -61), never descended. Same build: full nav suite
  14/14 PASS (0 gate failures, 0 invalid) + G100 air bench PASS (dig=2, surfaced) — no regression.
- Next targets: G90 (tunnel per-cell exec + pricing), G94 (snow-step), G96 (reach:armed nav=false
  shimmy). G101 (death = base loss) tied to the deferred night track.

## Current playthrough status

- Goal remains a complete natural `@gamer` playthrough, with visual observation and regression tests. Nether/End and full completion are not validated.
- Original one-high opening/low-ceiling ascent remains fixed: latest audit 2/2, all three head obstructions planned and mined, grounded arrival, 20 HP. Adjacent navigation 7/7 passed.
- Submerged/air recovery is committed and validated after merging origin/main2f027a41. The current retreat-region change has completed its controlled and adjacent audits.
- The latest natural run preserved diamond tools and armor17, acquired ignition materials and advanced through caves, then starved to10HP while prioritizing optional resources over unknown food. The client is disconnected alive; no world restoration, inventory injection or time change.
- Newest verified backup: workspace outputs/natural-checkpoints/20260916-034354-food-pursuit-stop.tar.gz,57,272,367bytes,80entries,SHA25665ddbdf2b3e87049f1d9ece16ff8cda57800858b1944fa4a2c59f5d929938f93. Natural client remains disconnected at(-10.5,43,-389.6),10HP,armor17.
- Cave food exploration and false world-change placement bans now pass controlled and adjacent audits; natural validation is next. Open risks include intermittent bridge/physics jump falls, multi-mob combat, planned deep descents, partial-water/slab transitions and full-game completion. One bridge failure in the intermediate audit remains recorded; the water patch does not claim to repair it.
- Push and Telegram publication remain pending the existing target-specific confirmations. No retry or alternate route.

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


## 2026-09-15 — MLG landing surface must keep partial collision blocks

### Investigate
- Natural death target153,-44,-191 is deepslate_tile_stairs (server read-only block query); above is air. Water bucket was present in hotbar2 before and during the fall, with a shield offhand.
- The clutch ray hits a collision shape, but placeMLGBucketTask then uses WorldHelper.isSolidBlock to move partial blocks down one cell. This wrapper is the vanilla full-solid check and explicitly excludes partial supports. The ensuing reach ray hits the original stair/slab and cannot reach the block below it. Upstream MovementFall does not discard partial landing surfaces this way.
- Initial solid30/pack33/edge45 probes used a real bucket and survived, but the first slab/stair probes exposed a measurement gap: sampled health was20 after fast respawn, even though the server logged fatal falls. Added a server deathCount objective; no rescue is accepted after a death. Damage control without a bucket loses9HP from12blocks. The no-water slab/stair attempts remain failed, not accepted.
- Corrected stairs-death-2 records1server death, zero bucket use and MLG active. Further baseline controls are pending.

### Plan
- Keep all nonempty collision landing surfaces as the click target. Retain the down-one adjustment only for collisionless hits such as fluids. Do not change late-equipment timing without evidence.
- Test stairs/slabs repeatedly, solid/pack/offset controls, and actual water placement plus zero deaths. Build/deploy only after baseline trials stop.

### Implement
- Landing normalization now checks collision-shape emptiness. This edit is not built or accepted yet.

- First shape fix clean build/deploy succeeded (mlg-shape logs). Bottom slab fixed-slab-1 passed at28FPS:1server bucket use,0deaths,20HP. Stairs fixed-stairs-1 still died at25FPS despite1bucket use. This is a real remaining failure, not accepted as repaired. Waterlogging a stair does not put fluid above its upper collision surface.
- A west full-block bank beside the stair floor still failed on the shape-only binary:29FPS,1use,1death. The bot keeps steering toward the stair instead of the available bank. Direct vanilla Waterloggable bytecode confirms the bucket fills WATERLOGGED in that same block; FluidState exposes source fluid height.
- Added one shared waterCushionsLanding predicate to candidate selection and the placement gate: for fluid-fillable blocks compare collision maximum height against source-water height. Bottom slabs remain candidates; high waterloggable supports are rejected while searching for a reachable alternative. The additional height filter only applies when a usable water bucket is present. Second clean build succeeded8s; verified deployment is starting. Tests pending.
- Probe now stores server use/death counters and median FPS. Initial solid-control-1 missed a brief empty bucket between pour and pickup and was correctly not accepted; solid-stats-1 confirms1server use and0deaths. All11pre-first-build clips decoded. Current fixture also gates final X/Z inside the arena so respawn at world spawn cannot count as landing.

- Surface-model west-bank and slab probes passed3/3 each so far, with1use,0deaths,20HP. All west runs refill the bucket, while all three slab runs finish holding an empty bucket. The slab water is recorded at support.up(), and pickup accepts only Blocks.WATER, so both producer and consumer miss the actual waterlogged source.
- Added a shared waterPlacementPosition calculation for placement prediction and pickup position, plus source-fluid recognition in MLGBucketFallChain. Added --require-refill to the fixture. These follow-up edits are not yet built; the current rescue matrix is still running on the previous surface-model binary.

- Surface-model matrix completed6/6 west-bank escapes and6/6 bottom-slab rescues,25–29FPS,1use each,0deaths,20HP. West targets/landings are actually on the available bank (first target3199,-61,1101; final3199.4,-60,1101.5), not on distant surrounding grass. All slab runs end with an empty bucket; model-slab-6 explicitly records0water buckets in the inventory. Pack33 and offset45 controls also passed with the bucket refilled; wall-bank control still running. The conservative water-height check may reject other high partial shapes; no universal clutchability claim.
- Fetch found no incoming origin/main commits. Publishing remains pending the existing target-specific auto-review confirmations; no retries.

- Refill build/deploy succeeded10s. refill-slab1..3 passed with the bucket restored; refill-slab4 is a real failure at28FPS:rescued,0deaths,20HP,but0filled buckets. The batch stopped before navigation audit. All first three/four artifacts are retained, not counted as6/6.
- MLGBucketFallChain unconditionally clears lastMLG at the end of every non-pickup priority tick, defeating its existing4second recovery window if source/inventory confirmation is not available immediately on landing. Retain an actual attempted placement through that existing window; still clear no-attempt falls immediately. This affects doneMLG consumers (food/combat), so the final audit must include hungry mining defence and confirm completed recovery releases the chain. New build/tests pending.

### Acceptance
- Window-retention build/deploy succeeded10s (mlg-window logs). Final matrix passed6/6 slab rescues with refill, plus west-bank/east-bank/pack controls:9/9,26–29FPS,one server bucket use,zero deaths,20HP,one filled bucket afterward. An additional diagnostic trial passed at27FPS and confirms attempted source3202,-61,1100 plus recovery_released=true; the final collector and release gate are live-tested. All10final videos decoded; final slab frame inspected. Earlier refill-slab4 failure remains recorded.
- Adjacent audit passed5/5:flat/staircase/descend/bridge/water,22.3–29.3FPS,no invalid runs. Original one_high passed2/2 with all3head blocks planned/mined,grounded20HP arrival. Reach-bridge control placed its support and arrived safely. Hungry mining defence passed with6cobblestone and zero mining-to-sword interruptions; final health17HP after combat, not20. All8audit videos decoded. Artifacts: workspace outputs/mlg-fall,outputs/mlg-audit and deploy/runner/artifacts/20260915-205835.
- Assessment: the original partial-support clutch refusal now gives way to actual bottom-slab rescue or steering to a nearby safe bank; high waterloggable supports are not mistaken for protection. Water returns to the bucket, and the existing recovery window survives late confirmation. These controlled results do not prove a replay of the natural death or arbitrary falls. Planned deep descents (G3), combat reliability and full-game completion remain open. Save locally, back up the current natural world without restoration, then resume observation.


## 2026-09-16 — Natural playthrough after bridge and MLG repairs

### Observe
- HEAD8d30f01a resumed the preserved natural world after a verified backup (20260915-210833-post-diamond-death.tar.gz, SHA25646091ee8043adf9d7eb5912509a919c3ccc2f18973ff1f8d50cfb55227dc454c). No world restoration, inventory injection, teleport or time change.
- survival-mlg-fixed completed900seconds, ending88.3,33,-11.5. It recovered stone tools, an iron pick, shield and a filled water bucket. A table/furnace placement delay resolved without intervention. Eight clips decoded; tunnel, crafting, water and final mining frames inspected.
- Health fell20 to17 during the skeleton encounter around639seconds; clip6 at12seconds visibly shows close combat. It remained17 through the final896.9second sample. This establishes temporal association, not the exact damaging hit. No death observed.
- Continued the existing task without restarting it as survival-gold-continued. By132seconds it had mined/smelted gold and held a golden helmet in inventory, then descended toward diamond ore49,11,-56. The filled water bucket remains present. This continuation is still live; no diamond or whole-game success claim yet.
- Latest fetch has no incoming origin/main changes. Publication remains pending the existing target-specific confirmations; no push or Telegram retry.


## 2026-09-16 — Submerged roof traversal

### Investigate
- The continuation acquired a diamond pick and one remaining diamond by420seconds, then drowned at21:32:57UTC. Server log confirms drowning. At458–471seconds the body remained33.0,6.2,-59.5 under a stone ceiling while FastNavigator repeatedly refused the water leg. At471seconds health was7; next sample was the respawn. The natural client was explicitly disconnected, then the observer interrupted. All5continuation clips decoded, underwater frame inspected. World not restored.
- MovementQueue admission lacks a cardinal downward stroke, though dispatch already constructs MovementSwim for liquid edges. Baseline dive1/2 both produce a complete three-cell downward plan, then109short-prefix refusals, zero off-route refusals, no arrival and drowning damage at29FPS.
- Baseline roof1 produced a complete route with a dive, horizontal crossing under a lower ceiling and surfacing. Initial sinking bypassed the first dive, so qShort stayed0. The first horizontal MovementSwim repeatedly failed: actual feet hovered around-55.4 while the destination's modeled feet were-56 beneath ceiling-54. Movement.update applied JUMP after the swim update whenever y<dest.y+0.6. That extra lift makes the body too high for the route.

### Implement and pending validation
- Admit all six cardinal liquid strokes; retain existing land edge admission. Extract base water inputs into an overridable method preserving land behavior. MovementSwim allows the extra0.6lift only when the destination body envelope fits, otherwise controls depth at the planned feet height and actively sinks when above it.
- Clean build succeeded8seconds; nested deployment verified. First fixed-dive1 entered the target cell at-56.4 but the fixture required an unnecessary-56.6; after navigation completed it waited underwater and damaged the bot. Retained as a failed measurement, not product acceptance. Fixture now scores target-cell entry; the full exit uses actual submersion and300air instead of requiring the entire body to float above water. Raw player reads run on the client thread.
- First corrected short measurement reached the goal healthy but sampled9.5FPS, so it is invalid. Recording now warms up in spectator mode before a fresh survival start. Warm dive1 passes27FPS, no refusal,20HP. Full exit and repeated/adjacent audits remain pending. Baseline videos all decoded.
- Separate open survival-policy problem: WorldSurvivalChain only presses jump; it does not choose reachable breathable air when a roof blocks surfacing. Do not claim the movement fix alone solves arbitrary underwater resource pursuit.


### Reachable-air recovery and acceptance
- The swim-only binary passed six warm dives and six roof traversals at 26.5–29 FPS, all healthy. However, an automatic survival-policy baseline still drowned at 29 FPS despite an available route: holding jump cannot choose an exit behind a roof.
- FastPlanner now supports a bounded condition-goal search over its existing movement graph. FastNavigator initializes that goal before dispatch, publishes on the client thread with existing generation/world guards, and rejects incomplete condition searches rather than sending a placeholder start to physics.
- GetToAirTask requests a reachable standing-body cell with breathable eye height. WorldSurvivalChain preempts resource/combat activity below half air and retains recovery until oxygen is full, then releases the original task. Failed searches are rate-limited; the task uses ordinary navigation rather than a scripted escape trajectory.
- Clean build succeeded in 9 seconds; nested deployment verified. Final automatic matrix passed 6/6 (three ordinary exits, three with a nearer unreachable decoy pocket), 25–29 FPS, 20 HP throughout, 300 air. Separate release-gated trial passed at 29 FPS. Two open-water controls passed; a final explicit dive passed at 14 FPS. The latter is a validity-floor sample, not a performance comparison.
- Working-task interruption passed 3/3 at 29 FPS: a resource task was active before escape, GetToAirTask took priority, air reached 300, health stayed 20, and recovery released. The resource task targeted old fixture stone at 3105,-29,1040, so these trials establish task/navigation interruption and resumption, NOT physical mining of the newly installed underwater floor.
- Adjacent final audit passed 7/7 (bridge, water, flat, staircase, descend, wall2, gaps), 22.7–29.3 FPS, no invalid runs. Original one_high passed 2/2, all three head blocks mined, grounded 20 HP arrival. Reach-bridge placed its support; retarget discarded the stale plan; slab MLG used one bucket, zero deaths, recovered the bucket and released recovery. Hungry mining produced six cobblestone with zero continuous mining-to-sword interruptions; final health 14 HP, not 20.
- All final matrix/audit clips decoded. Decoy exit, air recovery and working-task frames inspected. Artifacts: workspace outputs/submerged-route and outputs/air-audit; navigation deploy/runner/artifacts/20260915-220448.
- Failed/invalid evidence retained: first dive fixture demanded unnecessary depth after successful goal-cell entry; corrected short trial ran at 9.5 FPS; intermediate bridge audit fell at 27.4 FPS and respawned. A later bridge pass at lower FPS cannot establish a repair. Neither arbitrary sealed caves nor the original natural death have been proven survivable by these controlled tests.
- Incoming origin/main de9c6adc exposes pillar clearance with unchanged 0.05 default; 2f027a41 names/documents the unchanged entity haul cap. Both reviewed for local integration; post-merge build and focused live controls pending.

- Post-merge acceptance (HEAD0ec47b74): clean build9s, nested deployment verified. Stone and smoker pillars each placed3blocks without opening a GUI; hungry mining passed6cobblestone with zero continuous-queue pick-to-sword interruptions, final17HP. Decoy-air escape passed, original one_high2/2 passed in6.2/7.3s with3head blocks removed and20HP. All5saved videos decoded, clearance frame inspected. Resuming preserved natural world next.


## 2026-09-16 — Natural observation on merged air-recovery build

- Started the preserved gamer world on b191422f after server login verification, without restoration, inventory injection or time change. Observer survival-air-fixed is still live; do not build during it.
- The bot pursued an old iron pick at57,13,-44. At33.4s it was submerged at68.5,49.5,-50.6 and GetToAirTask took over. At39.5s it reached the surface at62.3, replenished air, then resumed the main task. First video frame39 inspected: actual surface air recovery with full health. This validates a natural-water recovery, not a replay of the earlier roof death.
- Repeated dives could not reach the pick35blocks below the lake floor. Existing40second budget and25second no-closing checks did fire, but repeated attempts/wandering consumed several minutes. Server later confirmed no item within4blocks of the target; a client-thread tracker read held only a clay drop. By303s the bot switched to wood. The delay mechanism is not yet established, so no speculative blacklist change was made.
- First sample at1.8s was19.833HP, then20; the earlier conversational shorthand that every first-five-minute sample was20 was imprecise. Health stayed20 in the observed water recovery. At520.2s it fell to19 while pursuing a pig; exact damage cause unproven.
- By424s wooden pick; by474s stone pick and sword; by584s five raw pork, coal and wood. Smoker placement delayed but resolved without intervention; by716s five cooked pork in inventory,19HP. Container re-approach on uneven terrain and repeated pickup pursuits remain efficiency risks. First six completed clips decoded; mining and air recovery frames inspected. Observation continues.

- First observer completed900s; all8clips decoded. Health fell at835.9s during container approach (20 to17.833) and886.9s during zombie approach; finished899.1s at70.2,95,-277.7,18.833HP. Continuation started without restarting the task; do not assume zero recording gap.
- survival-air-continued reached cooked pork12, cooked chicken4, mutton5, flint and steel, one obsidian and wool5. It recovered20HP and started iron acquisition, but creeper retreat interrupted. At211–230s retreat20 remained active around98,86,-253; at236s switched to10;242s16.8HP,248.8s10.233HP,256.6srespawn. Server confirms tester1 was blown up by Creeper at22:48:10UTC. Natural world explicitly disconnected, observer37626 interrupted(exit130). All3continuation videos decoded;242frame inspected.
- Next focused investigation: FleeLive computes a point from the centroid of all supplied dangers, while RunAwayFromCreepersTask supplies every tracked creeper. Actual route logs show17–21danger points. A far cluster can pull that point through/toward a nearer threat, and target() does not require its own reached() predicate to hold. This is a source-derived hypothesis pending a controlled baseline. Large repeated physics drift at replay tick7 is also present; preserve as a separate execution issue rather than assigning every failure to the centroid. Filtered natural log retained in outputs/survival-air-continued/creeper-death-log.txt.


## 2026-09-16 — Retreat goals and escaping an existing creeper exclusion zone

### Investigate
- Installed-baseline FleeLive probe: single threat produced a safe endpoint, but close6 plus six threats20blocks away produced a target at the close threat; its own reached() returned false. Opposed6 also returned an unsafe point. Artifact: outputs/flee-geometry-baseline.json.
- Installed-baseline condition search on a flat arena found safe paths for single6, clustered6 and opposed6. With a creeper3blocks away it returned complete=false and only the start node: the destination-only5block veto sealed all first steps, including steps outward. Publish and plan ran in one client-thread MethodHandle composition; no race with the danger publisher. Artifact: outputs/flee-planner-baseline.json.

### Implement and pending acceptance
- FleeLive is now an exclusion region, with immutable danger snapshots for worker searches and live completion checks. Its driver uses the existing nearest-condition FastNavigator path. Point goals remain on their existing path. Creeper edge safety checks the whole node segment: outside cannot enter the exclusion ring; already inside may increase separation without first moving closer.
- First clean build10s/deploy verified. The same graph probe now returns a complete outward route for inside3 and preserves the other3controls. Actual first pilots passed: cluster, opposed, frozen inside3, AI single, AI close, open-sided slope and confined slope. Each had0server deaths,20HP and actual safe separation,17–27FPS. Frozen inside started exactly3blocks away; AI close first sample was4.22 after initial movement, not3. Open-sided slope barely gained height, so the additional confined slope requires real ascent to-53.1 or above. These are pilot samples, not a measured pass rate.
- Final source refinement matches the hostile-retreat retention radius (requested distance+2) for creepers, retaining occluded near threats but excluding distant loaded ones. Region driving clears the previous resource point so a separate stuck escape cannot inherit it. Final clean build/deploy and repeated/adjacent acceptance are pending.
- Newest verified natural backup: outputs/natural-checkpoints/20260915-230443-post-creeper-death.tar.gz,56,296,799bytes,80entries,SHA25656e985d244d944baf0f200870db695776a7084543f4bfbaeb0a4e8112077e0ac. Zero natural players during backup; save-on restored. No rollback.

### Normalized final retreat matrix
- Final clean build9s and nested deployment verified. Initial final-slope-2 failed the height gate while reaching safe distance15.3,20HP,0deaths. AI wandered to11.55blocks during warmup and the short corridor allowed a flat bypass. All four initial final-* clips decoded; the failed frame is retained and excluded from normalized rates.
- Fixture now freezes AI during warmup, resets the player before activation, validates initial separation and extends corridor walls behind the start. Confined arrival requires3blocks of real ascent. Equipped controls use ordinary dig/build permissions, stone tools and32blocks.
- Final normalized matrix20/20: active close creeper6/6, active confined ascent6/6, frozen cluster/opposed/inside2each, equipped close and equipped ascent. Every run:20HP,0server deaths, safe separation, region routing observed, valid initial separation; median FPS16–29. Frozen inside starts3blocks away; active positions are measured. All20videos decoded; close and staircase frames inspected. Artifacts: workspace outputs/flee-region/stable-*.
- Adjacent audit is running; shared routing change remains uncommitted pending completion. Latest fetch has no incoming changes. Natural executor drift and fuse-aware route cost remain open; controlled passes do not prove natural survival or the End.

### Retreat adjacent acceptance
- Final adjacent batch exited0. Navigation7/7 passed at22–29.3FPS with no falls or invalid runs (deploy/runner/artifacts/20260915-232159). Original one_high2/2 passed6.2/6.5s, three head blocks planned/mined, grounded20HP arrival; recording shown to the user.
- Decoy-air and working-task air recovery passed29/28FPS,20HP,300air and recovery release. As previously, the working control establishes task interruption/resumption, not mining the new underwater floor. Slab MLG at33blocks with shield passed23FPS, one bucket use, no death,20HP, bucket recovered and recovery released.
- Hungry mining passed with six cobblestone, zero continuous-queue sword interruptions, final17HP. Occluded hostile retreat12and30 both passed, each with nine predicate controls; no FPS rate is inferred from that older fixture. Retarget rejected the queued stale publication and applied the new one.
- All adjacent clips decoded. Air and staircase frames inspected. Syntax and diff checks clean. Artifacts: workspace outputs/flee-audit. Local commit follows; natural-world verification is the next pass. Neither fuse-weighted routing nor repeated natural physics drift is declared solved.

## 2026-09-16 — Natural run on accepted retreat9b0166d9
- Verified gamer-server login, then observed900seconds without restoration, inventory injection or time changes. Observer14646 exited0; all8clips decoded. Artifacts: workspace outputs/survival-retreat-fixed. Continuation29734 records the existing task without --start; do not assume zero recording gap.
- At46.7s health20to19.6667 at83.3,124,-28.5 on the cliff; exact cause unproven. Pillager defence was visible at52/59s, then coal acquisition at108s with20HP. No later sampled damage or respawn in this first observer.
- By160s8coal/2rawiron; crafting-table placement delay resolved by185s without intervention. Shield by227s, iron pick by323s, two buckets by424s. Repeated workstation placement delays resolved; no speculative placement change made.
- Water pursuit around481s had transient incomplete physics paths, but mining advanced by494–527s. Water bucket acquired before584s. Descended from98to28through mined shaft without damage; eight raw gold by775s, golden helmet by874s. At894.8s72.7,6,-65.5,20HP, pursuing diamond80,3,-54.
- Visually inspected cliff digging, tight-mine placement, furnace GUI, water-route search and shaft descent frames. This natural run has not yet exercised the new creeper retreat, so controlled20/20 remains its direct evidence. Whole-game completion remains open.

### Natural continuation and next food-search blocker
- Continuation29734 completed900seconds, all8clips decoded. Every sample20HP; no respawn. Diamond pick/sword by93s, leggings by208s, boots by486s, chestplate by679s (armor17). Workstation delays resolved without intervention. Frames74,353,616 inspected.
- At729s CollectFoodTask(220) selected Searching/TimeoutWanderTask at31.3,-6,-142.7. Through897.8s the bot remained within the same small chamber, repeatedly logging Failed exploring. Last31.5,-5,-142.2,20HP. Client explicitly disconnected after observation; no world restoration or inventory change.
- Verified backup outputs/natural-checkpoints/20260916-000244-underground-food-stall.tar.gz:56,330,745bytes,80entries,SHA256030b13d0d0aa19782028a7df2cadfea24ef5d25113ec2b75af1909ba6e86c4f3. Zero natural players; save-on restored.
- Source: no known food falls through to TimeoutWanderTask. Its ordinary exploration targets the current cave-height band and uses physics pathing; its dig/build surface recovery only triggers if all four cardinal neighbours are solid. The natural chamber has open neighbours, so it keeps choosing exploration without a surface-search objective.
- New controlled baseline uses an open-neighbour chamber under8or20blocks of stone, with a diamond pick and64blocks, and actual CollectFoodTask. First four trials failed to emerge at29FPS,20HP; repeated baseline still running. No production food-search change yet. Proposed focus: when no food option exists underground in the Overworld, give ordinary dig/build navigation a stable dry-surface target; keep local food options ahead of that and ordinary surface exploration afterward.

### Food surface implementation, first failure and release correction
- Baseline completed6/6 without emergence (three8block and three20block roofs), all29FPS/20HP; all6clips decoded and deep-chamber frame inspected. RCON read-only checks confirm natural open neighbours at31,-6,-143 and32,-5,-142, so enclosed-body recovery is not the applicable path.
- CollectFoodTask now selects a stable dry surface in loaded nearby columns when no food option exists underground in the Overworld. Heightmap excludes leaves; support height, full body fit, fluid and reachability policy are checked. Existing GetToBlockTask owns digging/building. Local food remains earlier in selection; ordinary exploration resumes at the surface. The food amount and Nether policy are unchanged.
- First build/deploy succeeded8s. Shallow pilot failed at26FPS: it climbed from-58to-51 and opened the roof, but retained original target-50 after excavation lowered its support. Repeated no-progress followed. All health20; failed clip decoded and frame inspected. The old fixture also demanded the original terrain height, which is not a valid oracle after digging; neither issue is hidden as a pass.
- Corrected retention checks the current column before keeping the old route. Rebuild8s and nested deployment succeeded. Fixture now reads actual surface height on the client thread and requires both exposure and release of the surface-search task. New pilot batch1177 is running; no food-surface acceptance or commit yet.


### Pillar short-window investigation (2026-09-16)
- Food release pilot failed under a low chamber roof: every available placement tick waited for sneak pose (18/18), with zero clicks/placements and no stolen jump. A separate food run took mined stairs and emerged at the measured surface-51 with20HP/28FPS, releasing the surface task. This is one successful route, not food-policy acceptance.
- Isolated one-rung pillar from-60 to-59 with support-61 and roof-57 reproduced zero placement in6/6 old-binary trials. Videos decoded and a frame inspected. These original probes did not collect FPS; no timing comparison is claimed. The repeated trace matched the chamber failure: apex-58.8, sneakWait18, placeAt18, jumpStolen0.
- ClientPlayerEntity bytecode computes cached sneaking pose before input.tick. PillarTask cleared sneak each tick and only requested it after placement clearance, near the apex. The short window closes before the pose gate observes the request. Baritone MovementPillar also requests sneak while grounded, unlike the incomplete port.
- PillarTask now holds sneak through the centred jump, retaining actual-pose, clearance, ray and placement gates. Existing fixture gains a low-roof one-rung case and FPS recording. Clean Tungsten build succeeded in4s; deployment/acceptance in progress. No accepted fix yet.

- Early-sneak binary passed6/6 low-roof rungs with actual server blocks, grounded arrival and20HP (19.5–29FPS). Stone, smoker, furnace, crafting table and vine three-rung controls passed. Blocked-ceiling control behaved correctly but was excluded at13FPS during recorder startup; after a three-second recording warmup it passed at29FPS. Off-centre control passed at29FPS. All completed clips decoded; low-roof final image inspected. Original one-high and navigation audits are in progress.

- Same early-sneak binary passed the original one-high regression2/2 (three overhead blocks planned/mined,20HP,grounded) and navigation7/7 (bridge,water,flat,stairs,descent,two-high-wall,gaps),14.4–29FPS,no falls/invalid runs. All seven navigation clips and the one-high clip decoded; wall frame inspected. Integrated shallow/deep food-exit repetition now running; food policy remains unaccepted.


### Food exit height correction
- Six full dry exits passed (three depth8,three depth20,25–29FPS,20HP). Local bread and surface-start controls passed. The wet-column control FAILED at29FPS/20HP: excavation opened a sky shaft at-54, four blocks below the surrounding-50 surface, and current-column heightmap incorrectly released the surface task. Ordinary wandering then stalled in the pit. Clip decoded and final image inspected; not accepted as food success.
- Keep the selected surface elevation across excavation, releasing only within one upward step of that level. This still handles the previous one-block floating endpoint while preventing a deep exposed shaft from counting as arrival. New build and repeated wet/dry validation pending.


### False placement protection found during repeated food exits
- Surface-level binary passed wet1/deep1/wet2, then deep2 FAILED at28FPS/20HP. It climbed from-58 to-41 and spent the remainder repeatedly attempting a pillar rejected by place policy. Full trace identifies a WorldSurvivalChain predicate denying the position; configured deny zones were empty.
- At01:14:13 the chain interpreted a world block change at3600,-44,1599 as a failed placement and banned the surrounding radius50. WorldBlockModifiedMixin emits BlockPlaceEvent for any air-to-solid change, including server updates; this is not evidence the bot attempted placement. The detector also mistakes a subsequently mined block for a placement failure.
- Isolated baseline: IdleTask on clean terrain, server changes an adjacent cell stone then air without any player placement. Policy at the player changed allowed→denied and an unrelated pillar placed0 blocks at29FPS. Artifact outputs/place-events/baseline-world-change; no real claim exists there.
- Remove this world-change-based placement claim detector/subscription. Keep explicit protection hooks/zones and actual executor failure handling: PathExecutor refuses its timed-out cell through PlaceRules, PillarTask bounds progress and remembers a failed column, BridgeTask bounds placement/movement. Baritone BuilderProcess likewise follows its actual movement/executor status. This does not claim all placement failure handling is complete. Build and repeated event/placement/protection controls pending.

- False-event fix root build11s and nested deployment succeeded. Six world-change trials passed at19–28.5FPS: policy remained allowed, actual pillar block appeared, grounded arrival and20HP. Six explicit-zone negative controls remained denied while the adjacent unprotected cell remained allowed. All six clips decoded; final image inspected. Existing explicit protection is retained; no claim that real protected-column retry behavior is repaired. Full deep/wet exits are now being repeated on this binary.


### Final combined food/protection exit matrix
- Final binary passed all6 full exits: depth20×3 in85.0/82.38/87.15s at26.5–29FPS, wet-column depth8×3 in37.82/27.33/35.04s at27–29FPS. All20HP, grounded at the actual surface and released surface search. Local bread4 control passed27FPS without surface routing; surface-start control passed29FPS without unnecessary ascent. All8 videos decoded; final deep image inspected.
- The earlier failed wet and deep runs remain preserved. The food change requires the selected surface-level correction and the independent false placement-protection fix; neither earlier intermediate binary is presented as accepted. Focused adjacent audit is running before commits/natural resumption.

- Final focused adjacent audit62002 passed: low-roof smoker pillar29FPS; original one-high2/2 (6.4/6.7s,three overhead blocks,20HP,grounded); bridge28.2FPS and two-high wall23.3FPS without falls; hungry threat mined6cobblestone without switching to sword during continuous mining and finished20HP; working underwater recovery28FPS reached air and released recovery; AI creeper inside-radius retreat26FPS,20HP,0deaths and valid initial geometry. All clips decoded; air frames inspected. Acceptance is for these controlled cases, not a completed natural playthrough.


## Natural observation after29aa2828 and starvation selection failure
- Verified preserved natural login20HP/armor17. In482.3s the bot acquired flint, wood and flint-and-steel, traversed the lush cave and climbed toY22 toward a chest. Health fell20→10 around310–358s; subsequent retreat/coal collection moved it to-9.5,-14,-288.4. RCON confirmed Easy difficulty,foodLevel0,saturation0,HP10. Food discovery was never selected despite no food in inventory. All4 clips decoded; frames26/101/180/276/351 inspected. One task-chain read at345s returned an error and recovered next poll.
- Stopped observer and disconnected alive. Verified backup20260916-015507-starvation-stop.tar.gz:56,351,895bytes,80entries,SHA256b1cfd3971c5b71a6222128cfe5525a20cf4ef3677a90562c42a287e74833bd94;zero players,save-on restored. No rollback, inventory injection or time edits.
- CollectFoodPriorityCalculator returns0.1 before its scarcity multiplier whenever no source is known. Optional chest/ore scores50–100 beat this even at zero hunger. Actual full-gamer baseline in a foodless chamber with nearby coal reproduced no food selection at foodLevel0 and28.5FPS, with health decreasing.
- Add a shared emergency-food predicate: hunger<=10 or health<=10, food potential<10. It selects food before the unknown-distance fallback and bypasses the equipment gate while urgent. Higher-level survival/defence chains remain unchanged. Full-gamer hungry/equipped, hungry/unarmed and fed controls pending; not accepted yet.

- Root build completed in8s and deployment verified nested jars. No Tungsten edits in this pass. The public ExecuteCommand("@gamer") drives the complete selector in the new food_priority_test fixture.
- Six normalized controls passed: hungry/equipped twice22–25FPS, hungry/unarmed twice28–28.5FPS, fed twice15–29FPS. All initial FPS samples are retained over the20s hungry observation; two earlier short unarmed observations at9/12FPS remain invalid. Intermittent FPS drops also occur in fed/equipped controls; no performance repair or external-load cause is claimed.
- Low-health fed control started8HP/food20, selected food by9.15s and passed26FPS. The unchanged task-retention timers can delay selection. All seven accepted control clips decoded; equipped/unarmed final frames inspected.
- Two fed setup attempts were invalid because saturation amplifier5 adds only12 hunger from empty. The fixture now applies amplifier20 after survival mode and asserts food20 before starting. The earlier spectator-timing hypothesis was not established. Setup failures are not bot-selection failures. Full exit and adjacent audit pending.

## Food consumption and external lifecycle follow-up
- Normalized adjacent audit passed one-high2/2 at20HP and nav_flat/staircase/descend3/3 at25.0/28.7/27.7FPS, without falls. Initial clearance repeats traversed correctly but lost health because the preceding hunger scenario left zero food; its fixture now clears the hunger effect and replenishes saturation. Original failed artifacts are retained in workspace outputs/priority-audit-starved.
- The hungry-threat audit then failed its meal gate despite killing both targets and mining6cobblestone: hunger5 stayed5 with16bread,20HP and29FPS. FoodChain reported no edible food because bread was behaviour-protected. The observed pick-to-sword transition coincided with a real combat cancellation and is not the failing threat gate.
- Restart-only control on the same binary passed the threat case: hunger0→20,6cobblestone,targets dead,final17HP. A subsequent full-gamer food run passed28FPS and restored behaviour depth1/empty protection. Later one-bread trials reproduced depth2 with seven food protections after stop; the origin of that extra scope is not yet proven.
- Direct meal baseline: give one bread during active gamer food search at hunger0. Three runs retained hunger0 at27–28FPS; the first final frame shows the bread in inventory while digging continues. CollectFoodTask adds consumption protection to its collected foods, and FoodChain asks canThrowAwayStack before eating. Separate discard-only protection from consumption reservations, preserving inherited reservations, important/custom-named protections and scope copies. Not accepted until live meal and negative controls pass.
- Debug logging also showed Task.stop on Worker-Main-11. ExecuteCommand and stopPathing use executeInNetworkThread, backed by Util.getMainWorkerExecutor, while task ticks run on the client. A bounded paused-client probe reproduced both premature start and premature stop6/6. Dispatch these lifecycle commands on the client, reusing RunInnerCommand for ExecuteCommand. This proves thread misuse; it does not alone prove the entire scope-leak mechanism.

- Combined AltoClef-only build completed10s, deployed with nested-jar verification. No Tungsten source change. Fixed paused-client lifecycle probe passed6/6: start and stop leave runner state unchanged while paused, then apply after release. Behaviour depth returned to1 with empty reservations after all pairs.
- Live consumption matrix passed6/6 at18–28FPS: bread twice, baked potato, sweet berries, bread with throwAwayUnusedItems=false, and inherited bread reservation. Ordinary meals increased hunger0→5 (berries0→2); reserved bread remained uneaten at0. Every sample denied discarding the supply; ordinary samples allowed consumption, the reserved sample denied it. Each completed trial restored depth1 with empty reservation/discard sets. All six clips decoded; pilot frame inspected. These are distinct positive/negative cases, not six estimates of one success rate.
- Test-helper failures retained separately: sweet_berries is absent from TaskCatalogue, so the probe now uses the game item registry; the first keep-unused setup used the wrong capitalization for throwAwayUnusedItems, then explicitly restored its already-pushed test scope. Enter now rolls back on partial setup failure. These invalid attempts are not production failures or evidence about the original leak.
- Parameterized food_priority_test, food_policy_probe and task_dispatch_test are in deploy/runner. Original starvation selection controls and the new meal controls share the full gamer path. Final combined exit/navigation/combat audit is running before acceptance.

- Final combined audit passed: full gamer exits the foodless chamber in29.24s at28FPS,12HP,grounded and releases the surface goal; original one-high2/2 in7.0/6.4s with three overhead blocks removed and20HP; navigation flat/staircase/descend3/3 at24.7/29.0/26.3FPS, no falls or invalid runs; hungry mining ambush mines6cobblestone, defeats both targets, eats from hunger5→20 and finishes20HP at29FPS with no sword switches during continuous mining. All clips decoded, exit/combat frames inspected. Inactive behaviour scope returned to depth1 with both sets empty.
- Acceptance covers command serialization, controlled urgent food selection and consumption, and these adjacent movements. It does not establish a universal scope-leak fix or a completed natural food recovery. Natural world remains preserved at10HP around-9.5,-14,-288.4 until resumption; full Minecraft completion remains open.

## Natural food recovery after17718cf2: cave-vine interaction obstruction
- Resumed preserved world at-8.7,-9,-289.5,10HP/armor17. Food discovery immediately won and pursued a known pig; the bot climbed to restingY7 before repeating the same failed pillar. No food acquired yet. One initial task-chain read errored and recovered. Stopped at167.2s and disconnected alive; both videos decoded and frames27/78 inspected.
- Runtime pillar repeats: apex8.25 from restingY7, placeAt(-11,7,-301), readyNull25/25, no placement, ray hitting cave_vines(-11,9,-301). Client-thread geometry snapshot confirms granite supportY6, airY7/8, cave-vine tipY9 and stemsY10/11. These plants have no body collision but are non-replaceable and intercept the placement ray. This differs from the existing climbable wall-vine control.
- Verified new backup20260916-030629-cave-vine-stop.tar.gz:56,357,123bytes,80entries,SHA256cf837b4154a1e7430411814ff814c7059b03ed365c61a78d1808e826aac1ad2c. Zero players,save-on restored. Brief stopped-task reconnection only captured geometry; no teleport,inventory or world edits. Next resume expects-10.5,7,-300.6 at10HP.
- Controlled navigator reproduction is running before a core change. FastPlanner body clearance and FastNavigator ceiling preparation currently ignore collision-empty plants; real placement requires interaction clearance too. Ordinary climbable vines and explicit break protection must remain valid.

### Cave-vine interaction clearance: controlled investigation
- Two navigator baselines reproduce zero placed blocks, no arrival, 20HP and29FPS. Both clips decode; the first final frame shows repeated pillar retries. The normal jump reaches1.25blocks above the stance, so this is an interaction obstruction, not a short jump or physical roof.
- First implementation failed identically (zero placed,27FPS). The shared predicate incorrectly exempted every CLIMBABLE-tagged block. Runtime inspection proves cave_vines belongs to that tag on1.21.11; its collision is empty but outline spans x/z0.0625..0.9375. Earlier assumption that cave vines are not climbable was false. One early test attempt preceded Py4J readiness and never set up the fixture.
- Revised predicate checks the outline against the centered player-width column, preserving wall-mounted vines. The planner prices explicit head clearance, navigator schedules it before pillaring, and executor does not skip collision-empty explicit targets. Required mining cost now has an explicit-removal entrypoint; ordinary movement pricing retains its walk-through shortcut. Validation pending.

- Shape-based pilot still failed: clearance queued3cells, but every mining attempt immediately aborted with "no visible face", then navigation gave up (0placed,17FPS). Both visibility and occluder rays in PathExecutor used COLLIDER; plants have no collision. Changed these two mining rays to OUTLINE, matching vanilla and existing RotationHelper/Baritone tracing. This is a measured second stage of the same clearance defect. Shape-pilot video decoded; final validation pending.

### Cave-vine controlled acceptance (adjacent audit pending)
- Final outline-ray pilot passes:3 actual rungs, grounded arrival in3.88s,20HP,18FPS. Six normalized repeats (three tip-at-head, three tip-at-jump-eye) pass6/6 in2.79–4.40s,22–29FPS,20HP,all3rungs. All videos decode; pilot and lower-tip final frames visually inspected.
- Breaking-disabled negative control passes: vine intact,0placed,no arrival,22FPS. Policy is restored in finally. Planner probe on the jump-eye case records the first vine in toBreak; returned plans are partial, so this is not a claim of complete initial planning. Navigator completes after clearing/replanning.
- One diagnostic repeat arrived with3rungs but median10FPS and is retained as invalid. The synchronous planning probe now has its own3s settling period before movement sampling; threshold remains14FPS. Six accepted results above use that separation.
- Existing pillar_interaction_test now supports --cave-vines --navigator, --vine-tip-offset1|2 and --no-break; food and allowBreak state normalized/restored. No duplicate fixture added. Fresh fetch found no incoming origin/main commits. Adjacent audit running; natural recovery not yet resumed.

### Cave-vine adjacent audit accepted locally
- Four pillar controls pass: wall vine27FPS/3rungs, low roof29FPS/1rung, blocked roof29FPS/0rungs and refusal, smoker27FPS/3rungs/no GUI. Original one-high passage2/2 (7.3/6.4s), all3headblocks planned and mined,20HP. Take-off slab2/2 (15.7/14.1s),20HP. All clips decode; wall-vine and original-clearance frames inspected.
- Navigation flat/staircase/descend3/3,22.0/23.3/29.7FPS,no falls or invalid runs. Artifacts deploy/runner/artifacts/20260916-032935; all3videos decode.
- Hungry mining ambush passes:6cobblestone,targets gone,hunger5→20,29FPS,final17HP after combat,0sword switches during a continuous mining queue. Video decodes and final frame inspected. Workspace outputs/cave-vine-audit holds the adjacent fixtures.
- Assessment: same plant column changed from0rungs/no arrival in both baselines to6/6 completed rises in2.79–4.40s. This removes a measured natural food-pursuit blocker through shared geometry/planning/mining, with no timeout workaround or server-specific production coordinates. Original clearance remains green. Natural food recovery and full-game completion remain unvalidated; resume preserved world next. Push/Telegram publication still awaits the existing target-specific answers.

### Natural cave-vine recovery and next food-pursuit investigation
- Resumed exact saved stance(-10.5,7,-300.6),10HP,armor17. Live log at03:34:24 queues the actual cave-vine tip(-11,9,-301); at03:34:25 mining completes, and movement continues. Natural obstruction is cleared. No world/inventory/time edits.
- Climbed toY30 by137s andY47 by355s, then repeatedly returned throughY39–47 instead of reaching food. Targets changed among pig/sheep/chicken. Server reads show pigs atY84/85 and sheepY86/87, roughly45blocks above the cave. getEntitiesInfo reports no nearby livestock; its close-entity range does not establish that the tracked surface targets are absent.
- Health remained10 and armor17 throughout. FPS intermittently fell to1–4 during ongoing routing, later recovered22–29; no causal attribution established. Food not acquired, full food recovery remains unvalidated.
- Stopped observer and disconnected alive at(-10.5,43,-389.6),10HP,armor17,634blocks. New verified backup20260916-034354-food-pursuit-stop.tar.gz:57,272,367bytes,80entries,SHA25665ddbdf2b3e87049f1d9ece16ff8cda57800858b1944fa4a2c59f5d929938f93. save-on restored. Next focused pass isolates a fixed high animal goal from food-target reselection on disposable terrain before changing production logic.

### High-food controlled investigation: mining progress series
- Direct45-block approach controls were inconclusive for arrival: first reachedY-16 and ended its task, but fixture compared the original pig spawn instead of its live position; retained invalid. The repeat measured live target and ended the180s window9.11blocks away, still climbing,20HP/29FPS. Initial routing delay varied; no stationary-target success rate or mechanism claimed.
- Fixture teardown initially rejected `difficulty Normal`; explicitly restored normal and original true flags, then fixed case parsing and finally restoration. These are fixture errors, not production failures.
- Full CollectFoodTask with one fixed pig45blocks above succeeds in191.76s,3raw pork,20HP/29FPS. It repeatedly logs "Not closing" and costs that pig while continuing to mine; no alternate source exists in this control. Video decoded. More animals may make these false costs destructive, but that causal link remains to validate.
-20s read-only diagnostic captured173 approximate snapshots of the actual KillEntityTask progress checker. At03:58:13.921 the new block(4200,-20,2200) has0progress while mine_last remains0.8888889; at03:58:14 it reports "Not closing". Same transition repeats03:58:17.877→18,21.244→21,25.161→25 across distinct successfully mined cells. New progress reaches0.177/0.355/0.533 below the old0.888 baseline; distance_failed remains0. The failure flag resets in the same task tick, so it was not directly captured. Source LinearProgressChecker compares scalar progress deltas across its0.5s window.
- Plan: make the mining signal cumulative observed work, counting only positive within-target damage increments; changing target at0 must not renew the no-work timer. Retain confirmed-solid-to-air credit and store an immutable block position. Validate productive target transitions and zero-work target switching, then real high-food pursuit and adjacent mining/navigation/clearance before resuming natural world. No production implementation yet.

### Mining progress series accepted; natural pursuit still open
- MovementProgressChecker now accumulates positive observed damage work across blocks and retains an immutable target position. Switching to an undamaged target grants neither work nor a timeout reset. Existing solid-to-air, distance, eating and pathing guards remain. AltoClef build/deploy succeeded in15s plus deployment; no Tungsten changes.
- Controlled live checker snapshots pass productive transitions and same-block work; zero-work target switching and same-block stalls fail as required. The fixture changes controller snapshots on the client thread and restores them; it is not a physical mining test. Initial cold-start timeout and a genuine0.696s no-work gap are retained as invalid positive trials, not hidden. Accepted productive samples include positive work after each transition; timeout remains0.5s.
- Six physical food scenarios acquire meat at20HP: depth8 single49.96s/25FPS, competing37.27s/27.5FPS; depth20 single81.73s/28FPS, competing120.33s/24FPS; depth45 single191.63s/28FPS, competing268.68s/25FPS. All clips decode; final high-competing frame inspected. The old single45 run already succeeded in191.76s, so no speedup or endpoint improvement is claimed. Repeated old cross-block false costs are the measured mechanism.
- High45 fixed runs had no pursuit warnings. Expanded matrix has ONE remaining Not-closing warning at04:24:49 during depth20 competing, followed by mining completion04:24:50. Its cause is unresolved; this commit does not claim all pursuit abandonment repaired. Natural vertical-food TODO remains open.
- Adjacent audit passes: checker guards, full gamer foodless exit28.5FPS, original one-high2/2 in6.7/7.6s with3head blocks removed and20HP, nav flat/staircase/descend3/3 at24.7/23/26.7FPS without falls/invalids, hungry mining ambush6cobblestone/targets gone/final17HP and no sword interruption during123 mining samples. All clips decode; clearance and combat frames inspected. Nav artifacts20260916-043022; workspace outputs/mining-progress-audit. Repeated clearance trials share one overwritten media path; both JSON outcomes retained, one final clip.
- Assessment: removes a proven cross-block scalar-comparison error without relaxing stall detection. Food routes complete under controlled geometry, but natural recovery and full game remain unvalidated. Resume preserved natural world and inspect the remaining abandonment if it recurs. Publication remains pending the existing target-specific answers.

### Natural food recovery after5582bbc4
- Resumed the exact preserved stance(-10.5,43,-389.6),10HP/armor17, with no inventory/world/time edits. Climbed beyond the formerY39–47 loop toY64 by96s. First recorded food:3raw pork at227.4s. Health increased to16.93 by239.5s and20 by448.6s; ordinary resource collection resumed between food tasks.
- No additional Not-closing or entity-budget warnings in this natural segment through448.6s. The one depth20 fixture warning remains unresolved and is not erased by this recovery. Three completed120s clips decode, underground mining and surface gathering frames inspected; clip2 shown locally to the user. Observer continues900s with visual checks, so this is a mid-run checkpoint, not full-game completion.
- Controlled unknown-food exit and urgent-selector tests, plus this actual recovery, close the immediate food-exploration/priority/vertical-pursuit incidents. Wider combat, bridge/deep-descent reliability, Nether and End remain open. No publication retry.

### Observer shutdown incident and recovery (not a combat regression)
- The900s natural run collected cooked_mutton17,cooked_porkchop5,cooked_chicken4 and ended20HP/armor17. All8clips decode. However, stop-on-exit stopped all survival tasks at04:49:40 and left the player connected; the operator delayed disconnection. A zombie killed the idle player at04:51:10. This is our observer lifecycle error, not evidence against the movement fix or autonomous combat.
- Backup20260916-045218-surface-food-recovered.tar.gz is MISNAMED: it contains the post-death state (respawn95.5,128,-21.5,armor0,empty),58,935,737bytes,80entries,SHA25674778f57dc885f236953e80ee464705cd68dcecd243547ba0b52bf36313ab6d2. Never treat it as a recovered-food checkpoint.
- watch_survival --stop-on-exit now disconnects on the client thread, confirms inGame=false, then stops the task. Defense remains active through recording finalization. SIGTERM is catchable so finally runs. Short flat-server observation passed and ended inGame=false with0players; SIGKILL remains outside finally guarantees. No mod rebuild needed.
- Recovery restores verified034354 pre-run backup while preserving the entire post-death world separately as world-after-observer-death-20260916-045218. This deliberately repeats the successful15-minute gameplay instead of injecting its inventory. Restoration and login verification are recorded next.

### Recovery verification and Docker startup blocker
- New helper containers stayed Created without running, including --network none; they were removed and their waiting CLI processes terminated. No helper performed its rename/extraction. The world was instead restored through docker cp into the stopped existing server, after confirming both archives have the identical80-entry file set. The post-death archive remains retained externally.
- Read-back verification compares every regular file SHA256: all67files exactly match034354. Evidence: workspace outputs/natural-checkpoints/restore-034354-verified.json. Current natural state is the restored pre-run10HP/armor17 cave checkpoint, not the successful surface state. No player connected.
- Docker also stalls starting the existing gamer-server; inspect remains exited with no new server startup. Other running services remain up. A Docker Desktop restart would interrupt unrelated mineswarm/services, so a specific user question is pending; no global restart performed. Available disk283GiB, so no unsupported disk-exhaustion claim. Full-game continuation blocked on restoring container startup.

## 2026-09-16 — the 00:12 stare, the node-9 halt, the tunnel, nav_steep (G91)

### Investigate
- Playthrough on HEAD 78028a43 (survival stand, `gamer_smoke.py 1`, recorded): PASS, 97 items, 0 deaths; ladder first craft 42.8 s, stone tools 64.4, furnace/coal 173, food 216, iron ore 368, iron 389, **iron tools 411 s**. The runner's stall detector fired only at 561 s ("no new rung"); the operator found an eight-second stare at 00:12 of the 12x clip that no detector saw.
- The stare: two aimers in one tick — the navigator's dig (`at the dig` on a stone) and altoclef's DestroyBlockTask ("Block in range" on the table beside it); vanilla resets break progress when the crosshair leaves the block. Counters: breakMissWhy transit=1035, dbAimWait=0; `stopOrphanRoute` cannot end a navigator dig during "in range" (300 ms drive-tick gate); `tickBreaking` had no yield to `minerOwnsAim()` (the walk path had one). Reproduced on the flat stand with `mineBlocks` + a new `destroyBlockAt` primitive on adjacent blocks: transit 77 in 4 s.
- "stopped n33 idx9 bot(-71.5,95,-1464.5)": read with the record's own semantics (`bot` = body at the search's exit, `phys` = the simulated agent's furthest point, `idx` = the search's lookahead), it is a search east to a truncated target killed one second later by `TungstenHelper.stop()` (313 external stops in the run) while the body chased a raw-chicken drop to the west (frames t=546–550 s: `GetToDropTask … at -78,94,-1471`, food 2/20). A one-second target flip of the G38/G23 family, not a stand.
- The three underground minutes after it: the return to the smoker 84 blocks away, 14 lower, "dig allowed" — a 7-deep shaft and a two-high tunnel executed one cell per plan (`truncateAtBreaks` cuts at the first break cell; each cell: re-plan, `steerTo` with sneak, WindMouse aim, two breaks ≈ 3 s) while the planner prices the cell at its break ticks ≈ 1 s (G90).
- `DSIC near=… walk=… makeNew=…` printed to chat every tick for 4000 ticks (G92); RTGATE is rate-limited and stays.
- nav_steep INVALID on every first attempt after a respawn (5/5 today: suite, `--only`, recorded, `--pin verboseDebugLogging=true`), bit-identical drift numbers, the second attempt from the same start passing. The `Agent.compare` trace (System.out under verboseDebugLogging) settled it: root vx 0.063 (captured while the walker still moved the body), body vx 0.006 at replay tick 1 (it had coasted to rest during the 650 ms search); both sprint-jump at tick 2 and 15; the simulation clears the first column's lip by 2.4 cm at tick 23, the body arrives one tick later 20 cm lower, hits the face, falls. Not fps (identical at 29.6 after a client recreate), not the dig-yield change. The playthrough log: 53 replay aborts, 42 at ticks 8–10 — the same class.

### Plan
- One aimer per tick: the dig path of `tickBreaking` yields while a miner owns the aim (`execDigYieldMiner`), mirroring the walk path. Verify on the bench and the nav suite.
- The physics hand-off takes its root from a body at rest: release every movement key, wait (≤ 20 ticks) for |v_h| < 0.02 on the ground, then `find()`; water and ladders exempt; `physicsHandoffFromRest` flag for A/B; `navHandoffRest=settled/timedOut` and `pfRootMoving` (roots taken from a moving body, all callers) to measure the rest.
- Record G90 (tunnel per-cell cycle and pricing) and G92 with the numbers; the drop-flip stays a data point under G38/G23.

### Implement
- b091dfac: PathExecutor dig yield + `destroyBlockAt` + `execDigYieldMiner`; bench after: execDigYieldMiner=82, transit 0/0, both blocks broken in 6.0 s; nav suite 13/14, gate failures 0, nav_steep INVALID (pre-existing, above). 08746bfe: checklist §4y (how an autonomous run is reviewed).
- G91 fix in FastNavigator (settle before `find()`), `pfRootMoving` in PathFinder, counters exported over py4j, G92 gated behind verboseDebugLogging — built and deployed; verification below.
- Verification: nav_steep `--only` six times on the new build — first attempts 5/6 clean at 9.0–9.2 s (before: 0/5), `navHandoffRest=1/0 pfRootMoving=0` read over py4j after a pass. The one fall has a different signature (`drift 2.327 at tick 26, expected (7.52,-58.00) actual (7.62,-60.32)`, the simulation two blocks up over the gap) and did not recur in four traced runs; recorded as G91b, open.
- Nav suite on the build: 14/14, gate failures 0 (nav_gaps one first-go fall, the recorded course flake, passed on the retry). Committed 69ddb20f; released **v0.95.0** (jar attached, verified with `gh release view`).

### The 25-minute run on v0.95.0 (12:39 UTC, fresh start #44, recorded, frames reviewed at every flat window)
- Ladder: wood/first craft/table/wood tools 461 s, stone tools 616 s; two deaths (a Breeze in a trial chamber at y=-28 after a 90-block shaft, then the respawn at world spawn with nothing); no iron in 25 minutes. Walls, each with its frame and its dump: a wooden-pickaxe DROP from an earlier run ten blocks underground held the first seven minutes while spruce stood across the lake (G93); sixty seconds on a step down onto a snow layer (`gaveUp … hop[0,-1,1] … [grass_block|snow|air]`, 32898 tests, G94); 110 s over a cobblestone two below the feet — `execDigYieldMiner=1392` against `dbBlocked=1430/0/0`: the day's dig yield stood down for a miner that was only holding the keys (the back-off branch stamps `minerAimUntilMs` and swings at nothing) — a regression of b091dfac; the night job to a block 86 below, then the trial chamber (G95); after the respawn `reach:armed … nav=false` and a shimmy (G96).
- Fix: a separate mining claim, `minerMineUntilMs`/`minerOwnsMining()`, refreshed only by DestroyBlockTask's "Block in range, mining..." branch; the executor's dig yields to that alone; the key claim keeps its walk-path yield. Bench `self_floor_dig_test.py` (stone two below the feet under a dirt floor, `@get cobblestone 1`): PASS, cobblestone in 3.2 s, `execDigYieldMiner=0 dbBlocked=23 navBreak=1`. Bench `two_driver_mine_test.py` (the 00:12 scene): PASS, both blocks in 4.8 s, transit 0. The runner's fresh start now kills last run's drops around the body where it landed (`execute as … at @s`), not around the console. Released **v0.95.1** (d255b05b).

### The 60-minute run on v0.95.1 (13:40 UTC, recorded, frames at every flat window)
- The first run past iron: wood 22 s, stone tools 44 s, coal 113 s, food 183 s, **iron tools 295 s**, shield ~273 s, bucket 1551 s, water bucket ~1600 s, **diamonds** from 1642 s (a shaft to y=7), **diamond pickaxe 1757 s, diamond boots 1987 s, diamond chestplate 2085 s**; 747 items, 0 deaths. The runner's ladder has no rungs past "bucket"; the task snapshots and the frames carry the rest.
- Two walls, both in the navigator's hand-off, both with the frame and the log line: **G97** (t=596–1367, nine minutes without a step in a pit at (1431.7,70,-1491.5)) — the plan complete and climbing 4.5 out of the pit first, G60's "the goal is 3 below — not towering up" firing 115 times because the iron ore was three lower and twenty-five blocks away, every ore in reach then "given up 3 times in a row — marking it unreachable", the chain "No tasks". **G98** (t=2377–3567, twenty minutes at the bottom of its own diamond shaft, the next goal nine blocks straight up) — every plan partial (two ledges, then flagged tower cells) and under the five blocks the walk-the-partial rule wants, so the dead-end branch handed the GOAL to the physics search, which has no place move: "Failed! No block path", "no progress … giving the route up" ×64.
- Fixes: G60's refusal gains a horizontal radius (`noTowerWhenGoalIsBelowRadius` 6, `navTowerAllowedFar`); a partial plan that carries a flagged build or dig is walked to it (`navPartialBuild`) and the flagged hand-off does the rest. Bench `shaft_exit_test.py` (a 2x1 shaft cut into a stone mound — the first version dug into the flat floor, left the world at -64, fell into the void and PASSED on the respawn height; the scene is checked now).
- Verification: shaft_exit out of ten deep in 6.5 s, 22 deep in 9.9 s, 22 deep with the planner starved to 20 ms in 10.1 s — all with a COMPLETE plan (`navPartial=0/0`), so the tower hand-off from a shaft is proven and the G98 partial branch is not exercised by this bench; it rests on the run's log signature until a scene reproduces the partial (the planner generated 28 pillar moves against 100k climb nodes there — recorded under G98). pit_escape PASS, self_floor 3.2 s, two_driver 4.7 s transit 0, nav suite 14/14 with no invalid runs. Released **v0.95.2** (c594f758).

### The second 60-minute run, on v0.95.2 (15:10 UTC, fresh start #49, recorded, frames reviewed)
- Iron tools 657 s, bucket 796 s, a shaft to y=0 for diamonds (281 items by t=1299) — then **three deaths**: drowned in a flooded cave at y=0 (frame 21:30, "Reaching breathable air — Finding a reachable air pocket", hp 12, finished by a zombie; G100), the respawn at world spawn 1700 blocks away with nothing; rebuilt to the **nether stage** (t=1948, "Going to Nether → Construct Nether Portal → Getting flint & steel" — the first time), then at night by the spawn a zombie at melee range with no weapon, `NIGERUNDAYOO`, death two (frame 33:50) and three (G101, data for the deferred night track). The last fifteen minutes at the lip of a pool, (93,107,-113): MovementSwim declared the one-cell stroke onto the bank done by distance with the feet still in the water, released the keys, the water carried the body back, "body has not left … for 121 ticks" ×106 (G99). The pit and shaft walls of the first run did not recur (`navPartialBuild=16`, `navHandoffRest=10/1`).
- Fix: a swim stroke whose destination is not liquid arrives only with the feet on it; the 0.6 tolerance stays for water-to-water strokes. Bench `pool_bank_test.py` (a 3x3 pool two deep, the goal the bank cell east of it). Also recorded: `Average Position` printed 13446 times (G102) — the end-portal average, logged once per value now.
- The bench's first run FAILED and found the other half (G99b): the navigator's sphere arrival accepted a body IN THE WATER as settled — "arrived (1.6)" with the body rising at y=-61.04 toward the bank at -60 (goalRise 0.95 for one tick) — stopped the queue mid-stroke, and the body floated with no inputs until it drowned 22 s later (no survival chain under a bare gotoXYZ). Water counts as settled only when the goal itself is in water.
- Verification: pool_bank PASS twice, on the bank in 2.3 s (`mqSteps=3 swimAim=23 navWet=1/0`); nav_water PASS twice; nav suite 14/14 (nav_gaps' first-go fall, the recorded course flake, passed on the retry). Released **v0.95.3**.

### Checkpoints (operator: "ты бы уж тогда фиксировал ЧЕКПОИНТ где начинаются косяки")
- Two sixty-minute runs from an empty inventory in one day, each spending thirty minutes to reach the state whose wall was the question, was the mistake. `deploy/runner/checkpoint.py` freezes the whole gamer world (2082 MB, 29.7 s: save-off, save-all flush, docker cp, save-on; meta.json with seed, time of day, the bot's position/hp/inventory ids when online) and restores it (kick, stop, swap `/data/world`, chown to the server's user, start, wait for rcon). `gamer_smoke.py --from NAME` swaps the world in right before `@gamer` (the reset and the spiral still run and are discarded with the swap; `GAMER_SPAWN` pinned so the forest search does not spend minutes), `--checkpoint-every MIN` freezes the middle of a run, and the end of every run is frozen as `last` unless `--no-save-end`. First checkpoint on disk: `post-run2` (the end of the second sixty-minute run). Checklist §4y.7, AUTOTESTING.md.

### The third run, and what the checkpoints found (v0.95.3, fresh start #56)
- Ladder to food in four minutes (wood 22 s, stone tools 87 s, furnace/coal 197 s, food 240 s), then **46 minutes with no advance and no iron**: from t≈850 to t=3620 the bot alternated "Doing stuff in crafting_table: [[bread] x 17]" (an empty grid, "Moving wheat x 17 to slot", 1 wheat in the pack) and "Picking up the crafting table while we are at it" (break fails, "Maybe private area", "Blacklisting extra crafting table" ×41). Two roots (G103): `foodUnits=220` is a speedrun stockpile that keeps food ranked over iron with 11 bread already held, and a food craft that cannot progress (no wheat, no reachable source) does not yield to the next rung as the pursuit budget and nav give-up do.
- Checkpoints earned their keep and corrected the diagnosis: resuming `cp0916-2241-t900` (15 min in) did NOT reproduce the loop — the bot went straight on, iron reached at 347 s (six minutes from the saved state, the time a fresh run spends on wood). So the deadlock is a later, state-specific accretion (several unbreakable placed tables, the wedged wheat, the blacklists), held in `loop-bread` (the end-of-run world), not at minute 15. G103 is fixed and reproduced from `loop-bread`, next pass — not from a fresh hour.
