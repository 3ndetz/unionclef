# TODOs

## 🔴🔴 CRITICAL REGISTER — full audit 2026-07-27 (do NOT delete an entry without a fix + test)

> Full write-up with evidence: **[docs/ai/audit-2026-07-27-tungsten-full.md](docs/ai/audit-2026-07-27-tungsten-full.md)**.
> Method: 7 parallel source readers + 7 adversarial verifiers, 88 findings survived re-check.
> This register exists so nothing critical is silently dropped. Every line carries file:line.
> Mark `[x]` ONLY with a fix AND a stand test. Mark `[~]` for partially landed.

### C0 — reframing facts (not bugs, but everything depends on them)
- [ ] **`baritone/` IS NOT COMPILED.** `settings.gradle.kts`: `// include(":baritone")`. The live
  pathfinder is **`shredder/`**, in the same `baritone.*` package. Every `import baritone.…` in
  altoclef resolves to shredder. AGENTS.md is wrong on this — fix the doc.
- [ ] **Coupling reality:** 78/561 altoclef files import `baritone.*` (→shredder), 7 import tungsten.
  Not just pathing: `Input` (44), `Goal` (23), `Rotation` (16), and `baritone.altoclef.AltoClefSettings`
  — altoclef's own settings class lives INSIDE the shredder module. Baritone is a load-bearing type
  library here, not a pluggable backend.

### C1 — DEAD CODE THAT SILENTLY DISABLES WHOLE FEATURES
- [x] **C1.1 `TungstenHelper` is permanently dead.** ЗАКРЫТО 2026-07-27: рефлексия выкинута, прямые типизированные вызовы. `initReflection()` (TungstenHelper.java:74)
  looks up `PathFinder.searchTimeoutMs`, a field moved to `TungstenConfig` (`PathFinder.java:83`
  says so). `NoSuchFieldException` → `reflectionReady=false` forever → **`isTungstenLoaded()` always
  returns false** → `tryPathTo`/`tryPathToEntity`/`stop`/`isActive`/`isLocked` are permanent no-ops.
  The whole documented "tungsten as fallback when baritone fails" layer has NEVER run. Also
  `EXECUTOR` is `public static PathExecutor EXECUTOR;` (no initialiser) → latent NPE in the same method.
- [x] **C1.2 `combatExecutorEnabled` gates nothing** ЗАКРЫТО 2026-07-28: настройка и `airStrafeMultiplier` удалены (ноль чтений). — the flag is read NOWHERE, yet `CombatExecutor`
  burns a 30-tick full physics sim per 10 ticks for a debug overlay. `airStrafeMultiplier` likewise.
- [ ] **C1.3 zero-caller code:** `AttackTiming.canAttack` + `isCritState` (so no crit/w-tap timing at
  all), `WeaponSelector.reset`, `FollowEntityTask` jam-detection state, dead decrease-key branches in
  both heaps, `VoxelWorld` (never populated, never read).

### C2 — BLOCK-SPACE SEARCH IS STRUCTURALLY BROKEN
- [ ] **C2.1 Move generation is an either/or that has no good branch.** `BlockNode.getChildren:292-301`
  returns early for `smartMoves`, so `shouldRemoveNode` — and with it **both `tryPlanBreakThrough` and
  `tryPlanPlaceThrough`** — is never reached. So: `smartMoves=false` (DEFAULT) = ~1086 children/expansion
  (~15 000 in the deep retry) but break+place work; `smartMoves=true` = ≤8 clean children but **no break,
  no place, no ladders, no water, no vines, no slime, no diagonals**. **Neither mode is complete.**
- [ ] **C2.2 No g-cost accumulation.** `BlockSpacePathFinder.updateNode:345-364` does
  `child.cost = child.cost + 1` (the CHILD's own cost, not `current.cost + step`), and the `BlockNode`
  constructor **discards its `cost` argument** (BlockNode.java:162-168). Every computed cost — mining
  ticks (`:675`), bridge penalty (`:718`), all of `ActionCosts` — is **decorative**. The search is
  greedy best-first on the heuristic alone.
- [ ] **C2.3 Knowingly-broken distance math on the DEFAULT path.** `getDistFromStartSq:366-377`
  computes Y and Z diffs from `start.x`; the comment admits the copy-paste bug and gates the correct
  form behind `smartMoves` (off). That function gates every partial emission and the `failing` flag
  that arms the timeout. Downstream `bestSoFar:313-328` `continue`s on the furthest node, so it can
  only ever return a node that is NOT the best — inverted selection, admitted at `:298`.
- [x] **C2.4 Physics A\* drops most of its branching.** ЗАКРЫТО 2026-07-27: обе ветки зовут общий acceptChildIfValid. `PathFinder.java:1111` and `:1118` do
  `return null;` inside a chunk loop (`children.size() > 5` path), **aborting the whole chunk on the
  first rejected child** — non-deterministically, since it depends on ForkJoin scheduling order.
- [ ] **C2.5 Closed set is inert.** `PathFinder.java:538-590` quantises to 0.01 blocks and keys on
  inputs/yaw → essentially no state dedup → endless re-expansion of near-identical states.
- [ ] **C2.6 `FastPlanner`'s result is discarded** unless COMPLETE within 250 ms
  (`PathFinder.java:784`), so on any long route the guide is always the blind scan.

### C3 — PERFORMANCE (PERF-1 root causes, now with file:line)
- [ ] **C3.1** Blind scan does ~1086 `new BlockNode` × ~10 `getBlockState` ≈ **10 000+ world reads per
  A\* expansion** (baritone: ~10-15 neighbours).
- [ ] **C3.2** The `MIN_PRIORITY` search thread farms real work onto NORM-priority pools including the
  shared `ForkJoinPool.commonPool` — the "never win CPU against the client thread" comment
  (`BlockSpacePathFinder.java:48-51`) is not what the code does.
- [ ] **C3.3** `TungstenModRenderContainer.*.clear()` is called from the search loop **bypassing the
  render-config gate and the 20 Hz throttle** in `RenderHelper`: `BlockSpacePathFinder.java:209`,
  `BlockNode.java:315`, and `wasCleared:328` (the last runs per CANDIDATE CHILD). These are
  `Collections.synchronizedCollection` → multiple ForkJoinPool threads convoy on one lock.
- [ ] **C3.4** A synchronous 800-node BFS runs on the **client tick thread** whenever the walker is idle
  in the altoclef primary nav.

### C4 — THREAD SAFETY / CORRECTNESS
- [ ] **C4.1 All searches read the live `ClientWorld` off-thread**, from two worker pools, with no
  `BlockStateInterface` equivalent and no chunk-loaded guard. `VoxelWorld` (the would-be cache) is dead.
- [ ] **C4.2 `PathExecutor` state (path/tick/stop/queues) is mutated from the PathFinder worker thread
  while the client thread replays it** — no synchronisation, no `volatile`. `breakQueue` is a
  non-volatile public field written by the search thread.
- [ ] **C4.3 `pendingBreaks`/`pendingPlaces` are static mutable globals** mutated from background threads.
- [ ] **C4.4** Search threads write to Minecraft chat directly from background threads.

### C5 — BREAK / PLACE (the user's headline question: both ARE plumbed in, both are crippled)
- [~] **C5.1 Break is cardinal, same-Y, ONE cell.** ЧАСТИЧНО 2026-07-28: слом добавлен в FastPlanner (тот движок, что реально водит бота) и ПРОБИВАЕТ проход ('Mining done — passage open'). Осталось: маршрут после добычи не возобновляется; dig up/down по-прежнему нет. `BlockNode.java:641`:
  `if (dy != 0 || |dx|+|dz| != 1) return false`. **No dig-down, no dig-up**, no break-to-ascend/descend,
  no diagonal. `@gamer` mining strategies are literally not expressible. One cell per full re-search.
- [ ] **C5.2 Break cost priced with the item CURRENTLY HELD** while the executor swaps to the best tool
  → ~20× mismatch.
- [ ] **C5.3 The executor mines whatever the CROSSHAIR hits**, so `BreakRules` is enforced on the
  intended block, not the one vanilla actually breaks.
- [ ] **C5.4 `mineBlocks()` silently no-ops** on any block with an empty collision shape and still
  reports "Mining done".
- [ ] **C5.5 `planPlaceMoves` ships OFF** and nothing in the default path turns it on → the shipped
  bridging behaviour is still the **reactive 14-second-stall patch** the project rules forbid.
- [ ] **C5.6 `stringPull` deletes the very nodes carrying the break/place plan** before anything reads
  them (`BlockSpacePathFinder.java:412-429`, no `hasBreaks()`/`hasPlaces()` guard).
- [ ] **C5.7 Place has exactly ONE shape** (horizontal bridge, cardinal, same-Y). **No pillar-up as a
  search move.** Pillar/godbridge exist only as reactive tasks bolted on beside the pathfinder.
- [ ] **C5.8 "Cheaty placement" CONFIRMED IN CODE:** `BridgeTask`/`PillarTask` place with a
  **fabricated `BlockHitResult`** and **no aim-convergence check** — the packet goes out regardless of
  where the camera points. And fills emit **up to 96 placements in one client-thread task with no
  throttle** (that is the "6 glass appeared at once" clip).
- [ ] **C5.9** `BridgeTask` has no re-equip / no fallback when the stack empties mid-bridge. Build
  material is a hardcoded 8-item list duplicated in two files, with a third policy elsewhere.

### C6 — COMBAT (root causes, all code-verified)
- [x] **C6.1 THE "STANDS STILL" ROOT.** ЗАКРЫТО 2026-07-27, релиз v0.62.0: мёртвая полоса убрана, 3/3 боевых сценария PASS. (a) `PunkPlayerTask.enterCombat:214-220` **hard-stops all
  navigation** (`PATHFINDER.stop`, `EXECUTOR.stop`, `FollowEntityTask.stop`) — only `combatMove` can
  move the bot. (b) `CombatController.java:138-142` presses forward only at `dist > 3.4` and back only
  at `dist < 2.0` → **in 2.0-3.4, melee range, NOTHING is pressed**. (c) The strafe is the only
  remaining motion and it is suppressed entirely near a drop (`:159-160` sets BOTH keys false); on a
  1-wide bridge the direction flips every tick and it never strafes at all.
- [x] **C6.2 The bot parks OUTSIDE its own reach.** ЗАКРЫТО 2026-07-27: дистанция выведена из TriggerBot.REACH. `combatMove` is content at `dist > 3.4`
  **centre-to-centre**; `TriggerBot` requires `REACH = 3.0` **eye→closest hitbox point**
  (TriggerBot.java:30,59-63,80). At 3.4 centre-to-centre the eye-to-hitbox distance is ≈3.1 > 3.0 →
  `gateReach` fails. It neither closes nor hits. Hard logic bug, not aim feel.
- [~] **C6.3 Three writers fight for the keys in one tick.** ЧАСТИЧНО 2026-07-27: в БОЮ введён CombatMoveIntent, клавиши пишутся один раз за тик. Навигация — ещё нет. `SafetySystem`'s entire WASD/sprint output
  (49 `setPressed` calls) is **overwritten by `combatMove`**, which runs after it in
  `CombatController.tick` (`:36` then `:94`). Then `VoidGuard` runs after and zeroes all four WASD keys.
  Globally: **14 tungsten classes, 202 `setPressed` sites, no arbitration**, resolved only by
  undocumented call order — plus shredder's `InputOverrideHandler`, which yields for tungsten's
  `EXECUTOR` but **NOT** for its `BlockPathWalker`, so it can mute every walker key press.
- [ ] **C6.4 No health input at all** in the tungsten combat engine → 2 of 6 declared stages are
  unreachable. No retreat, no eat, no gap-apple, no potion, no totem.
- [ ] **C6.5 Shield is NEVER raised by the combat engine.** `ShieldBlocker` is reachable only from
  py4j/`CombatPrimitives`, i.e. only if the agent drives it by hand. Directly contradicts FIGHT-1.
  The primitive also presses `useKey` without checking what is in hand.
- [ ] **C6.6 No w-tap / sprint-reset / crit timing.** `AttackTiming.canAttack`/`isCritState`: zero
  callers. Crit jumps fire on a 280-600 ms RANDOM cadence → crits are accidental.
- [ ] **C6.7 Aim + the whole stage machine run per RENDER FRAME with no delta-time term** → every
  tuning constant is framerate-dependent. **This invalidates the past "combat feel" tuning**, which was
  done on a low-FPS stand.
- [ ] **C6.8** `WeaponSelector` is hotbar-only, **enchantment-blind** (plain netherite 100 beats
  Sharpness V iron 75), rescans once/21 ticks, and is called from exactly ONE place
  (`PunkPlayerTask.java:202`, COMBAT mode only). No offhand, no bow/crossbow-by-range.
- [ ] **C6.9** `PunkPlayerTask`'s "no hits for 5 s → re-approach" is a self-perpetuating 5-second
  interrupt cycle, not a recovery.
- [ ] **C6.10** `WindMouse` accumulates pixel deltas while any `Screen` is open (incl. chat) and dumps
  the whole pile in one frame when it closes. `KnockbackEstimator`'s enchantment read is a permanent
  zero and `simulateKnockback` has no terrain collision.

### C7 — INTEGRATION / OPS
- [ ] **C7.1 `UnstuckChain` preempts and tears down tungsten follow/punk and throws the aim to a random
  angle** (URG-2 confirmed). `SafeRandomShimmyTask`'s forced baritone inputs nullify tungsten's key
  presses. `MobDefenseChain` is completely tungsten-unaware and preempts tungsten combat at HP≤10.
- [x] **C7.2 Config persistence poisons defaults permanently.** ЗАКРЫТО 2026-07-28: configVersion + файл не создаётся без явной правки настройки. `TungstenConfig.load():250-262`
  unconditionally re-`save()`s the whole object → once `tungsten.json` exists, **every future shipped
  default is shadowed forever** on that machine. Any stand result from a machine with an old
  `tungsten.json` is suspect.
- [ ] **C7.3 MCP server binds `0.0.0.0` with NO authentication and wildcard CORS, enabled by default.**
- [ ] **C7.4** `gotoXYZ`/`gotoFar`/`stopPathing` — the primary agent movement levers — are routed
  through the **human chat anti-spam rate limiter**.
- [ ] **C7.5** `TungstenBridge` mutates the global persisted `TungstenConfig.searchTimeoutMs` as a side
  effect of delegation and never restores it. Same pattern for the pathfinder accept-thresholds.
- [ ] **C7.6** Server-specific data hardcoded in Java source (`ButlerConfig` chat formats).

### C9 — DOC LANGUAGE DEBT (my own violation, 2026-07-28)
- [ ] **C9.1 `docs/NAVIGATION.md` is written in Russian** (591 lines). The language rule in
  AGENTS.md and at the top of the checklist says ALL instructions/docs/checklists/code
  comments are ENGLISH. I wrote that document — and the stop-hook text — in Russian while
  editing the very rules meant to enforce this. The hook is fixed; the document still needs
  translating.

### C8 — TEST ENVIRONMENT
- [ ] **C8.1** The Mac stand (`mactrindetz.local`) is **not reachable from this session**: ssh key is
  rejected and the creds endpoint is blocked by the permission classifier. Local Windows docker has no
  `mineswarm-mc:amd64` image and the local jars are stale (0.27.0 vs `mod_version=0.61.0`). Standing up
  the stand locally is a prerequisite for every "tested" claim and for the demo videos.

## 🚀 PRIORITY BLOCK — PERFORMANCE + PIPELINED PATHING + REAL BLOCK-SPACE + FIGHTER (user 2026-07-25)

> Order is the user's: **PERF-1 is FIRST PRIORITY**, then the pipelined pathing (PIPE-1) with the
> realism fix it depends on (REAL-1), then the fighter (FIGHT-1). Acceptance is comparative and
> physical: **guaranteed faster than baritone end-to-end**, and clears parkour baritone cannot.

- [ ] **PERF-1 (P0) — FPS/performance is terrible; fix it.** User: "фпс ещё ужасный, производительность
  дохлая. Это первый приоритет." Profile what actually burns frame time and tick time (renderers
  rebuilt per frame, unconditional physics sims, per-tick scans/raycasts, search threads), separate
  mod cost from the stand's software-GL cost with a measurement, then cut. Acceptance: a measured
  before/after FPS + tick-time number on the stand, with the mod idle / navigating / fighting.
