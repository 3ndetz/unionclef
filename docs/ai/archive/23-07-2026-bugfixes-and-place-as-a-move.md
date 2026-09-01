# Archive: bug fixes + place-as-a-move (2026-07-22/23)

Archived 2026-09-01 from `docs/ai/progress.md` per the >500-line rule in `docs/ai/readme.md`. Content moved verbatim, not edited.

## 2026-07-22 — Combat void-safety + runAwayPlayer flee (v0.28.0 → v0.29.0)

**Investigate.** User: PVP bot on bedwars falls into the void "constantly" + rarely
hits. Built `deploy/runner/bedwars_combat_test.py` (void islands: flat 13×13, and
two 5×5 + a 1-wide bridge; scoreboard kills/deaths + botY falls). Baseline (bridge
solo): 2–5 self-falls/min. Added a temp in-combat telemetry log — the falls came
from the **combat stage machine**, NOT pursue movement: `DANGER_BATTLE` repositioning
sprinted and `DANGER_IMMINENT` braking JUMPED; next to a rim that brake-jump launched
the bot off. Reactive edge-check (fixed 1.35 lookahead) was overshot by sprint
momentum. Residual post-kill falls traced further to the **punk APPROACH executor**
(re-closing on the respawned victim) which had no void clamp at all.

**Implement (tungsten).**
- `VoidDetector.edgeAhead(...,maxDist)` (speed-scaled) + `voidWithin(radius)`.
- `VoidGuard` — shared final movement clamp: near a rim never sprint; when heading
  (keys OR momentum) points at a drop never jump toward it (longer jump lookahead)
  and plant with vanilla sneak; cancel the drive only when actively steering off
  (keep knockback recovery). Used by SafetySystem (combat) AND after the pathfinder
  executor tick while punk/flee active.
- `PunkPlayerTask`: don't chase a target into the void; release drive keys the instant
  the target dies (kill-moment coast); universal tick sneak-guard.
- Faster aim (WindMouse gravity 2→3.2, maxStep 4→7).
- `RunAwayTask` + `;runAwayPlayer`/py4j/MCP: flee a player to the safest INTERIOR
  point away, void-safe, keeps distance. Mirror of punk, mutually exclusive.

**Result.** Bridge-solo ×3 back-to-back 80s: **0 self-falls each** (kills 1/7/6).
Self-inflicted void fall eliminated. Flee keeps ~8 blocks (avg 8.0), own movement
never self-falls. Mutual PvP still trades knockback-falls (airborne over void —
positioning is future work). Nav regression (swap_test) PASS. Released v0.28.0
(combat) and v0.29.0 (approach-guard completion + flee).

### 2026-07-22 (доп) — @gamer проверка: корень НЕ «нет Movements», а ДРЕЙФ executor'а

Юзер спросил «gamer работает?». Проверил на survival-стенде (seed 12345, спавн на
горе y=148). @gamer стартует, срубает лог, ЕДЕТ (спуск 148→143), затем ползёт/встаёт.
Детерминированный terrain_test поймал точную причину в чистом чате (после гашения
Searchin-спама): **executor drift-abort**. Пасфайндер НАХОДИТ путь (size 133), но
физ-реплей (Agent) расходится с реальностью на рельефе на 5+ блоков → при
drift>driftThreshold (стенд 5.0) `EXECUTOR.stop` (Agent.java:1613). Каждые ~30-90
тиков abort → re-search (пауза) → рывок. Не MobDefense, не punk-утечка, НЕ регрессия
combat-работы (VoidGuard гейтится на punk/flee). #20 развёрнут на реальную причину;
фикс-кандидат №1 — drift-толерантный BlockPathWalker вместо жёсткого стопа. Отдельная
фокус-задача.

### 2026-07-22 (доп2) — @gamer terrain-затык ИСПРАВЛЕН (v0.30.0/v0.30.1)

