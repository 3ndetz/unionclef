# Archive: foundational tungsten features (2026-07-20/21, shredder pathfinder v2)

Archived 2026-09-01 from `docs/ai/progress.md` per the >500-line rule in `docs/ai/readme.md`. Content moved verbatim, not edited.

## DROP-IN SWAP baritone→tungsten + ROOT CAUSE of the headless stall (2026-07-21) — DONE

User: thoroughly close out the drop-in replacement of baritone with tungsten. Turned out to be a
deep pre-existing bug, unrelated to tungsten.
- setTungstenPathing(true)/pathingMode (py4j+MCP): tungsten-primary. With it
  CustomBaritoneGoalTask.driveTungstenPrimary calls tungsten PATHFINDER.find
  directly (like ;goto — clear EXECUTOR.stop + find, async). Hook in GetToBlockTask.
  onTick BEFORE wander. TungstenHelper.setPrimary flag (volatile). NB: TungstenHelper
  reflection was DEAD (it looked for searchTimeoutMs on PathFinder, but that's on
  TungstenConfig) — worked around it with a direct call (altoclef depends on tungsten).
- ROOT CAUSE of why @goto/@gamer did NOT move on the bench (diagnosed via the [trtick] log of
  the winning chain): **MobDefenseChain won EVERY tick at prio 70** — on
  PEACEFUL the peaceful check was commented out, a false run-away kept sticking → UserTaskChain
  (navigation) NEVER ticked. Restored the peaceful shortcut. This choked both
  baritone and tungsten (baritone was NOT dead, just blocked). Plus UnstuckChain+
  WorldSurvivalChain shimmy was also preempting — deferred when primary is active.
- Side finding, a bug in MY OWN diagnostics: ConnectToServer while in-game → altoclef DISCONNECT
  CHAIN (self-inflicted client disconnects). Tests now connect only when not already in-game.
- Test swap_test PASS: @goto arrives (dist 0.6) with both tungsten-primary and baritone.

## Baritone compatibility: privates + multi-target combat (2026-07-21) — DONE

Answer to a large block of the user's goals (full tungsten compatibility with baritone
constraints + levers for the agent).
- PLACE protection (symmetric to BREAK): PlaceRules.canPlace → canPlaceHook →
  altoclef shouldAvoidPlacingAt. Consulted in placeBlockAtRaw (all of
  WorldEdit/build) and BridgeTask (the godbridge stops at privates). Config
  allowPlace/placeDenyZones. Private-area as a LEVER: markProtectedArea(x,y,z,r)/
  clearProtectedAreas puts it into BOTH deny lists (place+break) — the "claim" convention.
  Predicates for the agent: canPlaceBlock/canBreakBlock (py4j+MCP). Test protect_test
  PASS: both place and break are forbidden inside a private zone, fine outside, clear lifts it.
- PLACE_PLAN viz (renderPlacePlan): the godbridge draws "will place here" in green.
- Multi-target combat: PunkPlayerTask.startAny(allow, avoid) — nearest
  allowed target, auto-retarget; punk/punkAny/punkAvoid/punkStop/punkStatus
  (py4j+MCP). The brain decides who to hit, tungsten executes. Test multitarget_test
  PASS: avoid→no target, allow→target taken, stop→reset.
- Recorded/updated goals 12-18. Big items remaining: 13 drop-in replacement of baritone,
  15 long-range routes (receding-horizon), 18 tungsten_speedrun/@gamer.

## MCP server IN THE MOD over LAN (2026-07-21) — DONE

User: "can you put mcp straight in the java client on a port and expose it to LAN?" — yes.
- McpServer.java: `com.sun.net.httpserver` (JDK, no dependencies) bind 0.0.0.0:
  mcpPort. Streamable HTTP JSON-RPC 2.0: initialize/tools/list/tools/call/ping.
  24 lever-tools on top of Py4jEntryPoint (single source — the same methods, without
  the py4j hop and docker-exec). Each with a description + JSON schema.
- Settings Settings.mcpEnabled(true)/mcpPort(25350). Starts after the py4j gateway in
  initializePythonSender. compose publishes 25350 (LAN).
- Test mcp_test PASS: initialize→serverInfo unionclef, tools/list=24, getGameState
  (read) inGame=true, fillSelection (ACTION) over HTTP → 4/4 dirt. Claude drives the
  bot over http://<lan-ip>:25350/mcp — the claimed control surface is alive.
- Recorded new MEGA-GOALS 2 (TODOS 12-17): drop-in replacement of baritone, full
  break/place compatibility + private-area detection, long-range routes, plan viz,
  multi-target combat.

## Integration: agent loop see→move→build (2026-07-21) — DONE

Capstone validation of the whole workbench. agent_loop_test runs EXACTLY a bedwars
micro-scenario using only the agent's levers:
1. getGameState → found the bed (17,-60,16, dist 20.5) from the bot's position (2.5,2.5)
2. gotoXYZ → arrived (2.5→15.5, arrived dist 0.0), polling pathStatus
3. buildDefenseAround → built, shell 4/4, placed 7
PASS. `remaining=['16,-59,16','17,-58,16']`, `complete=false` — the top cells are out of
reach → the correct "agent repositions and calls again". buildDefense placed=7
(not the inflated 88 from the old note — in this scenario the counter is fine). The composition
perception→movement→building works as a single whole, not in isolation.

