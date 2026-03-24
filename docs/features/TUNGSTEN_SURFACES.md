# Tungsten — Block Surfaces & Collision Tracking

Status of pathfinding/physics for non-standard blocks.
Legend: ✅ works, ⚠️ partial/buggy, ❌ broken, ❓ not checked

---

## ViaVersion Collision Fixes (avoidStuckFence / avoidStuckAnvil)

### Fences, Walls, Panes, Bars — `avoidStuckFence`

**Problem:** On ViaVersion servers, adjacent fences/walls/panes may have
connection bars the 1.21 client doesn't render. Bot walks into invisible
collision and gets teleported back by the server.

**Fix:** `BlockStateChecker.isConnected()` treats any fence-like block with
an adjacent fence-like neighbor as "connected" regardless of client-side
`Properties.NORTH/SOUTH/etc`. This makes `wasCleared → isObscured` reject
paths through fence blocks at block-space level.

**Status:** ✅ Block-space correctly routes around fence connections.

### Anvils — `avoidStuckAnvil`

**Problem:** On ViaVersion, anvil rotation may differ from client — server
has `|` but client shows `---`. Bot approaches from the wrong side and gets
stuck on the real hitbox.

**Fix:**
- Block-space: `adjacentToAnvil` rejects nodes with anvils in cardinal
  neighbors at foot level (no side approach).
- Physics: `AgentBlockCollisions` replaces anvil collision with intersection
  of both rotations — center square `(0.125, 0, 0.125)-(0.875, 1.0, 0.875)`.
  Bot can stand on center top but not edges.

**Status:** ✅ Side approach blocked, center-top standing works.

---

## Movement Surfaces

### Ice

**Status:** ⚠️ Physics has ice via `Block.getSlipperiness()` (friction 0.98),
but simulation mismatch with real server — stable drift above threshold.

**Problem:** Agent sim calculates ice friction slightly differently from the
actual server, causing position mismatch → drift correction kicks in → jerky
movement or path abandonment.

**TODO:** Investigate friction formula in `Agent.travelLiving()` — compare
with actual MC ice physics. Likely a rounding or ordering difference in
velocity/drag application.

### Soul Sand

**Status:** ❌ No special handling in tungsten.

**Problem:** Soul sand has velocity multiplier 0.4 (massive slowdown).
`Agent.getVelocityMultiplier()` may or may not read this from the block —
needs verification. If it doesn't, the sim will predict normal speed while
the real player crawls → huge drift.

**TODO:** Verify `getVelocityMultiplier` applies soul sand slowdown. If not,
add it. Also check if block-space pathfinder accounts for the slowdown in
cost calculation (should prefer avoiding soul sand).

### Honey Blocks

**Status:** ⚠️ Sliding mechanic exists in Agent (descent dampened to -0.05),
but not tested for pathfinding correctness.

**TODO:** Verify sim matches server behavior. Check if pathfinder avoids
honey or handles the slowdown.

### Slime Blocks

**Status:** ⚠️ Walking on slime works. Bounce routing doesn't.

**What works:**
- NPE fix: starting pathfind on slime no longer crashes (null check
  for `this.previous` in `shouldRemoveNode` line 718).
- Bounce condition: inverted to `> 0` (fell onto slime = expand yMax).
- `SlimeBounceMove` physics move exists — jumps on slime, rides arc
  toward target with sprint.

**What doesn't work:**
- Block-space A* never plans "fall on slime → bounce to target" routes.
  The heuristic penalizes going DOWN (away from target in Y), so A*
  always prefers falling sideways and walking under the target.
- `SlimeBounceMove` fires toward the NEXT block-space waypoint, which
  is already under the platform — bounce goes wrong direction.

**Architectural limitation:** Standard A* with distance heuristic can't
plan "go down to go up" routes. Attempted fixes (platform scan shortcut,
heuristic modification) caused lag from scanning hundreds of blocks per
node expansion. Needs a fundamentally different approach.

**TODO (hard):** Pre-computed bounce edges (scan slime before pathfinding,
add direct platform→platform connections) or two-phase pathfinding
(normal A* fails → detect slime → plan bounce route separately).

### Vines

