# Baritone → tungsten: placement and breaking port specification

Produced 2026-07-30 from two unit studies (MovementTraverse, MovementPillar) plus verification
against the current tungsten tree. Companion to [BARITONE-PORT.md](BARITONE-PORT.md) (the audit
that found 58 re-derived / 40 missing behaviours); this file is the *work order* for the
placement and breaking half of it.

`baritone/` is not compiled — `settings.gradle.kts` keeps it as a source reference. At the time
this was written shredder occupied the `baritone.*` package and was the live delegation target;
since the "G-0" migration (2026-08-24) shredder is ALSO not compiled, and tungsten is the only
pathfinder. This does not change the work below — it was always about copying logic into
tungsten, never about calling shredder. Porting therefore means **copying the logic into
tungsten**, never calling it. The `baritone/` tree in this repo is already yarn-migrated, so
every block quoted below is copy-ready as written.

---

⛔⛔ STATUS CHECKED 2026-09-02: Units 1-3 EXIST IN CODE, Unit 4 DOES NOT, and none of the
"Deletes" columns below were carried out — this doc's Verdict, written as a diagnosis of the
CURRENT problem, now describes a problem this project half-solved a different way. Checked
directly, not inferred from the plan having looked plausible:

- **Units 1-3 landed**: `tungsten/path/movements/Movement.java`, `MovementTraverse.java` (590
  lines), `MovementPillar.java` (468 lines), `MovementState.java`, `MovementStatus.java` all
  exist, and `MovementTraverse` is referenced from `FastPlanner`, `PathExecutor`, `BlockNode`,
  `BridgeTask`, `FastNavigator` — not an orphaned port. Commit `62e11084`, "tungsten: port
  baritone movement substrate + MovementTraverse/MovementPillar (unit 1-3, **unwired**)".
- **The "unwired" in that commit message did not stay true, but "one engine" never happened
  either.** `TODOS.md`'s C5.15 through C5.20 sagas (this same repo, dated through 2026-07-31)
  document the ported moves actually driving the live chase/goto path via `MovementQueue` +
  `BlockPathWalker` — wired, just capped: the "continuous prefix" rule that `BlockPathWalker`
  requires limits ported-move coverage to **~4% of a route** (C5.18), which is a real,
  different, already-tracked blocker from anything in this spec.
- **Unit 4 ("one place planner, one price") was never done.** `BlockNode.tryPlanPlaceThrough`,
  `BlockNode.toPlace`, and `ActionCosts.PLACE_ONE_BLOCK_COST` — all three named for deletion in
  the Unit 4 row below — are still live code (`BlockNode.java:500,762`, `ActionCosts.java:74`,
  the last still read by `FastPlanner.java:1185`). The "rival planner prices the same move
  differently" defect this Verdict names is still exactly as described.
- **The symptom this Verdict opens with (a wall of glass appearing at once) IS fixed, but not
  by consolidating engines** — `helpers/BlockPlaceHelper.java` (its own javadoc confirms this
  precisely) found that the rate gate baritone expects on every placement had been ported only
  into a private `Movement` method, so `PathExecutor.tickPlacing`/`BridgeTask`/`PillarTask`/
  `Py4jEntryPoint.fillCells` all still placed uncapped. Fix shipped: the gate moved to
  `BlockPlaceHelper`, a single shared cooldown every placement path now goes through — same
  fix philosophy as this Verdict argues for (one owner of a shared concern), applied to the
  RATE only, not to the engine count. Matches `TODOS.md` C5.8 ("cheaty placement... fixed").
- **Net picture**: multiple placement/movement engines still coexist exactly as the Verdict
  below describes, in violation of the "never leave duplicate engines" rule elsewhere in this
  project's own checklist — but the specific complaints this file was written to fix (rate,
  and to a real but partial degree, movement-substrate quality) have each been separately
  addressed. The full consolidation this file specifies (delete `tickPlacing`/`placeQueue`/
  `tryPlanPlaceThrough`/`PLACE_ONE_BLOCK_COST`, one Movement per step, one price) remains
  undone and is still the honest state of the "user's headline question" (`TODOS.md` C5).
- **What DID land is high-fidelity, not a rough approximation** — every specific, hard-won
  pitfall named below (P1-P5, and the unnumbered rules) was checked directly against
  `MovementTraverse.java` as it stands today, not assumed from the plan having looked good:
  `COST_INF` is its own positive `1000000` constant with a comment citing the exact reasoning
  below (`:137-142`); `wasTheBridgeBlockAlwaysThere` gates the sprint guard exactly as P3
  describes (`:153,390,410`); the swapped-argument `calcRotationFromVec3d(dest, playerHead,
  ...)` order appears exactly where P5 says it must, in the `MOVE_BACK` backplace branch
  (`:474`, with every OTHER call site in the same file correctly using the opposite,
  "normal" argument order — `:316,342,440,466`); `isInSneakingPose()` gates the click per the
  sneak-pose rule (`:432`); the backplace-exclusion `continue` in the side-place scan is
  present verbatim (`:250-251`). So the gap in this project is not "the port was done
  carelessly" — it is specifically Unit 4 never being started, and the walker-integration cap
  (C5.18) that arrived after this spec was written.

## Verdict

Tungsten must stop treating a placement as an activity that runs *beside* the step that needs
it. Today a bridge is: the planner marks a cell (`FastPlanner.placeAcross`, FastPlanner.java:920)
and flags it for the physics engine that has no place move (`relax(..., true, ...)`,
FastPlanner.java:954); the pathfinder hands a list of positions to a second engine
(`EXECUTOR.placeQueue`, PathFinder.java:318, :999, :1031-1032, :1094); that engine aims and clicks
in its own tick (`PathExecutor.tickPlacing`, PathExecutor.java:430-628) while `BlockPathWalker`
walks the body and negotiates for the camera through a shared flag (`placerOwnsAim`,
BlockPathWalker.java:483-517); a rival planner prices the same move differently
(`BlockNode.tryPlanPlaceThrough`, BlockNode.java:692-731) against a flat invented price
(`PLACE_ONE_BLOCK_COST = WALK * 2.5`, ActionCosts.java:29); and two standalone tasks
(`BridgeTask`, `PillarTask`) implement the manoeuvre a third and fourth time. Every measured
failure below is a seam between those parts. What replaces all of it is baritone's structure: **one
movement object per one-block step**, which prices itself at plan time (`cost()`) and, at run time,
decides walk-vs-break-vs-side-place-vs-sneak-backplace *itself, every tick, from world state*
(`updateState()`), with exactly one per-tick writer of keys and camera (`Movement.update`,
Movement.java:122-151) and exactly one promotion to "click now" — the player's real crosshair
(`MovementHelper.attemptToPlaceABlock`, MovementHelper.java:840-851). Port `MovementTraverse` and
`MovementPillar` verbatim, with the `MovementHelper` / `RotationUtils` / `RayTraceUtils` statics
they call, into a new `kaptainwutax.tungsten.path.movements` package; delete the split-seam engine
rather than tuning it.

---

## How to read this

Four units, in implementation order. Each is a landable change with its own nav-suite gate. The
verbatim code is the deliverable, not an illustration — copy it, then apply only the substitutions
listed in that unit's table. Do not "improve" it in transit: every measured pitfall in the last
section was a place where a previous pass improved it.

Stand and gate (run_suite.py:1-14):

```
docker compose -f deploy/compose.test.yml --profile pvp up -d
python3 deploy/runner/run_suite.py nav                      # whole suite
python3 deploy/runner/run_suite.py nav --only nav_bridge --repeat 3
```

Exit 0 = every selected gate scenario passed; 1 = gate failure; 2 = inconclusive, host starved
(run_suite.py:275-282). Per-course gates are `reached goal (tol 2.5)`, `no self-fall`,
`freezes == 0` (scenarios_nav.py:84-90); FPS is recorded, never a gate (scenarios_nav.py:93-95).
The regime the port has to survive is the stand's, not a desktop's: the last green `nav_gaps`
recorded `avg_fps=10.0` (`deploy/runner/artifacts/20260727-173226/nav_gaps/verdict.json`).

| # | Unit | Gate course | Deletes |
|---|------|-------------|---------|
| 1 | Movement substrate (state, executor, helpers, rotation) | none — baseline capture | nothing |
| 2 | `MovementTraverse` + `MovementQueue` + one-owner wiring + baritone pricing | `nav_bridge` | `PathExecutor.tickPlacing`, `placeQueue`, `BridgeTask` body, walker aim yield, `PLACE_ONE_BLOCK_COST`, "At the gap" shortcut |
| 3 | `MovementPillar` | `nav_wall2` | `PillarTask` body, `FastNavigator` pillar heuristic |
| 4 | One place planner, one price | full suite audit | `BlockNode.tryPlanPlaceThrough`, `BlockNode.toPlace` |

---

## Unit 1 — the movement substrate

### What it is

The five pieces every ported movement needs and tungsten has none of: a per-movement state
object, the single per-tick applier, the block predicates, the rotation/raytrace maths, and the
queue that owns the movement list. Nothing changes behaviour in this unit — it is dead code until
unit 2 wires it — which is deliberate: it is the unit where the copy is checked against upstream
line by line, with no course to blame.

### Verbatim upstream code to copy

`baritone/src/main/java/baritone/pathing/movement/Movement.java:36` — the scan order. Copy
exactly; the order is load-bearing (DOWN last is what makes `preferDown` work, and NORTH-first is
what makes a side place deterministic):

```java
public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP =
        {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
```

`baritone/src/main/java/baritone/api/pathing/movement/ActionCosts.java:20-97` — the numbers. These
are ticks, one unit system, and they are the reason the search can tell a cheap side place from an
expensive backplace:

```java
double WALK_ONE_BLOCK_COST = 20 / 4.317;              // 4.633
double WALK_ONE_IN_WATER_COST = 20 / 2.2;             // 9.091
double WALK_ONE_OVER_SOUL_SAND_COST = WALK_ONE_BLOCK_COST * 2;
double SNEAK_ONE_BLOCK_COST = 20 / 1.3;               // 15.385  <-- the bridge price
double SPRINT_ONE_BLOCK_COST = 20 / 5.612;            // 3.564
double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST;  // 0.769
double LADDER_UP_ONE_COST = 20 / 2.35;                // 8.511
double COST_INF = 1000000;

double FALL_1_25_BLOCKS_COST = distanceToTicks(1.25);   // 6.2343
double FALL_0_25_BLOCKS_COST = distanceToTicks(0.25);   // 3.0710
double JUMP_ONE_BLOCK_COST = FALL_1_25_BLOCKS_COST - FALL_0_25_BLOCKS_COST; // 3.1633

static double velocity(int ticks) {
    return (Math.pow(0.98, ticks) - 1) * -3.92;
}

static double distanceToTicks(double distance) {
    if (distance == 0) {
        return 0; // Avoid 0/0 NaN
    }
    double tmpDistance = distance;
    int tickCount = 0;
    while (true) {
        double fallDistance = velocity(tickCount);
        if (tmpDistance <= fallDistance) {
            return tickCount + tmpDistance / fallDistance;
        }
        tmpDistance -= fallDistance;
        tickCount++;
    }
}
```