## Movement lever: gotoXYZ + pathStatus + stopPathing (2026-07-21) — DONE

Keystone of the cognitive agent — the perception (getGameState) → action link.
- gotoXYZ(x,y,z): navigation to a coordinate via the TUNGSTEN pathfinder (ChatMessage
  with the CONFIGURABLE prefix TungstenMod.getCommandPrefix(), not a hardcoded ';').
  Fire-and-poll. The agent also uses it to reposition for far-away fillSelection cells.
- pathStatus(): busy(hasActiveTask)/pos/distance-to-goal/arrived(<1.5). The agent
  loops gotoXYZ→pathStatus until arrived, then acts.
- stopPathing(): a total stop — both ;stop (tungsten) and @stop (altoclef).
- DIAGNOSIS on the bench: first routed through @goto (baritone/shredder) — the goal
  was set (busy=true), but the bot did NOT move headless (pos frozen for 70s). tungsten
  ;goto drove cleanly (1.5→18.5, dist 0.6). Switched to tungsten (also matching the project
  goal of "a single tungsten pathfinder"). Test goto_test PASS (dist 1.5, arrived).
- retreat/chase are intentionally NOT primitives — the agent composes them from goto+getGameState
  (block 6 philosophy: the agent decides strategy, the mod executes).

## WorldEdit-like //set: select + fillSelection (2026-07-21) — DONE

Levers for the agent (block 9), NOT server commands — plain coordinates, works in
survival through real block placement (the placeBlockAtRaw physics primitive).
- select(x1,y1,z1,x2,y2,z2): stores the region (_selMin/_selMax), renders a yellow
  highlight (the SELECTION container, gated by renderVisualization), returns
  min/max/volume. clearSelection() clears it.
- fillSelection(block): //set — places the block in every replaceable cell of the selection
  WITHIN REACH, bottom to top (every cell has support: floor or a previously
  placed block). Cap of 96 placements/call (truncated flag) so as not to freeze the
  render thread; returns filled/remaining/already/complete → the agent
  repositions (tungsten goto) and calls again for the far cells. Philosophy:
  the primitive executes, the agent orchestrates reachability.
- BUG + FIX: fillSelection ran inside onClientThread and called placeBlockAt,
  which wrapped onClientThread again → the nesting DEADLOCKS the render thread
  (client-thread timeout, 0 cells). Moved the core into placeBlockAtRaw (assumes
  on-thread, single source); placeBlockAt = a wrapper, fillSelection calls Raw
  directly. Test worldedit_test PASS (4/4 dirt, complete=true).