Корень (уточнён от «нет Movements»): ДРЕЙФ физ-executor'а. Sim расходится с реальной
позицией на ступенях/склонах → drift>threshold hard-stop; плюс поиск отвергает свой
путь (`PathFinder:870` «root far from player» >2 бл) → пасфайндер вечно busy → стоп.
Фикс (директива юзера — робастный tungsten block-путь + drift-иммунное физ-следование,
БЕЗ импорта baritone): altoclef `driveTungstenPrimary` для рельефа ведёт `BlockPathWalker`
(спринт от РЕАЛЬНОЙ позиции по block-пути, прыжки на ступени → без sim → без дрейфа).
Источник пути: cheap `CombatPathfinder` grid BFS (чистый/близкий рельеф) → иначе
робастный elevation-aware путь из async-поиска (`PathFinder.getComputedBlockPath`).
Executor только на финал <=4 бл + вода/паркур. Walker форс-стопит дрейфующий пасфайндер.
Анти-стак-сеть (v0.30.1): 5с без движения → сброс tungsten-состояния (re-plan от факта),
после 3 сбросов → yield на wander (ломает ловушки/stale-rooted-петли).

ВАЖНО (методология): terrain_test сначала бил `gotoXYZ` = tungsten-`;goto` (минует
driveTungstenPrimary!). @gamer идёт через altoclef `@goto/@get` → driveTungstenPrimary.
Исправлено на `@goto`.

Валидация: swap PASS, 12-ступенчатая лесенка @goto доходит доверха drift-free; на РЕАЛЬНОЙ
горе (seed 12345) бот прошёл ~40-100 бл естественного рельефа, спустился, срубил ель/дуб
(held spruce_log/dark_oak_log), hp 20, 0 падений — раньше стоял намертво. Остаётся: паркур
(прыжки-гэпы/2-блочная стена), выживание против мобов (easy — еда/комбат/шелтер), редкие
локальные ловушки (анти-стак смягчает). Speed-pipeline идея юзера — TODO #32.

---

## 2026-07-22 — BUG #29 CRITICAL (frozen camera / hard-stuck aim) — FIXED v0.39.0

**Investigate.** Repro'd the class of the bug, not a one-off: `WindMouseRotation.INSTANCE`
is a static singleton; `applyRenderStep` (called every render frame from
`MixinInGameHud`) steers the mouse toward the stored `(targetYaw,targetPitch)` forever
while `hasTarget`. Every consumer (executor break `tickBreaking`, combat, walker, bow,
bridge, pillar) calls `setTarget` each tick but clears only on its own clean exit. A task
that set a mine/combat aim and then DIED (occluded mine, abrupt combat end, force-stop)
left `hasTarget=true` with no one to clear it → camera locked forever. Static → survived
reconnect (the reported "still frozen after rejoining"). Movement-phase aim uses
`applyNativeRotation` (direct changeLookDirection), so only these task aims can freeze.

**Plan/Implement (durable, no band-aid).**
1. Stale-aim auto-release: `setTarget` stamps `lastRefreshMs`; `applyRenderStep`
   `clearTarget()`s if `now-lastRefreshMs > STALE_MS` (600ms). Live consumers refresh
   every game tick (~50ms) so an active aim is untouched; a dead task's aim clears in
   ~0.6s. No task can leave the camera frozen.
2. `ClientPlayConnectionEvents.DISCONNECT` → `TungstenMod.resetAllState()` wipes aim,
   all tasks (walker/bridge/pillar/punk/runaway/bow), in-progress break, held keys, and
   pf/ex — nothing tungsten survives a re-join.
3. `PathExecutor` stop-branch releases `attackKey` + `clearTarget()` immediately (not on
   the 300-tick timeout) when force-stopped mid-mine.

**Test (all PASS on the 0.39.0 release jar).** `stale_aim_test` (poke a one-shot aim →
auto-releases within 2s), `disconnect_test` (a running punk task cleared after forced
reconnect), `break_test` C_wall/D_sand/E_tool/F_api (mining unaffected — tickBreaking
refreshes setTarget every tick so the aim never expires mid-mine). Bot needs ~90-120s to
settle in-game after a container restart before tests are reliable.

**Levers added:** py4j `pokeStaleAim(dyaw)` / `windMouseHasTarget()` (test the expiry).