- [ ] **PIPE-1 (P0) — FAST BLOCK PATH FIRST, PHYSICS COMPUTED FROM A FUTURE NODE WHILE MOVING.**
  User's design, verbatim intent: build a fast baritone-class block pathfinder INSIDE tungsten; the
  bot **starts walking that block path immediately**; the physics search then computes **from a
  future node (~t+10) DURING the movement**, so the computation overlaps with walking and the bot is
  genuinely faster end-to-end — "чтобы РЕАЛЬНО УСКОРЯТЬ включая расчёт, а не только сам путь".
  Handoff must be seamless (no stop at the splice point).
  - **Simple mode**: move purely on block nodes, baritone-like, as fast as possible (accelerated
    jumps), while the main route is still computing. **Toggleable by a parameter (a function to turn
    it off), ON by default.**
  - **Must NOT break parkour**: any segment reachable only through the physics engine (fence jumps,
    awkward gaps) is computed by physics even when short — "тут уж нужно просчитывать физикой даже
    мелкие маршруты".
  - Acceptance: A/B bench vs baritone on identical start/goal (real terrain + parkour courses) —
    tungsten wins on time-to-goal AND clears courses where baritone fails.
- [ ] **REAL-1 (P0, URGENT, previously unrecorded) — block-space plans PHYSICALLY IMPOSSIBLE routes.**
  User: the search leads through openings that are ~1.5 blocks tall because a SLAB caps them ("стены
  где полтора блока свободно а сверху закрыто полублоком"), then the physics engine cannot execute it
  and the bot stalls. Block-space passability must reflect REAL collision shapes (player 1.8 tall /
  0.6 wide) — slabs, stairs, trapdoors, fences, carpets, snow layers. Blocks PIPE-1: a fast planner
  over an unreal graph just fails faster.
- [ ] **FIGHT-1 (P1) — the warrior bot: more aggressive, faster, shield-aware, smart weapon swaps.**
  User: "должен быть ещё агрессивнее, ещё быстрее, уметь пользоваться щитом и грамотно менять
  вооружение." Architecture note from the user: the FULL-COMBAT ORCHESTRATOR probably belongs on the
  altoclef side (strategy: whom to fight, when to block, when to swap, consumables), while **tungsten
  computes all trajectories and moves under the hood** (aim, ballistics, movement, timing, reach).
  Builds on the existing split (2.5 in this file) and on WeaponSelector (v0.59.0, hotbar melee only).

> User verdict on the clips I sent: "НИ ОДИН ИЗ TODO не сдан", "ГЛОБАЛЬНОЕ ПОЗОРИЩЕ". He is right:
> the clips showed no visualisation, a sluggish camera, a bot standing on a ledge doing nothing, a bot
> fighting with a BOW and dying, and a "terrain" bench built out of a toy strip. My allround report
> ("switched to the sword and finished him") was FALSE — the timeline shows the bot died 4 times for
> 1 kill and the criteria did not even check deaths. Everything here is RE-OPENED; do not mark any of
> it done without a clip the user can watch.

- [ ] **URG-1 (P0) — tungsten cannot path FROM A BLOCK EDGE over a void.** Live: the bot stands ON the
  edge of a block above the void for half the fight, tungsten logs "Ran out of nodes / no block path"
  — it believes it is airborne. Requirement (user): **tungsten must find a route FROM ANY POSITION a
  player can stand in.** ROOT FOUND: `BlockSpacePathFinder.search` starts at `player.getBlockPos()`,
  which floors the entity CENTRE; standing on an edge floors into the NEIGHBOURING column whose floor
  is air -> start node unsupported -> no children -> dead search. FIX IN PROGRESS: `snapToSupport()`
  (collision-box footprint cells -> landing cell below -> small sweep). Needs stand proof on
  edge_duel + a dedicated ledge-start test.
- [ ] **URG-2 (P0) — altoclef Stuck-fix fires CONSTANTLY when not stuck** (there is a GitHub issue; still
  live). ROOT: `UnstuckChain.checkGenerallyStuck` only tests "moved < 1.5 blocks over ~200 samples"
  with no check that the bot is even TRYING to move, and skips only when tungsten is PRIMARY — so
  combat (circle-strafe holds position ON PURPOSE), any non-primary tungsten segment, crafting and
  waiting all trip it, and the shimmy then throws the aim/task away. FIX IN PROGRESS: guards for
  combat / tungsten-active / no-movement-keys-pressed. Needs a live repro test.
- [ ] **URG-3 (P0) — VISUALISATION MUST BE VISIBLE IN EVERY CLIP.** No clip showed tungsten drawing its
  route, and there is NO arrow-trajectory rendering at all. ROOT: the stand's persisted `tungsten.json`
  had `renderVisualization/renderPathMoves/renderCombat/... = false` (shipped defaults are true —
  persist poisoning), and BowShooter never rendered anything. FIX IN PROGRESS: arrow-flight arc +
  predicted-impact marker in BowShooter; `;settings reset` / py4j `resetTungstenConfig()`; the suite
  resets config and pins visualisation ON before every recorded run.
- [ ] **URG-4 (P0) — combat camera is TOO SLOW/smooth.** User: "юзеры крутят мышь РЕЗКО", clean
  WindMouse, doubts the dampers are needed, wants the parameters tuned for SPEED. Stand was running
  gravity 2.0 / maxStep 4.0 / wind 0.8 (persisted, months old). Shipped defaults now 12.0 / 25.0 /
  0.15. STILL TO DO: judge the feel on video, decide whether the aim low-pass + velocity EMA dampers
  earn their keep at all.
- [ ] **URG-5 (P0) — the bot FIGHTS WITH THE BOW and dies.** Live: after shooting it kept swinging the
  bow in melee with a sword in the hotbar, and died repeatedly. ROOT: the tungsten punk pipeline has
  ZERO weapon handling — TriggerBot swings whatever is held. FIX IN PROGRESS: `WeaponSelector`
  (best hotbar melee, hooked into the COMBAT stage). ALSO FIXED IN THE SUITE: every combat scenario
  now carries a "bot deaths" gate — the old criteria let a 1-kill/4-death run report PASS.
- [ ] **URG-6 (P0) — chase_terrain bench must run on the REAL WORLD GENERATOR.** User: "РЕЛЬЕФ — это
  РЕАЛЬНЫЙ ГЕНЕРАТОР МИРА, а не сраный плоский мир"; the shape of the bench is: send the baritone bot
  running in a direction, **tungsten must CATCH it, ideally KILL it**. FIX IN PROGRESS: the scenario
  now runs on `gamer-server` (normal terrain, seed 12345), no arena building, victim runs 140 blocks
  on baritone, our bot punks it; gates = caught + killed + no deaths.
- [ ] **URG-7 (P1) — bow shoots VERY SLOWLY.** Aim used the slow WindMouse mode and only released
  inside a 3.5° cone, so each shot took seconds. FIX IN PROGRESS: fast nav-mode aim for the bow.
  Still to measure: shots per minute on the stand.
- [ ] **URG-9 (P1) — SPECTATOR CAMERA CLIENT for demos.** The arrow arc DOES render now, but a
  first-person recording looks straight down the trajectory, so it reads as a dot at the crosshair.
  Path/jump/combat overlays film fine (proven on melee_basic), ballistics do not. Add a third
  headless client to `compose.test.yml` as a spectator cam (the `capture_demo.record_ext` pattern:
  spectator gamemode, fixed vantage perpendicular to the action) and record ranged/bridge scenarios
  from it. Until then no clip can honestly claim to "show the trajectory".
- [ ] **URG-8 (P1) — BENCH DESIGN OFFER FROM THE USER (accept):** he offers to hand over **schematics**
  for the test polygons and to mark **start = gold block / finish = diamond block**. Build the import
  path: a `@@schem load` / buildBlocks-based loader + an arena builder that pastes a schematic and
  reads the gold/diamond markers as start/finish instead of hand-coded coordinates. This replaces my
  ad-hoc geometry (RW-7) and is how every future polygon should be authored.

## ⛔⛔ URGENT REWORK BACKLOG (user live-tested the demo videos, 2026-07-24 round 2 — RECORD ONLY, do NOT fix; user will take each as its own focused pass)

> Overarching verdict (user): the current build / godbridge / combat mechanisms look CHEATY and
> UNNATURAL — "как будто читерский", "БРЕД". They must be REWORKED to be PHYSICALLY SIMULATED,
> SLOWER (baritone-like), with VISIBLE physics/jumps/approach, and every change MUST be tested on a
> REAL SERVER and checked it is NOT anti-cheat-flagged. "каждый такой ПУК надо тестировать на
> сервере и смотреть, что не будут это флагать." These are MY screwups to fix, recorded now.

> PROGRESS 2026-07-24 (live mac-stand run via the new `pvp` suite): 7/8 gate scenarios PASS after
> F4+F6. RW-9 chase: F4 landed (chase_flat never-catches -> reliable PASS; chase_terrain catches but
> flaky -> needs F10 move-gen). RW-6 ranged: F6 landed (bow lead from position deltas; ranged_moving
> 1/6->2-4/6, allround 0->2 ranged hits). RW-1 combat: melee/edge/narrow-bridge scenarios PASS on the
> stand (0 freezes/standstill) — the live "stands still" feel did NOT reproduce as a gate fail, so
> the combat rework (F1-F3) needs a harder human-jitter scenario. See docs/ai/audit-2026-07-24-pvp.md.

- [ ] **RW-1 — PvP combat still bad / not a speedrunner.** Live symptoms (user):
  - Still spins slowly ("всё ещё медленно крутится"), moves slowly and badly ("медленно и плохо
    двигается"), and STANDS doing a long-look ("стоит смотрит долговид") OFTEN DURING the attack.
  - MOSTLY STANDS / barely moves WHEN THE TARGET IS NEARBY ("бот большую часть времени стоит, почти
    не двигается когда цель рядом") — at close range it should be constantly moving (strafe/hop),
    not frozen. This is the dominant symptom in close combat.
  - Requirement: must attack WITHOUT breaks, like a professional speedrunner ("должен атаковать
    без перерывов как профессиональный спидранер") — never pause/stare mid-fight.
  - TEST INFRA to build: a proper COMBAT, MOVING target that fights back ("найти и создать
    полноценную боевую двигающуюся цель"). Run PvP sessions on DANGEROUS EDGE/BORDER zones
    ("на опасных пограничных зонах") where BOTH bots hit each other AND competently keep footing
    1 block from the drop ("оба бота должны бить друг друга и грамотно устоять на 1 блоке от
    падения"). i.e. edge-aware combat + a live sparring partner, not a static dummy.
  - Related existing item: LIVE-B COMBAT FULL REWORK (below) — same theme; fold in.
- [ ] **RW-2 — Building: approach + break-order not visible; placement looks instant/cheaty.**
  - Breaking a block IS visible, but the bot's APPROACH to the block it must break is NOT
    ("не видно как он ПОДХОДИТ к нужному блоку"), and it's NOT visible how it CHOOSES which block
    to break first ("не видно как он выбирает какой блок ломать первым") — need a real, visible
    walk-to-target + break-order.
  - Placement is BROKEN/absurd: in the //replace clip all 6 glass appeared INSTANTLY at once, "как
    будто ЗАМЕНИЛИСЬ КОМАНДОЙ" ("БРЕД!!!"). The build mechanism looks broken or cheaty. MUST be
    tested on a REAL SERVER, not just the stand ("надо тестировать это на реальном сервере").
  - Requirement: building must be SLOW and physical like baritone ("просто строительство медленное
    как в baritone") — walk to each cell, aim, place ONE block at a time, real timing.
- [ ] **RW-3 — Godbridge is cheaty (no-look, no physics).**
  - The bot places blocks under itself "cheatily" without even looking where it puts them ("както
    читерски ставит под себя блоки не видя даже куда он их ставит — это бред"). No physics visible,
    no jumps visible.
  - Requirement: a REWORK OF THE MODES ("нужен rework режимов") toward physically-simulated
    acceleration + deliberate JUMPS with in-flight block-adjustment-under-self ("физически-
    симулированные ускорения, продуманные джампы с подстройкой блоков под себя в полёте"). Slower,
    natural, real aim at the placement.
- [ ] **RW-4 (cross-cutting) — REAL-SERVER + ANTI-CHEAT validation for every mechanic change.**
  Every build/bridge/combat rework "ПУК" must be tested on a real server and visually checked it is
  not flagged by anti-cheat. Ties to item 11 (anti-cheat humanization) and task #64 (live bedwars).
- [~] **RW-5 — Build ONE CLEAR, GOOD test pipeline for everything (current tests are a mess).**
  User: "сейчас везде мусор." Unify the ad-hoc per-feature scripts (deploy/runner/*.py) into a clear,
  documented, repeatable pipeline: one entrypoint, named suites (nav/parkour/combat/build/bridge/
  ranged), consistent PASS/FAIL reporting, fresh-bot handling, frame+log verification baked in (the
  new verify-with-eyes rule). It must be OBVIOUS what each test checks and how to run it.
  · PROGRESS 2026-07-24: **suite v1 LANDED** — `deploy/runner/run_suite.py` + `uctest/` lib (one
    generic py4j bridge, raising rcon, deterministic arena builders, freeze/self-fall/stand-still
    detectors, artifacts+timeline, retry-once flake policy) with the `pvp` suite: melee_basic,
    edge_duel, narrow_bridge_duel, chase_flat, chase_terrain (RW-9 bench, REAL @goto runner not
    rcon-tp), bow_flee(+hard, info until kite lever), ranged_moving, bridge_assault(+defended),
    allround. Docs: docs/features/PVP_SUITE.md. NOT yet run on the stand (this session had no Mac
    access — permission classifier blocked ssh/creds); first stand run = next step. Legacy script
    migration (F12) remains. Full audit backing it: docs/ai/audit-2026-07-24-pvp.md (5 root causes
    with file:line for RW-1/RW-2/RW-3/RW-9/#67 + ordered fix plan F1-F12).
- [ ] **RW-6 — RANGED/bow demo video from tungsten is MISSING and must exist.**
  No clip shows tungsten ranged shooting; there should be one (bow aim + trajectory + hit). Build a
  clean ranged demo (bow_moving_test already validates the mechanic on the stand) once the capture
  pipeline is solid. Part of the demo set alongside bridge/worldedit/pvp.
- [ ] **RW-7 — Test polygons/ranges need to be better designed and clearer.**
  User: "Полигоны тестов нужно лучше проработать и сделать более понятными." The current arenas are
  ad-hoc and visually ambiguous (see the demo-video saga). Design clean, purpose-built, self-evident
  test polygons (labelled, minimal clutter, deterministic geometry) for each capability.
- [ ] **RW-8 — Prepare SEVERAL PARKOUR regression stands of varying difficulty.**
  User: "паркуры он всегда мог проходить раньше" — parkour USED TO always pass; changing logic must
  not silently break it. Build a graded parkour suite (easy → hard: flat gaps, ascending steps, slime
  bounces, mixed) as a REGRESSION GATE run on every pathfinder/physics change, so a regression is
  caught immediately. (Directly relevant: terrain_test A/B currently FAIL on physics drift — this
  suite would tell us whether that is a regression or long-standing, which we currently can't prove.)
- [ ] **RW-9 — Follow-player NEVER catches a moving target (constant re-route). OLD bug, still live.**
  User: when the target moves, the bot CONSTANTLY rebuilds the route and NEVER catches it ("постоянно
  перестраивает маршрут, и НИКОГДА не может догнать цель. Это старый косяк ещё"). NOTE: LIVE-A was
  marked FIXED v0.52.0 on the STAND (follow_altoclef_test avg 1.4), but the user still sees it fail
  LIVE — stand PASS != live, RE-OPEN. Root is likely the same re-plan churn (physics re-search
  restarts every time the target strays) that LIVE-A/LIVE-B describe; the fix must make the chase a
  CONTINUOUS pursuit of the live target, not a stop-and-re-plan loop.
  - BENCH TO BUILD (the essence, user): run TWO pipelines simultaneously — bot #1 RUNS AWAY on
    BARITONE, bot #2 CHASES on TUNGSTEN, over COMPLEX/HARD terrain. Tungsten MUST CATCH UP
    ("Tungsten ДОЛЖЕН ДОГНАТЬ — вот суть бенча"). Pass = closes distance to melee/contact within a
    bound; fail = never catches. This is a real moving-target chase over terrain, not a flat loop.

## 🐞 BUGS (from live user testing — each = its own GitHub issue, fix by priority, per checklist)

### ⛔ URGENT LIVE BUGS (user live-tested v0.44.0, 2026-07-23 — combat/follow are NOT actually working; my earlier [x] on 2.1/2.2/2.3/2.8/2.10 was WRONG: stand pvp_test PASS != live. RE-OPENED.)
> DELIVERED THIS SESSION (stand-verified; combat FEEL needs LIVE verification — the stand can't
> reproduce a packet-jittery human): v0.45 SHIFT-stick fix; v0.46 movement (walker on + tickDirect
> spin fix -> approaches a moving target); v0.47 aim yaw-smoothing + bunny-hop; v0.48 enemy-velocity
> EMA (root anti-shake); v0.49 LIVE-tunable combat knobs (combatAimSmoothing/combatVelSmoothing/
> combatBunnyHop* via ;settings). REMAINING: live-tune the feel on user feedback; blocking-entity on
> the attack line (nuanced, needs a repro); @gamer-on-tungsten validation (LIVE-C); core_bridge stays
> a #1.6.1 deferral (3 fix attempts, all reverted — flakiness root is the block-space search).
- [x] LIVE-A (URGENT) MOVING TARGET -> STANDS STILL — FIXED v0.52.0 (2026-07-24). Stand-validated:
  follow_altoclef_test PASS (avg dist to a ~3 b/s looping victim 30 -> 1.4), follow_test PASS (avg 2.2),
  pvp_moving PASS (combat approach shares the engine — first hit 6.7s, 20 dmg, improved not regressed).
  THREE layers, found by INSTRUMENTING (walker per-tick DEBUG) not guessing: (1) @follow routed to
  BARITONE (altoclef FollowPlayerTask -> GetToEntityTask, primary=false) -> now drives the tungsten
  follow engine; (2) DIRECT sprint aimed at a ~2s-STALE snapshot -> BlockPathWalker.steerLive re-aims
  at the LIVE target every tick; (3) THE REAL KILLER — DIRECT bailed "danger -> BFS" on nearly every
  tick because hasHolesOnPath scanned the WHOLE line to the 20-block-away target, so the drift-prone
  physics executor did all the moving -> now guards only the IMMEDIATE ~4 blocks (rolling lookahead,
  still void-safe). Also: bail cooldown, bot-displacement stall detection, floored the test arenas.
  Contained: tickDirect used ONLY by follow + PunkPlayer APPROACH; terrain (@goto) uses tickBFS.
  OLD ROOT NOTES (superseded, kept for history):
  · ROOT (found): FollowEntityTask.tick
  drives movement via the physics pathfinder (budget 0.5-3s), which is stopped+restarted every time
  the target strays (line ~208-218); the immediate drift-immune BFS walker only runs at `dist > 6`
  (startFind line ~237), so at CLOSE range there is NOTHING moving the bot while the physics search
  churns. FIX: the immediate walker / direct-sprint must drive the chase at ALL ranges (continuous
  movement toward the live target), physics executor only for precise/terrain legs — mirror the
  @goto walker-primary design. The 2.8 "hysteresis" fix was insufficient. TEST: pvp_moving_test +
  live human target.
  ⭐ ALSO ROOT (found 2026-07-23): the IMMEDIATE MOVERS ARE DISABLED BY DEFAULT —
  `followBlockPathFinderEnabled=false` (FollowEntityTask.startFind never runs the instant drift-immune
  BFS walker; bot depends only on the physics pathfinder -> re-plans forever -> STANDS STILL) and
  `enableLeap=false` (no close-range sprint-approach). combatMove (strafe/kite) is enabled but only
  runs in the COMBAT state, which the broken approach never reaches. Rework: drive approach with the
  immediate walker at ALL ranges + make it robust (no overshoot), physics executor only precise legs.
- [ ] LIVE-B == ⭐ COMBAT FULL REWORK (user 2026-07-23: "combat нужен FULL REWORK, полноценный
  ОТДЕЛЬНЫЙ заход, а не полуфиксы"). Do NOT patch piecemeal — dedicated focused pass, likely a
  fresh context. Live symptoms + root diagnosis:
  · DOESN'T CLICK to attack even with a clear line — just stares. ROOT: the attack gate
    (TriggerBot.tick) is actually CORRECT (fires unless out of reach >3, aim >40deg off, on
    cooldown, or block-LOS blocked). It doesn't fire because the PRECONDITIONS fail: gateReach
    (bot never approaches to <=3 -> LIVE-A no-mover close-range) and gateAngle (aim SHAKES, never
    within 40deg). So "no hit" is a SYMPTOM of the movement + aim bugs, not the gate.
  · AIM SHAKES violently: WindMouse chases a position-packet target that jumps every tick, with
    no smoothing/deadzone/velocity-lead stabilisation -> angle stays >40 -> no attack.
  · Should ALWAYS BE MOVING: active bunny-hop / strafe / jump AROUND the target, never stand and
    stare. Current combatMove is passive / gated off in too many stages.
  · Blocking ENTITY on the attack line (another mob between bot and target) — also needs handling
    (reposition / switch target / attack the blocker), secondary but in scope.
  REWORK REQUIREMENTS: (1) reliable approach to melee reach at ALL ranges (immediate walker/direct,
  not the re-planning physics search — see LIVE-A); (2) stable aim (smoothing + deadzone + proper
  velocity lead for packet-moving players) so angle<40 holds -> the gate fires; (3) always-moving
  bunny-hop + circle-strafe kite; (4) blocking-entity handling; (5) LIVE re-test each (stand
  pvp_test is necessary but NOT sufficient — it passed while live failed; add a moving/human-like
  scenario). My earlier [x] on 2.1/2.2/2.3 was WRONG (stand PASS != live).
- [ ] LIVE-C @gamer STILL runs on BARITONE, not tungsten-primary. User wants tungsten.
  ✅ PROGRESS v0.53.0 (2026-07-24): setTungstenPathing couples smartMoves ON -> tungsten-primary now
  CLIMBS reachable terrain (terrain_test A staircase/B steep/D PASS; earlier A/B "fail" was smartMoves
  OFF, not a wrapper bug). C (2-block wall) needs blocks = correct. ⛔ DEFAULT FLIP STILL BLOCKED:
  gamer_smoke (tungsten-primary @gamer on gamer-server) = bot MOVES but 0 items, stalls ~60s with
  'Ran out of nodes!'. Root: that fires when BlockSpacePathFinder.openSet EMPTIES (L195) — the search
  explored ALL reachable nodes without reaching the goal => the @gamer goal is genuinely UNREACHABLE
  via tungsten's move-set on hard/mountainous terrain (NOT a budget bump; it's move-gen/reachability).
  NEXT FRESH PASS (deep): block-space move-gen/reachability on hard terrain (break/place-as-a-move in
  the search, water/cliff handling, or receding-horizon sub-goal segmentation). Do NOT flip
  TungstenHelper.primary until this + a clean gamer run (items>0). ORIGINAL NOTE:
  ROOT (found
  2026-07-23): `TungstenHelper.primary = false` by DEFAULT -> altoclef nav (@goto/@get/@gamer) uses
  baritone; `setTungstenPathing(true)` (sets useTungsten + experimentalPathfinding -> setPrimary(true))
  flips it, but nothing enables it by default. FIX is NOT a blind default flip: tungsten-primary for
  FULL @gamer survival is unvalidated (terrain-stuck history 13.3b; and combat is only now being
  reworked). Do a validated @gamer-on-tungsten run first (the nightly full-game pass), THEN default
  it on. Interim: the walker (v0.44.0 face-before-move) made terrain nav solid, so tungsten-primary
  is closer to ready than before.
- [x] LIVE-D SHIFT/sneak STICKS — audit 2026-07-24 code-verified the fix IS implemented (VoidGuard
  sneak release when not near an edge + driving->idle key release, MixinClientPlayerEntity.java:108);
  needs only a live re-confirmation. ORIGINAL NOTE:
  SHIFT/sneak STICKS ~5s randomly (esp. pressing sprint near an edge). ROOT FOUND:
  VoidGuard.protect (combat/VoidGuard.java:56) and SafetySystem edge-sneak set `sneakKey.setPressed(true)`
  near a void edge but NEVER release it; when the driving task (flee/punk/combat) ends the sneak is
  left pressed over the human player's control. resetAllState() releases all keys but only fires on
  DISCONNECT, not on task-end. FIX (in progress): VoidGuard releases sneak when not near an edge +
  release mod-controlled keys once on the driving->idle transition.

- ⛔⛔ **MANDATORY FINAL TESTS (боевое крещение, user 2026-07-24) — обязательны для сдачи:**
  - **LIVE-BEDWARS (task #64):** зайти на РЕАЛЬНЫЙ публичный bedwars (через ../mineswarm инструкции —
    `mc.musteryworld.net`, пиратка/offline, MC 1.21.x; @connect/@game навигация по меню сервера),
    сыграть катку на tungsten: убить >=1 РЕАЛЬНОГО игрока, РЕАЛЬНО пошопиться (покупка в живом меню
    магазина), попасть стрелой в РЕАЛЬНОГО игрока, мостить к чужим островам/кроватям. Стенд-части
    (void-остров) уже PASS (bedwars_combat/bridge/bow); осталось РЕАЛЬНЫЙ сервер.
  - **@gamer ПОЛНЫЙ ПРОХОД НА TUNGSTEN (task #67):** бот УСТОЙЧИВО проходит игру @gamer на tungsten
    ДО КОНЦА, не ломается ни на каких маршрутах. Баритон это проходит — довести tungsten целиком,
    чтобы баритон вообще НЕ требовался. Блокер: 'Ran out of nodes' на сложном рельефе (см. LIVE-C/#59)
    — глубокая доработка генерации ходов/достижимости block-space поиска. Пока НЕ флипать primary по умолчанию.

- [x] BUG #26 (CRASH, DONE 2026-07-22) `PathExecutor.getCurrentNode` did `path.get(-1)` on an
  EMPTY path ("mining without a physics leg") → IndexOutOfBounds in the entity tick → whole
  client crash on a goto that needs a 1-block mine. Fix: guard empty path (return null;
  caller null-checks). Needs build+test (mining goto → no crash).
- [x] BUG #27 (unreachable goal → infinite search) FIXED. (a) bounded give-up: altoclef 14s
  net-progress give-up (v0.35) + tungsten search stall-cap (v0.41, 20s no-progress); (b)
  place-to-reach: pillar-up (v0.38) + bridge-across (v0.41) fire on the give-up when the goal
  needs placing and a block is in inventory. GitHub issue #27 closed. (Proactive place-as-a-move
  IN THE SEARCH remains a refinement — see the place-as-a-move item below.)
- [~] PLACE-AS-A-MOVE (user asked "did you add building/bridging to tungsten?"). PRACTICAL GOAL
  DELIVERED: the bot now DOES pillar up (v0.38) and bridge across a gap (v0.41) during @goto —
  validated (pillar_reach_test, bridge_goto_test). CORE BRIDGE RELEASED v0.42.0 (the proper
  in-core fix, per the no-band-aids directive): BRIDGE is now a FIRST-CLASS block-space move,
  mirroring break-through exactly — BlockNode.tryPlanPlaceThrough (toPlace) ->
  PathFinder.pendingPlaces (truncate + 'bridging without a physics leg') -> PathExecutor.tickPlacing.
  Capability-aware + SEGMENTED: gated on planPlaceMoves + per-cell PlaceRules.canPlace (protected
  zones) — one capability-aware pathfinder (break here / place there / walk elsewhere). The CPU-spin
  on wide gaps was FIXED by chaining: a bridge cell's PLANNED floor counts as solid for the next
  child, so ONE search plans the whole multi-cell bridge (no node-budget exhaustion). VALIDATED:
  core_bridge_test PASS — ;goto across a 7-wide sky void plans the bridge, paves cobblestone
  (x=2,3,4), crosses, no spin. DEFAULT OFF -> parkour/walk/existing nav untouched. Exposed as an
  AGENT PRIMITIVE via ;goto + setTungstenPlanPlaceMoves (agent decides when to build).
  RELIABILITY (2026-07-23 focused pass): core_bridge is ~2/6 flaky. WHITE-BOXED (diag_bridge_white.py,
  existing Debug msgs): the search plans the bridge on MOST find() calls; the failures are the physics
  leg simulating walking ACROSS the un-bridged gap and FALLING (drift ~159 blocks, endpoint y=-57 while
  the bot is at y=101) on the find() calls where the block search returns a fall-partial. Two handoff-
  level fixes both regressed to 0/8 (the `blockPath.size()<=2` gate is LOAD-BEARING: alternates pave/
  walk) -> reverted to stable 2/6. CORRECT FIX (next focused pass, #1.6.1-adjacent): when a place/break
  is pending, the PHYSICS search must target the TRUNCATED block-path endpoint (the gap edge), not the
  goal, so the physics leg stops at the edge instead of simming a fall. Invasive physics-search change;
  regressed twice, do it FRESH with break_test (4/4) as the regression guard.
  NOT YET (next focused passes): (a) PROACTIVE @goto bridging — needs the walker to yield a gap
  stub to the executor's place-planned leg (the auto-integration was reverted; @goto still bridges
  REACTIVELY, v0.41); (b) CORE PILLAR place-move (up) for raised goals / 2-block walls (course C).
  STAND NOTE (corrected): the test container has NO CPU limit on a 16-core host (~2.4 cores used) —
  CPU was NEVER the flapping cause. v0.43.0 gated the per-tick physics sim (400->240% CPU) anyway.
  The client boots to the MAIN MENU; tests must call ConnectToServer (they do). Server persists bot
  position across a CLIENT restart, so verify tp reset before a run.
- [x] BUG #28 ('Ran out of nodes' on hard parkour / flaky terrain climb) FIXED v0.44.0. The flaky
  ~40% climbing was NOT the search — it was a walker CONTROL-FEEDBACK SPIN: the walker pressed
  forward every tick regardless of facing, so while the humanized WindMouse yaw was still turning,
  the bot walked the wrong way, shifting the waypoint bearing, moving the aim target -> spiralled
  in a circle (white-box trace: yaw swept ~680deg). FIX: face-before-move (gate forward/sprint/jump
  on yaw within 45deg while onGround; keep momentum while airborne so gap jumps/bounces aren't cut).
  VALIDATED x8 fresh: A 3-wide staircase 6/8->7-8/8, B parkour gaps 4/8->8/8, slime PASS. #34
  (v0.40) parkour move-gen in CombatPathfinder was a prerequisite. REMAINING (minor): 1-block-WIDE
  staircase still flaky at the very top (pathological lateral precision; real terrain is wider);
  pure async ;goto (gotoXYZ, no walker) parkour parity is separate (#1.6.1 async move-gen).
- [x] #34 Tungsten parkour move-gen (jump gaps) — DONE v0.40.0 for the walker path (course B
  climbs, A/D no regression, break_test intact, combat unchanged). Course C (2-block vertical
  wall) — NOW ALSO WORKS (verified 2026-07-23, diag_pillar_c.py 3/3): with a block in hand +
  planPlaceMoves, the walker (v0.44 face-before-move) + the reactive place-as-a-move climb the
  2-block wall onto the ledge. So the full terrain suite (A staircase 7-8/8, B parkour gaps 8/8,
  C 2-block wall 3/3 w/blocks, D air-goal snap) works. Remaining terrain gaps: 1-block-WIDE
  staircase precision (edge case) + pure async ;goto parkour parity (#1.6.1).
- [x] BUG #29 (CRITICAL, live test 2026-07-22) Camera FREEZES locked on a block forever, bot
  hard-stuck; never recovers, survives reconnect. FIXED v0.39.0. Root: WindMouseRotation is a
  static singleton that steered the mouse toward its stored target every render frame — a task
  that set a mine/combat aim and died without clearTarget() locked the camera forever (static →
  survived reconnect). Durable fix: (a) stale-aim auto-release — setTarget stamps a timestamp,
  applyRenderStep releases if nothing refreshed it for 600ms (live consumers refresh every tick,
  a dead task's aim clears in ~0.6s); (b) DISCONNECT hook wipes all tungsten state (aim/tasks/
  break/keys); (c) executor releases attackKey+aim immediately on stop mid-mine. Tests:
  stale_aim_test, disconnect_test, break_test (mining unaffected) — all PASS on the 0.39.0 jar.
- [x] BUG #30 (unreal routes into walls) ADDRESSED — symptom no longer reproducible. #34 (v0.40)
  made CombatPathfinder (walker source) generate only physically-valid moves by construction; #29
  killed the frozen aim; #50 (v0.41) caps unreachable searches; anti-stuck net + executor drift-
  abort catch the rest. VERIFIED: with a real bedrock wall, the bot routes AROUND (wall_recover_test),
  doesn't ram forever. GitHub issue #30 closed. Future hardening: async-search route validator.
- [x] BUG #31 (break-through not completing) ADDRESSED. break_test passes all 4 courses consistently
  (mine door / sand-fall / tool-equip / API). The 'searches forever' half shares #27/#30/#50 roots
  (now fixed — gives up / routes around). GitHub issue #31 closed. Reopen with a live repro if it recurs.
- [x] BRIDGING/BUILDING in path — DONE. Place-as-a-move complete: pillar-up (v0.38) + bridge-
  across-gap (v0.41). @goto now paves a bridge toward the goal when stalled at the edge of a real
  gap with a block in inventory (bridge_goto_test: crosses a 7-wide sky void). Remaining: a 2-block
  vertical WALL onto a ledge (terrain C) still needs a pillar-beside-wall variant — separate.
- [x] USER BUG (2026-07-22) goal on air / upper 2-tall-grass block -> tungsten computes forever.
  FIXED v0.41.0: (a) GoalSnap snaps non-standable ;goto/click targets to reachable ground; (b)
  PathFinder stall-cap (20s no real progress -> give up, re-roots don't mask it); (c) ;goto stops
  its search the instant the bot arrives. goal_air_test: tungsten goes inactive in 2-4s (was
  forever). NOTE for testers: run terrain_test on a FRESH bot — sky-tp tests leave stale async
  block-path state that makes a following terrain run stall (cross-test artifact, not a regression).

## МЕГА-ЦЕЛЬ 2: TUNGSTEN = ПОЛНОЦЕННАЯ ЗАМЕНА BARITONE + ИНТЕГРАЦИЯ — юзер 2026-07-21 (вечер)

> Дословные идеи юзера (фиксирую чтобы не забыть). ПРИНЦИП прежний: удобный
> ИНСТРУМЕНТАРИЙ, а не хардкод/скрипты; везде хорошие КОНФИГИ + API + ВИЗУАЛИЗАЦИЯ
> по мере реализации. Тестировать грамотно во все стороны.

- [x] 12. MCP-сервер В МОДЕ по LAN (реализовано 2026-07-21): `com.sun.net.httpserver`
  bind 0.0.0.0:mcpPort, Streamable HTTP JSON-RPC (initialize/tools/list/tools/call),
  24 инструмента-рычага поверх Py4jEntryPoint (single source). Настройки mcpEnabled/
  mcpPort, compose публикует 25350. Тест mcp_test PASS: initialize→unionclef,
  getGameState (чтение) + fillSelection (действие) через HTTP → 4/4. Клод рулит по
  http://<lan-ip>:25350/mcp. Осталось: подключить в мой Claude-конфиг, дописывать
  инструменты по мере роста рычагов
- [~] 13. TUNGSTEN как DROP-IN замена baritone (проверить что реально работает):
  - [x] 13.1 тумблер setTungstenPathing(on)/pathingMode (py4j+MCP) — включает
    useTungsten + experimentalPathfinding (shredder делегирует tungsten плоские И
    ascend/descend сегменты через TungstenBridge). Flag-тест PASS (off→on→off).
    @goto/@get/@gamer с ним делегируют сегменты tungsten
  - [x] 13.1b КОРЕНЬ НАЙДЕН И ПОФИКШЕН: НЕ shredder-движение — весь altoclef-task-
    чейн глушился! MobDefenseChain выигрывал КАЖДЫЙ тик (prio 70, ложная run-away
    на PEACEFUL — закомmenченная peaceful-проверка) → UserTaskChain (навигация)
    никогда не тикался. Восстановил peaceful-шорткат MobDefense. Плюс UnstuckChain
    +WorldSurvivalChain defer при tungsten-primary (их shimmy тоже преемптил).
    После фикса: swap_test PASS — @goto доходит (dist 0.6) и с tungsten-primary, И
    baritone (был просто заблокирован, не мёртв). Диагностика: [trtick]-лог чейнов
  - [x] 13.2 swap работает: setTungstenPathing(true) → GetToBlockTask.driveTungsten
    Primary зовёт tungsten PATHFINDER.find напрямую (как ;goto). Тест swap_test PASS
  - [x] 13.3 tungsten ВЕДЁТ реальные altoclef-таски (главный анблок, 2026-07-22).
    ВАЖНО: ранний вывод «tungsten замерзает на рельефе/drift» был ПОСПЕШНЫМ и
    ОПРОВЕРГНУТ тщательным диагнозом (задачи #19/#23):
    · tungsten НОРМАЛЬНО ходит по рельефу — контр-курс step-up/step-down/gap PASS
      (;goto за 4с, no drift). Не рельеф и не drift-порог (frozen при threshold=5).
    · @gamer на gamer-server замерзал по ДВУМ причинам: (1) спавн в ОКЕАНЕ — бот
      утонул; (2) КОНФЛИКТ ВВОДА: shredder InputOverrideHandler при inControl()=true
      ставит PlayerMovementInput (форс-клавиши=0 когда baritone не пасится) →
      обнуляет setPressed tungsten-executor'а → бот стоит, а sim уезжает (drift 5+
      при неподвижном боте). Диагноз: чистый ;goto движет (нет altoclef-таска),
      @get замерзал (таск активен).
    · ФИКС: inControl() возвращает false при TungstenModDataContainer.isExecutor
      Running() → KeyboardInput читает клавиши tungsten. Тест @get log 3 с деревьями
      на tungsten-primary: бот доехал 0.5→8.4 И нарубил 3 лога (blocks 0→3). Регрессия
      swap_test PASS. tungsten теперь ведёт навигацию+майнинг altoclef-тасков.
  - [~] 13.3b @gamer на tungsten-primary — TERRAIN-ЗАТЫК ИСПРАВЛЕН (v0.30.0, #20).
    Проверка 2026-07-22: бот застревал на горном рельефе. Корень — НЕ «нет Movements»,
    а ДРЕЙФ физ-executor'а (sim расходится с реальностью на ступенях/склонах → hard-stop
    + поиск отвергает «root far from player» → вечно busy → стоп). Фикс (директива юзера):
    рельеф ведёт BlockPathWalker (спринт от РЕАЛЬНОЙ позиции по block-пути → без sim →
    без дрейфа); источник — cheap grid BFS, иначе робастный block-space путь
    (PathFinder.getComputedBlockPath); executor только на финал <=4 бл + вода/паркур.
    ПРОВЕРЕНО на РЕАЛЬНОЙ горе (seed 12345): бот прошёл ~40 бл естественного рельефа,
    спустился, нашёл ель, СРУБИЛ (held spruce_log), hp 20, 0 падений — раньше стоял.
    swap PASS. Остаётся: паркур (прыжки-гэпы/2-блочная стена — #20 note), устойчивый
    полный проход (nightly), один end-stall на многократном сборе — доследить
  - [x] 13.3d РОБАСТНОСТЬ КРИВЫХ GOAL (#25) — СДЕЛАНО (v0.31.0). goalToVec снапит
    невалидную цель на ближайшую standable-клетку: цель В БЛОКЕ → стоять сверху; цель
    В ВОЗДУХЕ (клик по траве → воздушная клетка над поверхностью) → спуститься на землю
    снизу. Валидные standable-цели без изменений (нет регрессии — swap/staircase PASS).
    Тест D: @goto на воздух (5,-55,0) → бот дошёл до земли (5.6,-60), reached-ground PASS.
    Остаток «сломать блок-цель если в блоке» — на altoclef-логике (GetToBlockTask уже
    майнит цель), анти-стак (v0.30.1) страхует от вечного залипания у недостижимой клетки
    (adjustOnPathStart/isInGoal). НЕ сломать текущее (перетест).
  - [x] 13.3c ВОДНАЯ НАВИГАЦИЯ tungsten (2026-07-22, #24 DONE): корень — shouldRemove
    Node гнал водные ходы через walk-based StreightMovementHelper, который на
    вертикальном/подводном ходе давал неопр. направление → отвергал swim-up →
    бот тонул на дне (resetSearch size=1). Фикс В ЯДРЕ (не скрипт, откатил костыль
    WaterSafety): в воде соседние водные клетки проходимы плаванием напрямую +
    surfacing air-клетки принимаются → всплывает и вылезает. Тест бассейна PASS:
    со дна -63 → цель (12,-60,3), hp 20, не утонул. Очень сложный рельеф — #20/#21
  - [ ] 13.4 ПОРТ BARITONE-ЭВРИСТИК в tungsten (напоминание юзера): у baritone куча
    важного (эвристики A*, стак-детект, wander, dimension-логика, cost'ы) — портить
    в tungsten по мере. ВАЖНО: делать КРАСИВО, в отдельных потоках, ничего не
    блокировать (tungsten find() уже async — свой поток; держать этот принцип везде)
- [~] 14. ПОЛНАЯ break/place-совместимость tungsten с ограничениями baritone/altoclef:
  - [x] 14.1 BREAK: BreakRules → canBreakHook → shouldAvoidBreaking бриджит (защита
    кроватей, avoid-листы, protected-зоны). Приват-детект «не могу сломать → обхожу
    радиус» течёт через тот же хук. Тест protect_test PASS: приват-зона блокирует
    ломание СОЛИДНОГО блока (canBreakBlock=false; воздух всегда «ломаем»)
  - [x] 14.2 PLACE ГОТОВО: PlaceRules + canPlaceHook (симметрично BreakRules) →
    shouldAvoidPlacingAt. Консультируется в placeBlockAtRaw (весь WorldEdit/build) и
    BridgeTask (годбридж стопается на приватах). Config allowPlace/placeDenyZones.
    Тест PASS: place denied внутри, allowed снаружи, re-enabled после clear
  - [x] 14.3 canPlaceBlock(x,y,z) py4j+MCP (canPlace/policyAllows/replaceable);
    canBreakBlock уже был. Оба как MCP-tools
  - [x] 14.4 markProtectedArea(x,y,z,r)/clearProtectedAreas — агент помечает приват,
    кладётся в ОБА deny-списка (place+break). py4j+MCP. Тест PASS
  - [ ] 14.5 (осталось) авто-детект приватов В САМОМ tungsten при фейле слома (сейчас
    авто-детект на стороне altoclef WorldSurvivalChain; прокинуть/подхватывать в
    tungsten-исполнителе тоже, чтоб при живой игре само помечало)
- [~] 15. ОГРОМНЫЕ ДАЛЬНИЕ МАРШРУТЫ (progressive/receding-horizon pathing):
  - [x] 15.1+15.3 gotoFar(x,y,z,horizon) — рычаг: горизонт-сегменты к далёкой цели,
    каждый вызов = один сегмент <=horizon блоков, агент крутит gotoFar→pathStatus→
    gotoFar до finalSegment. Не просит пасфайндер о всём пути (тот фризит на
    огромных целях). Переиспользует gotoXYZ (tungsten) — без правки ядра A*. Тест
    far_test PASS: 60 блоков за 3 сегмента, финал dist 0.6. MCP-tool есть
  - [ ] 15.2 (осталось) авто-версия внутри ядра: idle-навигация пока считается
    сегмент, seamless-переход; сейчас чанкинг на уровне рычага (агент оркестрирует).
    Для полного baritone-паритета — сегментация в самом BlockSpacePathFinder
- [~] 16. ВИЗУАЛИЗАЦИЯ ПЛАНОВ (правило по умолчанию — всё визуализируем):
  - [x] BREAK_PLAN контейнер есть (подсветка блоков к слому)
  - [x] 16.1 PLACE_PLAN контейнер + гейт renderPlacePlan; годбридж рисует «сюда
    поставим» (зелёный). Регрессия bridge PASS. Осталось: fillSelection/build тоже
    в PLACE_PLAN (сейчас fill показывает через жёлтый SELECTION-бокс)
  - [x] 16.2 виз проверена ВЖИВУЮ демо-захватами (x11grab): слайм-клип показал
    зелёный goal-бокс + цветные ноды пути + красную линию направления; bridge-клип
    показал стройку моста. Пути/цели/бридж рендерятся. (bой/break/place-планы —
    по мере живых сценариев; контейнеры и гейты на месте)
- [~] 17. КОМБАТ-ДВИЖОК: multi-target / avoid-target (интеграция altoclef):
  - [x] 17.1 PunkPlayerTask.startAny(allow, avoid) — бьёт БЛИЖАЙШЕГО из allow
    (пусто=любой), не трогая avoid; tryRediscover авто-ретаргет по политике
    (isAcceptable). Мозг решает кого, tungsten исполняет
  - [x] 17.3 py4j/MCP-рычаги: punk/punkAny/punkAvoid/punkStop/punkStatus. Тест
    multitarget_test PASS: avoid=[t2]→target None, allow=[t2]→target t2, stop→сброс
  - [x] 17.4 УБЕГАНИЕ (#26) — СДЕЛАНО (v0.29.0). RunAwayTask (tungsten-native,
    зеркало PunkPlayerTask): вместо пути К цели — путь к безопасной ВНУТРЕННЕЙ точке
    ПРОЧЬ от неё, ре-план по мере погони; void-aware (flee-точка только не у края) +
    общий VoidGuard на executor (убегание не уносит в бездну своим движением).
    Угловые фолбэки от стен/пропастей. Рычаги: ;runAwayPlayer <name> [dist],
    py4j/MCP runAwayPlayer/runAwayStop/runAwayStatus. Взаимоисключение с punk. Тест
    runaway_test: держит ~8 бл (avg 8.0) на 15x15, своё движение не роняет в void.
    Остаётся avoid-ОБХОД (path around avoid-целей) — отдельная мелкая доработка.
  - [ ] 17.2 (осталось) altoclef-мозг: приоритизация целей (ХП/дистанция/угроза),
    связка с threat-table (attackPlayer/avoidPlayer уже есть отдельно) — свести
- [ ] 18. БОЕВОЕ КРЕЩЕНИЕ = ПОЛНЫЙ ПРОХОД ИГРЫ + tungsten_speedrun таск:
  - идея юзера: @gamer в altoclef юзает baritone по максимуму + куча умной логики
    (учёт СКОЛЬКО РЕСУРСОВ осталось, докопать земли если не хватает при стройке,
    чтоб НЕ ЗАСТРЯТЬ, крафт и пр.) — ВСЁ это надо учесть/не сломать
  - [ ] 18.1 аудит @gamer: какие фичи baritone юзаются, где учёт ресурсов/материалов,
    где может застрять — составить карту зависимостей перед подменой на tungsten
  - [ ] 18.2 tungsten_speedrun таск: спидранит игру на TUNGSTEN-механиках вместо
    baritone (punk на NPC, всё нужное поверх tungsten). Свой таск, не ломая @gamer
  - [~] 18.3 два критерия «крещения»: (a) PVP + строительство на bedwars живьём;
    (b) @gamer/speedrun. ПРОГРЕСС: @gamer стартует на survival, baritone-версия
    рубит дерево (2→22 блоков за 70с) — ранняя игра работает. tungsten-версия
    застревает на рельефе (drift). Полный проход — nightly, после tungsten 1.6
  - [ ] 18.4 учёт ресурсов при стройке (мысль юзера): планировщик знает сколько
    блоков есть, докапывает недостающее (земля/булыжник) — не обещает мост/стену
    длиннее запаса; связка с inventorySpace + altoclef-инвентарём
- [~] 20. ДЕМО-РОЛИКИ/GIF (showcase для Discord/GitHub-релизов):
  - [x] сняты 3 клипа x11grab (внешняя запись экрана — getScreenshot грузил рендер
    и ломал движение): bridge (godbridge мост 14 бл), slime (паркур+виз пути), pvp
    (мили vs tester2). GIF+MP4, доставлены юзеру. Тулинг: capture_demo.py
  - [ ] полировка: спрятать HUD/дебаг-чат, дальше камера, чистые сцены; break-клип
  - [ ] залить на GitHub, вставить в релиз-ноты; авто-отправка в ТГ (нужен chat_id)
- [ ] 21. MLG-МУВЫ: дальние атаки + паркур + баллистика (идея юзера):
  - агент СИМУЛИРУЕТ траектории полёта стрелы и выбирает наиболее вероятные
    попадания, УЧИТЫВАЯ что он сам может сменить позицию / прыгнуть в полёте —
    просчёт «выстрел из лука в прыжке с учётом ИНЕРЦИИ»
  - арбалет: заранее зарядил → паркуром встал в удобную позицию → эпично
    подпрыгнул → в полёте выстрелил по НАВЕСНОЙ траектории → попал
  - связка: TrajectorySolver (баллистика с упреждением уже есть) + tungsten
    паркур/инерция игрока + bow/crossbow примитивы. Просчитывать позицию СТРЕЛКА
    (своя инерция/прыжок) а не только цели. «По красоте»
- [ ] 23. FAR-FAR TODO (user 2026-07-22, AFTER everything — after issues/PRs + main merge):
  make tungsten a FULL player — traversal/vehicle mastery, "по красоте":
  - ELYTRA autonomy: descend mountains / cross gaps on elytra, tracking DURABILITY and
    remaining flight; auto-boost with FIREWORKS (rocket count aware); land safely, avoid
    hazards mid-flight.
    - REFERENCE (we already have it in-repo): baritone has full Nether elytra control —
      `baritone/process/elytra/ElytraBehavior.java`, `baritone/process/ElytraProcess.java`,
      `api/process/IElytraProcess.java`, `command/defaults/ElytraCommand.java`,
      `launch/mixins/MixinFireworkRocketEntity.java`. COPY the mechanic / adapt into
      tungsten, and study the hard problems they already solved (path solver over terrain
      while flying, firework boost timing, pitch/aim control, durability/landing, chunk
      loading ahead of the flight) instead of rediscovering them.
  - VEHICLES: use and (if needed) PLACE minecarts and boats autonomously as part of a route.
  - MLG on vehicles: boat-in-lava tricks — jump across BURNING boats over lava (place boat,
    hop, repeat) as an MLG crossing. Very far future, "по красоте".
  - General: hazard-aware traversal — pick the safe descent/route considering fall damage,
    lava, void, mob threat, item durability/stock.
- [~] 19. Разбор PR/issues — DONE for this session's scope (2026-07-23):
  - CLOSED with fix notes (fixed this session): #29 (frozen camera, v0.39), #26 (crash, v0.34),
    #27 (unreachable forever, v0.35+v0.41), #28 (ran-out-of-nodes parkour, v0.40), #17 (sprint-jump
    loop to unreachable, v0.41 stall-cap), #30 (unreal routes — routes around now), #31 (break-through).
  - COMMENTED + re-test requested (my work likely helps, need repro): #12 (@gamer freeze), #13
    (always stuck), #20 (recalc loop on terrain change).
  - PRs: ALL handled autonomously (user 2026-07-23 — review+test+merge/close myself, never defer).
    #10 MERGED (1.21.11->main). #23 CLOSED — reviewed all 5 fixes (MobDefense worstSafety, StlHelper
    Double.compare, GoalRunAway cost>0.001, WorldSurvival single-increment, FoodChain stopEat flag
    clear); ALL already in current main (same fixes/comments incorporated via the 1.21.11 work), the
    rest is build/wrapper noise. #22 CLOSED — its stuck/freeze fixes (WorldSurvival move-gated
    increment, UnstuckChain interval=0, tungsten executor try-catch) also all already in main; the
    branch is 448 commits behind (237-file diff) so merging would REVERT the whole current line.
    RiaDev1's fixes ARE in main, via the active branch, not these PRs. Zero open PRs remain.
  - LEFT OPEN (out of this session's pathfinding scope — altoclef crafting/inventory/features):
    #25, #19-craft, #18 (EntityTracker leak), #16, #15, #24, #21 (godbridge sneak), #7, #5, #2.
    Each needs its own repro→core-fix→test pass per the checklist — separate work.
  - СЛЕПОК на 2026-07-22 (3ndetz/unionclef): 3 открытых PR — #23 (misc hidden bugs from
    code audit, RiaDev1), #22 (18 bug fixes: pathfinding/combat/entity-tracking/stuck/
    NPE, RiaDev1), #10 (сама ветка 1.21.11). 14 открытых issues, ключевые (RiaDev1,
    похоже реальные баги): #21 годбридж вечный sneak после фейла установки, #20
    PathingBehavior recalc-loop при смене рельефа, #19 CraftWithMatchingMaterials берёт
    низший тир, #18 EntityTracker blacklist unbounded (memory leak), #17 PathExecutor
    sprint-jump infinite loop к недостижимой цели, #16 PickupFromContainer низший тир;
    плюс #25 крафт-инвентарь (WaluigiDrip), #15 (Guo8410), #13/#12 «always stuck»/freeze
    на @gamer 1.21.11 (FlipperFlopper99), #24/#7/#5/#2 (3ndetz). Разбирать по одному ПО
    ЧЕКЛИСТУ (воспроизвести → чинить в ядре → тест → либо коммент-вопрос).
- [x] 22. МЕРДЖ `1.21.11` → `main` — DONE 2026-07-23 (merge commit 9d8fa96). Promoted the whole
  tested v0.29-v0.41 line to main; conflict was only a stale mod_version (0.21.1 -> kept 0.41.0).
  PR #10 (1.21.11→main) auto-closed as MERGED; main and 1.21.11 now in sync (0 ahead).

## МЕГА-ЦЕЛЬ: ПОРТ ПОЛНОГО BARITONE + WORLDEDIT В ФИЗ-МОДЕЛЬ TUNGSTEN — юзер 2026-07-21

> ПРИНЦИП (напоминание юзера 2026-07-21): рядом ПОЛНЫЙ исходник baritone (baritone/,
> и shredder/ — форк). Для КАЖДОЙ задачи ниже СНАЧАЛА смотреть их реализацию и
> фиксы, внедрять проверенное, НЕ повторять их ошибок. Референсы:
> - бридж/установка как A*-ход: baritone MovementParkourPlace / MovementTraverse (positionsToBreak/ToPlace)
> - схематик-строительство: baritone.process.BuilderProcess (+ ISchematic, палитра, порядок)
> - cost'ы break/place, падающие блоки: baritone.pathing.movement.MovementHelper
> - WorldEdit-like: baritone `sel`/`#set` команды (SelCommand, BuilderProcess selection)
> - A*: baritone.pathing.calc.AStarPathFinder (open set, эвристики) — сравнить с tungsten

Идея: перенести ВЕСЬ функционал baritone (в т.ч. умное schematic-строительство)
+ WorldEdit-подобные команды (`//sel`, `//set`, `//pos1/2`, `//replace`, `//walls`
и пр.) в tungsten и ВСТРОИТЬ в его мега-физдвижок — чтобы, например, в паркуре
он мог ЭПИЧНО ставить блоки (jump-place, бридж через пропасть), а также строить
схемы. tungsten становится единым pathfinder+builder на физике.

- [ ] 7. Block PLACING в tungsten (ФУНДАМЕНТ всего строительства):
  - [x] 7.1 примитив placeBlockAt(x,y,z) — авто-выбор блока, наведение на грань опоры, interactBlock. Тест place_test PASS (4/4 блока: линия+стек). tungsten-подвод в reach — след. шаг
  - [x] 7.2 ГОДБРИДЖ ГОТОВ (2026-07-21): переписал на НЕПРЕРЫВНУЮ pave-ahead модель — БЕЗ sneak, sprint вперёд + мостим до 2 клеток пола вперёд каждый тик (целевая клетка на уровне пола → плоское расширение, падать неоткуда). Ключ: физика точно знает позицию, кладём блок ДО того как нога дойдёт до края. Тест PASS: прошёл ровно N=5 блоков на sprint-скорости, не упал, gap 5/5 (первый прогон без стоп-условия промостил 138 блоков!). Остановка по дистанции. Сломанная sneak+step версия выброшена. Готча: для tungsten-правок нужна ./gradlew clean build (инкрементальная кэширует)
    - [x] bridgeTo(x,y,z) к цели (для bedwars — мост к чужому острову) + команда ;bridge + py4j. Тест PASS: форвард 5.6 + bridgeTo дошёл до x=11
    - [x] визуализация: тумблеры renderVisualization/renderPathMoves/renderBreakPlan/renderCombat (;settings), подсветка клеток бриджа. Регрессия slime/bridge/break PASS — не сломано
    - [ ] ОТЛОЖЕНО (риск ядра A*): глубокая интеграция bridge как block-space move (goto через пропасть авто-мостит). Обоснование: годбридж есть как ;bridge/bridgeTo/py4j-примитив, а по философии block 6 когнитивный АГЕНТ сам решает когда мостить → авто-детект в A* не критичен. Делать отдельной фокус-сессией по образцу baritone MovementTraverse:122-168 (bridge=place at dest.down(), cost=walk+place, side-place/backplace). Диагональный годбридж — туда же
  - [ ] МИНОР: canReach (py4j prediction) флачит — иногда block-space возвращает частичный стаб (found=true/reached=false/breaks=0) вместо полного пути. Захардить ретраем поиска (F_api тест это ловит)
- [ ] 11. АНТИ-ЧИТ ГУМАНИЗАЦИЯ ПОВОРОТОВ (важный поинт юзера 2026-07-21):
  - НИКОГДА не setYaw/setPitch напрямую — античиты палят сразу. Все повороты через mouse-pipeline (changeLookDirection пиксельно-квантованно / WindMouse), «сервер видит как физическую мышь». В боевой ауре (WindMouseRotation) уже настроено грамотно — переиспользовать
  - [x] перевёл ВСЕ мои примитивы с setYaw/setPitch на mouse-pipeline (2026-07-21): BridgeTask+BowShooter→WindMouse (тикаются, сходятся человеко-подобно), майнинг-прицел→WindMouse, placeBlockAt→changeLookDirection (одношот, пиксельно-квантованно). Тесты: bridge PASS с гуманизацией (z-разброс 4.49 естественный), break+place регрессия PASS. Path-replay уже был на changeLookDirection (enableNativeRotation)
  - [ ] humanize-переменные тоньше: пауза на большие углы «поднять мышь», разброс по вкусу (WindMouse-параметры уже дают wind/gravity)
  - [ ] проверить бридж/бой в РЕАЛЬНОМ bedwars против анти-чита — если флагает, крутить humanize-параметры (первая проверка живьём)
  - [x] 7.3 inventorySpace() — свободные слоты + подсчёт блоков по типам (planner не обещает мост длиннее запаса). Тест PASS (free=35, blockCount=64)
  - [ ] 7.4 использование инструментов при ломании (equipToolHook уже есть) + расширить на выбор блока для установки
- [ ] 8. Schematic-строительство (baritone BuilderProcess-аналог на tungsten):
  - [ ] 8.1 загрузка схемы (.schem/.litematic/.nbt), парс палитры и блоков
  - [ ] 8.2 планировщик порядка постройки (снизу вверх, доступность позиций, не замуровать себя), tungsten ведёт к каждой позиции и ставит
  - [ ] 8.3 докупка/добыча недостающих материалов (связка с altoclef-инвентарём)
- [ ] 9. WorldEdit-подобные команды в tungsten (`;` или свой префикс):
  - [~] **9.0 КРИТЕРИЙ СДАЧИ (юзер 2026-07-24): `@@`-ОБРАБОТЧИК КОМАНД WorldEdit — БАЗА ГОТОВА v0.56.0.**
    Валидировано (worldedit_cmd_test PASS): @@pos1/@@pos2 -> селект, @@set stone (3/3), @@replace
    stone cobblestone (3/3), @@copy -> @@paste (реанкор), @@size. Плюс @@walls/@@hollow/@@cyl/@@sphere,
    @@cleanup (уборка лесов, diag_scaffold PASS, без вечного цикла), @@restat/@@minestat, @@hpos1/@@hpos2.
    Префикс `@@` (дистанцирует от основных `@`, обходит клеш с @set). ОСТАЛОСЬ: @@undo (нужен слой
    истории операций), @@schem load как клиентский файл-op (сейчас агент парсит .schem -> buildBlocks).
    ⤵ исходный критерий (для истории):
  - ⛔ **9.0 (исходно `;;`): ОБРАБОТЧИК КОМАНД WorldEdit.** Обернуть ВСЕ
    примитивы в `;;`-команды по образцу WE: `;;pos1 ;;pos2` (углы селекта по позиции игрока),
    `;;hpos1 ;;hpos2` (углы по блоку под ПРИЦЕЛОМ), `;;sel`, `;;set <block>`, `;;replace <from> <to>`,
    `;;walls`, `;;hollow`, `;;cyl`, `;;sphere`, `;;schem load <name>`, `;;paste`, и сколько ещё смогу
    (`;;copy ;;cut ;;undo ;;stack ;;move ;;size ;;count`). Скопировать командлист из WE. Префикс `;;`
    (отдельно от `;` движение/бой и `@` altoclef). Каждая = тонкая обёртка над py4j-примитивом.
    Тестировать каждую. + МУСОР-CLEANUP после стройки (леса/диагонали) БЕЗ вечного цикла (см. отд. task).
  - [x] 9.1 selection: py4j select(x1,y1,z1,x2,y2,z2) — хранит регион, рендерит жёлтую подсветку (SELECTION-контейнер, гейтится renderVisualization), возвращает min/max/volume; clearSelection(). Тест worldedit_test PASS
  - [~] 9.2 операции: //set + //walls + //hollow + //cyl + //sphere ГОТОВЫ (shapes 2026-07-23,
    worldedit_shapes_test 3/3: cyl=circle, hollow=6-face shell, sphere=ellipsoid; py4j+MCP). fillSelection(block)=//set (все клетки), wallsSelection(block)=//walls (4 вертикальные стены, полый центр). Общее ядро fillCells(predicate) — без дублей. ЧЕСТНЫЙ blockName: equipHotbarBlock экипирует названный блок из хотбара (не молча ставит что в руке). Снизу вверх (опора у каждой), кап 96/вызов (truncated), возвращает filled/remaining/complete → агент репозиционируется для дальних. Тест PASS: //set cobblestone держа dirt (4/4, доказан equip), //walls кольцо 8/8 + центр air. Осталось: //replace (нужен синхронный break-примитив), //hollow/cyl/sphere (генераторы позиций поверх fillCells)
  - [x] 9.3 "sel set 0" и прочее WorldEdit-like — select+fillSelection как py4j-рычаги для агента (не хардкод сервера, чистые координаты)
  - [x] 9.4 в survival режиме операции идут через РЕАЛЬНУЮ установку (placeBlockAtRaw/interactBlock), НЕ команды сервера — работает в выживании. NB fillSelection де-нест: placeBlockAtRaw (single source, без вложенного onClientThread — тот дедлочил рендер-тред)
- [~] 10. Интеграция: единый tungsten = pathfind + break + place + build + WE-ops. Цикл агента see→move→build ВАЛИДИРОВАН (agent_loop_test PASS): getGameState→gotoXYZ→buildDefenseAround на bedwars-микросценарии, композиция работает как целое. Осталось: break/mine как рычаг, schematic (block 8), baritone-фичи по мере переноса

## КОГНИТИВНЫЙ АГЕНТ В PVP-ИГРАХ (bedwars и др.) — крупная задача юзера 2026-07-21

Философия (дословно): НЕ скриптовать BedWars, а сделать РЕЖИМ/поверхность, где
когнитивному агенту (Клод по py4j/MCP) УДОБНО играть — он сам выбирает тактику
(застроить кровать, что купить в магазе, когда переть/отступать), а ИГРА/МОД
ПОМОГАЕТ механикой в жёстких битвах (прицел, траектории, пасфайндинг, установка
блоков). Критерий успеха: «чтобы МНЕ САМОМУ (Клоду) было удобно играть и побеждать
за агента». Не всё скриптовать — агент решает, мод исполняет.

- [ ] 6. Поверхность управления PVP для когнитивного агента:
  - [x] 6.1 ВОСПРИЯТИЕ: getGameState() py4j — self(hp/pos/blocks/held/armor/onGround) + players[](name/pos/distance/hp/sprinting) + beds[](детект в r=40). Тест PASS. Магазин читается getOpenScreen, покупка clickMenuByName (не дублируем). Осталось (по вкусу): таймеры раунда, свой/чужой цвет команды
  - [~] 6.2 ТАКТИЧЕСКИЕ ПРИМИТИВЫ (мод исполняет, агент командует): ЕСТЬ attack(mouseClick/interactCrosshairEntity), aim+shoot(shootArrowAt/solveArrowAim, траектория), shield(shieldBlock), placeBlock(placeBlockAt), buildDefense(buildDefenseAround), fill/walls(fillSelection/wallsSelection). GOTO-РЫЧАГ ГОТОВ (2026-07-21): gotoXYZ(x,y,z)→tungsten-пасфайндер + pathStatus(busy/pos/distance/arrived) + stopPathing(;stop+@stop) — keystone perception→action, им же агент репозиционируется для дальних fillSelection-клеток. Тест goto_test PASS (дошёл dist 1.5). retreat/chase — НЕ примитивы, агент композит из goto+getGameState (philosophy: agent decides). Осталось: mineTo (нужен break-примитив)
  - [ ] 6.3 МАГАЗИН: читать меню магазина (getOpenScreen), покупать по имени (clickUiSlot) — агент решает ЧТО купить, примитив «buy(itemName)» исполняет
  - [x] 6.4 ЗАСТРОЙКА КРОВАТИ: buildDefenseAround(x,y,z) — защитный панцирь (стороны+крыша), переиспользует placeBlockAt. Тест PASS: кольцо вокруг кровати замкнуто 4/4 при обходе с 4 сторон. NB: «раздутый счётчик 88» из ранней заметки — ЛОЖНАЯ тревога: py4j стрингифицирует Java-List, а тест делал len(строки)=число символов (~88 для ~7-9 клеток), не элементов. Реально ставит 7-9 клеток корректно (agent_loop: placed=7). Тест починен (конвертит списки). Осталось: выбор материала/паттерна агентом
  - [ ] 6.5 «Игра помогает в жёстких битвах»: авто-ассист прицела/блока щитом/крит-тайминга при активном бою, но СТРАТЕГИЮ (куда идти, кого бить, когда отступать) держит агент
  - [ ] 6.6 BedWarsTask: НЕ выкидывать, но переосмыслить — сделать вариант/режим `@game bedwars cognitive` (или флаг), где вместо скрипта включается tungsten-нападение (;punkPlayer smart) + агент рулит через py4j/MCP. Починить приколы старого таска (устарел)
  - [x] 6.7 умная стрельба из лука в бою (2026-07-22): TrajectorySolver вплетён в
    боевой путь altoclef. KillPlayerTask УЖЕ решает melee<10 блоков / лук+пёрл на
    дистанции (политика «когда стрелять» = altoclef), и его ShootArrowSimpleProjectileTask
    теперь целится через TrajectorySolver с упреждением по self-tracked velocity.
    Улучшение прицела распространяется на бой автоматически (bedwars-нападение через
    KillPlayerTask). Валидировано: стоячая цель 5/5 с 24 блоков (v0.32.0).
  - [ ] 6.8 итог: MCP-инструменты (см. блок 5) = именно этот control-surface, с описаниями чтобы агент понимал что делает каждый

## Управляющий интерфейс (py4j + MCP) — задача юзера 2026-07-20

- [ ] 4. Полный слой ввода мыши в py4j (инкапсулированно, БЕЗ дублей):
  - [x] 4.1 interactCrosshairEntity() — right-click сущности под прицелом (рамки/меню). NB: на headless interactionManager через onClientThread виден как «not in game» — надёжнее key-путь (useKey), см. 4.2
  - [ ] 4.2 mouseClick(button) — left/right/middle одним параметризованным методом (переиспользовать InputControls.tryPress CLICK_LEFT/RIGHT; middle = pickItemKey). Мировой клик через КЛАВИШУ (работает headless, в отличие от interactionManager-обёртки)
  - [ ] 4.3 screenClickAt(x,y,button) — клик по ЭКРАННЫМ координатам для GUI-меню (open Screen.mouseClicked/Released); инвентарные слоты уже есть (clickUiSlot) — не дублировать
  - [ ] 4.4 разобраться, почему interactionManager==null в onClientThread-лямбде (player не null: lookAt работает) — либо чинить, либо задокументировать и везде идти через key-путь
- [ ] 5. MCP-сервер к моду (юзер хочет подрубать Клода напрямую по MCP):
  - [ ] 5.1 тонкий адаптер поверх СУЩЕСТВУЮЩЕГО py4j (один источник правды — методы Py4jEntryPoint; MCP их оборачивает, НЕ дублирует логику)
  - [ ] 5.2 каждый инструмент с описанием/промптом и JSON-схемой (аннотации на методах либо манифест, чтобы не дублировать сигнатуры)
  - [ ] 5.3 py4j остаётся «ради прикола»/для тестов-раннеров; MCP — основной путь для интерактивного Клода
  - [ ] 5.4 транспорт: MCP-сервер (Node/Python) в контейнере рядом с клиентом, ходит к py4j 25333; или мод хостит SSE-эндпоинт напрямую
  - принцип: максимум инкапсуляции и переиспользования, минимум дублей

- [x] implement Tungsten
  - [x] fixes for autoclef
  - [x] implement
- [x] Create the new merged repo
  - [x] change baritone mojmap to altoclef yarn
    - [x] fix mixins
  - [x] 1.21 runs successfully and working
- [ ] 1. Create a new pathfinder: combination of baritone and Tungsten
  - [x] 1.1 Find a suitable name for the new pathfinder
    - autobots theme: Optimus, Bumblebee, Megatron, Starscream, Soundwave, Ironhide, Ratchet, Jazz, Grimlock, Shockwave?
    - ninja turtle theme: Leonardo, Michelangelo, Donatello, Raphael?
    - Solved: "shredder"
  - [x] 1.2 Copy the codebase of baritone
  - [x] 1.3 Implement into project and replace altoclef's baritone calls with shredder
  - [ ] 1.4 Improve baritone features in shredder
    - [x] Fix stupid debug spam and spam "failed"
    - [x] 1.4.1 Implement ACCELERATION for simple safe paths
      - [x] 1.4.1.1 Implement acceleration for straight line running to run and jump when applicable
      - [ ] 1.4.1.2 Implement diagonal moving acceleration and make diagonal movement instead of horizontal stairs-like movement
        - [ ] 1.4.1.2.1 remove stupid mega-multi-change view path nodes when path is clear and simple without danger and complexity
        - [ ] FAR TODO - unrealizeable. Complex. Can't do normally.
  - [x] 1.5 add safe ENTROPY: HUMAN-like movements
    - [x] 1.5.1 WindMouse camera smoothing in LookBehavior (render-frame, settings: windMouseLook/Gravity/Wind/MaxStep)
    - [x] 1.5.2 TungstenBridge — smart delegation of simple flat segments to tungsten (settings: useTungsten, tungstenMinSegment)
  - [ ] 1.6 Tungsten deep integration — improve pathfinding + reduce drift
    > НАХОДКИ 2026-07-21 (из @gamer-теста, головной старт для фокус-захода):
    > tungsten-primary @gamer ЗАМЕРЗАЕТ на survival-рельефе. Проверено: НЕ drift-
    > порог (frozen даже с driftThreshold=5.0). updateVelocity уже ВАНИЛЬНО-
    > КОРРЕКТНА (порог 1e-7, normalize при mag>1), airStrafing уже 0.02/0.026 —
    > симуляция на FLAT верна (slime PASS). Значит расхождение на рельефе =
    > КОЛЛИЗИИ/step-up/склоны/тайминг (1.6.1 block-space Movements + 1.6.2 macro-
    > actions), а НЕ пороги 1.6.3. Приоритет: 1.6.1 (baritone Movements для
    > BlockNode: Traverse/Ascend/Descend/Parkour со step-up/gap/slope) — сейчас
    > BlockNode.getChildren слепо сканит круг r=8, на рельефе не находит проход.
    > Driftкоррекция driftCorrectionEnabled(false) снапит позицию (анти-чит-риск);
    > правильный путь — 1.6.4 closed-loop yaw (анти-чит-safe). НЕ торопить —
    > каждый фикс с перетестом slime, иначе ломается рабочий flat.
    - [ ] 1.6.1 BlockSpace: заменить примитивный BlockSpacePathFinder на baritone-level эвристики
      - BlockNode.getChildren сейчас просто сканирует 3D круг radius=8 — тупой перебор
      - Нужно: адаптировать baritone Movements (Traverse, Ascend, Descend, Parkour, Pillar) для BlockNode
      - Это даст: знание про step-up высоты, gap distance, fence collision, slope — до запуска physics A*
      - Результат: physics A* получает 2-3 умных направления вместо 100+ слепых
      - > РАССЛЕДОВАНО (#34a, 2026-07-22, terrain_test): КОРЕНЬ провала курсов B/C — здесь.
      >   B (диагональ +2x+1y через 1-блочные ямы): async BlockSpacePathFinder НЕ находит
      >   маршрут к цели → driveTungstenPrimary получает вырожденный 2-wp stub от
      >   CombatPathfinder → walker лимпит по 2-wp (stop/restart каждый шаг убивает
      >   sprint-моментум для running-прыжка) → падает в яму. C ("Ran out of nodes!") —
      >   вообще без пути. ВЫВОД: чинить НЕ в walker'е и НЕ в выборе пути (пробовал:
      >   edge-timed gap-jump в walker + предпочтение robust-пути для вырожденного stub +
      >   staleness-guard — всё ОТКАЧЕНО, т.к. A-нейтрально но B не решает; корень — SEARCH
      >   не маршрутизирует рельеф). Нужен ЭТОТ пункт (baritone MovementAscend/Parkour в
      >   BlockNode.getChildren) + 1.6.2 macro-actions. A (сплошная лестница) и flat —
      >   работают (walker + executePath), их НЕ трогать.
      - > INVESTIGATED further (2026-07-22): found two REAL bugs in BlockSpacePathFinder —
      >   (1) getDistFromStartSq used start.x for the Y and Z diffs (garbage distances);
      >   (2) bestSoFar had inverted selection logic returning the wrong node. BUT they are
      >   LOAD-BEARING: course A routes via the async BlockSpacePathFinder (branch 2 —
      >   CombatPathfinder returns <2 for the staircase), and the garbage distances made
      >   bestSoFar emit partial paths A depends on mid-climb. Correcting either bug
      >   REPRODUCIBLY stalls A at (10.7,-51) — two clean warm runs identical; reverting
      >   restores A to (13,-48). CONCLUSION: the search cannot be fixed piecemeal — the
      >   distance calc + failing-flag + bestSoFar + move generation must be reworked
      >   TOGETHER, keeping A (the canary) green at every step. Reverted to v0.32.0-stable.
      >   Interim positive signal to reuse in the rework: with the corrected search, course
      >   C returned real "BFS 18/14 wp" paths instead of "Ran out of nodes".
      - > SmartMoves scaffolding BUILT (2026-07-22, flag-gated, DEFAULT OFF = v0.32.0, A safe):
      >   SmartMoves.java (tungsten-native Traverse/Ascend/Descend/Parkour/parkour-ascend
      >   neighbour gen), TungstenConfig.smartMoves, BlockNode.getChildren branch,
      >   py4j setTungstenSmartMoves, terrain_test SMART=1. getDistFromStartSq/bestSoFar
      >   fixes also gated on the flag. RESULT (SMART=1): still FAILS — A stalls at
      >   (10.7,-51) "Ran out of nodes" with the FIXED search regardless of neighbour gen
      >   (blind or SmartMoves) → the real entanglement is the failing-flag/bestSoFar/
      >   isPathComplete/receding-horizon interaction + node budget + cycles from Descend
      >   moves, NOT just neighbour generation. NEXT (focused effort): instrument the search
      >   loop (why "Ran out of nodes" mid-staircase; node count; why fixed bestSoFar stalls
      >   at 5-block failing threshold near goal), rework holistically with A green each step,
      >   then flip smartMoves default. Scaffolding is committed and dormant (safe).
      - EXPERIMENT PLAN (#1.6.1 focused effort, smartMoves flag isolates all of it):
        - [~] E1 near-goal completion: clear `failing` when goal within MIN_DIST_PATH so
          the search completes standing next to the target (fixes (10.7,-51) "ran out of
          nodes"). TESTING NOW.
        - [ ] E2 cycle/budget: Descend moves let the search oscillate up/down; add proper
          closed-set use for smart neighbours + cap/instrument node count; log why "ran out
          of nodes" fires (numNodes at exit, openSet size).
        - [ ] E3 heuristic admissibility with jump/parkour costs (computeHeuristic vs the
          new ActionCosts) so A* is guided, not exhaustive.
        - [ ] E4 walker execution of SmartMoves paths: jump timing for ascend/parkour
          (baritone MovementAscend.updateState model: jump when flatDist<=1.2 && sideDist<=0.2).
        - [ ] E5 diagonals + water/break/ladder parity in SmartMoves (blind scan has them).
        - [ ] E6 once A green + B/C(where possible) route under smartMoves: flip default,
          broad regression (slime/swap/goto/gamer smoke), release.
    - [ ] 1.6.1b (#34b) C-курс «2-блочная вертикальная стена» физически НЕпроходим прыжком
      (ванильный sprint-jump apex ~1.25 блока). Нужен block-placing: пиллар-вверх (ставить
      блок под себя в прыжке) или лестница из блоков. Это отдельная крупная фича (примитив
      установки уже есть — placeBlockAtRaw; нужна pillar-parkour-логика в исполнении).
    - [ ] 1.6.2 Macro-actions в physics A*: sprint-jump как одна нода вместо 12 тиков
      - Сейчас: каждый тик = нода с 100 вариантами input. 12 тиков прыжка = 12 нод
      - Нужно: "sprint-jump к blocknode X" = одна нода, внутри 12 Agent.tick() без ветвления
      - Результат: дерево A* мельче на порядок, timeout хватает на 20+ прыжков
    - [ ] 1.6.3 Simulation fixes (поштучно, с перетестом pathfinder после каждого)
      - [ ] velocity threshold 1e-5 → 0.003 (vanilla correct) + перетест
      - [ ] AgentInput.normalize → убрать, нормализация в updateVelocity + перетест
      - [ ] airStrafingSpeed 0.06 → 0.02/0.026 + перетест
      - [ ] setSprinting movementSpeed → attribute-like toggle + перетест
      - [ ] fallDistance double → float + перетест
      - Каждый фикс отдельно. Если pathfinder ломается — подстроить costs/heuristic ДО следующего фикса
    - [ ] 1.6.4 Closed-loop executor: yaw-коррекция на основе реальной позиции
      - Сейчас: open-loop, слепо воспроизводит pre-computed input
      - Нужно: каждый тик вычислять posError, корректировать yaw на delta к ожидаемой позиции
      - Результат: drift не накапливается, пути не abort'ятся
    - [ ] 1.6.5 Idle movement: circular path пока pathfinder считает
      - Генератор idle-маршрута от текущей позиции (круг/восьмёрка)
      - Seamless switch idle→real path когда pathfinder досчитал
    - [x] 1.6.6 (эксперимент #32, ЗАКРЫТ 2026-07-22) speed-pipeline «идти по BFS пока
      физика считает ноды впереди». ЗАМЕРЕНО reaction_test.py: @goto → первое движение
      за ~0.1с (0.06-0.07с прогретый, 0.26с холодный старт, avg 0.11с, 5 прогонов).
      Вывод: drift-immune walker (BlockPathWalker) СТАРТУЕТ мгновенно и вообще НЕ ждёт
      физику (сам сприентит к BFS-вейпоинтам от реальной позиции). Премис #32 (медленный
      физ-компьют блокирует старт) неактуален — pipeline-оптимизация НЕ нужна. Юзерова
      гипотеза «или у нас всё и так ок» подтверждена.
- [ ] 2. PVP: полный аудит и переделка комбата tungsten (smart + fast + effective)
  - [x] 2.1 Аудит: почему боится ударить (чрезмерные пре-условия атаки?), низкий DPS, зависание при взгляде в траву (raycast LOS через tall grass?)
    - Итог: триггер гейтился на ванильный mc.targetedEntity (OUTLINE-пик, блокируется травой), прицел вёл с упреждением по COLLIDER; ESCAPE пол-цикла кулдауна; движение к цели выключено дефолтом. Детали: docs/ai/progress.md
  - [x] 2.2 Переделка по результатам аудита: агрессивность, точность, скорость решений
    - свой гейт (reach+COLLIDER LOS+угол+кулдаун) + прямой attackEntity; без ESCAPE-на-кулдауне; движение в бою включено + дожим последних полблока; крит-окно при падении
  - [x] 2.3 Боевой тест на стенде: PASS — первый удар 4.3с, жертва убита (20.0), 0 зависаний, бой в высокой траве (deploy/runner/pvp_test.py)
  - [~] 2.10 (URGENT 2026-07-22, user PVP feedback on v0.31.0) DYNAMIC COMBAT MOVEMENT.
    The bot was STATIC: `CombatController.tick` ran ONLY aim(WindMouse)+trigger — zero
    legs — so it rooted, only rotated+clicked; no strafe/jump/kite; didn't handle a
    target occluded by another entity; showed jump trajectories but never jumped.
    (2.2's claim "движение в бою включено" had regressed / never lived in CombatController.)
    FIX: `CombatController.combatMove()` — LOS+safe = circle-strafe + kite to melee reach
    + randomised crit-jumps; no-LOS = walk the pathfinder route to flank the occluder;
    danger = release legs (safety owns motion); every strafe/jump void-checked. Testing.
  - [ ] 2.4 Полноценный комбат-арсенал (мысли юзера, зафиксировано 2026-07-20):
    - выбор оружия по ситуации: топор/меч/лук; mace-булава с высоты; трезубец (бросок); арбалет; снежки для первой отдачи — примитивы бросков ещё не сделаны
    - расходники: эндер-пёрлы (гэп-клоуз/отступление), золотые яблоки по ХП — сторона altoclef, не начато
    - [x] щит: примитив ShieldBlocker + CombatPrimitives.shieldHold (2026-07-21) — тест shield_test PASS (0/3 урона от стрел при контроле 2/2); тайминги против топора — за мозгом altoclef
    - учёт ХП своего и цели в принятии решений — сторона altoclef, не начато
  - [ ] 2.5 Архитектурный сплит комбата (мысль юзера): tungsten = чистые комбат-ПРИМИТИВЫ с API расширения (удар, прицел, щит, бросок, движение, тайминги); altoclef = мозг боя (анализ поля, ХП, выбор оружия/расходников, стратегия) поверх этого API
  - [x] 2.6 Стрельба из лука (ГОТОВО 2026-07-22, v0.32.0 — прицел altoclef на TrajectorySolver):
    - [x] TrajectorySolver на tungsten (2026-07-21): ваниль-баллистика стрелы (drag 0.99, гравитация 0.05), бисекция по питчу через симуляцию полёта, 3-итерационное упреждение по velocity цели. Примитив BowShooter (прицел→заряд→трекинг→выстрел), py4j shootArrowAt/solveArrowAim
    - [x] автотест bow_test.py PASS: 3/5 по стоячей, 2/5 по РЕАЛЬНО бегущей цели с 18 блоков (ваниль-разброс стрел учтён в порогах). NB: упреждение принципиально не работает по телепортирующимся целям (velocity=0)
    - [x] связать с altoclef-логикой лука (2026-07-22): ShootArrowSimpleProjectileTask.calculateThrowLook теперь дергает TrajectorySolver для прямого выстрела (замена старого g=0.006 closed-form). Упреждение — по per-tick position-delta (getVelocity() у чужих игроков ~0, они двигаются position-пакетами → без deltas нет lead). Выбор оружия/когда стрелять остались в altoclef (KillPlayerTask: melee<10 блоков, лук/пёрл на дистанции). High-angle артиллерия + out-of-range = fallback calculateThrowLookLegacy. Тест bow_altoclef_test.py (@shoot путь)
    - [ ] нейросеть-поправки — пока НЕ нужна: аналитика попадает; вернуться, если реальный бой покажет систематический промах
- [ ] 3. Tungsten block break/place: научить ломать (и в идеале ставить) блоки
  - [x] 3.1 Block-space поиск с учётом ломания — v1: tryPlanBreakThrough (соседняя клетка, ваниль-тики через calcBlockBreakingDelta); NB: A* не аккумулирует cost — в открытом мире обход выигрывает у пролома, честная аккумуляция = следующий шаг
  - [x] 3.2 Физическое исполнение — v1: майнинг в конце сегмента (aim + зажатый attackKey, ваниль майнит), retry гонит следующий лег; отдельный BreakBlockMove с паузой replay не понадобился
  - [x] 3.3 Гравитационные блоки: cost-надбавка за FallingBlock-стек + доломка упавшего в проход (курс D с песком — PASS)
  - [ ] 3.4 (далёкое будущее) редстоун/поршни в модели мира; аккумуляция cost в block-space A*
  - [x] 3.5 Автотест-курсы: запечатанные бедрок-коробки с dirt-дверью (C) и песком над дверью (D) — оба PASS (deploy/runner/break_test.py)
  - [ ] 3.6 Инвентарь и инструменты — сторона ALTOCLEF (мысли юзера, 2026-07-20):
    - та же логика сплита, что в комбате: взаимодействие с инвентарём = altoclef, примитивы исполнения = tungsten
    - [x] научить брать и ИСПОЛЬЗОВАТЬ инструменты — Ступень 1 СДЕЛАНА: equipToolHook (tungsten объявляет «ломаю блок», altoclef экипирует лучший инструмент через getBestToolSlot/forceEquipItem). Автотест E_tool PASS: deepslate-дверь, кирка вне хотбара, курс в бюджете времени
    - [ ] следом: cost в block-space от ЛУЧШЕГО ДОСТУПНОГО инструмента (второй хук bestBreakTicks), не от текущей руки
    - block PLACING: брать блоки из инвентаря, отличать МУСОРНЫЕ блоки от ценных, строить предпочтительно из дешёвого (сначала земля/булыжник)
    - учёт КОЛИЧЕСТВА: понимать «у меня 10 блоков земли или нет» — планировщик не должен обещать мост из 20 блоков при 10 в инвентаре
    - интерфейс tungsten↔altoclef: tungsten объявляет потребность (нужен инструмент X / нужно N блоков), altoclef решает чем платить из инвентаря
    - переменных много: hardness×инструмент×зачарования, мусор/не мусор, резерв блоков, порядок трат — двигаться инкрементально, каждая ступень с автотестом
  - [ ] 3.7 ВИЗУАЛИЗАЦИЯ ломания (фидбек юзера 2026-07-20: «ломается блок, но он даже не показан»):
    - [ ] подсветка блоков, запланированных к слому (из toBreak плана) — рендер-бокс до и во время майнинга
    - [ ] прогресс ломания на подсвеченном блоке (стадии/цвет по breaking progress)
    - [ ] общий принцип: КАЖДУЮ механику стараться красиво визуализировать (как рендерятся пути/цели) — это правило по умолчанию для всех будущих фич
  - [ ] 3.8 БАГ: после слома блока задача завершается, а не продолжает путь до цели — довести «goto сквозь стену» до бесшовного (слом → продолжение без видимой «смерти» задачи; retry-цепочка должна быть незаметной)
  - [x] 3.9 Конфиги — СДЕЛАНО (2026-07-21): docs/features/TUNGSTEN_CONFIG.md — полный справочник всех полей tungsten.json (ломание/комбат/follow/пути/совместимость) с дефолтами и «когда менять»; про переопределение сохранённым файлом предупреждено; в ноты следующего релиза — раздел конфигурации
  - [x] 3.10 Богатое API — СДЕЛАНО (2026-07-21), автотест F_api PASS:
    - [x] BreakRules — единая политика «можно ли ломать»: config deny-список блоков, deny-ЗОНЫ [x1,y1,z1,x2,y2,z2], block entities всегда запрещены; применяется в планировщике, исполнителе (перепроверка каждый тик) и API
    - [x] связка с altoclef: canBreakHook → AltoClefSettings.shouldAvoidBreaking (break-avoiders, защита кроватей, protected-зоны тасков — один источник правды)
    - [x] py4j: canBreakBlock(x,y,z) и canReach(x,y,z,withBreaking) → reached/pathSize/breaks/endDistance — эвристика «дойдём ли: с ломанием (reached=true, breaks=2) / без (found=false)» проверена тестом
- [ ] 2.7 PVP: доводка по реальному использованию (фидбек юзера 2026-07-20 — «работает ужасно»):
  - [x] «ждёт вечно чего-то» — главный источник убит: вечные пере-планы follow (см. 2.8); pvp_moving_test: 0 фризов за 120с погони с боем
  - [x] «телепортирует взгляд» при майнинге — плавный поворот 16°/тик, атака только при доведённом прицеле; (в бою прицел и так через WindMouse)
  - [x] прогнать против ДВИЖУЩЕЙСЯ цели — pvp_moving_test.py PASS: первый урон 6.8с (с погоней), 18.0 урона, 0 фризов
  - [ ] реальный бой с человеком — остаётся финальной проверкой (skypvp-крещение, 2.9)
  - [ ] «левый клик плохо/редко жмётся» (live-наблюдение юзера) — диагностика: кулдаун-гейт 0.95 слишком строгий? LOS/угол-гейты режут чаще, чем нужно? свинг не виден (attackEntity без нажатия клавиши)? добавить видимый клик/свинг и трассировку частоты атак
  - НИ ОДНА ветка не считается завершённой, пока не работает в реальной игре гладко — критерий юзера
  - [x] 2.7.1 BEDWARS: ПАДАЕТ В VOID + 0 КИЛЛОВ (юзер 2026-07-22, тест релиза 0.26 —
    моя задача #28) — ИСПРАВЛЕНО (v0.28.0). Причина (найдена in-combat телеметрией):
    не pursue-движение, а САМА stage-машина боя — DANGER_BATTLE спринтовал,
    DANGER_IMMINENT-торможение ПРЫГАЛО, и у края мелкого острова brake-jump
    выкидывал бота в бездну; reactive edge-check смотрел лишь 1.35 бл (спринт-инерция
    перелетала). Фикс: финальный void-aware clamp движения — не спринтовать/не прыгать
    К обрыву в ЛЮБОЙ стадии, sneak-стоп у края (ванильный) со скорость-масштаб. lookahead,
    нокбэк-recovery сохранён (гасим только когда сам рулю в край), не преследуем цель в
    бездну. Aim ускорен (WindMouse gravity 3.2/maxStep 7). Тест bedwars_combat_test
    BRIDGE SOLO 90с: 11 киллов, 0 смертей, 0 падений (было 2-5/мин). MUTUAL — падения
    только от нокбэка, симметрично с идентичным ботом (обычный PvP, не «сам в бездну»).
    Остаётся (future): позиционирование «край за спиной» для нокбэк-падений в mutual.
- [ ] 2.9 Боевое крещение PVP: skypvp на mlegacy.net (задача юзера 2026-07-20)
  - [x] заход на сервер: mlegacy-капча (rotation-пазл) — клики/повороты РЕШЕНЫ (interactCrosshairEntity/mouseClick), но сам визуал-пазл на дрейфующем рендере не гарантируется; юзер: даже человек через vnc не может → УШЛИ на musteryworld (там анти-бот проходится авто)
  - [x] ЖИВОЙ ЗАХОД musteryworld + НАВИГАЦИЯ ХАБА (2026-07-21): коннект+auth+register, компас→МИНИ-ИГРЫ→BEDWARS по именам через новый clickMenuByName — Я В BEDWARS-ЛОББИ. «Самый сложный» вызов (меню-навигация) решён надёжно
  - [ ] очередь в матч (прыжок «быстро начать») → реальный бой bedwars против живых → тест tungsten-нападения
  - точка входа игры: `@game bedwars` / когнитивная поверхность (блок 6)
- [x] 2.8 FollowEntityTask: преследование сломано на движущейся цели — ИСПРАВЛЕНО (2026-07-21):
  - причина: пере-план каждые 0.75с при смещении цели >1.5 блока — поиск (бюджет 0.5-3с) убивался вечно, путь не эмитился
  - [x] гистерезис: мин. 2с между пере-планами + порог max(3.0, 25% остаточной дистанции)
  - [x] автотест follow_test.py: жертва бежит по прямоугольнику ~3 бл/с 90с — средняя дистанция 2.0 (лимит 10), финальная 0.6, 0 фризов — PASS
  - [ ] (запас на будущее, пока не нужно) инкрементальное достраивание хвоста и прямой charge при LOS
  - [x] 1.8 Tungsten слайм-паркур: автономное использование slime blocks (bounce routing)
    - [x] физика/роутинг: падение на слайм без урона, bounce-дети в block-space, SlimeBounceMove
    - [x] автотест-стенд фазы 0 (deploy/, мак): оба слайм-курса PASS
  - [x] 1.7 Fix jump bridging (bridgingMode jump/back_jump)
    - [x] 1.7.1 Rewrite state machine: sprint-speed telly bridge (FJ_SPRINT → FJ_AIRBORNE continuous)
    - [x] 1.7.2 Fix placement: processRightClickBlock bypasses crosshair (objectMouseOver MISS at 86°+)
    - [x] 1.7.3 Fix sprint: setSprinting(true) forces sprint at entity level
    - [x] 1.7.4 Fix TestBridgingCommand: GoalBlock at player Y (was GoalXZ → pathfinder descended)
    - [x] 1.7.5 Optimize: debug flag, drift correction, cooldown, path-end graceful exit
<!-- Верхнеуровневые задачи. Пишет юзер, AI отмечает выполнение. -->
<!-- Формат: - [ ] задача / - [x] задача -->