`Movement.java:122-151` — the single applier. Point by point: flight off; run the state machine;
swim out of liquid; dig out of a wall; hand the target to the aim actuator with its force flag;
**release every key, then press exactly what this tick declared, then clear the map**. A key not
set this tick is released this tick — nothing latches, which is what makes "one owner" true rather
than aspirational:

```java
@Override
public MovementStatus update() {
    ctx.player().getAbilities().flying = false;
    currentState = updateState(currentState);
    if (MovementHelper.isLiquid(ctx, ctx.playerFeet()) && ctx.player().getPos().y < dest.y + 0.6) {
        currentState.setInput(Input.JUMP, true);
    }
    if (ctx.player().isInsideWall()) {
        ctx.getSelectedBlock().ifPresent(pos -> MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos)));
        currentState.setInput(Input.CLICK_LEFT, true);
    }

    // If the movement target has to force the new rotations, or we aren't using silent move, then force the rotations
    currentState.getTarget().getRotation().ifPresent(rotation ->
            baritone.getLookBehavior().updateTarget(
                    rotation,
                    currentState.getTarget().hasToForceRotations()));
    baritone.getInputOverrideHandler().clearAllKeys();
    currentState.getInputStates().forEach((input, forced) -> {
        baritone.getInputOverrideHandler().setInputForceState(input, forced);
    });
    currentState.getInputStates().clear();

    // If the current status indicates a completed movement
    if (currentState.getStatus().isComplete()) {
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    return currentState.getStatus();
}
```

`Movement.java:153-193` — `prepared()`: break what is in the way before the movement runs. This is
break-as-part-of-a-move, and it is why a ported movement needs no separate break engine. Note the
deliberate fallback when no face is reachable, and that the `somethingInTheWay` / `UNREACHABLE`
branch at :186-191 is unreachable for single-cell movements (the loop always returns inside the
`if`):

```java
protected boolean prepared(MovementState state) {
    if (state.getStatus() == MovementStatus.WAITING) {
        return true;
    }
    boolean somethingInTheWay = false;
    for (BetterBlockPos blockPos : positionsToBreak) {
        if (!ctx.world().getNonSpectatingEntities(FallingBlockEntity.class, new Box(0, 0, 0, 1, 1.1, 1).offset(blockPos)).isEmpty() && Baritone.settings().pauseMiningForFallingBlocks.value) {
            return false;
        }
        if (!MovementHelper.canWalkThrough(ctx, blockPos)) { // can't break air, so don't try
            somethingInTheWay = true;
            MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, blockPos));
            Optional<Rotation> reachable = RotationUtils.reachable(ctx, blockPos, ctx.playerController().getBlockReachDistance());
            if (reachable.isPresent()) {
                Rotation rotTowardsBlock = reachable.get();
                state.setTarget(new MovementState.MovementTarget(rotTowardsBlock, true));
                if (ctx.isLookingAt(blockPos) || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)) {
                    state.setInput(Input.CLICK_LEFT, true);
                }
                return false;
            }
            //get rekt minecraft
            //i'm doing it anyway
            //i dont care if theres snow in the way!!!!!!!
            //you dont own me!!!!
            state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                    VecUtils.getBlockPosCenter(blockPos), ctx.playerRotations()), true)
            );
            state.setInput(Input.CLICK_LEFT, true);
            return false;
        }
    }
    if (somethingInTheWay) {
        state.setStatus(MovementStatus.UNREACHABLE);
        return true;
    }
    return true;
}
```

`Movement.java:225-237` — the status ladder. PREPPING → WAITING → RUNNING in one call once
`prepared()` is true:

```java
public MovementState updateState(MovementState state) {
    if (!prepared(state)) {
        return state.setStatus(MovementStatus.PREPPING);
    } else if (state.getStatus() == MovementStatus.PREPPING) {
        state.setStatus(MovementStatus.WAITING);
    }
    if (state.getStatus() == MovementStatus.WAITING) {
        state.setStatus(MovementStatus.RUNNING);
    }
    return state;
}
```

`MovementState.java:28-92` — the shape: `{MovementStatus status; MovementTarget target = {Rotation
rotation, boolean forceRotations}; Map<Input,Boolean> inputState}`. Statuses used by the two
ported movements: `PREPPING, WAITING, RUNNING, SUCCESS, UNREACHABLE, FAILED`. Inputs used:
`MOVE_FORWARD, MOVE_BACK, SPRINT, SNEAK, JUMP, CLICK_LEFT, CLICK_RIGHT`.

`MovementHelper.java:715-722` — the soft "walk that way" target. Not forced, and **the current
pitch is preserved**; this is what keeps the camera level while walking and is not
interchangeable with a forced target:

```java
static void moveTowards(IPlayerContext ctx, MovementState state, BlockPos pos) {
    state.setTarget(new MovementTarget(
            RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                    VecUtils.getBlockPosCenter(pos),
                    ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()),
            false
    )).setInput(Input.MOVE_FORWARD, true);
}
```

`RotationUtils.java:88-139` — the aim maths, including the relative wrap that stops a yaw from
taking the long way round:

```java
public static Rotation wrapAnglesToRelative(Rotation current, Rotation target) {
    if (current.yawIsReallyClose(target)) {
        return new Rotation(current.getYaw(), target.getPitch());
    }
    return target.subtract(current).normalize().add(current);
}

public static Rotation calcRotationFromVec3d(Vec3d orig, Vec3d dest, Rotation current) {
    return wrapAnglesToRelative(current, calcRotationFromVec3d(orig, dest));
}

private static Rotation calcRotationFromVec3d(Vec3d orig, Vec3d dest) {
    double[] delta = {orig.x - dest.x, orig.y - dest.y, orig.z - dest.z};
    double yaw = MathHelper.atan2(delta[0], -delta[2]);
    double dist = Math.sqrt(delta[0] * delta[0] + delta[2] * delta[2]);
    double pitch = MathHelper.atan2(delta[1], dist);
    return new Rotation((float) (yaw * RAD_TO_DEG), (float) (pitch * RAD_TO_DEG));
}

public static Vec3d calcLookDirectionFromRotation(Rotation rotation) {
    float flatZ = MathHelper.cos((-rotation.getYaw() * DEG_TO_RAD_F) - (float) Math.PI);
    float flatX = MathHelper.sin((-rotation.getYaw() * DEG_TO_RAD_F) - (float) Math.PI);
    float pitchBase = -MathHelper.cos(-rotation.getPitch() * DEG_TO_RAD_F);
    float pitchHeight = MathHelper.sin(-rotation.getPitch() * DEG_TO_RAD_F);
    return new Vec3d(flatX * pitchBase, pitchHeight, flatZ * pitchBase);
}
// Rotation.java:116-118 -> public Rotation withPitch(float pitch) { return new Rotation(this.yaw, pitch); }
// Rotation.java:126-131 -> isReallyCloseTo(other) = yawIsReallyClose(other) && |pitch - other.pitch| < 0.01
```

`RotationUtils.java:152-248` — the reach gate. A rotation is only "reachable" if a raytrace along
the rotation the aim actuator will *actually* produce lands on the wanted block. Centre first,
then the six side offsets interpolated across the block's outline shape:

```java
public static Optional<Rotation> reachable(IPlayerContext ctx, BlockPos pos, double blockReachDistance, boolean wouldSneak) {
    if (BaritoneAPI.getSettings().remainWithExistingLookDirection.value && ctx.isLookingAt(pos)) {
        Rotation hypothetical = ctx.playerRotations().add(new Rotation(0, 0.0001F));
        if (wouldSneak) {
            HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), hypothetical, blockReachDistance, true);
            if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(pos)) {
                return Optional.of(hypothetical); // yes, if we sneaked we would still be looking at the block
            }
        } else {
            return Optional.of(hypothetical);
        }
    }
    Optional<Rotation> possibleRotation = reachableCenter(ctx, pos, blockReachDistance, wouldSneak);
    if (possibleRotation.isPresent()) return possibleRotation;
    BlockState state = ctx.world().getBlockState(pos);
    VoxelShape shape = state.getOutlineShape(ctx.world(), pos);
    if (shape.isEmpty()) shape = VoxelShapes.fullCube();
    for (Vec3d sideOffset : BLOCK_SIDE_MULTIPLIERS) {   // {0.5,0,0.5}Down {0.5,1,0.5}Up {0.5,0.5,0}North {0.5,0.5,1}South {0,0.5,0.5}West {1,0.5,0.5}East
        double xDiff = shape.getMin(Direction.Axis.X) * sideOffset.x + shape.getMax(Direction.Axis.X) * (1 - sideOffset.x);
        double yDiff = shape.getMin(Direction.Axis.Y) * sideOffset.y + shape.getMax(Direction.Axis.Y) * (1 - sideOffset.y);
        double zDiff = shape.getMin(Direction.Axis.Z) * sideOffset.z + shape.getMax(Direction.Axis.Z) * (1 - sideOffset.z);
        possibleRotation = reachableOffset(ctx, pos, new Vec3d(pos.getX(), pos.getY(), pos.getZ()).add(xDiff, yDiff, zDiff), blockReachDistance, wouldSneak);
        if (possibleRotation.isPresent()) return possibleRotation;
    }
    return Optional.empty();
}

public static Optional<Rotation> reachableOffset(IPlayerContext ctx, BlockPos pos, Vec3d offsetPos, double blockReachDistance, boolean wouldSneak) {
    Vec3d eyes = wouldSneak ? RayTraceUtils.inferSneakingEyePosition(ctx.player()) : ctx.player().getCameraPosVec(1.0F);
    Rotation rotation = calcRotationFromVec3d(eyes, offsetPos, ctx.playerRotations());
    Rotation actualRotation = BaritoneAPI.getProvider().getBaritoneForPlayer(ctx.player()).getLookBehavior().getAimProcessor().peekRotation(rotation);
    HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRotation, blockReachDistance, wouldSneak);
    if (result != null && result.getType() == HitResult.Type.BLOCK) {
        if (((BlockHitResult) result).getBlockPos().equals(pos)) return Optional.of(rotation);
        if (ctx.world().getBlockState(pos).getBlock() instanceof AbstractFireBlock && ((BlockHitResult) result).getBlockPos().equals(pos.down())) return Optional.of(rotation);
    }
    return Optional.empty();
}
```

`RayTraceUtils.java:33-68` — the raytrace, and the sneaking eye at **1.27** (standing 1.62,
`IPlayerContext.java:101-103`). `ShapeType.OUTLINE`, `FluidHandling.NONE`:

```java
public static RaycastContext.FluidHandling fluidHandling = RaycastContext.FluidHandling.NONE;

public static HitResult rayTraceTowards(Entity entity, Rotation rotation, double blockReachDistance, boolean wouldSneak) {
    Vec3d start;
    if (wouldSneak) {
        start = inferSneakingEyePosition(entity);
    } else {
        start = entity.getCameraPosVec(1.0F); // do whatever is correct
    }
    Vec3d direction = RotationUtils.calcLookDirectionFromRotation(rotation);
    Vec3d end = start.add(direction.x * blockReachDistance, direction.y * blockReachDistance, direction.z * blockReachDistance);
    return entity.getWorld().raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, fluidHandling, entity));
}

public static Vec3d inferSneakingEyePosition(Entity entity) {
    return new Vec3d(entity.getX(), entity.getY() + IPlayerContext.eyeHeight(true), entity.getZ());
}
```