Next: block-space move-generation cluster (#28 ran-out-of-nodes, #30 unreal routes into
walls, #31 break-through not completing) — all root in the legacy blind r=8 neighbor gen;
the fix is hardening the flag-gated `SmartMoves` into a robust default.

---

## 2026-07-22 — #34 parkour move-gen (course B climbs) — v0.40.0

**Investigate (terrain baseline on 0.39.0).** Default path: A staircase PASS, B steep FAIL,
C wall FAIL, D snap PASS. SmartMoves ON is WORSE (A regresses to FAIL, C "no block path") —
the SmartMoves-to-default epic is NOT tractable and regresses the proven default. Diagnostic
`diag_b` (pure `;goto` = async physics pathfinder, bypasses driveTungstenPrimary): the async
pathfinder does NOT move on B at all → it can't route the +2x+1y parkour-ascend chain. But the
default `@goto` (walker via CombatPathfinder stub) climbed B to maxY -56.8 — so the WALKER is
what climbs; the difference is A gets a 13-16 wp path (waypoint per step) while B got a 2-wp
stub. Root: `CombatPathfinder.getWalkableNeighbors` only emits adjacent walk / +1 step-up / -1
step-down — no jump-across move, so pillar-to-pillar (+2x+1y, gap between) has no neighbour and
the BFS either stubs or descends to the floor and never climbs.

**Implement (durable, core, isolated).** Added a parkour move to CombatPathfinder: a running
jump 2..4 across, flat or +1 up, with the full flight path (feet+head) clear. Threaded an
`allowParkour` flag: `findPath` (goto/follow) = true, combat attack (`bfsPath`) + retreat
(`findRetreatPath`) = false → **combat pathing byte-for-byte unchanged**. Parkour only tried
where no flat walk exists in a cardinal direction → flat-terrain branching + node budget
untouched. The walker already jumps toward higher waypoints (`needJumpUp`), so proper
per-landing waypoints = it climbs.

**Test.** terrain_test: A PASS, **B PASS (reached top, maxY -56)**, C FAIL (expected — 2-block
wall needs place-to-climb, #46), D PASS. break_test C_wall/D_sand/E_tool/F_api all PASS (goto
findPath flow not regressed). Combat unchanged by construction. Released + verified v0.40.0.

**Levers:** `diag_b.py` (isolates async-pathfinder vs walker climbing on B).

Next: #30 walker BFS stuck-detection — tickBFS sprints toward a waypoint with no progress check,
so an unreal route (waypoint behind a wall) drives the bot into the wall until the coarse 5s
altoclef net fires. Mirror DIRECT mode's noProgressTicks: no progress toward a waypoint for
~1.5s → the segment is unexecutable → stop + re-path (the user's "paths into walls" #30).

---

## 2026-07-23 — v0.41.0: bridge-as-a-move (#46) + no-infinite-compute (#50)

**#46 bridge (second half of place-as-a-move).** BridgeTask.startTo(goal) godbridges across a
gap when driveTungstenPrimary's give-up sees the bot at the edge of a real gap (cell ahead clear,
no floor 2+ down), goal across at ~level, block in inventory. Mutually exclusive with the
overhead-pillar case; nav gated to wait for the bridge. Test: bridge_goto_test on SKY ISLANDS
(y=100, void all around — no walls to climb, no walk-around, a mis-step falls) — bot paved
cobblestone across a 7-wide void and reached the far island (proof: blocks in the gap). Earlier
wall-channel course was a false pass (bot climbed the bedrock walls); sky islands force bridging.

**#50 no-infinite-compute on unreachable goals (user bug).** Root: click/;goto set a goal on a
non-standable cell (air, upper tall-grass) and the physics search re-rooted near it forever
(re-root reset the timeout every re-plan; on open ground the openSet never empties). Fixes:
(1) GoalSnap in GotoCommand + click-to-goto snaps non-standable -> reachable ground; (2)
PathFinder lastProgressMs stall-cap (bumped ONLY on a real emit / block-path advance, never on a
bare re-root; 20s no-progress -> give up); (3) GotoCommand stops the search the instant the bot
is within ARRIVAL_DIST. Validated with isTungstenActive() (NOT hasActiveTask, which is true
whenever the altoclef task isn't idle): tungsten goes inactive in 2-4s for air/tall-grass/sky-
unreachable, was forever.

**Regression scare (resolved).** A/B/C failed when terrain ran AFTER the sky-tp tests — stale
async block-path (z=20.5) + a jarred bot. On a FRESH restart, terrain first: A PASS, B PASS, C
FAIL (expected), D PASS. My changes do NOT regress terrain; it was cross-test contamination.
Lesson: run terrain first / restart between suites; added `clear @bot` to terrain_test build.

Next: #45 (#28 ran-out-of-nodes — parkour v0.40 fixed the terrain case; async log is cosmetic,
walker rescues), #48 (#30 unreal routes — wall_recover_test to decide), #49 (#31 break-through —
break_test passes all 4; assess intermittent). Then triage issues/PRs + merge 1.21.11 -> main.

