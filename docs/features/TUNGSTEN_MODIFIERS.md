# Tungsten: Velocity Modifiers & Effect Handling — Analysis

## Question

Does tungsten's physics simulation account for potion effects, beacon buffs, attribute modifiers (speed, jump boost, etc.) when generating paths?

## Short answer

**Partially.** Status effects (Speed, Jump Boost, etc.) are captured from the real player at pathfinding start and correctly applied in the Agent simulation. But attribute modifiers beyond status effects (custom NBT modifiers, equipment-based bonuses) are **not** individually tracked — they're baked into `movementSpeed` which is read from the player once.

## Detailed breakdown

### What IS handled

| Effect | Agent field | Where applied | Accuracy |
|--------|-------------|---------------|----------|
| **Speed** | `agent.speed` (amplifier) | `setSprinting()` line 1511-1513 | Good — recalculates movementSpeed with effect |
| **Jump Boost** | `agent.jumpBoost` (amplifier) | `jump()` line 521-522: `velY += 0.1F * (amplifier + 1)` | Correct — matches vanilla |
| **Slow Falling** | `agent.slowFalling` | Gravity reduction in `travel()` | Correct |
| **Dolphins Grace** | `agent.dolphinsGrace` | Swimming speed in `updateVelocity()` | Correct |
| **Levitation** | `agent.levitation` | Vertical velocity override | Correct |
| **Blindness** | `agent.blindness` | Sprint prevention | Correct |
| **Depth Strider** | `agent.depthStrider` | Water movement speed | Correct |

All of these are captured from the real player via `player.hasStatusEffect()` in `Agent.of(PlayerEntity)` (lines 1905-1910).

### How movementSpeed works

```
Agent.of(player):
  agent.movementSpeed = player.getMovementSpeed()
  // This returns the ENTITY ATTRIBUTE value, which already includes:
  //   base value (0.1) + speed effect + sprint modifier + soul speed + any custom modifiers
```

BUT when the Agent later calls `setSprinting()` during simulation (line 1503-1515), it **recalculates from scratch**:

```java
public void setSprinting(boolean sprinting) {
    this.movementSpeed = 0.1F;                          // hardcoded base
    if (sprinting) this.movementSpeed *= 1.3;            // sprint bonus
    if (this.speed >= 0) {                               // Speed effect only
        double amplifier = 0.2 * (this.speed + 1);
        this.movementSpeed *= (1.0 + amplifier);
    }
}
```

This means:
- The initial `movementSpeed` from `player.getMovementSpeed()` is **overwritten** on the first `setSprinting()` call.
- Any attribute modifiers that aren't the Speed status effect (e.g., equipment bonuses, custom NBT modifiers) are **lost** after the first tick.

### What is NOT handled

| Modifier | Status | Impact |
|----------|--------|--------|
| **Beacon Haste/Speed** | Speed effect IS captured; Haste is irrelevant to movement | No issue — beacons give Speed effect which is handled |
| **Soul Speed enchantment** | Not tracked | Would move faster on soul sand/soil than simulated |
| **Swift Sneak enchantment** | Not tracked | Sneak speed would be wrong |
| **Custom attribute modifiers (NBT)** | Lost after first setSprinting() | Rare in practice |
| **Slowness effect** | **Not tracked** | Agent has no `slowness` field — would simulate faster than real player |
| **Mining Fatigue** | Not relevant to movement | No issue |

### BlockSpace vs Agent physics

**BlockSpace** (`BlockSpacePathFinder`, `BlockNode`) does pure block-level A* — it finds which blocks to visit, not how to move between them. It has **no physics at all** — no effects, no velocity, no collisions. It just checks if blocks are walkable/passable.

**Agent** handles all physics: velocity, gravity, collisions, effects. It takes BlockSpace's block path and generates the actual movement inputs (yaw, keys, timing) between block nodes.

So effects only matter in the Agent layer, and BlockSpace is correct regardless.

### When effects change mid-path

The Agent captures effects once at pathfinding start. If an effect expires or is gained during path execution, the simulation becomes inaccurate. This is inherent to pre-computed paths — the agent can't predict when effects will change.

For short paths (typical in tungsten), this is rarely an issue. For long paths, drift detection (`Agent.compare()`) will catch the mismatch and trigger path recalculation.

## Recommendations

### Must fix: Slowness effect
Add `agent.slowness` field, capture it from `player.hasStatusEffect(StatusEffects.SLOWNESS)`, and apply it in `setSprinting()`:
```java
if (this.slowness >= 0) {
    double amplifier = 0.15 * (this.slowness + 1);
    this.movementSpeed *= (1.0 - amplifier);
}
```
Without this, any Slowness potion/beacon makes paths overshoot.

### Nice to have: preserve full attribute value
Instead of recalculating from `0.1F` in `setSprinting()`, store the base attribute value separately and apply sprint/effect multipliers to it. This would handle Soul Speed, Swift Sneak, and any custom modifiers automatically.

### Low priority: Soul Speed / Swift Sneak
These are niche cases. If the bot frequently walks on soul sand or sneaks, they matter. Otherwise, drift detection will compensate.
