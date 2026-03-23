# Tungsten Combat System

## Architecture

Three independent subsystems, each at its own frequency:

- **Mouse** (render freq ~60 FPS) — WindMouse rotation via synthetic mouse deltas
- **Legs** (render freq) — movement keys, braking, strafing
- **TriggerBot** (tick freq 20 TPS) — attack clicks via KeyBinding.onKeyPressed

All inputs are legit (key presses + mouse pipeline), no direct API calls.

## Combat Stages

Stage changes are logged to chat. Stage is re-evaluated every frame.

### PURSUE

**When:** default stage, or target moving away / standing on edge.

**Mouse:** aim at target's PREDICTED position. Lead = WindMouse convergence time (1-5 ticks).

**Legs:** run toward target's far-future predicted position (~20 ticks ahead).

### DANGER_BATTLE

**When:** KB analysis shows next enemy hit would knock us into a fall (2+ blocks).
We haven't been hit yet, but we're in a vulnerable position.

**Mouse:** normal combat aim with prediction.

**Legs:** reposition away from edge. Strafe parallel to edge. Still fighting.

### DANGER_IMMINENT

**When:** our velocity vector leads into a fall, or already falling into danger.

**Mouse:** face OPPOSITE to velocity vector. Override combat aim.

**Legs:** full counter-thrust.
- Sprint + W + jump opposite to velocity (escape jump when on ground)
- Low speed: sneak + W
- High speed / deep fall: sprint + W

### ESCAPE (future)

**When:**
- Target just hit → damage immunity window → our attacks useless, disengage
- Mutual edge danger → hit-and-run tactic needed
- Low HP → retreat and regroup

**Behavior:** disengage, create distance, re-engage when advantageous.

### DELICATE_BATTLE (future)

**When:** low HP, need careful play. No reckless sprint-jumps.

### Stage transitions

```
PURSUE ←→ DANGER_BATTLE ←→ DANGER_IMMINENT
  ↑              ↑                |
  |              |                |
  ↓              ↓                ↓
ESCAPE ←→ DELICATE_BATTLE        |
  ↑                               |
  └───────────────────────────────┘
       (velocity stabilized, safe ground)
```

## Implemented

- **TriggerBot** — KeyBinding.onKeyPressed, cooldown-aware, release cycle
- **WindMouse rotation** — synthetic raw pixel deltas → Mouse.cursorDeltaX/Y
  via MixinMouse. Full vanilla pipeline. Anti-cheat safe.
- **Combat stages** — PURSUE / DANGER_BATTLE / DANGER_IMMINENT with chat logging
- **Aim prediction** — target_pos + target_vel * lead_ticks. Lead = angular distance / WindMouse speed, clamped [1,5] ticks
- **DANGER_IMMINENT braking** — face opposite velocity, sprint/sneak + W, escape jump on ground
- **SafetySystem visualization** — velocity vectors, predicted positions, KB trajectories, aim prediction marker
- **Knockback prediction** — defensive (us getting hit) + offensive (enemy getting hit)
- **Configurable** — all params via tungsten.json / `;settings`

## Danger Levels (DangerLevel enum)

| Level | Fall blocks | Effect | DANGER_IMMINENT? |
|-------|-----------|--------|-----------------|
| NONE | 0-1 | safe | no |
| HEIGHT_RELIEF | 1-3 | height loss, no damage | no (ignored during ESCAPE) |
| HEIGHT_HIGH | 4-9 | fall damage | YES — brake + escape jump |
| HEIGHT_DEATH | 10+ | lethal | YES — maximum priority |

DANGER_IMMINENT only triggers on `isSerious()` (HEIGHT_HIGH or HEIGHT_DEATH).
ESCAPE stage can safely drop 1-3 blocks without panicking.

## Attack Timing Design (planned)

Sprint-jump approach path is pre-computed (CombatPathfinder attack waypoints).
Bot follows the jump route, then:

1. **During jumps**: follow waypoint path, maintain sprint momentum
2. **When LOS to target**: begin WindMouse turn toward target (aim prediction)
3. **When crosshair reaches target**: TriggerBot clicks automatically
4. **After hit**: path is immediately invalidated (KB changes vectors),
   recalculate from current position

Key insight: attack doesn't interrupt the path — the PATH brings us into range,
and the MOUSE system independently sweeps toward target whenever LOS exists.
The path only needs recalculation after KB events change positions.

Future: predict when LOS will exist along the path and pre-start the turn
N ticks before the LOS window opens (WindMouse needs convergence time).

## TODO

- [ ] **DANGER_BATTLE legs** — reposition away from edge while fighting
- [ ] **PURSUE legs** — sprint-jump chase with far-future prediction (~20 ticks)
- [ ] **ESCAPE logic** — disengage on immunity frames, hit-and-run near edges
- [ ] **DELICATE_BATTLE** — low HP conservative play
- [ ] **Movement zone mapping** — scan terrain for safe zones, jump waypoints
- [ ] **Jump waypoints** — precompute advantageous positions to jump to (high ground, safe landing)
- [ ] **WASD passthrough polish** — allow player manual input in safe situations
- [ ] **Legs system** — sprint-jump, strafe patterns, knockback recovery
- [x] **Danger classification** — DangerLevel enum: NONE / HEIGHT_RELIEF / HEIGHT_HIGH / HEIGHT_DEATH
- [ ] **Environmental hazards in pathfinding** — currently BFS avoids:
  lava, fire, magma, campfire, cactus, wither rose, berry bush, water,
  cobweb, soul sand, honey, powder snow.
  Still needed:
  - Lava pools (multi-block, detect even if adjacent blocks are safe)
  - TNT / moving entities (dynamic threats)
  - Hostile mobs near path (creepers, skeletons)
  - Fall damage at step-downs (BFS allows 1-block drops but doesn't account for sprint speed → overshoot)
- [x] **Unfocused window rotation** — fixed: UnfocusedMouseHelper applies deltas via changeLookDirection
- [ ] **Attack timing pipeline** — integrate attacks into jump path execution:
  - Follow waypoints → LOS detected → pre-turn → hit → recalc path

## Settings (tungsten.json)

| Setting | Default | Description |
|---------|---------|-------------|
| combatTriggerBotEnabled | true | Auto-click when crosshair on target |
| combatRotatesEnabled | true | Auto-rotation toward target |
| combatWindMouseGravity | 2.0 | Pull toward target (deg/frame) |
| combatWindMouseWind | 0.8 | Random jitter magnitude |
| combatWindMouseMaxStep | 4.0 | Max degrees per frame |
| combatWindMouseWindDist | 15.0 | Wind decay threshold (degrees) |
| combatWindMouseDoneThreshold | 0.5 | Snap threshold (degrees) |
| combatWindMouseFlickScale | 3.0 | Far-angle speed multiplier |