- //walls: wallsSelection(block) — 4 vertical walls (x/z==min/max), floor/
  ceiling/center left open; a flat layer → a ring. Shared core fillCells(predicate)
  (//set=all, //walls=borders) — no duplication.
- HONEST blockName: equipHotbarBlock equips the named block from the hotbar (id
  match with/without "minecraft:") — the agent names the block, the mod holds it; not found →
  placeBlockAtRaw auto-picks any block. Test: //set cobblestone while holding dirt → 4/4
  cobblestone (proves the equip), //walls → ring 8/8 + center air (hollow).
- Remaining in block 9: //replace (needs a synchronous break primitive),
  //hollow/cyl/sphere (position generators on top of fillCells).

## Cognitive agent perception + viz toggles (2026-07-21) — DONE

- getGameState() py4j: self(hp/maxHp/armor/pos/onGround/held/blocks) + players[]
  (name/pos/distance/hp/sprinting, sorted by distance) + beds[] (detects beds
  within radius 40 for bedwars — where to attack/defend). Test gamestate_test PASS.
  Read-only, doesn't touch the core — the base for the cognitive agent (block 6).
- Hardened canReach: retry the block-space search up to 4x, take the route that reached
  the target (fixes the flakiness of partial stubs) — F_api PASS again, reached=true/breaks=2.
- Viz toggles: renderVisualization (master) + renderPathMoves/renderBreakPlan/
  renderCombat (;settings), bridge-cell highlighting. Regression slime/bridge/break
  PASS — render gating didn't break pathfinding.
- Decision: deep A* auto-integration of the bridge is DEFERRED (core risk) — the godbridge exists
  as a primitive, the agent itself decides when to bridge (block 6 philosophy). Reference for
  the future: baritone MovementTraverse:122-168.

## Anti-cheat rotation humanization (2026-07-21) — DONE

User's point: anti-cheats flag direct setYaw/setPitch — turning must go through the mouse
pipeline "like a physical mouse" (already set up for combat via WindMouseRotation).
Switched ALL primitives: BridgeTask+BowShooter+mining aim → WindMouse (ticked,
converge human-like through the vanilla mouse pipeline); placeBlockAt →
changeLookDirection (one-shot, pixel-quantized). Path-replay was already on
changeLookDirection. Tests PASS: bridge with humanization (natural z-spread),
break+place regression green. clearTarget on mining completion (otherwise
conflicts with path-replay). Remaining: finer humanize (a "lift the mouse" pause), a live
test against anti-cheat in bedwars.

PRINCIPLE (user): the baritone source is right there — for A*-integration/schematics/WorldEdit
look at their MovementParkourPlace/BuilderProcess/MovementHelper, port what's proven,
don't repeat their mistakes.

## Godbridge (2026-07-21) — DONE

Rewrote the bridge into a CONTINUOUS pave-ahead model (per the user's idea "physics
computes the moves to place blocks efficiently"): NO sneak, sprint forward +
bridge up to 2 floor cells ahead EVERY tick. The target cell is at floor level →
flat extension, nothing to fall off of; physics knows the exact position → place the block
before the foot reaches the edge. Test PASS: exactly N=5 blocks at sprint speed,
did not fall, gap 5/5. The first run with no stop condition bridged 138 blocks in a row.
Stops by distance (advanced>=N). The broken sneak+step-phase version
(fell at the sneak-edge-hold) was thrown out entirely.

GOTCHA (confirmed): an incremental build does NOT recompile tungsten changes
→ for tungsten ALWAYS ./gradlew clean build.

## Sneak-bridge (2026-07-21) — backstory (broken version, before the godbridge)

- BridgeTask: state machine PLACE (stand and place a block forward) → STEP (step onto
  it) → repeat, always sneaking. Triggered by py4j bridgeForward.
- Path of fixes: geometry (ofFloored(y-0.1) — that is the support, not .down() — a double
  .down() placed into empty space, placed=0) → state machine → kill horizontal
  velocity in PLACE. Result: 1 block IS PLACED + the bot STEPS (advanced 1.8), but
  on the 2nd block, inertia+sneak-edge carries it off the far edge into the pit.
- A clean build gave BIT-FOR-BIT the same result → no staleness, the
  momentum-kill compiled but HAS NO EFFECT. THE EXACT BLOCKER: sneak does NOT hold
  the edge in the agent context (options.sneakKey.setPressed doesn't give isSneaking()
  edge-protection on a real ClientPlayerEntity) → the bot falls off the 2nd block;
  the velocity kill in PLACE is late (carried in STEP). Needs a focused session:
  a position-based edge-clamp in the task (stop forward BEFORE the edge, not relying on
  sneak) OR reuse shredder's jump-bridge (backward-bridge is solved there).
- The rest of the placement primitives (placeBlockAt/defense) — PASS. bridge_test is ready.
- GOTCHA FOR THE FUTURE: an incremental build sometimes does NOT recompile changes to the
  tungsten subproject → for tungsten changes ./gradlew clean build is more reliable.

## Block placement + bed defense build (2026-07-21) — DONE

- placeBlockAt(x,y,z): auto-selects a block from the hotbar, aims at the face of a support
  neighbor, interactBlock. inventorySpace(): free slots + block count.
  Test place_test PASS: 4/4 blocks (line+stack), free=35/blockCount=64.
- buildDefenseAround(x,y,z): a protective shell around the bed (sides+roof),
  reuses placeBlockAt. Test PASS: a ring around the bed 4/4 solid when
  checked from 4 sides. This is the foundation for building (blocks 7-10: bridge/schematic/
  WorldEdit) and bed-defense for BedWars.
- Minor: buildDefenseAround returns an inflated placed counter (88) — ground
  truth via rcon is correct, need to check for extra placeBlockAt calls.

## Live run on musteryworld + menu-quirk diagnosis (2026-07-21)

Live on mc.musteryworld.top through a test client (py4j):
- Connect + auto anti-bot check («Вы успешно прошли проверку!») + /register + login — checked
- Hub read: compass «Выбор сервера» in slot 0, minigame portals on the sides — checked
- The server menu opened (useHeldItem) and READ BY NAME: `СКАЙБЛОК`(0),
  `ВЫЖИВАНИЕ`(2), `ГРИФЕРСКИЙ`(4), `АНАРХИЯ`(6), `МИНИ-ИГРЫ`(8) — checked
- Click on `МИНИ-ИГРЫ`(8) → the minigames menu opened (title confirmed), BEDWARS
  (the red bed) visible by eye — checked
- Entering the bedwars lobby — NOT completed due to the root quirk (below).

**ROOT DIAGNOSIS (what the user called "the quirks of a stale task"):**
`getOpenScreen`/`getInventoryFull` PERIODICALLY read the container/inventory slots as
EMPTY, even though the menu is open and the items are rendering (confirmed: title='Выбор
сервера' open=True, but named slots=[] and hotbar=[]). A sampling race in headless mode:
the server periodically re-sends the inventory, and the onClientThread read lands in the window
where slots are empty. Clicking BY INDEX (clickUiSlot) always reaches the server (`МИНИ-ИГРЫ`
opened even on an empty read), but reliably determining the index BY NAME is not possible →
this breaks both autojoin (getCustomItemSlot reads the same slots), manual/cognitive
navigation, and future shop reading.

**FIX DONE AND VERIFIED LIVE (2026-07-21):** added py4j `clickMenuByName(
names, button, action, timeoutMs)` — retries reading the menu through the flaky windows until
a slot with the requested NAME is found, then clicks. Reuses read/click,
no duplication. Live result: compass → clickMenuByName("МИНИ-ИГРЫ")→idx 8 →
clickMenuByName("bedwars")→idx **11** (a manual guess of slot 2 was wrong — the method
found it by name) → «Сервер | Подключение к серверу bwlobby-1...» → **I AM IN THE BEDWARS
LOBBY** (scoreboard BEDWARS, nick tester1, live players, «Прыгай чтобы начать»).
Hub navigation on musteryworld is SOLVED and reliable. The perception lynchpin for the cognitive
agent (block 6) is ready.

Remaining on bedwars: queueing into a match (the «quick start» jump) → a real fight →
tungsten-attack test; + a cognitive surface (shop/bed building).

Built along the way (pushed): interactCrosshairEntity, mouseClick(l/r/m),
screenClickAt — the input layer; proven on mlegacy that clicks spin the captcha frames.

## Combat primitives: facade + shield (2026-07-21) — DONE

- CombatPrimitives (tungsten/combat): canHit gate, attack, shieldHold/Release,
  solveArrow, shootArrow — the execution surface for the altoclef brain.
- ShieldBlocker: holds use for N ticks, yields the key to the bow. py4j shieldBlock.
- Test shield_test.py PASS: a duel of primitives — an archer (BowShooter tester2)
  against a shield-holder: control without a shield 2/2 hits, with a shield 0/3 damage.
- Next in the arsenal: throw primitives (trident/snowball/pearl), mace hit;
  the brain part (weapon choice, HP logic, shield timing) — altoclef.

## Bow trajectory engine (2026-07-21) — DONE

- TrajectorySolver (tungsten/combat): vanilla ballistics (0.99 drag / 0.05 g),
  pitch by bisection over simulated flights, fixed-point lead
  (3 rounds). BowShooter primitive: aim→charge with tracking→release within a
  3.5° cone. py4j: shootArrowAt, solveArrowAim.
- bow_test.py PASS: 3/5 standing + 2/5 against a moving target (the target runs via its OWN ;goto —
  tp-movement has velocity=0, so leading against it is impossible by design).
- Next: hook into altoclef's bow logic (when to shoot/with what — that's its job),
  the shield primitive, and formalizing the combat API (#10).

## The "BreakRules + prediction API + config reference" bundle (2026-07-21) — DONE

- BreakRules (tungsten/path): a unified breaking policy — allowBreak, deny-blocks,
  deny-zones, block entities, canBreakHook (altoclef break-avoiders via
  AltoClefSettings.shouldAvoidBreaking). Both the planner and the executor (live
  re-check) go through it.
- py4j: canBreakBlock, canReach(withBreaking) — reached/breaks/endDistance.
  Gotcha: a stuck stop flag instantly broke the search in canReach (a 2-node
  stub) — now reset before each attempt; "found" was honestly renamed to "reached".
- docs/features/TUNGSTEN_CONFIG.md — the full config reference.
- Autotest F_api PASS together with the C/D/E regression (cycle 13).

## The "visible breaking + chase" bundle (2026-07-21) — DONE

- Mining visualization: a BREAK_PLAN container (the plan — orange boxes,
  the current block — red), rendered in MixinDebugRenderer. Regression C/D/E PASS.
- Look without teleporting while mining: a smooth 16°/tick turn, the attack
  key is only held once the crosshair is brought on target (<12°).
- Seamless goto resume after breaking: resumeGotoAfterMining — an immediate
  search restart instead of waiting for the retry chain.
- Chase (fix 0540a24): re-plan no more often than every 2s + threshold max(3.0, 25% of distance)
  instead of 0.75s/1.5 blocks (the search wasn't finishing in time to be emitted). Autotest
  follow_test.py: average distance 2.0/limit 10, 0 freezes — PASS.
- Remaining per user feedback: PVP in a real fight (rare clicks, endless
  waits — diagnose with a moving target), rich API (#16), configs with
  descriptions (#15), skypvp baptism on mlegacy.net (@game skypvp, manual captcha
  via noVNC on first entry).

## Need-fulfiller API, stage 1: tools (2026-07-20) — DONE

- Design: docs/features/TUNGSTEN_ALTOCLEF_API.md (split tungsten=execution /
  altoclef=inventory, for combat and for mining/building).
- Implementation: TungstenModDataContainer.equipToolHook ← altoclef
  (getBestToolSlot + forceEquipItem), called from PathExecutor.tickBreaking.
- Autotest: course E_tool (deepslate door, pickaxe outside the hotbar) PASS together with the
  C/D regression. Commit dcbb3a2.
- Next: the bestBreakTicks hook (cost from the best tool), then
  formalizing the combat API (#10), bow trajectories (#11), stages 2-3
  (block counting, placement).

## PVP rework + tungsten block breaking (2026-07-20, in progress)

### Investigate (audits complete)

**Combat — root causes of the symptoms:**
- "Afraid to hit": the trigger fires ONLY when `mc.targetedEntity == target`
  (the vanilla OUTLINE pick), while the aim leads the target with a COLLIDER lead →
  the crosshair misses the current hitbox → the hit is suppressed (TriggerBot.java:38,48).
- "Hangs up in grass": the OUTLINE pick is blocked by grass (it has an outline),
  the COLLIDER aim ignores grass → an eternal lock with no hits + oscillation
  COMBAT↔APPROACH via hasNoProgress(60) (PunkPlayerTask.java:76).
- Passivity: ESCAPE for the first half of EVERY cooldown cycle
  (SafetySystem.java:448: cooldown<0.5 → ESCAPE — runs away and turns away
  after every hit); movement toward the target disabled by default
  (TungstenConfig.combatMovementsEnabled=false); DANGER_BATTLE on a
  predicted KB-fall ≥2 blocks — blocks closing in on any terrain.
- Micro-freezes: a BFS of up to 2000 nodes every 10 ticks on the main thread
  (CombatPathfinder.java:47,201,237).

### Plan — combat (fixes) — DONE, TEST PASS

- [x] TriggerBot: its own gate (reach ≤3.0 to the hitbox + COLLIDER LOS + angle <40°
  + cooldown ≥0.95 via getAttackCooldownProgress(0f)) and DIRECT delivery via
  `interactionManager.attackEntity` + swingHand (bypassing crosshairTarget);
  crit window: hit at ≥0.85 while falling.
- [x] SafetySystem: removed "ESCAPE when cooldown<0.5"; KB_FALL_THRESHOLD 2→4.
- [x] TungstenConfig: combatMovementsEnabled default true.
- [x] PunkPlayerTask: COMBAT_RANGE 3.5→4.5, hasNoProgress 60→100.
- [x] FollowEntityTask.hasLineOfSight: raycast to the body center, not the feet.
- [x] CombatPathfinder: MAX_NODES 2000→800.
- [x] Test pvp_test.py: **PASS** (v6, jar 0.24.0+) — first hit at 4.3s,
  the target KILLED (20.0 damage), 0 freeze windows, the fight INSIDE a tall_grass patch.
  NB: the first "PASS" (6.6s) was invalid — an old jar was deployed (see lesson 1
  in the block-breaking section) and the target was standing in an open field.
- [x] Final PVP fixes along the gate trace: a direct-charge for the last
  half-block (a BFS tolerance of 1.5 left the bot at 3.06 with a reach of 3.0) and pinning
  combatMovementsEnabled in the test (a persisted config was overriding the new default).

### Result — block breaking: BOTH COURSES PASS (cycle 8)

- C_wall: the door is broken through (0,-60,20 → air), bot at the target in 12s
- D_sand: the door is broken through, the fallen sand is broken through too, bot at the target
- The road to green (lessons, all fixed):
  1. **The pipeline was deploying an OLD jar**: build/libs had both 0.23.3 and 0.24.0,
     `ls | head -1` picked the alphabetically-first one → every test after a release ran
     the morning's code. Fix: `ls -t` (by freshness). This same bug is why the PVP test
     "passed" against the old combat in an open field.
  2. Direct calls to updateBlockBreakingProgress were being reset by vanilla every
     tick (attackKey not held) — 15s without breaking 2 dirt. Fix: aim + hold
     attackKey, let vanilla mine on its own.
  3. An infinite flat world: going around any finite wall is cheaper than breaking through it
     (block-space A* does NOT accumulate cost) — tests moved into sealed
     bedrock boxes with a dirt door.
  4. Leftover guidance after truncation starved the physical search ("Ran out of nodes") —
     fix: if the player is already at the wall, mine with no physical leg (an empty
     path + breakQueue).
  5. Squeezing through a 1-block hole gives a drift of 0.84 against a threshold of 0.8 —
     tests set ;settings driftThreshold 1.5.
  6. shouldResetSearch: reset bestSoFar/closed on re-route (stale chains
     from the old root); a guard in setCurrentPath (root further than 2 blocks from
     the player → reject); the 253-branch re-search timeout 220ms → 3s.
  7. A persisted tungsten.json overrides the new config defaults —
     test runners now pin the critical settings explicitly (;settings ...).

### Plan — block breaking (v1, pragmatic slice)

Architecture: do NOT wrap the world and do not pause the replay. Segmentation via the
existing GotoCommand retry mechanism (MAX_RETRIES):
1. BlockNode.toBreak: block-space allows "breaking through the wall" for a NEIGHBOR
   cell if the feet/head blocks are breakable (calcBlockBreakingDelta>0, not
   bedrock); cost += breaking_ticks (vanilla: 1/delta) + recursion over any
   FallingBlock above (baritone pattern). Config: allowBreak (def true),
   breakCostMultiplier.
2. PathFinder: truncate blockPath at the first break node — physics drives the bot
   UP TO the wall; breakQueue is handed to the executor.
3. PathExecutor: after replay ends, if breakQueue is non-empty and the block is within
   reach 4.4 — a BREAKING tail: aim at the block center, attackBlock +
   updateBlockBreakingProgress + swingHand every tick, until the passage cells
   are walkable (the loop also covers fallen sand), then cb → retry GotoCommand
   → a new search in the now-clear world.
4. Tests: course C (a 2-high dirt wall on the path), course D (sand above
   → falls into the passage → gets broken through too). deploy/runner/break_test.py.

Key code locations (from the audit): BlockNode.shouldRemoveNode:421 (normal cube
reject) and wasCleared:552; children cost BlockNode:373; break execution —
the baritone pattern interactionManager.attackBlock/updateBlockBreakingProgress
(BaritonePlayerController.java:60-93, BlockBreakHelper.java:52); PathInput
doesn't need to change (breaking happens outside replay).

## Tungsten slime parkour + phase-0 autotest (2026-07-20)

### Investigate

- The slime mechanic in tungsten had been partially started and reverted (48dc410); status in
  `docs/features/TUNGSTEN_SURFACES.md`. The Agent.tick physics is correct (bounce,
  no fall damage, onSteppedOn slowdown) — what was broken was specifically the routing.
- Blockers found: `checkForFallDamage` (cuts off any fall >2.75),
  `isJumpImpossible` (cuts off children higher than +1.4 before the slime exceptions),
  airborne/midfall pruning in Node, a debug Thread.sleep(250) in the slime branch of
  block-space, an off-by-one at the top bounce level, SlimeBounceMove pressing jump
  on the landing tick (jump() overwrites the bounce velY back to 0.42).

### Plan

- [x] Slime exceptions in every pruning spot (isSlimeColumnBelow, scan 32)
- [x] Bounce height for block-space children from cumulative fall (min 1.25)
- [x] Rewrite SlimeBounceMove (initiate only from rest)
- [x] Phase-0 bench from AUTOTESTING.md on the mac (deploy/compose.test.yml)
- [x] Run slime_test.py (courses A: drop-4 → +3, B: drop-3 → +2) to PASS

### Implement

- [x] tungsten: bf48a82 — slime routing (6 files)
- [x] deploy/: 549bf11 — compose (itzg vanilla 1.21.11 + mineswarm-mc:amd64),
  runner slime_test.py (py4j via docker exec + rcon-cli), autotest.sh
- [x] Build on the mac: BUILD SUCCESSFUL 47s, jar deployed to deploy/run/mods
- [x] Iterations over failures:
  - `;goto` was silently ignored after `;stop` — a stuck `PATHFINDER.stop`
    (fix e1647fd: reset the flags in GotoCommand)
  - fill running before chunks loaded — forceload + build verification (c80018f)
  - a village on the course (superflat generates structures) —
    GENERATE_STRUCTURES=false + recreate the world (4fc7502)
  - drift-abort at 8.9 blocks: shouldResetSearch was re-routing the search without emitting a
    prefix while the executor was idle (fix 8ec354e: inline resetSearch)
  - course B "flat bounce" is impossible under vanilla physics (apex ~1.9) —
    removed the 1.25 minimum in block-space, the course was redone as drop-3 (8ec354e)
- [x] **Result: both courses PASS** (A in 6s, B in 6s, health 20 — no damage).
  The bench stays up on the mac (noVNC http://192.168.1.20:5820,
  to run again: `sh deploy/autotest.sh [--no-build]`)

## Autotesting — design of the auto-deploy/autotest pipeline (2026-07-20)

### Investigate

- mineswarm (`../mineswarm`): headless MC **clients** in Docker (PortableMC → Fabric 1.21.11,
  llvmpipe, noVNC), py4j baked-in; the mod is deployed by copying the jar into `game/minecraft/mods/` + restart.
  The gateway reaches py4j via `docker exec` (py4j listens on loopback inside the container).
- The mac (mactrindetz, M4 Max/48GB): Docker Desktop is present, the mineswarm mini-stack is already running
  (`docker-compose.mac.yml`, `mc-crossentropy` as linux/amd64 under Rosetta), clones of
  unionclef/mineswarm live in `~/repos/pet`, Java 21 is installed.
- unionclef already has: `Py4jEntryPoint` (~100 methods), an e2e-test skeleton
  `scripts/custom/example_server_test.py`, autoConnectServer, multi-version support (replaymod
  preprocessor), ClefForge docker build.
- Precedents: agicraftmc (RCON test server + push-based auto-deploy), nettyan-toolkit
  (self-hosted runner deploy).

### Plan

- [x] Write `docs/AUTOTESTING.md`: architecture (test server + N clients on the
  mineswarm-mc image + a python runner), the `deploy/` layout, scenarios (@goto, ;goto parkour,
  ;followPlayer, #goto bridge, nightly @gamer), the trigger (a self-hosted GH runner on the mac),
  phases 0-3 with estimates, risks (Rosetta/llvmpipe FPS → flakiness, an arm64 image as the fix).

### Implement

- [x] `docs/AUTOTESTING.md` — design only, no pipeline code was written (the phases are separate TODOs).

## Shredder — pathfinder v2 (baritone + tungsten)

### Investigate

- Studied the baritone structure: 341 Java files, 75 packages
- API surface: 158 files in `baritone/api/`, altoclef makes 144 imports from baritone
- Key entry points: `BaritoneAPI.java`, `IBaritone.java`, `Settings.java`
- Baritone is wired in as a Gradle subproject via the `namedElements` configuration
- Mixins: 19 client mixins in `baritone.launch.mixins`
- External dependencies: nether-pathfinder, mixin, jsr305

### Plan

- [x] Pick a name → **Shredder**
- [x] Copy baritone → shredder (the `baritone.*` packages kept as-is)
- [x] Set up metadata: build.gradle, fabric.mod.json, mixins.shredder.json
- [x] Register in settings.gradle.kts and build.gradle
- [x] TODO 2.3: Switch altoclef from `:baritone` to `:shredder`
- [ ] TODO 2.4: Implement windMouse / AI smooth camera movement
- [x] TODO 2.5: WindMouse + tungsten integration in shredder

### Implement

- [x] Copied baritone → shredder/ (341 files, `baritone.*` packages left as-is)
- [x] shredder/build.gradle — archivesBaseName "shredder", version 0.1.0, group "shredder"
- [x] fabric.mod.json — id "shredder", name "Shredder", author "3ndetz", GPL-3.0
- [x] mixins.baritone.json → mixins.shredder.json (content unchanged)
- [x] BaritoneMixinConnector → references `mixins.shredder.json`
- [x] settings.gradle.kts — added `include(":shredder")`
- [x] build.gradle — replaced `:baritone` → `:shredder`, removed the baritone dep
- [x] Altoclef imports unchanged — `import baritone.*` works since shredder exports the same packages

#### TODO 2.5 — WindMouse + Tungsten integration

##### 2.5.1 WindMouse in LookBehavior (render-frame camera smoothing)

- [x] Replaced the exponential-decay `updateSmoothRotation()` with a WindMouse algorithm in `LookBehavior.java`
  - WindMouse physics: gravity (pull to target), wind (random perturbation), velocity clamping
  - Dual-mode: `windMouseLook=true` → WindMouse, `false` → the old exp-decay (fallback)
  - Frame-time scaling: correct behavior at any FPS (not tied to 60)
  - Human-like flick: maxStep scales with distance (far angles → fast flick)
  - Snap threshold: at <0.3° to the target — snap, reset velocity
- [x] Added settings in `Settings.java`:
  - `windMouseLook` (Boolean, default true) — enable WindMouse
  - `windMouseGravity` (Double, default 3.5) — attraction strength to the target
  - `windMouseWind` (Double, default 1.2) — amplitude of random wind
  - `windMouseMaxStep` (Double, default 5.0) — max degrees per frame
- [x] Server-side rotation integrity: game tick still uses `peekRotation()` (mouse quantization + random jitter) → server packets unaffected
- [x] WindMouse state reset on: smoothActive activation, onWorldEvent, cancel

##### 2.5.2 TungstenBridge — delegation shredder → tungsten

- [x] Created `baritone.tungsten.TungstenBridge` — a coordinator between shredder and tungsten
  - State machine: INACTIVE → PATHFINDING → EXECUTING → RETURNING
  - Smart segment evaluator: checks ≥N consecutive flat MovementTraverse/Diagonal with no break/place
  - Delegation: launches tungsten PathFinder with a short timeout (3s), monitors the executor
  - Stall detection: abort if no progress for >60 ticks (3 sec)
  - Arrival detection: abort if the player is within 1.5 blocks of the target
  - Callback-based completion: executor.cb → RETURNING state
- [x] Added settings in `Settings.java`:
  - `useTungsten` (Boolean, default false) — enable tungsten delegation
  - `tungstenMinSegment` (Integer, default 8) — minimum simple moves for delegation
- [x] Wired into `PathExecutor.onTick()`:
  - Bridge tick BEFORE movement.update() — if tungsten active, shredder yields (clearKeys)
  - Segment evaluation every tick when bridge is inactive and not sprint-jumping
  - pathPosition snap forward to the resume point after tungsten completion
  - Bridge reset in cancel()
- [x] Build dependency: `shredder/build.gradle` → `implementation project(":tungsten", "namedElements")`

#### TODO 2.7 — Fix Jump Bridging

##### Investigate

- Current implementation in `PathExecutor.java` (lines 885-1050): a two-phase state machine (SPRINT → AIRBORNE)
- **Root cause 1 — rotation/objectMouseOver timing**: listener order in Baritone: LookBehavior(1st) → PathingBehavior(2nd) → InputOverrideHandler(4th). `tickJumpBridge` sets the rotation target + CLICK_RIGHT in onTick. Then `blockPlaceHelper.tick()` processes the click with `objectMouseOver` from the **previous** render frame (still forward-looking). Rotation is only applied in `onPlayerUpdate(PRE)` — AFTER the click is processed. Result: the click hits empty air.
- **Root cause 2 — no placement verification**: line 1035 unconditionally advances `jumpBridgeLastSolid` after a click, with no check that a block was actually placed. All subsequent clicks target blocks that don't exist.
- **Root cause 3 — 180° mid-air rotation**: the SPRINT phase looks forward, AIRBORNE tries to turn 180° in 1-2 ticks. WindMouse smoothing (3.5°/frame max) makes this impossible within the flight time. Even with a `blockInteract=true` snap, objectMouseOver only updates on the next render frame.
- BlockPlaceHelper: `rightClickSpeed=4` → a 3-tick cooldown between clicks. Over 12 airborne ticks = max 3-4 clicks.

##### Plan

- [x] Diagnosis: 3 root causes (timing, verification, rotation)
- [ ] New state machine: `SPRINT → PRE_ROTATE → BRIDGE`
  - SPRINT: sprint to the edge (as now), transition to PRE_ROTATE instead of jumping
  - PRE_ROTATE: rotate 180° backward BEFORE jumping (while standing on the ground)
  - BRIDGE: walk backward (MOVE_BACK = forward in the world), jump off the edge, place blocks mid-air
- [ ] Placement verification: check `canWalkOn` before advancing lastSolid
- [ ] Continuous bridging: stay in BRIDGE after landing (don't reset to NONE)

##### Implement

- [x] New state machine in `PathExecutor.java`:
  - `SPRINT → PRE_ROTATE → BRIDGE` (instead of `SPRINT → AIRBORNE`)
  - SPRINT: sprint to the edge, transition to PRE_ROTATE at `distToDest < 1.0`
  - PRE_ROTATE: sneak at the edge + rotate backward (yaw+180°, pitch 75°), wait for `yawDiff < 20°`
  - BRIDGE (on ground): MOVE_BACK (= forward in the world), JUMP at `distToEdge < 0.9`
  - BRIDGE (airborne): MOVE_BACK for momentum, track face center, click + verify
- [x] Placement verification:
  - `canWalkOn(bsi, expectedPlace)` checks that the block actually appeared in the world
  - lastSolid advances ONLY after placement is confirmed
  - Retry the click every tick until confirmed (instead of an optimistic advance)
- [x] Continuous bridging:
  - The BRIDGE phase does NOT reset on landing
  - On the ground: snap pathPosition, check nextMove, re-select throwaway, walk + jump
  - Between jumps: the bot stays backward-facing → no 180° rotation mid-air
- [x] Removed unused `jumpBridgeRandom` field
- [x] Added `jumpBridgeAirborne` + `jumpBridgeAirborneTicks` sub-state tracking
- [x] Added `wrapDegrees()` helper for rotation comparison

##### Rewrite — Sprint-Speed Telly Bridge (2025-03-20)

Complete rewrite of the jump bridge state machine. Key breakthroughs:

- [x] **TestBridgingCommand GoalBlock fix**: GoalXZ → GoalBlock at player Y level (prevents pathfinder descending to ground)
- [x] **processRightClickBlock bypass**: objectMouseOver raycast misses at 86°+ pitch. Direct `ctx.playerController().processRightClickBlock()` with calculated BlockHitResult bypasses crosshair entirely.
- [x] **setSprinting(true) force**: `Input.SPRINT` override alone doesn't re-trigger sprint. `ctx.player().setSprinting(true)` forces sprint at entity level.
- [x] **5-phase telly cycle**:
  1. FJ_SPRINT: face forward, W+Sprint, jump at edge (setSprinting on ground)
  2. FJ_AIRBORNE (placement): face backward (dynamic aim), no movement keys (pure inertia)
  3. FJ_AIRBORNE (recovery): snap forward + W+Sprint when nearing ground
  4. Landing: face forward, W+Sprint, setSprinting(true) → sprint preserved
  5. Continuous cycle back to FJ_SPRINT
- [x] **Y-level safety**: exits immediately if player drops 0.8 blocks below bridge
- [x] **Sneak on path end**: sneaks when jumpBridgeCanContinue fails
- [x] **bridgeCount ≥ 6**: prevents overshoot near goal, slow bridge handles last 5 blocks
- [x] **Cooldown 20 ticks**: fast re-activation after path transitions
- [x] **Scan-ahead 15**: finds longer consecutive bridge segments

Result: sprint=true on every jump, 2-3 blocks/jump, 30+ blocks without falling.

##### Optimizations

- [x] Debug logging behind `JB_DEBUG` flag (default false)
- [x] itemUseCooldown reset via reflection before each processRightClickBlock
- [x] Lateral drift correction in FJ_SPRINT (sign was inverted, fixed)
- [x] bridgeCount threshold tuned (4 minimum, scan-ahead 15)
- [x] Cooldown reduced to 10 ticks for faster re-activation between path segments
- [x] Graceful exit when <3 bridge moves remain (prevents overshoot at path end)
- [x] Dead FJ_LAND/FJ_BACKUP phases removed (continuous telly doesn't stop)

###### Remaining

- [ ] itemUseCooldown: replace reflection with @Accessor mixin
- [ ] Pre-sprint during slow bridge runway (first jump is walk-speed)
- [ ] A/D strafing during camera flick
- [ ] Anticheat-friendly rotation (WindMouse for the backward flick)

---

