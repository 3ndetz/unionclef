# PVP SUITE — unified combat/ranged autotest pipeline

Covers TODOS: **RW-5** (one clear pipeline), **RW-1** (combat test infra: live sparring
partner, edge zones), **RW-6** (ranged validation), **RW-7** (clean purpose-built
polygons), **RW-9** (chase bench). Runs on the `deploy/` stand (the Mac).

## How to run (on the docker host)

```bash
# stand up (server + both clients; pvp profile is required for every scenario here)
docker compose -f deploy/compose.test.yml --profile pvp up -d

# whole suite
python3 deploy/runner/run_suite.py pvp

# one scenario / repeats (flake analysis)
python3 deploy/runner/run_suite.py pvp --only narrow_bridge_duel
python3 deploy/runner/run_suite.py pvp --only chase_terrain --repeat 3

# list what exists
python3 deploy/runner/run_suite.py --list
```

Exit code 0 = every selected scenario PASS. Artifacts (timeline, chat, screenshots,
verdicts) land in `deploy/runner/artifacts/<run>/<scenario>/`, summary table on stdout
+ `summary.json`.

## Library layout (`deploy/runner/uctest/`)

| Module | What |
|---|---|
| `harness.py` | ONE py4j bridge (generic call-by-name through `docker exec`), rcon helpers, `wait_for`, artifacts dir |
| `actors.py` | `Bot` = container+nick: ensure-in-game, stop-everything, kits, settings pinning, state reads |
| `arena.py` | deterministic polygon builders: void islands, bridges (any width/height), terrain strip, edge platforms; start/finish markers |
| `scenario.py` | `Scenario` base: setup → drive → sample loop → verdict; freeze/self-fall detectors; retry-once flake policy |
| `scenarios_pvp.py` | the scenario catalogue (below) |

Old `deploy/runner/*_test.py` scripts stay until migrated; new work goes through the
suite. The suite reuses their proven conventions: flat world floor top at y=-60,
`forceload` before `fill`, snake_case 1.21.11 gamerules, scoreboard `k`/`d`
objectives, warm-bot wait (`inGame` poll) before any command.

## Conventions the suite enforces (learned the hard way)

- **Warm bot**: poll py4j `inGame` before the first command; cold clients wander.
- **Full reset between scenarios**: `@stop` + `punkStop` + `runAwayStop` +
  `stopPathing`, kill → respawn → `clear` → kit → `tp` (sky-tp tests leave stale
  async block-path state; a kill+tp reset clears it).
- **Settings pinning**: persisted `tungsten.json` in the container survives restarts
  and can carry stale defaults (e.g. `combatMovementsEnabled=false` shipped for
  months). Every scenario pins the toggles it depends on via `;settings`.
- **Verify with eyes**: a mid-run screenshot is captured for EVERY scenario (not only
  on fail) + recent chat is scraped for `command not found`/errors; a scenario that
  "passed" with an unknown command in chat is a FAIL (the demo-video saga).
- **Flakiness ≠ regression**: one automatic retry, only when the failure matches a
  flake signature (py4j down, never entered game, cold-start wander). A clean red
  runs no retry — it is a finding.

## Detectors (shared metrics, sampled every 1s via rcon + py4j)

- `timeline.jsonl`: positions, hp, distance, held item, `hurtTime`, scoreboard k/d,
  `bridgePlaced`/`pathStatus` when relevant.
- **freeze window**: displacement < 0.05 over 6s while the objective is unmet.
- **stand-still-near-target** (RW-1 dominant symptom): displacement < 0.3 over 4s
  while target distance < 4. Counted separately from full freezes.
- **self-fall vs knockback-fall**: a drop below `floor_y - 2` is a SELF-fall when
  `hurtTime == 0` in the last 2 samples before the drop, else knockback (bedwars
  knockback deaths are symmetric combat, walking off on your own is the bug).
- **placement rate** (anti-cheaty proxy, RW-2/RW-3): `bridgePlaced()` per second;
  sustained > 6 blocks/s is flagged in the verdict (informational until the
  physical-placement rework lands).

## Scenario catalogue (suite `pvp`)

Polygons are built at fixed coordinates around (0, -60, 0), void carved to bedrock
where stated; lime concrete = start, red concrete = objective (RW-7: self-evident
geometry, zero clutter).

