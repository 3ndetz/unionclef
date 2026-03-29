# Tungsten: Rotation Methods

## Three approaches to setting yaw/pitch

### 1. setYaw / setPitch (legacy)

```java
player.setYaw(targetYaw);
player.setPitch(targetPitch);
```

Directly writes the desired angle. Fast, deterministic, but the resulting
delta is an arbitrary float from `atan2`. Anti-cheat can flag this: a real
mouse produces deltas quantized to integer pixel steps.

Setting: `enableNativeRotation = false`

### 2. Pixel-quantized changeLookDirection (current default)

```java
double degreesPerPixel = sensScale * 0.15;
long pixelsX = Math.round(deltaYaw / degreesPerPixel);
player.changeLookDirection(pixelsX * sensScale, pixelsY * sensScale);
```

Converts the desired delta to integer mouse pixels, then feeds them through
vanilla's `changeLookDirection()`. The final yaw is quantized to the pixel
grid — identical to what a physical mouse would produce. Anti-cheat sees
valid pixel-aligned deltas.

Trade-off: rounding error up to `±degreesPerPixel / 2` (~0.075 deg at
default sensitivity). Negligible for parkour.

Setting: `enableNativeRotation = true`

### 3. WindMouse via MixinMouse (combat only)

```
setTarget(yaw, pitch)
  → per-render-frame WindMouse step
    → accumulate integer pixel deltas
      → MixinMouse injects into cursorDeltaX/Y
        → vanilla updateMouse() → changeLookDirection()
```

Full asynchronous pipeline with human-like noise and inertia.
Used in combat (`CombatController`).

Not suitable for pathfinding because of the timing mismatch — see below.

## Why WindMouse doesn't work for pathfinding

MC render loop order:

```
MinecraftClient.render():
  for each pending tick:
    ClientPlayerEntity.tick()   ← PathExecutor.tick() here (HEAD)
  Mouse.updateMouse()           ← MixinMouse consumes pixels here (AFTER all ticks)
  InGameHud.render()            ← WindMouse accumulates pixels here
```

`updateMouse()` runs **after** all game ticks. If PathExecutor accumulates
pixels on tick N, they won't be applied to yaw until **after** tick N has
already used the old yaw for physics. The simulation diverges.

### N-1 look-ahead workaround

Pre-accumulate pixels one tick ahead: on tick N, set pixels for node N+1.
Then `updateMouse()` applies them before tick N+1 runs.

```
Tick N:   keys for node N + accumulate pixels for node N+1
updateMouse: yaw becomes node N+1 ✓
Tick N+1: yaw is correct, keys for node N+1 + pixels for node N+2
```

Problems:
- **First tick**: no prior tick to pre-accumulate from. Would need a
  synthetic "zeroth" step in setPath(), adding a frame of latency.
- **Path changes**: if the path is replaced mid-execution, the pre-queued
  pixels belong to the old path. Need to flush and re-sync.
- **Timing sensitivity**: if MC runs multiple ticks per frame (lag spike),
  only the last tick's pixels get applied before updateMouse. Intermediate
  ticks see stale yaw.

Verdict: works in theory, fragile in practice. The synchronous pixel-quantized
approach (method 2) gets 95% of the anti-cheat benefit with zero timing risk.
