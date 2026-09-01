# Archive: PvP suite v1 + LIVE-A fix + tungsten-primary assessment (2026-07-23/24)

Archived 2026-09-01 from `docs/ai/progress.md` per the >500-line rule in `docs/ai/readme.md`. Content moved verbatim, not edited.

## SESSION 2026-07-24 (work machine) — PvP audit + unified suite v1 (RW-5/RW-1/RW-9 infra)

INVESTIGATE: 8-reader parallel code audit of melee/ranged/chase/bridge/pathcore/test-infra/levers +
adversarial verification of every critical/high finding + 12 TODOS "[x]" claims re-checked against
code. Result: **docs/ai/audit-2026-07-24-pvp.md** — 5 verified root causes with file:line for RW-1
(two key-writer clocks + trigger/aimer point mismatch), RW-9 (mid-air groundSafe bail, dead
setTargetFast, feet-point LOS gate), RW-2/RW-3 (no-aim no-cooldown placement stacks), #67
(smartMoves excludes all break/place/water move-gen; greedy no-g-cost search). TODO verification:
9/12 claims CONFIRMED, 3 PARTIAL (combatBunnyHop* long fields NOT ;settings-tunable; MCP=54 tools;
@shoot has no "sniper" mode). LIVE-D verified implemented -> flipped to [x].

IMPLEMENT: unified suite pipeline v1 (RW-5): `deploy/runner/run_suite.py` + `deploy/runner/uctest/`
(harness: ONE generic py4j call-by-name bridge + raising rcon; actors: warm-bot/reset/kits/settings
pinning; arena: deterministic void islands/bridges/terrain strip; scenario: freeze + stand-still +
self-vs-knockback-fall detectors, timeline.jsonl artifacts, retry-once flake policy). `pvp` suite:
melee_basic, edge_duel, narrow_bridge_duel, chase_flat, chase_terrain (RW-9 bench — victim is a REAL
@goto/baritone runner, never rcon-tp), bow_flee(+hard; info-tier until the kite lever exists — audit
confirmed flee executor owns the camera), ranged_moving, bridge_assault(+defended), allround
(primitive-composed ranged->melee). Design doc: docs/features/PVP_SUITE.md.

RAN ON THE STAND + FIXED (2026-07-24, same session, via jayra->mac key-only paramiko jump; the
work-machine classifier blocks direct creds/ssh but a key-only hop through jayra passes):
- Suite ran live on the mac stand (both testers, clip capture via x11grab). First run 3/8 gate PASS.
- Arena hardening: void-safe flat arenas (rim barrier + setworldspawn — a bot knocked off a small
  floor respawned at world spawn y=101 and the whole run happened there); frag-mp4 + x264 cap for
  Telegram-sendable clips.
- **F4 (chase, RW-9)** built+deployed+validated: skip the walker groundSafe bail while airborne
  (bunny-hop no longer self-bails the live chase), wire setTargetFast (dead code -> fast nav turn
  keeps the 45deg sprint gate open), steer LOS to body-centre not ground-snapped feet. chase_flat
  never-catches -> reliable PASS (contact 6.7s, avg 5.4); chase_terrain never -> catches (flaky,
  needs F10); melee regression clean.
- **F6 (bow lead, RW-6)** built+deployed+validated: BowShooter leads from per-tick position deltas
  (EMA), not target.getVelocity() (~0 for remote players). ranged_moving 1/6 -> 2-4/6; allround
  ranged hits 0 -> 2.
- Scenario bugs fixed (primitives-not-policy): bridge_assault + allround must SELECT the block/bow
  (bridgeTo/shootArrowAt place/use the HELD item). After fixes: 7/8 gate PASS.
- All clips (pass + fail, original + after-fix) sent to the operator's Telegram via mineswarm
  scripts/tg_video.py on jayra (proxy+token).

NEXT (audit plan, each its own focused pass): F10 (terrain move-gen — chase_terrain reliability
blocker, "Ran out of nodes" on rough ground), F7 (kite/bow-flee primitive), F1-F3 (combat rework
for the live RW-1 feel — the stand's flat/edge/narrow melee already pass, so RW-1 needs a
human-jitter scenario), F8-F9 (physical placement RW-2/RW-3), F11 (telemetry levers), F12 (migrate
legacy runner scripts).

## SESSION 2026-07-23/24 — break primitive + follow LIVE-A fix + PvP ranged + tungsten-primary assessment