`VecUtils.java:45-79` — two different centres, and they are not interchangeable:
`calculateBlockCenter` is the centre of the *collision shape* (used to aim at a block to break),
`getBlockPosCenter` is the geometric cell centre (used to aim at a block to walk towards or place
against):

```java
public static Vec3d calculateBlockCenter(World world, BlockPos pos) {
    BlockState b = world.getBlockState(pos);
    VoxelShape shape = b.getCollisionShape(world, pos);
    if (shape.isEmpty()) return getBlockPosCenter(pos);
    double xDiff = (shape.getMin(Direction.Axis.X) + shape.getMax(Direction.Axis.X)) / 2;
    double yDiff = (shape.getMin(Direction.Axis.Y) + shape.getMax(Direction.Axis.Y)) / 2;
    double zDiff = (shape.getMin(Direction.Axis.Z) + shape.getMax(Direction.Axis.Z)) / 2;
    if (b.getBlock() instanceof AbstractFireBlock) yDiff = 0;
    return new Vec3d(pos.getX() + xDiff, pos.getY() + yDiff, pos.getZ() + zDiff);
}

public static Vec3d getBlockPosCenter(BlockPos pos) {
    return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
}
```

`IPlayerContext.java:62-120` — the feet cell, the head, and what "looking at" means. The
`+0.1251` and the slab correction are not cosmetic: a naive `BlockPos.ofFloored(y)` reports the
wrong cell on landing ticks, which is exactly when `SUCCESS` is tested:

```java
default BetterBlockPos playerFeet() {
    // TODO find a better way to deal with soul sand!!!!!
    BetterBlockPos feet = new BetterBlockPos(player().getPos().x, player().getPos().y + 0.1251, player().getPos().z);
    try {
        if (world().getBlockState(feet).getBlock() instanceof SlabBlock) {
            return feet.up();
        }
    } catch (NullPointerException ignored) {}
    return feet;
}

default Vec3d playerHead() {
    return new Vec3d(player().getPos().x, player().getPos().y + player().getStandingEyeHeight(), player().getPos().z);
}

default Optional<BlockPos> getSelectedBlock() {
    HitResult result = objectMouseOver();
    if (result != null && result.getType() == HitResult.Type.BLOCK) {
        return Optional.of(((BlockHitResult) result).getBlockPos());
    }
    return Optional.empty();
}

default boolean isLookingAt(BlockPos pos) {
    return getSelectedBlock().equals(Optional.of(pos));
}
```

`BaritonePlayerContext.java:84-86` — and what `objectMouseOver` *is*. A live raytrace recomputed
this tick from the real eye and the real current rotations, **not** the per-frame cached hit:

```java
public HitResult objectMouseOver() {
    return RayTraceUtils.rayTraceTowards(player(), playerRotations(), playerController().getBlockReachDistance());
}
```

`MovementHelper` block predicates. Copy these bodies:

```java
// MovementHelper.java:340-367
static boolean isReplaceable(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
    Block block = state.getBlock();
    if (block instanceof AirBlock) return true;
    if (block instanceof SnowBlock) {
        if (!bsi.worldContainsLoadedChunk(x, z)) return true;
        return state.get(SnowBlock.LAYERS) == 1;
    }
    if (block == Blocks.LARGE_FERN || block == Blocks.TALL_GRASS) return true;
    return state.isReplaceable();
}

// MovementHelper.java:420-431 — the hazard list nav_hazard exists to test
static boolean avoidWalkingInto(BlockState state) {
    Block block = state.getBlock();
    return !state.getFluidState().isEmpty()
            || block == Blocks.MAGMA_BLOCK
            || block == Blocks.CACTUS
            || block == Blocks.SWEET_BERRY_BUSH
            || block instanceof AbstractFireBlock
            || block instanceof EndPortalFrameBlock
            || block == Blocks.END_PORTAL
            || block == Blocks.COBWEB
            || block == Blocks.BUBBLE_COLUMN;
}

// MovementHelper.java:637-647 — the ONLY definition of "a face I can click"
static boolean canPlaceAgainst(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
    if (AltoClefSettings.getInstance().shouldAvoidPlacingAt(x, y, z)) return false;
    if (!bsi.worldBorder.canPlaceAt(x, z)) {
        return false;
    }
    // can we look at the center of a side face of this block and likely be able to place?
    // (thats how this check is used)
    // therefore dont include weird things that we technically could place against (like carpet) but practically can't
    return isBlockNormalCube(state) || state.getBlock() == Blocks.GLASS || state.getBlock() instanceof StainedGlassBlock;
}

// MovementHelper.java:586-623 — needed by MovementTraverse.cost's standingOnABlock
static boolean mustBeSolidToWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
    Block block = state.getBlock();
    if (block == Blocks.LADDER || block == Blocks.VINE) return false;
    if (!state.getFluidState().isEmpty()) {
        if (block instanceof SlabBlock) { if (state.get(SlabBlock.TYPE) != SlabType.BOTTOM) return true; }
        else if (block instanceof StairsBlock) {
            if (state.get(StairsBlock.HALF) == BlockHalf.TOP) return true;
            StairShape shape = state.get(StairsBlock.SHAPE);
            if (shape == StairShape.INNER_LEFT || shape == StairShape.INNER_RIGHT) return true;
        } else if (block instanceof TrapdoorBlock) {
            if (!state.get(TrapdoorBlock.OPEN) && state.get(TrapdoorBlock.HALF) == BlockHalf.TOP) return true;
        } else if (block == Blocks.SCAFFOLDING) return true;
        else if (block instanceof LeavesBlock) return true;
        if (context.assumeWalkOnWater) return false;
        Block blockAbove = context.getBlock(x, y + 1, z);
        if (blockAbove instanceof FluidBlock) return false;
    }
    return true;
}

// MovementHelper.java:649-690 — mining price. Returns 0 when nothing needs breaking, so the
// same expression prices a walk and a dig; COST_INF is what makes an unbreakable cell unplannable.
static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, boolean includeFalling) {
    return getMiningDurationTicks(context, x, y, z, context.get(x, y, z), includeFalling);
}

static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, BlockState state, boolean includeFalling) {
    Block block = state.getBlock();
    if (!canWalkThrough(context, x, y, z, state)) {
        if (!state.getFluidState().isEmpty()) {
            return COST_INF;
        }
        double mult = context.breakCostMultiplierAt(x, y, z, state);
        if (mult >= COST_INF) {
            return COST_INF;
        }
        if (avoidBreaking(context.bsi, x, y, z, state)) {
            return COST_INF;
        }
        double strVsBlock = context.toolSet.getStrVsBlock(state);
        if (strVsBlock <= 0) {
            return COST_INF;
        }
        if (AltoClefSettings.getInstance().shouldAvoidBreaking(x, y, z)) {
            return COST_INF;
        }
        double result = 1 / strVsBlock;
        result += context.breakBlockAdditionalCost;
        result *= mult;
        if (includeFalling) {
            BlockState above = context.get(x, y + 1, z);
            if (above.getBlock() instanceof FallingBlock) {
                result += getMiningDurationTicks(context, x, y + 1, z, above, true);
            }
        }
        return result;
    }
    return 0; // we won't actually mine it, so don't check fallings above
}

static boolean isBottomSlab(BlockState state) {
    return state.getBlock() instanceof SlabBlock
            && state.get(SlabBlock.TYPE) == SlabType.BOTTOM;
}
```

`CalculationContext.java:186-200` — the place gate, one place, five reasons to refuse:

```java
public double costOfPlacingAt(int x, int y, int z, BlockState current) {
    if (!hasThrowaway) { // only true if allowPlace is true, see constructor
        return COST_INF;
    }
    if (isPossiblyProtected(x, y, z)) {
        return COST_INF;
    }
    if (!worldBorder.canPlaceAt(x, z)) {
        return COST_INF;
    }
    if (AltoClefSettings.getInstance().shouldAvoidPlacingAt(x, y, z)) {
        return COST_INF;
    }
    return placeBlockCost;
}
```

`BlockPlaceHelper.java:29-57` — how `CLICK_RIGHT` becomes a placement: one attempt every four
ticks, charged even when the attempt fails, and **the hit result handed to `interactBlock` is the
one the raytrace produced**:

```java
// base ticks between places caused by tick logic
private static final int BASE_PLACE_DELAY = 1;   // Baritone.settings().rightClickSpeed = 4

public void tick(boolean rightClickRequested) {
    if (rightClickTimer > 0) {
        rightClickTimer--;
        return;
    }
    HitResult mouseOver = ctx.objectMouseOver();
    if (!rightClickRequested || ctx.player().isRiding() || mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
        return;
    }
    rightClickTimer = Baritone.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
    for (Hand hand : Hand.values()) {
        if (ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, (BlockHitResult) mouseOver) == ActionResult.SUCCESS) {
            ctx.player().swingHand(hand);
            return;
        }
        if (!ctx.player().getStackInHand(hand).isEmpty() && ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand) == ActionResult.SUCCESS) {
            return;
        }
    }
}
```

`InputOverrideHandler.java:90-92` — and `CLICK_LEFT` forced on a tick cancels `CLICK_RIGHT`.
Breaking and placing never overlap. Port that interlock into the executor.

Copied without change, no substitutions, cited rather than quoted to keep this document readable:
`MovementHelper.java:146-272` (`canWalkThrough` family), `:374-418` (`isDoorPassable` /
`isGatePassable` / `isHorizontalBlockPassable`), `:447-555` (`canWalkOn` overloads), `:557-581`
(`canUseFrostWalker`), `:731-786` (`isWater` / `isLava` / `isLiquid` / `possiblyFlowing` /
`isFlowing`), `:788-804` (`isBlockNormalCube`), `RotationUtils.java:57-64`
(`BLOCK_SIDE_MULTIPLIERS`), `:259-261` (`reachableCenter`).

### Tungsten placement

New package `kaptainwutax.tungsten.path.movements`. No change to the physics engine.

| File | Contents |
|------|----------|
| `movements/MovementState.java` | `MovementStatus` enum, `MovementTarget{Rotation, boolean forceRotations}`, `EnumMap<Input,Boolean>`; plus a new `Input` enum `{MOVE_FORWARD, MOVE_BACK, MOVE_LEFT, MOVE_RIGHT, JUMP, SNEAK, SPRINT, CLICK_LEFT, CLICK_RIGHT}` |
| `movements/MovementExecutor.java` | `Movement.update()` (Movement.java:122-151) as the single per-tick applier + the `BlockPlaceHelper` 4-tick right-click gate + the `CLICK_LEFT` cancels `CLICK_RIGHT` interlock |
| `movements/MovementHelperB.java` | the ported statics above |
| `movements/RotationHelper.java` | `calcRotationFromVec3d` / `wrapAnglesToRelative` / `calcLookDirectionFromRotation` / `rayTraceTowards` / `inferSneakingEyePosition` / `reachable` / `reachableOffset` / `reachableCenter` / `calculateBlockCenter` / `getBlockPosCenter` / `isReallyCloseTo` / `playerFeet` / `playerHead` / `liveHit` |
| `movements/MovementQueue.java` | holds the movement list, ticks the head, advances on `SUCCESS`, replans on `UNREACHABLE`/`FAILED`, exposes `isRunning()` |