**Status:** ⚠️ Climbing works via `BlockTags.CLIMBABLE` + block-space allows
vine nodes within distance 6.3. Likely functional but not stress-tested.

### Ladders

**Status:** ✅ Climbing works. `ClimbALadderMove` batches ticks.
Block-space has specific ladder distance/height rules.

### Water

**Status:** ✅ Full swim physics: drag, fluid velocity, depth strider,
dolphins grace. `SwimmingMove` handles pathfinding in water.

### Scaffolding

**Status:** ⚠️ Physics partial, block-space doesn't route through.

**What works:**
- Physics: scaffolding is in `BlockTags.CLIMBABLE` → climbing velocity
  clamping works (Agent.java:1033). Sneak descent exception exists
  (Agent.java:731 — velY not zeroed on scaffolding when sneaking).
- `BlockShapeChecker` excludes scaffolding from "normal cube" so it's
  not treated as a wall.

**What doesn't work:**
- Block-space has no scaffolding-specific node generation (unlike ladders
  which have `ClimbALadderMove` and distance/height rules in `BlockNode`).
- Bot won't plan routes UP or DOWN through scaffolding towers.

**TODO:** Implement scaffolding pathfinding — needs block-space node
generation similar to ladders, plus a ScaffoldingMove in physics pathfinder
that handles the unique scaffolding mechanics (walk-in from the side,
descend with sneak, horizontal walking on top).

### Lava

**Status:** ❌ Physics exist (50% drag) but lava nodes are rejected
by `shouldRemoveNode` — bot will never path through lava.

---

## TODO: Path Start Stuck — thin/partial blocks

Bot starts "inside" the block's collision space and can't find any path out.
Server teleports back on every move attempt → infinite loop.

### Confirmed (100% reproduces):
1. All fence types (oak, nether brick, etc.)
2. All wall types (cobblestone, mossy, etc.)
3. All glass pane types (stained, regular)
4. Iron bars
5. Chains
6. Bamboo (solid stalk, not sapling)
12. Anvil (if inside block space)

### Needs checking:
7. Pointed dripstone
8. End rod
9. Lantern
10. Lightning rod
11. Candle
16. Decorated pot
17. Flower pot

---

## TODO: Non-full Hitbox Blocks — verify pathfinding

Blocks with hitboxes smaller than 1x1x1 that may cause issues with
standing, jumping, or routing.

13. Chests (all types, ender chest)
14. Skulls / heads
15. Grindstone
18. Conduit
19. Chorus plant stems
20. Dragon egg

---

## TODO: Damage Blocks — avoid or account for

Pathfinder should avoid or cost-penalize blocks that deal damage.

**Vanilla mechanic (verified in bytecode, MC 1.21.1 yarn):**
Block contact damage (cactus, fire, campfire, etc.) does NOT apply
knockback — `DamageSources.cactus()` has no source entity and no
position, so the knockback code in `LivingEntity.damage()` is skipped.

HOWEVER: `damage()` calls `scheduleVelocityUpdate()` for ALL damage
(unless tagged NO_IMPACT). This makes the server send its current
velocity to the client. If the server's velocity differs from the
client's predicted velocity (it always does slightly), the client
snaps to the server's version — **breaking jump trajectories**.

This is NOT predictable in Agent sim — it depends on the server/client
velocity difference at the moment of damage. The only fix is to
**avoid damage blocks entirely** via cost penalty in pathfinding.

Agent sim now sets `isDamaged=true` and `hurtTicks=10` when touching
damage blocks (`predictDamageFromBlocks` setting). This can be used
for cost penalties in block-space.

**Status:**
- **Lava** — rejected entirely by `shouldRemoveNode`
- **Magma block** — `isDamaged` flag set, no cost penalty yet
- **Cactus** — `isDamaged` flag set, no cost penalty yet
- **Campfire** (lit) — `isDamaged` flag set, no cost penalty yet
- **Fire** — `isDamaged` flag set, no cost penalty yet
- **Sweet berry bush** — velocity multiplier (0.8) + `isDamaged` flag
- **Wither rose** — `isDamaged` flag set, no cost penalty yet
- **Pointed dripstone** — fall damage when landing from ≥1 block, no handling
