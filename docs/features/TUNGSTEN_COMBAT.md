# Tungsten Combat System

## Architecture

Three independent subsystems, each at its own frequency:

- **Mouse** (render freq ~60 FPS) — WindMouse rotation via synthetic mouse deltas
- **Legs** (render freq) — movement keys, braking, strafing
- **TriggerBot** (tick freq 20 TPS) — attack clicks via KeyBinding.onKeyPressed

All inputs are legit (key presses + mouse pipeline), no direct API calls.

## Combat Stages

Stage changes are logged to chat. Stage is re-evaluated every frame.

### Stage 1: PURSUE

**When:** target is moving away from us (velocity dot product positive) or is about
to become unreachable, or target is standing on edge (opportunity to push off).

**Mouse:** aim at target's PREDICTED position. Prediction = target_pos + target_vel * N ticks.
N is based on how long our WindMouse will take to reach the target angle — if convergence
takes ~2 ticks, predict 2 ticks ahead. This is AIMING lead, not leg lead.

**Legs:** run toward target's far-future predicted position (target_pos + target_vel * ~20 ticks).
Legs prediction is longer because running needs more planning horizon than aiming.

**TriggerBot:** active, clicks when crosshair sweeps over target.

### Stage 2: DANGER_BATTLE

**When:** knockback analysis shows that the NEXT enemy hit would send us into a fall
(2+ blocks). We haven't been hit yet, but we're in a vulnerable position.

**Mouse:** aim at target (normal combat aim with prediction).

**Legs:** reposition away from edge. Don't sprint-jump toward danger direction.
Prefer strafing parallel to edge rather than toward/away from target.
Still fighting, just picking safer ground.

### Stage 3: DANGER_IMMINENT

**When:** we've been hit or our current velocity vector leads into a fall (detected by
SafetySystem predicted position check). This is the emergency brake.

**Mouse:** face OPPOSITE to velocity vector (brake direction). Override combat aim.

**Legs:** full brake.
- Low speed: sneak + W opposite to velocity
- High speed / deep fall: sprint + W opposite to velocity (full counter-thrust)

**TriggerBot:** still active (might land a hit while turning).

### Stage transitions

```
PURSUE ←→ DANGER_BATTLE ←→ DANGER_IMMINENT
  ↑                              |
  └──────────────────────────────┘
       (velocity stabilized, safe ground)
```

## What's implemented now (as of this writing)

### Working:
- **TriggerBot** — clicks via KeyBinding.onKeyPressed, cooldown-aware, release cycle
- **WindMouse rotation** — via synthetic raw pixel deltas injected into Mouse.cursorDeltaX/Y
  through MixinMouse. Full vanilla pipeline: sensitivity scaling → changeLookDirection.
  Pixel-quantized to match real mouse. Anti-cheat safe.
- **SafetySystem visualization** — velocity vectors + predicted positions for both players,
  fall danger markers, knockback trajectory prediction (defensive + offensive)
- **SafetySystem braking** — detects fall at predicted position, faces opposite velocity,
  soft brake (sneak) or hard brake (sprint reverse)
- **Configurable** — all WindMouse params + toggles via tungsten.json / `;settings` command

### NOT implemented yet:
- **Combat stages** (PURSUE / DANGER_BATTLE / DANGER_IMMINENT) — currently no stage machine,
  just binary: braking or not braking
- **Aim prediction** — currently aims at target's current position, not predicted.
  Need: short-horizon prediction for mouse (based on WindMouse convergence time),
  long-horizon prediction for legs
- **WASD passthrough** — currently braking overrides ALL player input including our own
  jumps and movement. Need: only override when actually dangerous, pass through safe inputs
- **Legs system** — no sprint-jump, strafe, or movement control beyond braking
- **DANGER_BATTLE stage** — no repositioning away from edges while fighting
- **PURSUE stage** — no chase logic, no target-running-away detection

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