`MovementExecutor` detail that decides whether this port works: `CLICK_RIGHT` does **not** press
`useKey`. It calls `mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit)` with
`hit = RealPlacement.readyToPlace(mc, placeAt)` and is a **no-op when that returns null**. That is
exactly baritone's `ctx.isLookingAt(goalLook)` gate, and `RealPlacement.readyToPlace`
(RealPlacement.java:44-61) already implements it — reuse it, do not write a second one.
`CLICK_LEFT` presses `attackKey`.

### Substitution table

| baritone | tungsten |
|---|---|
| `ctx.player()` | the `ClientPlayerEntity` passed into `tick` |
| `ctx.world()` / `context.bsi` | `player.getEntityWorld()` (`WorldView`) |
| `context.get(x,y,z)` | `world.getBlockState(scratch.set(x,y,z))` with a `BlockPos.Mutable` |
| `ctx.playerFeet()` | port `IPlayerContext.playerFeet` verbatim, `+0.1251` and the slab-up correction included |
| `ctx.playerHead()` | `new Vec3d(player.getX(), player.getY() + player.getStandingEyeHeight(), player.getZ())` — **not** `getEyePos()`, which already folds in the pose and destroys the standing-vs-sneaking-eye separation the backplace pitch depends on |
| `ctx.playerRotations()` | `new Rotation(player.getYaw(), player.getPitch())` |
| `ctx.objectMouseOver()` / `getSelectedBlock()` / `isLookingAt()` | `RotationHelper.liveHit(player, currentRotations, reach)` — a live raytrace, per `BaritonePlayerContext.java:84-86` |
| `ctx.playerController().getBlockReachDistance()` | `Math.min(player.getBlockInteractionRange(), 4.5)` (`Settings.java:385` = 4.5f) |
| `getLookBehavior().updateTarget(rot, true)` | `WindMouseRotation.INSTANCE.setTarget(yaw, pitch)` (WindMouseRotation.java:96) |
| `getLookBehavior().updateTarget(rot, false)` | `WindMouseRotation.INSTANCE.setTargetFast(yaw, player.getPitch())` (WindMouseRotation.java:101) — the `moveTowards` case, pitch preserved |
| `aimProcessor.peekRotation(r)` | no equivalent. Substitute the strictly more conservative form: raytrace from the player's **current** rotations, so a gate can only fire once the aim has actually arrived |
| `InputOverrideHandler` | `MovementExecutor`'s key writer (`KeyBinding.setPressed`) |
| `InventoryBehavior.selectThrowawayForLocation(true, …)` | `TungstenModDataContainer.equipBlockHook.run()` (TungstenModDataContainer.java:36) |
| `selectThrowawayForLocation(false, …)` (test only) | `TungstenConfig.get().allowPlace && player.getMainHandStack().getItem() instanceof BlockItem`. The tungsten hook takes no location, so baritone's per-location item filter has no equivalent — record the divergence, do not fake it |
| `context.costOfPlacingAt(x,y,z,state)` | `!hasThrowaway ? COST_INF : !PlaceRules.canPlace(world, pos) ? COST_INF : 20.0 * TungstenConfig.get().placeCostMultiplier` (PlaceRules.java:24-43, TungstenConfig.java:144) |
| `context.canSprint` | `player.getHungerManager().getFoodLevel() > 6` (CalculationContext.java:108) |
| `context.waterWalkSpeed` | `WALK_ONE_IN_WATER_COST` until a swim-speed model exists (CalculationContext.java:155) |
| `context.walkOnWaterOnePenalty` | `3.0` (`Settings.java:127`) |
| `context.jumpPenalty` | `2.0` (`Settings.java:122`) |
| `context.toolSet.getStrVsBlock` | `state.calcBlockBreakingDelta(player, world, pos)` as `BlockNode.breakTicks` already does (BlockNode.java:725-731). This is knowingly wrong by up to ~25x — see the ToolSet finding in BARITONE-PORT.md; it is not this port's unit |
| `AltoClefSettings.shouldAvoidBreaking` / `shouldAvoidPlacingAt` | `BreakRules.canBreak` (BreakRules.java:24) / `PlaceRules.canPlace` (PlaceRules.java:24) |
| `AltoClefSettings.shouldAvoidWalkThroughForce` | no equivalent. Drop those two early-returns |
| `logDebug` / `logDirect` | `Debug.logMessage` |
| Settings | new `TungstenConfig` booleans at baritone's verified defaults: `overshootTraverse=true` (Settings.java:365), `walkWhileBreaking=true` (:884), `assumeSafeWalk=false` (:167), `sprintInWater=true` (:801 — the unit report said false; the source says true), `rightClickSpeed=4` (:375) |

### Traps on the tungsten side

- **`COST_INF` has the wrong sign.** `ActionCosts.COST_INF = -1000000` (ActionCosts.java:4), while
  baritone's is `+1000000` (ActionCosts.java:46). Every ported line reads `if (x >= COST_INF)
  return COST_INF;`, which silently inverts against the tungsten constant. The movements package
  must declare its own `COST_INF = 1_000_000` and never read `path.calculators.ActionCosts.COST_INF`.
- **`RealPlacement.readyToPlace` reads a per-frame value.** It uses `mc.crosshairTarget`
  (RealPlacement.java:45); baritone's equivalent is a live raytrace recomputed this tick
  (BaritonePlayerContext.java:84-86). At the stand's measured `avg_fps=10.0`
  (`artifacts/20260727-173226/nav_gaps/verdict.json`) that value is stale by one to two ticks —
  precisely the window in which sneak takes effect and the eye drops 1.62 → 1.27. Re-base
  `readyToPlace` on `RotationHelper.liveHit(...)` in this unit, keep it as the single gate, and
  keep its replaceable-target branch (RealPlacement.java:56-60) which mirrors baritone's
  `selectedBlock.equals(placeAt)` case.
- **`canPlaceAgainst` already agrees with upstream** (RealPlacement.java:70-76 vs
  MovementHelper.java:637-647) and is the only piece of the current place path that does. It is
  missing the protection and world-border refusals; add `PlaceRules.allowedByPolicy(pos)`
  (PlaceRules.java:47) rather than writing a second predicate.

### What it deletes

Nothing. This unit is additive by construction; if it changes any course result, the substrate is
wired somewhere it should not be.

### Acceptance test

`python3 deploy/runner/run_suite.py nav` before and after, and the two summaries must be
identical course-for-course. This is also the baseline the next three units are judged against,
which is what the suite was built for (scenarios_nav.py:3-8). Record the artifact directory in the
commit message.

---

## Unit 2 — MovementTraverse: one one-block step, walk or bridge

### What it is

The whole one-block cardinal step as ONE object: walking it, mining what is in the way, opening a
door or gate on the way through, side-placing the floor, or sneak-backplacing the floor and
stepping onto it. `src` → `dest = src + one cardinal, same Y`. Constructed as
`super(baritone, from, to, new BetterBlockPos[]{to.up(), to}, to.down())` (MovementTraverse.java:58):
`positionsToBreak[0] = dest.up()` (head cell), `positionsToBreak[1] = dest` (feet cell),
`positionToPlace = dest.down()`. It carries exactly one piece of mutable state —
`wasTheBridgeBlockAlwaysThere` — which is the only thing gating sprint, so it lives on the movement
object and resets per attempt.

Two facts about this shape are the reason it works where the split engine did not. First, the cost
function is the filter: a backplace costs `WALK * (15.385/4.633) + 20 ≈ 35` ticks against 4.633 for
a walk and 3.564 sprinting, and a side place costs `4.633 + 20` with no sneak multiplier — so the
search bridges only where nothing cheaper exists and prefers side places automatically. Second,
the run-time decision is re-made every tick from world state, so there is no seam to fall through:
if the support appeared, the same object walks; if it vanished, the same object places.

### Verbatim upstream code to copy

Fields and geometry — `MovementTraverse.java:52-75`:

```java
    /** Did we have to place a bridge block or was it always there */
    private boolean wasTheBridgeBlockAlwaysThere = true;

    public MovementTraverse(IBaritone baritone, BetterBlockPos from, BetterBlockPos to) {
        super(baritone, from, to, new BetterBlockPos[]{to.up(), to}, to.down());
    }

    @Override
    public void reset() {
        super.reset();
        wasTheBridgeBlockAlwaysThere = true;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest);
    }
```

`cost()` in full — `MovementTraverse.java:77-169`. Note in particular: the ladder guard (you
cannot sneak-place off a ladder) is a `COST_INF` at plan time, not a run-time check; the
side-place scan **excludes the source column** because that neighbour is the backplace and costs a
sneak; and the three backplace vetoes (soul sand / half slab, not standing on a block, lily pad or
carpet over fluid) are refusals, not penalties:

```java
    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        BlockState pb0 = context.get(destX, y + 1, destZ);
        BlockState pb1 = context.get(destX, y, destZ);
        BlockState destOn = context.get(destX, y - 1, destZ);
        BlockState srcDown = context.get(x, y - 1, z);
        Block srcDownBlock = srcDown.getBlock();
        boolean standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, srcDown);
        boolean frostWalker = standingOnABlock && !context.assumeWalkOnWater && MovementHelper.canUseFrostWalker(context, destOn);
        if (frostWalker || MovementHelper.canWalkOn(context, destX, y - 1, destZ, destOn)) { //this is a walk, not a bridge
            double WC = WALK_ONE_BLOCK_COST;
            boolean water = false;
            if (MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1)) {
                WC = context.waterWalkSpeed;
                water = true;
            } else {
                if (destOn.getBlock() == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                } else if (frostWalker) {
                    // with frostwalker we can walk on water without the penalty, if we are sure we won't be using jesus
                } else if (destOn.getBlock() == Blocks.WATER) {
                    WC += context.walkOnWaterOnePenalty;
                }
                if (srcDownBlock == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                }
            }
            double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
            if (hardness1 >= COST_INF) {
                return COST_INF;
            }
            double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
            if (hardness1 == 0 && hardness2 == 0) {
                if (!water && context.canSprint) {
                    // If there's nothing in the way, and this isn't water, and we aren't sneak placing
                    // We can sprint =D
                    WC *= SPRINT_MULTIPLIER;
                }
                return WC;
            }
            if (srcDownBlock == Blocks.LADDER || srcDownBlock == Blocks.VINE) {
                hardness1 *= 5;
                hardness2 *= 5;
            }
            return WC + hardness1 + hardness2;
        } else {//this is a bridge, so we need to place a block
            if (srcDownBlock == Blocks.LADDER || srcDownBlock == Blocks.VINE) {
                return COST_INF;
            }
            if (MovementHelper.isReplaceable(destX, y - 1, destZ, destOn, context.bsi)) {
                boolean throughWater = MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1);
                if (MovementHelper.isWater(destOn) && throughWater) {
                    // this happens when assume walk on water is true and this is a traverse in water, which isn't allowed
                    return COST_INF;
                }
                double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, destOn);
                if (placeCost >= COST_INF) {
                    return COST_INF;
                }
                double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
                if (hardness1 >= COST_INF) {
                    return COST_INF;
                }
                double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true);
                double WC = throughWater ? context.waterWalkSpeed : WALK_ONE_BLOCK_COST;
                for (int i = 0; i < 5; i++) {
                    int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getOffsetX();
                    int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getOffsetY();
                    int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getOffsetZ();
                    if (againstX == x && againstZ == z) { // this would be a backplace
                        continue;
                    }
                    if (MovementHelper.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) { // found a side place option
                        return WC + placeCost + hardness1 + hardness2;
                    }
                }
                // now that we've checked all possible directions to side place, we actually need to backplace
                if (srcDownBlock == Blocks.SOUL_SAND || (srcDownBlock instanceof SlabBlock && srcDown.get(SlabBlock.TYPE) != SlabType.DOUBLE)) {
                    return COST_INF; // can't sneak and backplace against soul sand or half slabs
                }
                if (!standingOnABlock) { // standing on water / swimming
                    return COST_INF; // this is obviously impossible
                }
                Block blockSrc = context.getBlock(x, y, z);
                if ((blockSrc == Blocks.LILY_PAD || blockSrc instanceof CarpetBlock) && !srcDown.getFluidState().isEmpty()) {
                    return COST_INF; // we can stand on these but can't place against them
                }
                WC = WC * (SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST);//since we are sneak backplacing, we are sneaking lol
                return WC + placeCost + hardness1 + hardness2;
            }
            return COST_INF;
        }
    }