| id | polygon | actors + drive | PASS |
|---|---|---|---|
| `melee_basic` | 13×13 island, tall-grass patches | mutual `punk` 60s, iron swords | first hit ≤ 15s; damage ≥ 8; freezes = 0; stand-still windows ≤ 2 |
| `edge_duel` | 5×5 platform over void (RW-1 "1 block from drop") | mutual `punk` 60s | ≥1 kill; SELF-falls = 0 |
| `narrow_bridge_duel` | two 5×5 islands, **1-wide** bridge, 9 long, void below (bedwars) | mutual `punk` 90s; spawns force meeting mid-bridge | bot crosses or holds bridge; ≥1 kill; SELF-falls = 0 |
| `chase_flat` | 40×40 floor | victim loops a 20×20 rectangle via waypoint `@goto` (baritone), chaser `@follow` (tungsten) 90s | contact (dist < 2.5) ≤ 45s; avg dist last 30s < 4; freezes = 0 |
| `chase_terrain` | 48-long deterministic terrain strip (steps, 1–2 gaps, 2-high walls with rubble ramps) | victim ping-pongs the strip on `@goto` (baritone), chaser `@follow` (tungsten) 120s — **RW-9 bench: tungsten MUST catch** | contact ≤ 90s; no freeze > 8s |
| `bow_flee` | 40×40 floor, void border | OUR bot: `runAwayPlayer(chaser, 12)` + `shootArrowAt(chaser)` every 3s (bow + 64 arrows); chaser: `punk`, slowness I. **INFO tier** — audit: no kite primitive exists, the flee executor owns the camera and overrides bow aim (no WindMouse arbiter); promoted when the kite lever lands | survive 60s; arrow hits ≥ 2; SELF-falls = 0; avg dist ≥ 7 |
| `bow_flee_hard` | same, no slowness | same drive; **info tier** | survive ≥ 30s; hits ≥ 1 (records the real gap) |
| `ranged_moving` | 24-block lane | victim strafes a loop via real `@goto` runs (never rcon-tp: teleported targets have zero velocity — audit confirmed all old bow tests share this defect); 6 × `shootArrowAt` | hits ≥ 2/6 (vanilla spread) |
| `bridge_assault` | two 7×7 islands, 9-gap void, NO bridge; islands share axis+Y (bridgeTo is cardinal-only, target-Y-ignoring — deliberate arena fit) | bot: 64 cobble + sword; drive: `bridgeTo(edge)` → cross → `punk` victim | reaches enemy island ≤ 60s; kill ≤ 120s; SELF-falls = 0; `bridgePlaced` ≥ 8; placement-rate flag recorded |
| `bridge_assault_defended` | same + victim shoots arrows at the bridger | same; **info tier** until stable | crossing under fire; survive with ≥ 8 hp; kill ≤ 150s |
| `allround` | 40×40 floor, spawns 26 apart | composed from primitives (agent-style): `shootArrowAt` every 4s while dist > 10, switch to `punk` inside 10; victim `punk`s back the whole time | ≥1 arrow hit while far + melee kill; freezes = 0 |

### Know the ruler before the series — measured spread of `kills − deaths`

The duels are judged on the margin, and the three of them are **not** equally able to measure a
difference. Taken from healthy series on known builds (runs under the 14 fps floor excluded):

| course | healthy series | sd | resolves at n=6/arm |
|---|---|---|---|
| `melee_basic` | `0, −1, 0, 0, +1, −1` and `−1, −2, −2, −2, −1, 0` | **0.75** | ~0.9 |
| `edge_duel` | `+1, −1, +1, −3, −4, +1, −2, −5` (pre-pin) | **2.4** | ~2.8 |
| `narrow_bridge_duel` | `+3, −2, −3, +1, −4, +1` | **2.7** | ~3.1 |

`melee_basic` is three times the instrument the other two are, and the reason is the arena rather
than the code: an open field decides a fight on the trade, while a platform and a 1-wide bridge add
knockback geometry that the margin cannot separate from combat quality.

Rule of thumb for planning a series: to see a shift of `d` at two standard errors you need about
`8·(sd/d)²` runs **per arm**. A one-point shift costs 6 runs an arm on `melee_basic` and **57** on
`narrow_bridge_duel`. Every "effect" claimed on the bridge this session has been smaller than its own
spread, and all of them were withdrawn. When the sensitive course cannot show an effect, more runs on
the coarse one is not the answer — a different statistic is (see the ledger below, and CHECKLIST 4i).

### The other half of the ledger

Both fighters run this mod, so `DamageWatch` is counting on the **victim** too. `_ledger()` in
`scenarios_pvp.py` prints blows, damage and `deathsSeen` for both sides of every duel. A run yields
~50 blows a side against ~13 deaths, so the blow ratio resolves what the margin cannot. Read it as a
**ratio between the sides**, never as a total: the class's own javadoc records that its damage does
not reconcile with deaths × 20, and a shared undercount cancels in a ratio while it does not in a sum.

Two tiers: **gate** scenarios (regression — a red blocks release) and
**informational** (`bow_flee_hard`, `bridge_assault_defended`, placement-rate) that
record today's honest capability without blocking, until the corresponding rework
(LIVE-B combat, RW-3 physical bridging) lands — then they get promoted.

## Why these polygons (design notes)

- **Mutual punk is the sparring partner** (RW-1): both bots run the same combat
  engine, so knockback flies both ways and "fights back" is real, with zero extra
  code. Asymmetric drives (baritone runner vs tungsten chaser) are exactly the RW-9
  bench wording.
- **Terrain strip is generated from a fixed table, not noise** — deterministic
  geometry (RW-7), committed in code, so a chase regression is a regression and Ai
  runs are comparable.
- **bow_flee drives flee + aim together on purpose**: WindMouse camera is a single
  resource; flee movement and bow aim WILL contend. The scenario exposes and then
  guards the fix (turn-shoot-turn windows).
- **Narrow bridge forces edge-aware combat**: VoidGuard clamps vs strafe/knockback on
  a 1-wide walkway — the bedwars death class. Self-fall attribution separates "walked
  off" (bug) from "knocked off" (combat).