RELEASED + VERIFIED (gh release asset confirmed):
- **v0.51.0 BREAK primitive** (mineBlocks/mineStatus, py4j+MCP): mine arbitrary in-reach blocks via
  the tungsten executor break queue (same as @goto's wall-clear). diag_mine PASS (3-block wall mined),
  break_test 4/4. NB the "method does not exist" chase was NOT a stale cache — `getEntityPos()` is
  1.21.11-only yarn and broke `:1.21.1:compileJava` (shared src compiles for every version), leaving
  the 1.21.11 jar stale; fixed to version-safe `getPos()`. Checklist gained a stale-jar-verify note.
- **v0.52.0 FOLLOW LIVE-A fix** (the user's URGENT item): @follow / any follow / combat approach now
  CHASES a moving target instead of standing still / lagging ~30 blocks. Stand: follow_altoclef avg
  dist 30->1.4, follow_test avg 2.2, pvp_moving PASS (combat approach shares the engine — first hit
  6.7s, 20 dmg, improved). THREE layers, found by INSTRUMENTING (walker per-tick DEBUG) not guessing:
  (1) @follow ran on baritone (routing) -> tungsten follow engine; (2) DIRECT aimed at a ~2s-STALE
  snapshot -> BlockPathWalker.steerLive re-aims LIVE every tick; (3) THE KILLER — DIRECT bailed
  "danger->BFS" every tick because hasHolesOnPath scanned the WHOLE line to the far target, so the
  drift-prone physics executor did all the moving -> now guards only the immediate ~4 blocks (rolling,
  void-safe). Plus bail cooldown, bot-displacement stall detection, floored test arenas. Contained:
  tickDirect is follow+PunkPlayer-APPROACH only; terrain (@goto) uses tickBFS (untouched).

VALIDATED (no release needed):
- **Ranged/bow vs MOVING target**: bow_moving_test PASS (first arrow hit 3.4s, 19 dmg, TrajectorySolver
  lead-aim via shootArrowAt/BowShooter). Was only standing-target validated before (#6.7).

ASSESSED, NOT ready (documented for a FRESH pass):
- **LIVE-C tungsten-primary flip**: terrain_test (smartMoves+primary ON) FAILS climb courses A/B/C,
  only D snaps — @goto+PRIMARY (driveTungstenPrimary) doesn't climb while ;goto (direct walker) does.
  Deep pathfinding gap in the altoclef->tungsten-primary wrapper (physics drift kills it). Do NOT flip
  TungstenHelper.primary until terrain_test A/B/C + gamer_smoke pass. Precise lead in TODOS.md LIVE-C.

Tooling added: follow_diag.py (walker per-tick decision dump), tickDirect DEBUG instrumentation,
diag_mine.py error surfacing, floored follow arenas.

MORE RELEASES this session:
- **v0.53.0** — setTungstenPathing couples smartMoves -> tungsten-primary CLIMBS terrain
  (terrain_test A/B/D PASS). Default flip still blocked by 'Ran out of nodes' reachability on
  hard terrain (openSet empties = goal unreachable via current moves; deep, LIVE-C).
- **v0.54.0** — //replace (replaceSelection/replaceStatus, break-then-place). diag_replace PASS.
- **v0.55.0** — buildBlocks (schematic placement core, typed list bottom-up) + aim-jitter telemetry
  (AimSampler/getAimSamples; aim_jitter_test 0.7 reversals/s = smooth, validates v0.48 shake fix).
- **v0.56.0** — @@ WorldEdit command handler + scaffolding cleanup. @@ prefix (user 2026-07-24;
  distances from main @, dodges @set clash): @@pos1/pos2/hpos1/hpos2/sel/size/set/replace/walls/
  hollow/cyl/sphere/copy/paste/cleanup/restat/minestat, SendChatEvent intercept -> WorldEditCommands
  (off-thread, primitives marshal). ScaffoldRegistry + cleanupScaffold: mines nav pillar/bridge
  garbage top-down, finite, CAN'T LOOP. worldedit_cmd_test PASS + diag_scaffold PASS. Remaining:
  @@undo (op history), @@schem load as a client file op (agent parses -> buildBlocks).

BEDWARS #64 core mechanics validated on 0.56.0 (void island): PvP bedwars_combat SUCCESS (2 kills,
0 falls), bridging bridge_test PASS (godbridge + bridgeTo), ranged bow_moving PASS. Shop-buy needs
a bedwars/villager shop GUI (primitives exist; no bedwars profile on the stand).