```

`updateState()` in full — `MovementTraverse.java:171-362`. Read it as nine blocks in order:
walk-while-breaking (the fixed 26-degree pitch is the angle that still hits the block while
walking into it); clear the prep sneak; door; gate; wrong-Y; support-present (sprint gated on
`wasTheBridgeBlockAlwaysThere`, aim at `dest.up()` to keep the pitch level); support-missing (the
soul-sand/slab back-off, `attemptToPlaceABlock`, sneak at `dist1 < 0.6`, the pose-gated click,
then the backplace manoeuvre — reversed yaw plus `MOVE_BACK`):

```java
    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        BlockState pb0 = BlockStateInterface.get(ctx, positionsToBreak[0]);
        BlockState pb1 = BlockStateInterface.get(ctx, positionsToBreak[1]);
        if (state.getStatus() != MovementStatus.RUNNING) {
            if (!Baritone.settings().walkWhileBreaking.value) {
                return state;
            }
            if (state.getStatus() != MovementStatus.PREPPING) {
                return state;
            }
            if (MovementHelper.avoidWalkingInto(pb0)) {
                return state;
            }
            if (MovementHelper.avoidWalkingInto(pb1)) {
                return state;
            }
            if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(positionsToBreak[0]) || AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(positionsToBreak[1])) {
                return state;
            }
            // and we aren't already pressed up against the block
            double dist = Math.max(Math.abs(ctx.player().getPos().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().getPos().z - (dest.getZ() + 0.5D)));
            if (dist < 0.83) {
                return state;
            }
            if (!state.getTarget().getRotation().isPresent()) {
                return state;
            }
            // combine the yaw to the center of the destination, and the pitch to the specific block we're trying to break
            float yawToDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.calculateBlockCenter(ctx.world(), dest), ctx.playerRotations()).getYaw();
            float pitchToBreak = state.getTarget().getRotation().get().getPitch();
            if ((MovementHelper.isBlockNormalCube(pb0) || pb0.getBlock() instanceof AirBlock && (MovementHelper.isBlockNormalCube(pb1) || pb1.getBlock() instanceof AirBlock))) {
                pitchToBreak = 26;
            }
            return state.setTarget(new MovementState.MovementTarget(new Rotation(yawToDest, pitchToBreak), true))
                    .setInput(Input.MOVE_FORWARD, true)
                    .setInput(Input.SPRINT, true);
        }

        //sneak may have been set to true in the PREPPING state while mining an adjacent block
        state.setInput(Input.SNEAK, false);

        Block fd = BlockStateInterface.get(ctx, src.down()).getBlock();
        boolean ladder = fd == Blocks.LADDER || fd == Blocks.VINE;

        if (pb0.getBlock() instanceof DoorBlock || pb1.getBlock() instanceof DoorBlock) {
            boolean notPassable = pb0.getBlock() instanceof DoorBlock && !MovementHelper.isDoorPassable(ctx, src, dest) || pb1.getBlock() instanceof DoorBlock && !MovementHelper.isDoorPassable(ctx, dest, src);
            boolean canOpen = !(Blocks.IRON_DOOR.equals(pb0.getBlock()) || Blocks.IRON_DOOR.equals(pb1.getBlock()));
            if (notPassable && canOpen) {
                return state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.calculateBlockCenter(ctx.world(), positionsToBreak[0]), ctx.playerRotations()), true))
                        .setInput(Input.CLICK_RIGHT, true);
            }
        }

        if (pb0.getBlock() instanceof FenceGateBlock || pb1.getBlock() instanceof FenceGateBlock) {
            BlockPos blocked = !MovementHelper.isGatePassable(ctx, positionsToBreak[0], src.up()) ? positionsToBreak[0]
                    : !MovementHelper.isGatePassable(ctx, positionsToBreak[1], src) ? positionsToBreak[1]
                    : null;
            if (blocked != null) {
                Optional<Rotation> rotation = RotationUtils.reachable(ctx, blocked);
                if (rotation.isPresent()) {
                    return state.setTarget(new MovementState.MovementTarget(rotation.get(), true)).setInput(Input.CLICK_RIGHT, true);
                }
            }
        }

        boolean isTheBridgeBlockThere = MovementHelper.canWalkOn(ctx, positionToPlace) || ladder || MovementHelper.canUseFrostWalker(ctx, positionToPlace);
        BlockPos feet = ctx.playerFeet();
        if (feet.getY() != dest.getY() && !ladder) {
            logDebug("Wrong Y coordinate");
            if (feet.getY() < dest.getY()) {
                return state.setInput(Input.JUMP, true);
            }
            return state;
        }

        if (isTheBridgeBlockThere) {
            if (feet.equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (Baritone.settings().overshootTraverse.value && (feet.equals(dest.add(getDirection())) || feet.equals(dest.add(getDirection()).add(getDirection())))) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            Block low = BlockStateInterface.get(ctx, src).getBlock();
            Block high = BlockStateInterface.get(ctx, src.up()).getBlock();
            if (ctx.player().getPos().y > src.y + 0.1D && !ctx.player().isOnGround() && (low == Blocks.VINE || low == Blocks.LADDER || high == Blocks.VINE || high == Blocks.LADDER)) {
                // hitting W could cause us to climb the ladder instead of going forward
                return state;
            }
            BlockPos into = dest.subtract(src).add(dest);
            BlockState intoBelow = BlockStateInterface.get(ctx, into);
            BlockState intoAbove = BlockStateInterface.get(ctx, into.up());
            if (wasTheBridgeBlockAlwaysThere && (!MovementHelper.isLiquid(ctx, feet) || Baritone.settings().sprintInWater.value) && (!MovementHelper.avoidWalkingInto(intoBelow) || MovementHelper.isWater(intoBelow)) && !MovementHelper.avoidWalkingInto(intoAbove)) {
                state.setInput(Input.SPRINT, true);
            }
            BlockState destDown = BlockStateInterface.get(ctx, dest.down());
            BlockPos against = positionsToBreak[0];
            if (feet.getY() != dest.getY() && ladder && (destDown.getBlock() == Blocks.VINE || destDown.getBlock() == Blocks.LADDER)) {
                against = destDown.getBlock() == Blocks.VINE ? MovementPillar.getAgainst(new CalculationContext(baritone), dest.down()) : dest.offset(destDown.get(LadderBlock.FACING).getOpposite());
                if (against == null) {
                    logDirect("Unable to climb vines. Consider disabling allowVines.");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
            }
            MovementHelper.moveTowards(ctx, state, against);
            return state;
        } else {
            wasTheBridgeBlockAlwaysThere = false;
            Block standingOn = BlockStateInterface.get(ctx, feet.down()).getBlock();
            if ((standingOn.equals(Blocks.SOUL_SAND) && !AltoClefSettings.getInstance().shouldTreatSoulSandAsOrdinaryBlock()) || standingOn instanceof SlabBlock) { // see issue #118
                double dist = Math.max(Math.abs(dest.getX() + 0.5 - ctx.player().getPos().x), Math.abs(dest.getZ() + 0.5 - ctx.player().getPos().z));
                if (dist < 0.85) { // 0.5 + 0.3 + epsilon
                    MovementHelper.moveTowards(ctx, state, dest);
                    return state.setInput(Input.MOVE_FORWARD, false)
                            .setInput(Input.MOVE_BACK, true);
                }
            }
            double dist1 = Math.max(Math.abs(ctx.player().getPos().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().getPos().z - (dest.getZ() + 0.5D)));
            PlaceResult p = MovementHelper.attemptToPlaceABlock(state, baritone, dest.down(), false, true);
            if ((p == PlaceResult.READY_TO_PLACE || dist1 < 0.6) && !Baritone.settings().assumeSafeWalk.value) {
                state.setInput(Input.SNEAK, true);
            }
            switch (p) {
                case READY_TO_PLACE: {
                    if (ctx.player().isInSneakingPose() || Baritone.settings().assumeSafeWalk.value) {
                        state.setInput(Input.CLICK_RIGHT, true);
                    }
                    return state;
                }
                case ATTEMPTING: {
                    if (dist1 > 0.83) {
                        // might need to go forward a bit
                        float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()).getYaw();
                        if (Math.abs(state.getTarget().rotation.getYaw() - yaw) < 0.1) {
                            // but only if our attempted place is straight ahead
                            return state.setInput(Input.MOVE_FORWARD, true);
                        }
                    } else if (ctx.playerRotations().isReallyCloseTo(state.getTarget().rotation)) {
                        // well i guess theres something in the way
                        return state.setInput(Input.CLICK_LEFT, true);
                    }
                    return state;
                }
                default:
                    break;
            }
            if (feet.equals(dest)) {
                // If we are in the block that we are trying to get to, we are sneaking over air and we need to place a block beneath us against the one we just walked off of
                double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
                double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
                double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;
                // faceX, faceY, faceZ is the middle of the face between from and to
                BlockPos goalLook = src.down(); // this is the block we were just standing on, and the one we want to place against

                Rotation backToFace = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3d(faceX, faceY, faceZ), ctx.playerRotations());
                float pitch = backToFace.getPitch();
                double dist2 = Math.max(Math.abs(ctx.player().getPos().x - faceX), Math.abs(ctx.player().getPos().z - faceZ));
                if (dist2 < 0.29) { // see issue #208
                    float yaw = RotationUtils.calcRotationFromVec3d(VecUtils.getBlockPosCenter(dest), ctx.playerHead(), ctx.playerRotations()).getYaw();
                    state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, pitch), true));
                    state.setInput(Input.MOVE_BACK, true);
                } else {
                    state.setTarget(new MovementState.MovementTarget(backToFace, true));
                }
                if (ctx.isLookingAt(goalLook)) {
                    return state.setInput(Input.CLICK_RIGHT, true); // wait to right click until we are able to place
                }
                if (ctx.playerRotations().isReallyCloseTo(state.getTarget().rotation)) {
                    state.setInput(Input.CLICK_LEFT, true);
                }
                return state;
            }
            MovementHelper.moveTowards(ctx, state, positionsToBreak[0]);
            return state;
        }
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING || MovementHelper.canWalkOn(ctx, dest.down());
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.down())) {
            Block block = BlockStateInterface.getBlock(ctx, src.down());
            if (block == Blocks.LADDER || block == Blocks.VINE) {
                state.setInput(Input.SNEAK, true);
            }
        }
        return super.prepared(state);
    }
