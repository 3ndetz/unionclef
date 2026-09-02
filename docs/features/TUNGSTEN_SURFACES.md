# Tungsten — Block Surfaces & Collision Tracking

Status of pathfinding/physics for non-standard blocks.
Legend: ✅ works, ⚠️ partial/buggy, ❌ broken, ❓ not checked

⛔ **ARCHITECTURE NOTE, added 2026-09-02 — read before trusting any ✅/❌/⚠️ verdict below except
the Soul Sand section (already updated for the current system).** Every class this document
names — `BlockNode`, `BlockSpacePathFinder`, `PathFinder`, `ClimbALadderMove`, `SlimeBounceMove`,
`Node.shouldSkipNodeGeneration` — belongs to the LEGACY block-space search, from before
`FastPlanner` existed as a distinct planner and long before G-0 (2026-08-24) made it the primary
one. `FastPlanner` is never mentioned anywhere in this file outside the Soul Sand section. Some
findings here are genuinely still true of the primary planner (mining tool selection: confirmed
independently this session, `TODOS.md` C5.2's corrected entry), some are current-planner-shaped
questions this document simply never asked (vines, checked this session: `FastPlanner`'s own
ladder-climb branch only recognizes `LadderBlock`, not the `BlockTags.CLIMBABLE` scan this
section describes — see `docs/BARITONE-PORT.md`'s move-set-audit section), and the g-cost
accumulation "limitation" under Block Breaking below was fixed for the primary search path
(`BlockSpacePathFinder`'s relaxation now properly accumulates from the parent — confirmed
directly this session, `docs/BARITONE-PORT.md`'s COST MODELS section) though this document still
describes it as an open long-term problem. **Treat every unmarked verdict below as a claim about
the OLD system, not the one that ships today** — `docs/BARITONE-PORT.md` and
`docs/BARITONE-PORT-SPEC.md` are the current, actively-checked sources for the primary planner's
actual surface-handling gaps.

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

**Status:** ⚠️ Partially handled — checked 2026-09-02, this section's "no handling" and open TODO
questions are both answered now, one yes and one half-yes.

- **Physics slowdown: ✅, and always was.** `Agent.getVelocityMultiplier()`
  (`Agent.java:1280-1284`) calls `blockState.getBlock().getVelocityMultiplier()` — vanilla's own
  per-block-type lookup, not a hardcoded list — so soul sand's 0.4 multiplier was never actually
  a risk here; the sim reads whatever the real block returns generically.
- **Cost calculation: partial.** `ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST` exists and IS
  consulted, but only inside `MovementTraverse`'s own execution-time pricing — the approach/
  departure cost when a traverse step is already known to touch soul sand
  (`MovementTraverse.java:196-204`) and the backplace-impossibility rejection
  (`:258`). The SEARCH itself (`FastPlanner.step()`, the function that actually CHOOSES a route)
  still prices a plain cardinal step at a flat `ActionCosts.WALK_ONE_BLOCK_COST` regardless of
  what's underfoot — confirmed directly, see `docs/BARITONE-PORT.md`'s COST MODELS section. So
  the bot will correctly slow down and pay the execution-time cost once routed across soul sand,
  but the planner does not yet prefer a route that avoids it. `CombatPathfinder.java:526` also
  lists `Blocks.SOUL_SAND` for its own, separate hazard/slow classification during combat.

### Honey Blocks

**Status:** ⚠️ Sliding mechanic exists in Agent (descent dampened to -0.05),
but not tested for pathfinding correctness.

**TODO:** Verify sim matches server behavior. Check if pathfinder avoids
honey or handles the slowdown.

### Slime Blocks

**Status:** ✅ Drop-bounce routing works — autotested in-game (2026-07-20,
`deploy/runner/slime_test.py`: course A — sprint off a platform, fall 4 onto
slime, bounce to a +3 platform; course B — fall 3, bounce to +2. Both pass
in ~6s with full health).

**Physics (Agent.tick, was already correct):**
- Bounce: velY inverted on landing unless sneaking (Agent.java ~832).
- No fall damage on slime landing (fall() multiplier 0 unless sneaking).
- onSteppedOn slowdown: velX/velZ ×(0.4 + |velY|·0.2) when |velY| < 0.1.

**Pathfinding chain (fixed 2026-07-20):**
- `PathFinder.checkForFallDamage` — falls of any height allowed when the
  first collidable block straight below is slime
  (`MovementHelper.isSlimeColumnBelow`, scan cap 32).
- `Node.shouldSkipNodeGeneration` + `Node.createAirborneNodes` — same
  slime-column exemption for mid-fall node pruning (was hard-capped ~3).
- `BlockNode.isJumpImpossible` — on-slime nodes allow up-children to
  bounce height (`getSlimeBounceHeight(fall) - 0.2`) instead of 1.4;
  bounce-only children capped at horizontal distance 4. Basic
  height/distance pruning skipped for on-slime nodes.
- `BlockNode.getNodesIn3DCircule` — bounce yMax uses cumulative descent
  along the block path (not just the last hop), at least 1.25 (jump in
  place feeds the bounce); top bounce level now inclusive (off-by-one
  cut the top platform before). Debug Thread.sleep(250) removed — it ate
  half the 480ms block-search budget per slime expansion.
- `SlimeBounceMove` — batches the whole arc into one macro-node. Presses
  jump only when starting from rest (velY ≤ 0.1); when arriving with an
  inverted bounce velocity jump is never pressed (jump() would replace
  the bounce velY with 0.42 — this was the main executor-level bug).

**Known limitations:**
- The block-space heuristic still penalizes descending (dy×1.5), so
  "go down to bounce up" routes are found late; deep slime pits may need
  the generateDeep fallback pass (4800ms).
- Flat-slime (jump in place) gives apex ~1.9 in vanilla — nothing a normal
  jump can't land. Bounce children are generated only when the block path
  DESCENDS onto the slime; every bounce course needs a drop-in.
- Waypoint index advances one expansion behind the landing node, so the
  first bounce may aim at the slime itself and converge on the second.
- Structures near the course confuse routing (the original test world
  spawned a village on the course — the pathfinder tried to route through
  it). Test worlds use GENERATE_STRUCTURES=false.
- shouldResetSearch could re-root the physics search without emitting the
  walked prefix when the executor was idle → instant drift abort
  (fixed: prefix is emitted inline when the executor is not running).

### Block Breaking (break-through pathfinding)

**Status:** ✅ v1 works — autotested (2026-07-20, `deploy/runner/break_test.py`:
sealed bedrock box with a dirt door; course C — mine 2 blocks and pass;
course D — sand falls into the mined doorway and gets re-mined. Both pass.)

**How it works:**
- Block-space: `BlockNode.tryPlanBreakThrough` — an adjacent same-Y cell
  blocked only by breakable blocks becomes a valid child with a `toBreak`
  plan (top-down) and a cost of vanilla mining ticks
  (`calcBlockBreakingDelta`) × `breakCostMultiplier`. Gravity blocks above
  the passage add their mining cost up front.
- `PathFinder.truncateAtBreaks` cuts the physics guidance at the cell before
  the wall (the live world still has the blocks — simulating through them is
  impossible) and hands `pendingBreaks` to the executor on every emission.
- `PathExecutor.tickBreaking` at segment end: aims, `attackBlock` +
  `updateBlockBreakingProgress` + swing per tick, re-mines whatever falls
  into the passage cells (sand/gravel), waits 12 ticks for settling, then
  finishes the segment — the goto retry / continuation search re-plans in
  the opened world.
- Settings: `allowBreak` (default true), `breakCostMultiplier` (1.0).

**Limitations (v1):**
- Only horizontal same-Y break-through (walls); no digging down/up stairs,
  no block PLACING yet.
- Each wall costs a re-search after mining (segmented execution).
- Tool selection not implemented — mines with whatever is held.
- The block-space A* does NOT accumulate cost along the path, so mining
  competes with detours only via the heuristic — in open terrain a detour
  around any finite wall usually wins. Real cost accumulation is the
  long-term fix.
- Mining drives vanilla via aim + held attack key (direct
  updateBlockBreakingProgress is cancelled by vanilla every tick while the
  key is up). Squeezing through a 1-wide mined hole grazes walls — replay
  drift up to ~0.9, needs driftThreshold ≥ 1.0 on break-heavy routes.

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