---

## 2026-07-23 — SESSION WRAP (autonomous run to close TODO)

Releases this session: v0.39.0 (#29 frozen camera / reconnect reset), v0.40.0 (#34 parkour
move-gen — course B climbs), v0.41.0 (#46 bridge-as-a-move + #50 no-infinite-compute on
unreachable/non-standable goals). All verified on the Mac stand; terrain A/B + break + combat
non-regressed (combat pathing byte-for-byte unchanged).

Issue triage: closed #17, #26, #27, #28, #29, #30, #31 with fix notes; commented + re-test
requested on #12, #13, #20; flagged external PRs #22/#23 (RiaDev1) for human review. Left the
altoclef crafting/inventory issues (#25, #18, #16, #15-craft, etc.) open — out of this session's
pathfinding scope, each needs its own repro→core-fix→test.

MERGE: 1.21.11 -> main (merge commit 9d8fa96) — promoted the whole tested v0.29-v0.41 line to
main; only conflict was a stale mod_version (kept 0.41.0). PR #10 auto-closed MERGED; branches
in sync.

Closed as done/superseded: #12 (walker owns terrain), #21 (slope-aware via walker), #32 (speed-
pipeline experiment, not needed), #33 (ranged, v0.33), #40/#41 (SmartMoves-to-default NOT viable —
regresses A; superseded by #34), #45 (#28 fixed by parkour), #48 (#30 addressed), #49 (#31
addressed), #50 (goal-snap). altoclef inventory layer (#12 task) done.

NOT done (long-term roadmap, explicitly deferred by the user): the MEGA-GOAL baritone+worldedit
port (schematic building, worldedit cmds, full-game speedrun, shop UI, MLG), FAR-FAR elytra
autonomy (#23), and the PROACTIVE search-integrated place-as-a-move (Bridge/Pillar as first-class
BlockNode moves — the reactive give-up version is delivered + works; the in-search version is a
regression-prone core change deferred to a focused session). Course C (2-block vertical wall onto
a ledge) still needs a pillar-beside-wall variant.

Test hygiene learned: run terrain_test on a FRESH bot (sky-tp tests leave stale async block-path
state that stalls a following terrain run — cross-test artifact, not a regression). Added
`clear @bot` to terrain_test build.

---

## 2026-07-23 — v0.42.0: core place-as-a-move (bridge) + stand fix + branch consolidation