```

The placement gate — `MovementHelper.java:806-864`. Three ways to get an aim (already looking at
it; a side face whose *predicted* raytrace lands on the right block and the right face; nothing),
and exactly one way to be told "click now": the real crosshair:

```java
    static PlaceResult attemptToPlaceABlock(MovementState state, IBaritone baritone, BlockPos placeAt, boolean preferDown, boolean wouldSneak) {
        IPlayerContext ctx = baritone.getPlayerContext();
        Optional<Rotation> direct = RotationUtils.reachable(ctx, placeAt, wouldSneak); // we assume that if there is a block there, it must be replacable
        boolean found = false;
        if (direct.isPresent()) {
            state.setTarget(new MovementTarget(direct.get(), true));
            found = true;
        }
        for (int i = 0; i < 5; i++) {
            BlockPos against1 = placeAt.offset(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
            if (MovementHelper.canPlaceAgainst(ctx, against1)) {
                if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(false, placeAt.getX(), placeAt.getY(), placeAt.getZ())) {
                    Helper.HELPER.logDebug("bb pls get me some blocks. dirt, netherrack, cobble");
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
                double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
                double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
                Rotation place = RotationUtils.calcRotationFromVec3d(wouldSneak ? RayTraceUtils.inferSneakingEyePosition(ctx.player()) : ctx.playerHead(), new Vec3d(faceX, faceY, faceZ), ctx.playerRotations());
                Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(place);
                HitResult res = RayTraceUtils.rayTraceTowards(ctx.player(), actual, ctx.playerController().getBlockReachDistance(), wouldSneak);
                if (res != null && res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1) && ((BlockHitResult) res).getBlockPos().offset(((BlockHitResult) res).getSide()).equals(placeAt)) {
                    state.setTarget(new MovementTarget(place, true));
                    found = true;
                    if (!preferDown) {
                        break;
                    }
                }
            }
        }
        if (ctx.getSelectedBlock().isPresent()) {
            BlockPos selectedBlock = ctx.getSelectedBlock().get();
            Direction side = ((BlockHitResult) ctx.objectMouseOver()).getSide();
            // only way for selectedBlock.equals(placeAt) to be true is if it's replacable
            if (selectedBlock.equals(placeAt) || (MovementHelper.canPlaceAgainst(ctx, selectedBlock) && selectedBlock.offset(side).equals(placeAt))) {
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                ((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ());
                return PlaceResult.READY_TO_PLACE;
            }
        }
        if (found) {
            if (wouldSneak) {
                state.setInput(Input.SNEAK, true);
            }
            ((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ());
            return PlaceResult.ATTEMPTING;
        }
        return PlaceResult.NO_OPTION;
    }

    enum PlaceResult { READY_TO_PLACE, ATTEMPTING, NO_OPTION; }
```

Note the `faceY` divergence between the two place formulas — `+0.5` in `attemptToPlaceABlock`
(side face of a neighbour) versus `-1.0` in the backplace (`MovementTraverse.java:333`, the
vertical face between `src.down()` and `dest.down()`). They are different geometry; do not unify
them.

Door and gate passability — `MovementHelper.java:374-418`. The paired player positions in the gate
branch (head cell vs `src.up()`, feet cell vs `src`) are load-bearing:

```java
    static boolean isDoorPassable(IPlayerContext ctx, BlockPos doorPos, BlockPos playerPos) {
        if (playerPos.equals(doorPos)) return false;
        BlockState state = BlockStateInterface.get(ctx, doorPos);
        if (!(state.getBlock() instanceof DoorBlock)) return true;
        return isHorizontalBlockPassable(doorPos, state, playerPos, DoorBlock.OPEN);
    }
    static boolean isGatePassable(IPlayerContext ctx, BlockPos gatePos, BlockPos playerPos) {
        if (playerPos.equals(gatePos)) return false;
        BlockState state = BlockStateInterface.get(ctx, gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) return true;
        return state.get(FenceGateBlock.OPEN);
    }
    static boolean isHorizontalBlockPassable(BlockPos blockPos, BlockState blockState, BlockPos playerPos, BooleanProperty propertyOpen) {
        if (playerPos.equals(blockPos)) return false;
        Direction.Axis facing = blockState.get(HorizontalFacingBlock.FACING).getAxis();
        boolean open = blockState.get(propertyOpen);
        Direction.Axis playerFacing;
        if (playerPos.north().equals(blockPos) || playerPos.south().equals(blockPos))      playerFacing = Direction.Axis.Z;
        else if (playerPos.east().equals(blockPos) || playerPos.west().equals(blockPos))   playerFacing = Direction.Axis.X;
        else return true;
        return (facing == playerFacing) == open;
    }
```

### Tungsten placement

- `movements/MovementTraverse.java` — `static double cost(WorldView world, PlayerEntity player,
  int x, int y, int z, int destX, int destZ)` and `MovementState updateState(MovementState)`, per
  unit 1's substitution table. Drop the two `shouldAvoidWalkThroughForce` early-returns (no
  equivalent) and keep everything else.
- `movements/MovementQueue.java` — what the planner hands over. Instead of `Waypoint.toPlace` plus
  `needsPhysics`, `FastPlanner` emits, for each edge of the final route that is a same-Y cardinal
  step, a `MovementTraverse(srcCell, destCell)`. That covers every walk, every side place and every
  backplace — it is exactly `placeAcross`'s edge shape (FastPlanner.java:920-956). The queue ticks
  the head each client tick, advances on `SUCCESS`, and on `UNREACHABLE`/`FAILED` asks
  `FastNavigator` to replan from `player.getBlockPos()`.
- **Non-traverse edges keep their current path.** Parkour, ladder, slime, diagonal and (until unit
  3) pillar edges are passed straight to the physics executor as today. The port is additive at the
  edge level, which is what makes it incapable of regressing `nav_gaps` / `nav_ladder` /
  `nav_slime` by construction.
- Wiring in `MixinClientPlayerEntity.start()`: add `MovementQueue.tick((ClientPlayerEntity)(Object)this);`
  after `FastNavigator.tick` (MixinClientPlayerEntity.java:63) and before `BlockPathWalker.tick`
  (:66), and **skip `BlockPathWalker.tick` and `EXECUTOR.tick` (:85) entirely while
  `MovementQueue.isRunning()`**. One owner of keys and camera per tick is the whole point; a second
  writer is pitfall P1.
- `placeBudget` / `branchPlaced` (FastPlanner.java:279-290, :1000) stay exactly as they are — the
  planner still needs to know a future cell will be solid. Only execution changes.

### What it deletes

| Location | What goes |
|---|---|
| PathExecutor.java:430-628 | `tickPlacing()` — the entire split-seam place engine: its aim, its sneak decision, its `onAgainst` body-ownership gate (:559), its `MOVE_BACK` manoeuvre, its click |
| PathExecutor.java:43, :51-53 | `placeQueue`, `placingNow`, the `placeCalled/placeDeferred/placeInRange/placeClicked` counters, `placingTicks` |
| PathExecutor.java:189-191, :201, :253 | the `placeQueue` teardown and the `if (tickPlacing(...)) return;` hook |
| BridgeTask.java:26-225 | the standalone directed bridge — its godbridge/sneak model, its own stuck detector (:215), its own aim. A `MovementTraverse` chain *is* this behaviour, priced and reach-checked. Delete the class body and the tick call at MixinClientPlayerEntity.java:75; keep the py4j `start`/`startTo` entry points (BridgeTask.java:36, :70) re-pointed at `MovementQueue` |
| BlockPathWalker.java:483, :495-517 | `placerOwnsAim` and the camera-and-body yield. Dead once the walker does not run during a traverse |
| PathFinder.java:301-321 | the "At the gap — bridging without a physics leg" shortcut (:315) including its load-bearing `size() <= 2` gate. A bridge no longer needs a physics leg to be skipped; there is no physics leg for it |
| PathFinder.java:318, :999, :1031-1032, :1094 | the four `EXECUTOR.placeQueue = …` handoffs |
| ActionCosts.java:25-29 | `PLACE_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST * 2.5` — an invented flat number. Replaced by baritone's decomposition: `SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST` as the movement multiplier plus `blockPlacementPenalty = 20` as the place cost, so a side place and a backplace stop costing the same |
| FastPlanner.java:954 | the `true` (`viaJump` → `needsPhysics`) argument on `placeAcross`'s `relax`. A place edge must not be flagged for a search that has no place move — pitfall P2 |

### Acceptance test

Gate: **`nav_bridge`** (scenarios_nav.py:313-334) — a 6-block gap at x=13..18, floor level both
sides, barrier walls so there is nothing to walk around, `cobblestone 64` in `hotbar.0`,
`planPlaceMoves=true`. It passes only if the movement can place against the plank the previous
movement laid. Criteria: reached goal (tol 2.5), `self_falls=0`, `freezes=0`.

```
python3 deploy/runner/run_suite.py nav --only nav_bridge --repeat 3
python3 deploy/runner/run_suite.py nav
```

`--repeat 3` is not optional here: every prior attempt at this behaviour produced a distance that
varied run to run (P3, P5), so a single green is not evidence. Regression set that must stay
identical to unit 1's baseline: `nav_flat`, `nav_staircase`, `nav_steep`, `nav_gaps`, `nav_descend`,
`nav_water`, `nav_ladder`, `nav_slime`, `nav_break`, `nav_wall2`, `nav_hazard`. `nav_break`
(scenarios_nav.py:282-295) is the one that proves `prepared()` replaced the break path without
losing it; `nav_hazard` (:337-368, gate `min_hp >= 19.5`) is the one that proves
`avoidWalkingInto` came across.

---

## Unit 3 — MovementPillar: one block up, as a move

### What it is

Tower up one block: jump, place into the cell your feet just left, land on it. `src` = the feet
cell, `dest = src.up()`, `positionToPlace = src`, `positionsToBreak = {src.up(2)}`
(MovementPillar.java:51). The block goes into the cell the body currently occupies, against the
**top face** of `src.down()`, while the body is above `src`. There is no side face anywhere in this
move, which is why a pillar is geometrically possible and a same-cell side-face place is not (P4).

Two constraints set the whole design. Vanilla `World.canPlace` ends in
`isSpaceEmpty(null, voxelShape.offset(pos))`, so a full cube into the cell your own 0.6 x 1.8 box
occupies is refused by both server and client predictor: the body must have vacated, hence the
`y > dest.getY() + 0.1` gate (MovementPillar.java:265). And a vanilla jump crosses that height at
roughly tick 5 of 12 and falls back below it around tick 7 — a **~3-tick click window**. Everything
that must be true at click time (pitch converged, sneaking *pose* already active, throwaway
selected) is arranged on the ground, before take-off, by the branch order below. Do not reorder it.

**A multi-block tower is N movements, not one stateful task.** Upstream's path is a list of
`MovementPillar` instances, one per block of height; there is no height loop, no placed counter and
no stuck timer anywhere in the class. Port it that way: on `SUCCESS`, `src = src.up()`, re-run the
same machine, stop at the target Y or on `UNREACHABLE`.

### Verbatim upstream code to copy

Geometry — `MovementPillar.java:50-62`:

```java
public MovementPillar(IBaritone baritone, BetterBlockPos start, BetterBlockPos end) {
    super(baritone, start, end, new BetterBlockPos[]{start.up(2)}, start);
    //                          ^ positionsToBreak = src.up(2)      ^ positionToPlace = src
}

@Override
protected Set<BetterBlockPos> calculateValidPositions() {
    return ImmutableSet.of(src, dest);
}
```

`cost()` plus the vine helpers — `MovementPillar.java:64-168`:

```java
public static double cost(CalculationContext context, int x, int y, int z) {
    BlockState fromState = context.get(x, y, z);
    Block from = fromState.getBlock();
    boolean ladder = from == Blocks.LADDER || from == Blocks.VINE;
    BlockState fromDown = context.get(x, y - 1, z);
    if (!ladder) {
        if (fromDown.getBlock() == Blocks.LADDER || fromDown.getBlock() == Blocks.VINE) {
            return COST_INF; // can't pillar from a ladder or vine onto something that isn't also climbable
        }
        if (fromDown.getBlock() instanceof SlabBlock && fromDown.get(SlabBlock.TYPE) == SlabType.BOTTOM) {
            return COST_INF; // can't pillar up from a bottom slab onto a non ladder
        }
    }
    if (from == Blocks.VINE && !hasAgainst(context, x, y, z)) {
        return COST_INF;
    }
    BlockState toBreak = context.get(x, y + 2, z);
    Block toBreakBlock = toBreak.getBlock();
    if (toBreakBlock instanceof FenceGateBlock) { // see issue #172
        return COST_INF;
    }
    BlockState srcUp = null;
    if (MovementHelper.isWater(toBreak) && MovementHelper.isWater(fromState)) {
        srcUp = context.get(x, y + 1, z);
        if (MovementHelper.isWater(srcUp)) {
            return LADDER_UP_ONE_COST; // allow ascending pillars of water, but only if we're already in one
        }
    }
    double placeCost = 0;
    if (!ladder) {
        // we need to place a block where we started to jump on it
        placeCost = context.costOfPlacingAt(x, y, z, fromState);
        if (placeCost >= COST_INF) {
            return COST_INF;
        }
        if (fromDown.getBlock() instanceof AirBlock) {
            placeCost += 0.1; // slightly (1/200th of a second) penalize pillaring on what's currently air
        }
    }
    if ((MovementHelper.isLiquid(fromState) && !MovementHelper.canPlaceAgainst(context.bsi, x, y - 1, z, fromDown)) || (MovementHelper.isLiquid(fromDown) && context.assumeWalkOnWater)) {
        // otherwise, if we're standing in water, we cannot pillar
        // if we're standing on water and assumeWalkOnWater is true, we cannot pillar
        // if we're standing on water and assumeWalkOnWater is false, we must have ascended to here, or sneak backplaced, so it is possible to pillar again
        return COST_INF;
    }
    if ((from == Blocks.LILY_PAD || from instanceof CarpetBlock) && !fromDown.getFluidState().isEmpty()) {
        // to ascend here we'd have to break the block we are standing on
        return COST_INF;
    }
    double hardness = MovementHelper.getMiningDurationTicks(context, x, y + 2, z, toBreak, true);
    if (hardness >= COST_INF) {
        return COST_INF;
    }
    if (hardness != 0) {
        if (toBreakBlock == Blocks.LADDER || toBreakBlock == Blocks.VINE) {
            hardness = 0; // we won't actually need to break the ladder / vine because we're going to use it
        } else {
            BlockState check = context.get(x, y + 3, z); // the block on top of the one we're going to break, could it fall on us?
            if (check.getBlock() instanceof FallingBlock) {
                // see MovementAscend's identical check for breaking a falling block above our head
                if (srcUp == null) {
                    srcUp = context.get(x, y + 1, z);
                }
                if (!(toBreakBlock instanceof FallingBlock) || !(srcUp.getBlock() instanceof FallingBlock)) {
                    return COST_INF;
                }
            }
        }
    }
    if (ladder) {
        return LADDER_UP_ONE_COST + hardness * 5;
    } else {
        return JUMP_ONE_BLOCK_COST + placeCost + context.jumpPenalty + hardness;
    }
}

public static boolean hasAgainst(CalculationContext context, int x, int y, int z) {
    return MovementHelper.isBlockNormalCube(context.get(x + 1, y, z)) ||
            MovementHelper.isBlockNormalCube(context.get(x - 1, y, z)) ||
            MovementHelper.isBlockNormalCube(context.get(x, y, z + 1)) ||
            MovementHelper.isBlockNormalCube(context.get(x, y, z - 1));
}

public static BlockPos getAgainst(CalculationContext context, BetterBlockPos vine) {
    if (MovementHelper.isBlockNormalCube(context.get(vine.north()))) { return vine.north(); }
    if (MovementHelper.isBlockNormalCube(context.get(vine.south()))) { return vine.south(); }
    if (MovementHelper.isBlockNormalCube(context.get(vine.east())))  { return vine.east();  }
    if (MovementHelper.isBlockNormalCube(context.get(vine.west())))  { return vine.west();  }
    return null;
}
```

Numbers to check the port against: `JUMP_ONE_BLOCK_COST = 6.2343 - 3.0710 = 3.1633`, `jumpPenalty
= 2.0` (Settings.java:122), `blockPlacementPenalty = 20.0` (Settings.java:110) — so a plain pillar
step over solid ground is **25.163**, and 25.263 over air. Tungsten's current hand-priced 22.7
(`JUMP_ONE_BLOCK_COST = 4.633 + 6.5` at ActionCosts.java:11-12 plus `PLACE_ONE_BLOCK_COST = 11.58`
at :29) is a different number in a different unit system and goes with unit 2.

The tick machine — `MovementPillar.java:170-291`. Two lines that look like bugs and are not:
`fromDown` in `updateState` reads `src`, not `src.down()` (the name is off by one relative to
`cost()`); and `blockIsThere = false;` at :264 is already false. Copy both as they are, do not
"fix" them. The sneak gate (`y > src.y + 1.0`) is deliberately one notch earlier than the click
gate (`y > src.y + 1.1`) — that is the documented one-tick delay so the *pose* exists before the
click:

```java
@Override
public MovementState updateState(MovementState state) {
    super.updateState(state);
    if (state.getStatus() != MovementStatus.RUNNING) {
        return state;
    }

    if (ctx.playerFeet().y < src.y) {
        return state.setStatus(MovementStatus.UNREACHABLE);
    }

    BlockState fromDown = BlockStateInterface.get(ctx, src);   // NB: this reads src, not src.down()
    if (MovementHelper.isWater(fromDown) && MovementHelper.isWater(ctx, dest)) {
        // stay centered while swimming up a water column
        state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()), false));
        Vec3d destCenter = VecUtils.getBlockPosCenter(dest);
        if (Math.abs(ctx.player().getPos().x - destCenter.x) > 0.2 || Math.abs(ctx.player().getPos().z - destCenter.z) > 0.2) {
            state.setInput(Input.MOVE_FORWARD, true);
        }
        if (ctx.playerFeet().equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        return state;
    }
    boolean ladder = fromDown.getBlock() == Blocks.LADDER || fromDown.getBlock() == Blocks.VINE;
    boolean vine = fromDown.getBlock() == Blocks.VINE;
    Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
            VecUtils.getBlockPosCenter(positionToPlace),      // positionToPlace == src
            ctx.playerRotations());
    if (!ladder) {
        state.setTarget(new MovementState.MovementTarget(ctx.playerRotations().withPitch(rotation.getPitch()), true));
    }

    boolean blockIsThere = MovementHelper.canWalkOn(ctx, src) || ladder;
    if (ladder) {
        BlockPos against = vine ? getAgainst(new CalculationContext(baritone), src) : src.offset(fromDown.get(LadderBlock.FACING).getOpposite());
        if (against == null) {
            logDirect("Unable to climb vines. Consider disabling allowVines.");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        if (ctx.playerFeet().equals(against.up()) || ctx.playerFeet().equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        if (MovementHelper.isBottomSlab(BlockStateInterface.get(ctx, src.down()))) {
            state.setInput(Input.JUMP, true);
        }

        MovementHelper.moveTowards(ctx, state, against);
        return state;
    } else {
        // Get ready to place a throwaway block
        if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, src.x, src.y, src.z)) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }


        state.setInput(Input.SNEAK, ctx.player().getPos().y > dest.getY() || ctx.player().getPos().y < src.getY() + 0.2D); // delay placement by 1 tick for ncp compatibility
        // since (lower down) we only right click once player.isSneaking, and that happens the tick after we request to sneak

        double diffX = ctx.player().getPos().x - (dest.getX() + 0.5);
        double diffZ = ctx.player().getPos().z - (dest.getZ() + 0.5);
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        double flatMotion = Math.sqrt(ctx.player().getVelocity().x * ctx.player().getVelocity().x + ctx.player().getVelocity().z * ctx.player().getVelocity().z);
        if (dist > 0.17) {//why 0.17? because it seemed like a good number, that's why
            //[explanation added after baritone port lol] also because it needs to be less than 0.2 because of the 0.3 sneak limit
            //and 0.17 is reasonably less than 0.2

            // If it's been more than forty ticks of trying to jump and we aren't done yet, go forward, maybe we are stuck
            state.setInput(Input.MOVE_FORWARD, true);

            // revise our target to both yaw and pitch if we're going to be moving forward
            state.setTarget(new MovementState.MovementTarget(rotation, true));
        } else if (flatMotion < 0.05) {
            // If our Y coordinate is above our goal, stop jumping
            state.setInput(Input.JUMP, ctx.player().getPos().y < dest.getY());
        }


        if (!blockIsThere) {
            BlockState frState = BlockStateInterface.get(ctx, src);
            Block fr = frState.getBlock();
            if (!(fr instanceof AirBlock || frState.isReplaceable())) {
                RotationUtils.reachable(ctx, src, ctx.playerController().getBlockReachDistance())
                        .map(rot -> new MovementState.MovementTarget(rot, true))
                        .ifPresent(state::setTarget);
                state.setInput(Input.JUMP, false); // breaking is like 5x slower when you're jumping
                state.setInput(Input.CLICK_LEFT, true);
                blockIsThere = false;
            } else if (ctx.player().isInSneakingPose() && (ctx.isLookingAt(src.down()) || ctx.isLookingAt(src)) && ctx.player().getPos().y > dest.getY() + 0.1) {
                state.setInput(Input.CLICK_RIGHT, true);
            }
        }
    }

    // If we are at our goal and the block below us is placed
    if (ctx.playerFeet().equals(dest) && blockIsThere) {
        return state.setStatus(MovementStatus.SUCCESS);
    }

    return state;
}