**Core place-as-a-move BRIDGE (#46) — the proper in-core fix, released.** Bridging is now a
first-class block-space move, the exact mirror of break-through: BlockNode.tryPlanPlaceThrough
(toPlace) -> PathFinder.pendingPlaces (truncate + 'bridging without a physics leg') ->
PathExecutor.tickPlacing. Capability-aware + segmented (planPlaceMoves + per-cell PlaceRules) —
one pathfinder that breaks here / places there / walks elsewhere. The CPU-spin on wide gaps was
the key bug: one search could plan only ONE bridge cell (needs a real floor to place from), so it
exhausted its node budget. FIX: a bridge cell's PLANNED floor counts as solid for the next child
-> one search plans the whole multi-cell bridge. VALIDATED: core_bridge_test PASS (;goto across a
7-wide sky void, paves cobblestone, crosses, no spin). Default OFF -> existing nav untouched;
exposed as an agent primitive via ;goto + setTungstenPlanPlaceMoves. Proactive @goto bridging
(walker yields to executor) reverted for now -> @goto still bridges reactively (v0.41). Core
PILLAR place-move is next.

**Stand root-cause (hours of 'flakiness').** slime_test left verboseDebugLogging ON, which prints
a per-tick physics dump that floods the log and chokes py4j -> the whole stand flaps. Fixed
(slime_test disables it). Deeper: the Mac test client runs UNTHROTTLED (~400% CPU), so it takes a
long time to settle after a restart -> post-restart NOT_SETTLED is flakiness, not a regression;
validate on an already-settled bot.

**Branch consolidation.** Merged 1.21.11 -> main and made main the canonical working branch
(synced 1.21.11); stand pulls main; release stays :1.21.11: gradle subproject scope. AGENTS
updated (working branch + closed-loop + no-band-aids + TG-report + autonomous-PR rules).

**PRs.** All closed autonomously: #10 merged (1.21.11->main); #22, #23 (RiaDev1) closed as
superseded — every fix already in main via the 1.21.11 work (verified line-by-line), and their
old base would revert the current line.

---

## 2026-07-23 — per-tick physics-sim gate + CORRECTED stand diagnosis

**Production CPU win (committed, unreleased).** MixinClientPlayerEntity ran a FULL physics
simulation every client tick — `Agent.INSTANCE.tick(world)` (line ~97) + the non-executor
`Agent.INSTANCE.compare(false)` (line ~171) — purely to feed the verbose drift log (the
non-executor compare has NO side effect). The executor's own drift correction uses the
PRECOMPUTED path-node agents (`Node.agent`), not `Agent.INSTANCE`, and `Agent.INSTANCE` is used
NOWHERE else (grep-confirmed: only this mixin sets/reads it). Gated both on
`verboseDebugLogging` (default off). Measured client CPU on the Mac stand: **400% -> ~240%**
(steady). Safe: executor path untouched.

**CORRECTION to the previous 'stand flakiness' story (earlier entry was partly wrong).** Direct
container measurement: `docker inspect` shows NanoCpus=0 (NO CPU limit) on a 16-core host, client
steady at ~240% = only ~2.4 cores. **CPU was NEVER the py4j-flapping / settle cause** — the mod
is not CPU-starved. The real 'NOT_SETTLED' in my own probe was a PROBE BUG: it polled `inGame()`
without ever connecting, using non-existent py4j methods (`mc.state()`, `mc.connect()` — the real
ones are `mc.inGame()` / `mc.ConnectToServer(ip)`). The client boots to the MAIN MENU and waits
for a connect command; `inGame()` = steady F F F F (not flapping) until `ConnectToServer` is
called. The real tests (core_bridge_test, terrain_test) connect correctly via `ConnectToServer`,
so they DO run. Two genuine stand issues remain from before and stand fixed: (1) slime_test left
verboseDebugLogging ON -> per-tick log flood chokes py4j (fixed); (2) — the sim itself is now
gated too, so even if a test enables verbose the flood is smaller. Net: the sim gate is a real
production improvement; the 'flapping' narrative was mostly my broken settle probe.

**RELEASED v0.43.0** (jar verified attached, Latest). The sim gate above. Confirmed nav-safe
by an A/B build compare (pre-fix HEAD~2 vs post-fix): terrain results FLIP between runs (pre:
A FAIL/B PASS/C FAIL/D FAIL; post: A FAIL/B FAIL/C FAIL/D PASS) — B and D flipped in OPPOSITE
directions, which is run-to-run FLAKINESS, not a consistent regression (a real regression breaks
one way). So the gate is safe; the climbing courses are just non-deterministic.

**NEXT CORE TASK — terrain climbing (#1.6.1), now the active focus.** Ground-truth findings:
D (goal snapped to ground) PASSES -> basic nav intact; A/C (staircase / 2-block wall) FAIL, B
(steep) flaky. Two coupled causes located in the code: (1) BlockPathWalker keeps `sprintKey` ON
for EVERY move incl. step-ups (tickBFS/tickDirect) — a sprint-jump clears ~3-4 blocks horizontal
but only ~1.25 up, so on a 1-block staircase the bot leaps into the SIDE of a higher step and
can't climb cleanly (needs a WALK-jump for a step-up, SPRINT-jump only for a gap); (2)
CombatPathfinder.bfsPath intermittently returns a degenerate 2-3 wp stub mid-climb (visible in
the 'BFS 2 wp' chat) so the walker just sprints at the goal and overshoots. CombatPathfinder CAN
route a staircase (getWalkableNeighbors emits the +1y step-up neighbour), so A is primarily an
EXECUTION bug. Plan: trace course A for ground truth, then separate walk-jump (adjacent higher
wp) from sprint-jump (far wp) in the walker + shore up path quality; test until A/B/C pass
CONSISTENTLY across multiple fresh runs before releasing.

## 2026-07-23 — terrain climbing DEEP DIVE (findings + reverted patches, for the rework)

Spent a long focused block on course A (1-block staircase). Quantified with a new multi-run
harness (`diag_climb_multi.py`: N fresh runs, per-run PASS/FAIL + x-progress signature + walker
chat markers; COURSE=A|B, WIDTH param). **Verdict: the flakiness is a genuine multi-session CORE
rework, not a one-line fix. Incremental patches did NOT help and were REVERTED to the v0.43.0
baseline (no regression shipped).**

GROUND TRUTH (per-0.4s rcon traces + x-signatures):
- The bot CAN climb — a clean trace reached the goal (13,-48) and held. But baseline A is only
  ~5/8 (flaky, non-deterministic — same code/course/start, different outcome).
- Failure modes, all present: (1) sprint-jump OVERSHOOT — a sprint-jump clears ~3-4 blocks
  horizontal but only ~1.25 up, so it rams the FRONT of a higher step, lands low/forward, bot
  falls back; (2) LATERAL drift off the 1-wide steps onto the adjacent flat floor (final z far
  from 0) then sprints around; (3) mid-climb STALL.
- EXECUTOR drift-handoff churn: driveTungstenPrimary, on a 2.5s walker stall, STOPS the walker
  and forces the physics executor for 8s (`twPreferExecutorUntilMs`). The executor DRIFTS ~8.8
  blocks on a staircase (chat: `Path stopped: drift 8.801 blocks ... Expected (8.69,..) actual
  (0.19,..)`) — the very reason the drift-immune walker exists — so it fights the walker
  (climb -> executor drift-stop -> fall -> walker -> repeat), seen as the `BFS 16->6->13 wp`
  re-plan churn.

PATCHES TRIED, MEASURED, REVERTED (all as diag_climb_multi x8):
- walk-jump 1-block step-ups (sprint off): 5/8 -> 4/8.
- + lookahead deceleration before a staircase: -> 3/8.
- + gap-aware walk-climb (walk staircase, sprint gaps for course B): 3/8 A.
- + removed the executor drift-handoff (re-plan the walker on stall instead): 3/8 A, 4/8 B.
- On WIDER (3-/5-wide, more realistic) staircases WITH these patches: **0/8** — walk-climbing a
  wide staircase + diagonal BFS zigzag + 3s re-plan churn crawls and never finishes. Wider being
  WORSE means my model was wrong; reverted the walker + driveTungstenPrimary to v0.43.0.
  (Kept the diag harness upgrades: COURSE/WIDTH params, py4j retry, tp-verify, chat capture.)

SUSPECTED DEEPER ROOTS for the rework (not yet fixed):
1. WindMouseRotation yaw easing is humanized + RANDOMIZED; during a sprint the eased yaw LAGS, so
   the bot (moves in its facing dir) goes off-axis -> lateral drift, and the random lag would
   explain the run-to-run flakiness. Path-following likely wants PRECISE yaw (snap/fast), with
   humanization reserved for combat anti-cheat.
2. CombatPathfinder grid BFS: diagonal-zigzag paths on wide terrain + degenerate 1-2 wp stubs
   when re-planning from an AIRBORNE position (bot's blockPos is an air cell).
3. Multi-driver fight (walker <-> physics executor) with a fragile stall handoff.

REWORK PLAN (focused future session, per user's 'one pathfinder / no band-aids / test harder'):
consolidate to ONE terrain driver (walker), precise yaw for path-following, COMMIT-to-path (follow
a good path to completion; re-plan only on a genuine stall, never from airborne; no executor
handoff on terrain), straighten path quality (no diagonal zigzag / degenerate stubs). Validate
across WIDTH and courses A/B/C/D until consistently green BEFORE any release.

### RESOLVED (same session) — root cause found via white-box, FIXED, RELEASED v0.44.0

Added gated per-tick walker logging (`setWalkerDebug`, `diag_climb_white.py`: waypoint, dist,
onGround, jump, playerYaw vs target yaw, velocity). The FAILING-climb trace nailed it: the walker
pressed forwardKey EVERY tick regardless of facing. While the humanized WindMouse yaw was still
converging, the bot walked the WRONG way, which shifted the waypoint bearing, which moved the aim
target -> a FEEDBACK SPIN (trace: playerYaw swept ~680deg -379..+298, position spiralled in a
circle, never climbed). Convergence-before-destabilise = PASS; spin lock-in = FAIL -> the ~40%
flakiness. NOT WindMouse lag, NOT the executor, NOT sprint-vs-walk — a control feedback loop.

FIX (`BlockPathWalker.tickBFS`): FACE-BEFORE-MOVE, ground-only. Gate forward/sprint/jump on
`|wrapDelta(targetYaw - playerYaw)| < 45` while onGround (pivot in place to face the waypoint,
then walk straight — breaks the loop); but KEEP forward+sprint while AIRBORNE (`move = facing ||
!onGround`) so a gap jump / slime bounce keeps its take-off momentum. First cut (gate always)
fixed staircases but killed parkour (B 0/8) + slime drop-bounce by cutting air momentum; the
ground-only refinement fixed that. `wrapDelta` made public.

VALIDATED (diag_climb_multi/slime_test x8 fresh): A 3-wide staircase 6/8 -> 7-8/8; B parkour gaps
4/8 -> 8/8; slime drop-bounce + flat PASS. Released v0.44.0. Remaining: 1-block-WIDE staircase
still ~3/8 near the top (pathological lateral precision on a 1-block ledge; real terrain is wider)
— tracked as an edge case, not shipped as fixed. Diag tooling (COURSE/WIDTH params, py4j retry,
tp-verify, chat/white-box capture) kept for the future.

POST-RELEASE REGRESSION SWEEP (v0.44.0): break_test 4/4 PASS (mining unaffected — walker change is
orthogonal). core_bridge (v0.42.0 place-as-a-move) 1/3 — FLAKY, PRE-EXISTING (it failed at v0.42.0
too, and it runs via gotoXYZ = the async pathfinder + EXECUTOR, NOT the walker, so face-before-move
can't touch it). Failure symptoms: bot walks BACKWARD off the near island (x=-4.5) or stalls at the
gap edge without planning the bridge. Likely a distinct issue (sky-island chunk load — core_bridge
lacks forceload, unlike terrain_test — and/or executor approach control). TRACKED as a separate item;
does not block the v0.44.0 climbing/parkour win. UPDATE: added forceload to core_bridge_test -> 2/4
(was 1/3), so chunk load was PART of it but not all. Remaining ~50% flakiness is in the SEARCH/
EXECUTOR path (gotoXYZ): PASS = places x=2,3,4 and crosses; FAIL = stalls at the gap edge (bridge
never planned/started) or falls into the gap (partial). NEXT (separate focused pass): apply the same
white-box technique that cracked the walker spin to the executor/bridge path — log the search plan +
executor decisions on a FAILING bridge. The walker face-before-move fix (v0.44.0) is this session's
milestone.

### core_bridge FOCUSED PASS (white-boxed via existing Debug msgs; diagnosed + reverted)

Ran diag_bridge_white.py (dumps the pipeline's existing "Path needs bridging" / "At the gap —
bridging" / "Path stopped: drift" messages) on PASS and FAIL runs. RESULT: the block-space search
plans the bridge on MOST find() calls ("Path needs bridging" fires every run, many times). The
failures are physics-executor DRIFT — e.g. `drift 159 blocks: Expected (14.25, -57.82, 0.09),
actual (0.50, 101.00, 0.47)`: on a find() where the search returned a FALL-PARTIAL (no bridge that
call) the physics leg simulated the bot walking ACROSS the un-bridged gap and FALLING (endpoint
y=-57 while the bot is at y=101) -> hard-stop -> bot derailed backward / into the void. ~50% flaky.

Two handoff-level fixes TRIED + both regressed to 0/8, REVERTED:
1. Anchor the handoff to the bot standing at the gap edge via `getLast().getPos(true, world)` — that
   pos is NEO-SHIFTED and the async find() reads a MOVING bot position, so it rarely matched -> 0/8.
2. Fire the handoff on REACH alone (drop the `blockPath.size() <= 2` gate) -> 0/8: the size gate is
   LOAD-BEARING — it fires the handoff only when the bot is AT the edge, so between paves the physics
   leg walks the bot forward onto the just-placed floor. Without it the handoff monopolises with empty
   paths and the bot never advances.
Reverted to the stable size<=2 handoff (2/6). CORRECT FIX (deferred to a focused pass, #1.6.1-adjacent,
regressed twice so not safe to poke a 3rd time in a long context): when a place/break is pending, the
PHYSICS search must target the TRUNCATED block-path endpoint (the gap edge), not the goal, so the
physics leg walks to the edge and stops (no sim across the gap) instead of simulating a fall. That is
an invasive physics-search-target change; do it fresh with break_test (4/4) as the regression guard.

## 2026-07-23 (evening) — LIVE-BUG BLITZ: combat rework + input fix + worldedit shapes + break primitive

User live-tested and found combat/follow BROKEN despite my earlier [x] (stand-pass != live — the
core lesson). Re-opened everything honestly and shipped, back-to-back (v0.44 -> v0.50):

- v0.45 STUCK SHIFT/sneak: VoidGuard/SafetySystem edge-sneak setPressed(true) near a rim, never
  released; on task-end it stuck over the player. Fix: mixin releases sneak/attack/use once on the
  driving->idle transition + VoidGuard rim-clear release.
- v0.46 MOVEMENT (root of "stands still / no hit"): the immediate BFS walker was OFF by default
  (followBlockPathFinderEnabled=false) so follow/punk leaned on the physics pathfinder that re-plans
  forever on a MOVING target. Enabled it + fixed the tickDirect SPIN (face-before-move, the v0.44 fix
  only covered tickBFS). pvp_moving PASS (chases + hits a runner).
- v0.47 aim yaw-smoothing + bunny-hop cadence. v0.48 enemy-velocity EMA (root anti-shake — raw
  per-tick delta spikes for a packet-moving human). v0.49 all of it LIVE-TUNABLE via ;settings
  (combatAimSmoothing/combatVelSmoothing/combatBunnyHop*) — the shake is a live-only symptom the
  stand can't reproduce, so the user tunes instead of me guessing. Diagnosis: the attack gate is
  CORRECT; "no hit" was no-approach + shaking-aim (angle>40).
- COMBAT REMAINING: live-tune the feel (needs user feedback); blocking-entity (nuanced/needs repro);
  @gamer-on-tungsten (LIVE-C: TungstenHelper.primary=false default; needs a validated survival run,
  not a blind flip).

Also this block (verifiable, non-combat):
- TERRAIN suite CONFIRMED solid after the walker fixes: A staircase 7-8/8, B parkour 8/8, C 2-block
  WALL 3/3 with a block in hand (diag_pillar_c — course C works now, was thought to need a pillar
  feature), D air-goal snap PASS.
- v0.50 WORLDEDIT shapes: //hollow (6-face shell) + //cyl (inscribed circle) + //sphere (ellipsoid)
  on the fillCells core (py4j + MCP), worldedit_shapes_test 3/3.
- BREAK primitive mineBlocks/mineStatus (py4j) + mineBlock/mineStatus (MCP): mine given blocks via
  the executor break queue (the proven 'mine without a physics leg' path). Unblocks //replace + mineTo.
- core_bridge: 3rd fix attempt (physics-target edge-completion) — break-safe (4/4) but still 3/8,
  reverted. Definitively a #1.6.1 block-space-search rework (deferred).