@Override
protected boolean prepared(MovementState state) {
    if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.down())) {
        Block block = BlockStateInterface.getBlock(ctx, src.down());
        if (block == Blocks.LADDER || block == Blocks.VINE) {
            state.setInput(Input.SNEAK, true);
        }
    }
    if (MovementHelper.isWater(ctx, dest.up())) {
        return true;
    }
    return super.prepared(state);
}
```

`dist > 0.17` centres before jumping; `flatMotion < 0.05` refuses to jump while still sliding (you
would drift off the column and place under nothing) and releases jump the moment you are above the
destination floor. When `dist <= 0.17` and `flatMotion >= 0.05`, **neither input is set** — the bot
coasts to a stop. That is the intended behaviour, not a stall; a stuck timer bolted on here is
exactly the reactive patch AGENTS.md rule 6 forbids.

### Tungsten placement

- `movements/MovementPillar.java` — `static double cost(WorldView world, PlayerEntity player, int
  x, int y, int z)` and `MovementState updateState(MovementState)`, same substitution table.
- A driver in `MovementQueue`: on `SUCCESS`, advance `src` upward and re-run; stop at target Y or
  on `UNREACHABLE`. No height loop inside the movement.
- `FastPlanner.pillarUp` (FastPlanner.java:966-986) keeps its move generation and its
  `placedDepth`/`placeBudget` guard, re-priced to `JUMP_ONE_BLOCK_COST + placeCost + jumpPenalty +
  hardness`, and emits a `MovementPillar` edge instead of a `toPlace` waypoint.

### What it deletes

| Location | What goes |
|---|---|
| PillarTask.java:65-146 | the tick body — its own aim, its own place gate, its own stop conditions. The class shell and the py4j `startTo` (:36) stay, re-pointed at `MovementQueue` |
| MixinClientPlayerEntity.java:78 | `PillarTask.tick(...)` |
| FastNavigator.java:253-258 | the `rise > PlayerFitJumpHeight() && horiz < 2.5 → PillarTask.startTo(...)` heuristic handoff. A pillar is now an edge the search priced, not a guess made at the lip. (The comment at FastNavigator.java:248-250 already points here) |
| FastPlanner.java:984 | the `true` (`needsPhysics`) argument on `pillarUp`'s `relax` — same reason as unit 2, pitfall P2 |

### Acceptance test

Gate: **`nav_wall2`** (scenarios_nav.py:298-310) — a 2-block vertical wall onto a ledge,
`cobblestone 64` in `hotbar.0`, `planPlaceMoves=true`. It is the course that proves place-as-a-move
is reachable from the search, and two blocks means two `MovementPillar` instances chained, so it
also proves the N-movements model.

```
python3 deploy/runner/run_suite.py nav --only nav_wall2 --repeat 3
python3 deploy/runner/run_suite.py nav
```

`nav_slime` (scenarios_nav.py:243-278) is **not** a gate for this unit. It is documented as
unreachable by bouncing (the first bounce reaches ~4 blocks horizontally, the ledge is 8 away,
scenarios_nav.py:253-260) and it ships with cobblestone and `planPlaceMoves=true` precisely because
the intended solution is to build. After this unit it becomes reachable in principle; record the
result either way and do not tune the course.

---

## Unit 4 — one place planner, one price

### What it is

The sweep. After units 2 and 3 there are two place planners with two cost models
(`BlockNode.tryPlanPlaceThrough`, BlockNode.java:692-731, cost `20.0 * 0.15` behind an
`estimatedCostToGoal` gate at :697, versus `FastPlanner.placeAcross`, FastPlanner.java:920-956).
Keep `placeAcross`, delete the other. `tryPlanBreakThrough` (BlockNode.java:638) **stays** — it is
the break planner and has no rival.

### What it deletes

| Location | What goes |
|---|---|
| BlockNode.java:680-731 | `tryPlanPlaceThrough` — the second, differently-tuned place planner, including the `estimatedCostToGoal` node-explosion guard that only existed because the price was wrong |
| BlockNode.java:447 | its `shouldRemoveNode` hook (`if (tryPlanPlaceThrough(world, child))`). The break hook at :441 stays |
| BlockNode.java:113-120 | the `toPlace` field and `hasPlacePlan()`, once `BlockNode` is no longer a place planner |

Once baritone's numbers are in place the guard is unnecessary by construction: a backplace at ~35
ticks against 4.633 for a walk and 3.564 for a sprint prices itself out of any route that has an
alternative. That is the point of copying the numbers instead of inventing one.

### Acceptance test

Full suite, equal or better against the unit-3 baseline:

```
python3 deploy/runner/run_suite.py nav
```

`nav_bridge` and `nav_wall2` must still be green, and with `verboseDebugLogging=true` the chat log
must contain no `place-through planned` line and no `At the gap — bridging without a physics leg`
line (PathFinder.java:315) — both are now impossible, so either one appearing means a handoff
survived the sweep.

---

## Measured pitfalls

Five failures, with the numbers that were actually measured. They are recorded so nobody re-derives
them; each one is a structural consequence of the split-seam design, not a tuning miss.

**P1 — splitting a bridge step's walk from its place. Measured `called=11041 inRange=11040
clicked=0`.** Three attempts failed at three different seams: `BlockPathWalker` walking the body
while `PathExecutor.tickPlacing` placed; the `onAgainst` body-ownership gate
(PathExecutor.java:559); and the `placingNow` aim yield (BlockPathWalker.java:483-517). The
counters (PathExecutor.java:52, incremented at :434, :482, :623) say it exactly: eleven thousand
ticks in range, one deferral, zero clicks. **Fix:** one object owns the whole step.
`MovementTraverse.updateState` decides walk-vs-place-vs-step-on itself, every tick, from world
state, and `MovementExecutor` is the only thing that writes keys or the aim target. Do not
reintroduce a second per-tick key writer while a movement is running — skip `BlockPathWalker.tick`
and `PathExecutor.tick` entirely.

**P2 — handing a place move to the physics search. Measured as the residual ~50% flakiness, and
documented in-code at PathFinder.java:301-315.** `placeAcross` and `pillarUp` pass `viaJump=true`
(FastPlanner.java:954, :984), which becomes `needsPhysics`; the physics engine has no place move,
so it simulates across a floor that does not exist yet and falls. **Fix:** place edges route to
`MovementQueue` and are never flagged for physics. Drop the flag on both call sites in the same
change as the movement that replaces them.

**P3 — sprinting towards a lip. Measured 20.7 and 22.5 blocks short, twice each — the void-fall
signature.** At 8-12 fps one tick carries the bot past the edge. Baritone's guard is structural,
not a timeout: `wasTheBridgeBlockAlwaysThere` (MovementTraverse.java:55, cleared at :291, read at
:275) is a precondition of `SPRINT`, so a step whose floor the route placed is **never** sprinted
out of; and in the bridge branch `SNEAK` goes on at `dist1 < 0.6` (:303) before any click. Port the
flag. Do not substitute a distance heuristic or a tick budget.

**P4 — placing from on top of the block whose side face is needed. Measured `hit=12,-61 side=up`,
336 ticks in range, zero clicks.** Standing on a block, a ray towards its side face hits the TOP
face first, so the crosshair test can never pass. Baritone solves it geometrically, in two parts,
both mandatory: (a) the backplace only runs once `feet.equals(dest)` (MovementTraverse.java:330) —
the body is over the hole, not on the block; (b) sneaking lets the centre reach edge + 0.3 while
the 0.6-wide box stays supported, and the pitch used is `backToFace.getPitch()` towards the face
centre `((dest+src+1)*0.5, (dest+src-1)*0.5, (dest+src+1)*0.5)`, roughly 82 degrees down. For the
pillar the same problem is solved by the geometry of the move itself: the block goes onto the TOP
face of `src.down()`, from above, after the body has left the cell (`y > dest.getY() + 0.1`,
MovementPillar.java:265) — clicking earlier is a guaranteed no-op that still burns the 4-tick place
cooldown.

**P5 — porting the click without the manoeuvre. Measured: failed twice at 11.6 blocks.** The
backplace is one manoeuvre made of three inseparable parts: sneak; the **swapped-argument** yaw
(MovementTraverse.java:343 — `calcRotationFromVec3d(getBlockPosCenter(dest), ctx.playerHead(),
...)`, i.e. the yaw *from* the cell being paved *towards* the head, facing back up the bridge); and
`MOVE_BACK` (:345), which with a reversed body moves the bot **forward** along the bridge. That is
why baritone bridges walking backwards. Porting the click alone gets 11.6 blocks and stops.

---

## Rules with no number attached — do not re-derive these either

- **The sneak KEY is not the sneak POSE.** Both movements click only when
  `player.isInSneakingPose()` (MovementTraverse.java:308, MovementPillar.java:266). The pose lags
  the key by a tick, and a click in that tick raytraces from the standing eye (1.62) instead of the
  sneaking one (1.27) and misses the face. Keep the pose test, and keep `inferSneakingEyePosition`
  = `y + 1.27` for every `wouldSneak` raytrace (RayTraceUtils.java:66-68).
- **Never forge a `BlockHitResult`.** Do not construct a hit from a face centre and hand it to
  `interactBlock` — the packet claims a click the player never made. The only promotion to
  `READY_TO_PLACE` is the real crosshair (MovementHelper.java:840-851), and
  `RealPlacement.readyToPlace` (RealPlacement.java:44-61) is that test. If the aim never converges
  the movement stays in `ATTEMPTING`, presses `CLICK_LEFT` when the ray is blocked, and eventually
  fails. That is the correct outcome.
- **Do not omit the side-place scan's backplace exclusion** (`if (againstX == x && againstZ == z)
  continue;`, MovementTraverse.java:146-148). Without it every bridge step prices as a free side
  place, the search plans bridges it cannot execute, and the executor falls back to a manoeuvre the
  cost model never paid for.
- **Copy the cost decomposition, not a single flat number.** A backplace is `WALK *
  (15.385/4.633) + 20 ≈ 35`; a side place is `4.633 + 20` with no sneak multiplier; a pillar step
  is `3.1633 + 20 + 2 = 25.163`. One flat `PLACE_ONE_BLOCK_COST` cannot express the difference, and
  the search then needs the `estimatedCostToGoal` hack (BlockNode.java:697) to stop exploding.
- **`COST_INF` is `+1000000` in the ported code** and `-1000000` in `path.calculators.ActionCosts`
  (ActionCosts.java:4). Declare the movements package's own constant.
- **`objectMouseOver` is a live raytrace, not `mc.crosshairTarget`**
  (BaritonePlayerContext.java:84-86 vs RealPlacement.java:45). At `avg_fps=10.0` the cached value
  is one to two ticks stale — the exact width of the sneak-pose and jump-apex windows.
- **`sprintInWater` defaults to `true`** (Settings.java:801). The unit study said false; the source
  is authoritative.
- **When a precondition fails, the movement fails.** No block in inventory → `COST_INF` at plan
  time (CalculationContext.java:187) and `NO_OPTION` + `UNREACHABLE` at run time
  (MovementHelper.java:812-816, MovementPillar.java:227-229). Unbreakable head cell → `COST_INF`,
  never planned. On a ladder → `COST_INF`, and `prepared()` sneaks. Door or gate closed →
  right-click branch, no progress that tick, retry. None of these needs a timeout, and adding one
  hides the case that should have been priced.