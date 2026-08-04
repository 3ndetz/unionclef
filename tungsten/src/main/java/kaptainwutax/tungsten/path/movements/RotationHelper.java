package kaptainwutax.tungsten.path.movements;

import java.util.Optional;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldView;

/**
 * Baritone's aim maths, reach gate and raytrace, copied into tungsten.
 *
 * <p>Provenance, line for line: {@code baritone/src/main/java/baritone/api/utils/RotationUtils.java}
 * (the angle maths at :88-139, the reach gate at :152-261), {@code
 * baritone/src/main/java/baritone/api/utils/RayTraceUtils.java:33-68} (the raytrace and the
 * sneaking eye), {@code baritone/src/main/java/baritone/api/utils/VecUtils.java:45-79} (the two
 * block centres) and the context accessors from {@code
 * baritone/src/main/java/baritone/api/utils/IPlayerContext.java:62-120}. Nothing here is
 * re-derived: this is the piece tungsten previously forged (it built {@code BlockHitResult}s out of
 * face centres), so it is the piece that has to be faithful.
 *
 * <p>{@code baritone/} is a source reference and is not compiled, and shredder owns the {@code
 * baritone.*} package (AGENTS.md) — so this file may only reference tungsten and Minecraft. Where
 * baritone's yarn names have moved on since that tree was migrated, the compiled shredder copy of
 * the same file is the oracle: {@code entity.getWorld()} -> {@code entity.getEntityWorld()} and
 * {@code player.getPos()} -> {@code player.getEntityPos()} (shredder RayTraceUtils.java:63,
 * VecUtils.java:108). Positions are read as {@code getX()/getY()/getZ()}, which is the same number
 * as {@code getEntityPos().x/.y/.z} without depending on which interface declares it.
 *
 * <h2>Adapters written here because tungsten has no equivalent</h2>
 * <ul>
 *   <li>{@code IPlayerContext} — replaced by the {@code playerHead} / {@code playerRotations} /
 *       {@code blockReachDistance} / {@code liveHit} / {@code getSelectedBlock} / {@code
 *       isLookingAt} statics below, each taking the player explicitly.</li>
 *   <li>{@code ctx.objectMouseOver()} is a LIVE raytrace recomputed this tick from the real eye and
 *       the real current rotations ({@code BaritonePlayerContext.java:84-86}), NOT {@code
 *       mc.crosshairTarget}. {@link #liveHit} is that; at the stand's measured 10 fps the cached
 *       crosshair is one to two ticks stale, which is exactly the width of the sneak-pose window
 *       (docs/BARITONE-PORT-SPEC.md, "Traps on the tungsten side").</li>
 *   <li>{@code baritone.getLookBehavior().getAimProcessor().peekRotation(r)} — tungsten's
 *       {@code WindMouseRotation} cannot be asked what it will produce next, so {@link
 *       #peekRotation} is the identity, which is exactly baritone's own processor with no
 *       randomisation. See the note on that method for why the alternative (raytracing from the
 *       CURRENT rotations) cannot be used inside {@code reachable}.</li>
 *   <li>Baritone settings have no tungsten counterpart yet, so upstream's DEFAULTS are hardcoded
 *       and named in comments: {@code remainWithExistingLookDirection = true} (Settings.java:781),
 *       {@code blockReachDistance = 4.5f} (Settings.java:385).</li>
 * </ul>
 *
 * <p>The {@code Rotation} value type it operates on is {@link Rotation} — the substrate's verbatim
 * copy of {@code baritone/api/utils/Rotation.java}, in this same package, so every line here reads
 * exactly as upstream does.
 */
public final class RotationHelper {

    private RotationHelper() {}

    /** RotationUtils.java:45-52. */
    public static final double DEG_TO_RAD = Math.PI / 180.0;
    public static final float DEG_TO_RAD_F = (float) DEG_TO_RAD;
    public static final double RAD_TO_DEG = 180.0 / Math.PI;
    public static final float RAD_TO_DEG_F = (float) RAD_TO_DEG;

    /**
     * Offsets from the root block position to the center of each side — RotationUtils.java:57-64.
     * The order is load-bearing: Down is tried before Up, so a block that can be reached from
     * underneath is aimed at from underneath.
     */
    private static final Vec3d[] BLOCK_SIDE_MULTIPLIERS = new Vec3d[]{
            new Vec3d(0.5, 0, 0.5), // Down
            new Vec3d(0.5, 1, 0.5), // Up
            new Vec3d(0.5, 0.5, 0), // North
            new Vec3d(0.5, 0.5, 1), // South
            new Vec3d(0, 0.5, 0.5), // West
            new Vec3d(1, 0.5, 0.5)  // East
    };

    /**
     * RayTraceUtils.java:33. {@code NONE} means fluids are transparent to the trace, which is why a
     * block under water can still be aimed at and placed against.
     */
    public static RaycastContext.FluidHandling fluidHandling = RaycastContext.FluidHandling.NONE;

    /** baritone Settings.java:781 {@code remainWithExistingLookDirection}, default true. */
    private static final boolean REMAIN_WITH_EXISTING_LOOK_DIRECTION = true;

    /** baritone Settings.java:385 {@code blockReachDistance}, default 4.5f. */
    private static final double BLOCK_REACH_DISTANCE = 4.5;

    // ------------------------------------------------------------------------------------------
    // IPlayerContext adapters
    // ------------------------------------------------------------------------------------------

    /** IPlayerContext.java:97-99. */
    public static Rotation playerRotations(Entity entity) {
        return new Rotation(entity.getYaw(), entity.getPitch());
    }

    /**
     * IPlayerContext.java:87-89. NOT {@code getEyePos()}: that folds in the current pose, which
     * would destroy the standing-eye (1.62) versus sneaking-eye (1.27) separation that every
     * {@code wouldSneak} raytrace and the backplace pitch depend on.
     */
    public static Vec3d playerHead(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY() + entity.getStandingEyeHeight(), entity.getZ());
    }

    /**
     * IPlayerContext.java:101-103 — the two eye heights baritone hardcodes. The sneaking one is
     * used by {@link #inferSneakingEyePosition}; do not replace it with the live pose height,
     * because the whole point is to ask "where would my eye be if I were sneaking" one tick
     * BEFORE the pose changes.
     */
    public static double eyeHeight(boolean ifSneaking) {
        return ifSneaking ? 1.27 : 1.62;
    }

    /**
     * {@code IPlayerController.getBlockReachDistance()} (IPlayerController.java:57-59), which is
     * {@code creative ? 5.0 : settings.blockReachDistance}. Tungsten substitution per
     * docs/BARITONE-PORT-SPEC.md: clamp the vanilla attribute to baritone's 4.5 default, so a
     * server that hands out a longer reach does not make the gate optimistic.
     */
    public static double blockReachDistance(PlayerEntity player) {
        if (player == null) {
            return BLOCK_REACH_DISTANCE;
        }
        return Math.min(player.getBlockInteractionRange(), BLOCK_REACH_DISTANCE);
    }

    /**
     * {@code ctx.objectMouseOver()} — BaritonePlayerContext.java:84-86. A live raytrace along the
     * player's CURRENT rotations, recomputed on the spot. This is the one and only promotion to
     * "click now" in the ported placement path; never forge a {@link BlockHitResult}.
     */
    public static HitResult liveHit(PlayerEntity player) {
        return liveHit(player, playerRotations(player), blockReachDistance(player));
    }

    /**
     * Explicit form of the same thing, for callers that already hold the current rotations and the
     * reach (which is every per-tick caller). Pass the CURRENT rotations: a live hit computed from a
     * wished-for rotation is not a crosshair test, it is the peek, and the two must not be confused.
     */
    public static HitResult liveHit(PlayerEntity player, Rotation rotation, double blockReachDistance) {
        return rayTraceTowards(player, rotation, blockReachDistance);
    }

    /**
     * IPlayerContext.java:62-81, verbatim including the {@code +0.1251} and the slab correction.
     * Neither is cosmetic: a naive {@code BlockPos.ofFloored(y)} reports the cell below on landing
     * ticks, which is exactly when SUCCESS is tested, and standing on a slab puts the feet in the
     * cell above the slab's own.
     */
    public static BetterBlockPos playerFeet(PlayerEntity player) {
        // TODO find a better way to deal with soul sand!!!!!
        BetterBlockPos feet = new BetterBlockPos(player.getX(), player.getY() + 0.1251, player.getZ());

        // sometimes when calling this from another thread or while world is null, it'll throw a
        // NullPointerException that causes the game to immediately crash. catch it and ignore it.
        try {
            if (player.getEntityWorld().getBlockState(feet).getBlock() instanceof SlabBlock) {
                return feet.above(); // tungsten's BetterBlockPos spells up() as above()
            }
        } catch (NullPointerException ignored) {}

        return feet;
    }

    /** IPlayerContext.java:110-116. */
    public static Optional<BlockPos> getSelectedBlock(PlayerEntity player) {
        HitResult result = liveHit(player);
        if (result != null && result.getType() == HitResult.Type.BLOCK) {
            return Optional.of(((BlockHitResult) result).getBlockPos());
        }
        return Optional.empty();
    }

    /** IPlayerContext.java:118-120. */
    public static boolean isLookingAt(PlayerEntity player, BlockPos pos) {
        return getSelectedBlock(player).equals(Optional.of(pos));
    }

    /**
     * Stand-in for {@code getLookBehavior().getAimProcessor().peekRotation(rotation)}
     * (RotationUtils.java:236, MovementHelper.java:826): "what will the aim actuator actually
     * produce if I ask for this?".
     *
     * <p>Tungsten's {@code WindMouseRotation} has no peek, and the identity is precisely baritone's
     * own processor with randomisation off. It is deliberately NOT "raytrace from the player's
     * current rotations": {@code reachable} exists to PROPOSE an aim (it is what {@code
     * Movement.prepared} uses to decide where to look before breaking), so tracing from the
     * current rotations would make it answer "no" until the bot already happened to be looking at
     * the block — a deadlock. Safety is not lost, because the promotion to READY_TO_PLACE is the
     * real crosshair ({@link #liveHit}), which does use the current rotations.
     */
    public static Rotation peekRotation(Rotation requested) {
        return requested;
    }

    // ------------------------------------------------------------------------------------------
    // RotationUtils — the angle maths
    // ------------------------------------------------------------------------------------------

    /**
     * RotationUtils.java:88-93. The relative wrap is what stops a yaw from taking the long way
     * round (e.g. from +179 to -179 is a 2 degree turn, not 358).
     */
    public static Rotation wrapAnglesToRelative(Rotation current, Rotation target) {
        if (current.yawIsReallyClose(target)) {
            return new Rotation(current.getYaw(), target.getPitch());
        }
        return target.subtract(current).normalize().add(current);
    }

    /** RotationUtils.java:105-107. */
    public static Rotation calcRotationFromVec3d(Vec3d orig, Vec3d dest, Rotation current) {
        return wrapAnglesToRelative(current, calcRotationFromVec3d(orig, dest));
    }

    /** RotationUtils.java:116-125. */
    private static Rotation calcRotationFromVec3d(Vec3d orig, Vec3d dest) {
        double[] delta = {orig.x - dest.x, orig.y - dest.y, orig.z - dest.z};
        double yaw = MathHelper.atan2(delta[0], -delta[2]);
        double dist = Math.sqrt(delta[0] * delta[0] + delta[2] * delta[2]);
        double pitch = MathHelper.atan2(delta[1], dist);
        return new Rotation(
                (float) (yaw * RAD_TO_DEG),
                (float) (pitch * RAD_TO_DEG)
        );
    }

    /** RotationUtils.java:133-139. */
    public static Vec3d calcLookDirectionFromRotation(Rotation rotation) {
        float flatZ = MathHelper.cos((-rotation.getYaw() * DEG_TO_RAD_F) - (float) Math.PI);
        float flatX = MathHelper.sin((-rotation.getYaw() * DEG_TO_RAD_F) - (float) Math.PI);
        float pitchBase = -MathHelper.cos(-rotation.getPitch() * DEG_TO_RAD_F);
        float pitchHeight = MathHelper.sin(-rotation.getPitch() * DEG_TO_RAD_F);
        return new Vec3d(flatX * pitchBase, pitchHeight, flatZ * pitchBase);
    }

    // ------------------------------------------------------------------------------------------
    // RotationUtils — the reach gate
    // ------------------------------------------------------------------------------------------

    /** RotationUtils.java:152-154. */
    public static Optional<Rotation> reachable(PlayerEntity player, BlockPos pos) {
        return reachable(player, pos, false);
    }

    /** RotationUtils.java:156-158. */
    public static Optional<Rotation> reachable(PlayerEntity player, BlockPos pos, boolean wouldSneak) {
        return reachable(player, pos, blockReachDistance(player), wouldSneak);
    }

    /** RotationUtils.java:172-174. */
    public static Optional<Rotation> reachable(PlayerEntity player, BlockPos pos, double blockReachDistance) {
        return reachable(player, pos, blockReachDistance, false);
    }

    /**
     * RotationUtils.java:176-220. A rotation counts as "reachable" only if a raytrace along the
     * rotation the aim actuator will actually produce lands on the wanted block. Centre first, then
     * the six side centres interpolated across the block's OUTLINE shape (not its collision shape —
     * that is what you can click, and it is why a fence or a slab is aimed at correctly).
     */
    public static Optional<Rotation> reachable(PlayerEntity player, BlockPos pos, double blockReachDistance, boolean wouldSneak) {
        WorldView world = player.getEntityWorld();
        if (REMAIN_WITH_EXISTING_LOOK_DIRECTION && isLookingAt(player, pos)) {
            /*
             * why add 0.0001?
             * to indicate that we actually have a desired pitch
             * the way we indicate that the pitch can be whatever and we only care about the yaw
             * is by setting the desired pitch to the current pitch
             * setting the desired pitch to the current pitch + 0.0001 means that we do have a
             * desired pitch, it's just what it currently is
             */
            Rotation hypothetical = playerRotations(player).add(new Rotation(0, 0.0001F));
            if (wouldSneak) {
                // the concern here is: what if we're looking at it now, but as soon as we start sneaking we no longer are
                HitResult result = rayTraceTowards(player, hypothetical, blockReachDistance, true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(pos)) {
                    return Optional.of(hypothetical); // yes, if we sneaked we would still be looking at the block
                }
            } else {
                return Optional.of(hypothetical);
            }
        }
        Optional<Rotation> possibleRotation = reachableCenter(player, pos, blockReachDistance, wouldSneak);
        if (possibleRotation.isPresent()) {
            return possibleRotation;
        }

        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getOutlineShape(world, pos);
        if (shape.isEmpty()) {
            shape = VoxelShapes.fullCube();
        }
        for (Vec3d sideOffset : BLOCK_SIDE_MULTIPLIERS) {
            double xDiff = shape.getMin(Direction.Axis.X) * sideOffset.x + shape.getMax(Direction.Axis.X) * (1 - sideOffset.x);
            double yDiff = shape.getMin(Direction.Axis.Y) * sideOffset.y + shape.getMax(Direction.Axis.Y) * (1 - sideOffset.y);
            double zDiff = shape.getMin(Direction.Axis.Z) * sideOffset.z + shape.getMax(Direction.Axis.Z) * (1 - sideOffset.z);
            possibleRotation = reachableOffset(player, pos, new Vec3d(pos.getX(), pos.getY(), pos.getZ()).add(xDiff, yDiff, zDiff), blockReachDistance, wouldSneak);
            if (possibleRotation.isPresent()) {
                return possibleRotation;
            }
        }
        // WHAT IS IN THE WAY? Measured on the playthrough: while the bot stands within four
        // blocks of its target log, this method returns empty on 98% of ticks, which is what
        // stops the swing and eventually gets the log blacklisted. Distance cannot be the cause
        // at that range, so the ray must be hitting something else -- record what, by name, at
        // the centre aim, and let a run say whether it is the tree's own leaves.
        try {
            Vec3d eyes = wouldSneak ? inferSneakingEyePosition(player) : player.getCameraPosVec(1.0F);
            Rotation aim = calcRotationFromVec3d(eyes, Vec3d.ofCenter(pos), playerRotations(player));
            HitResult r = rayTraceTowards(player, peekRotation(aim), blockReachDistance, wouldSneak);
            // A LAST VALUE IS NOT A DISTRIBUTION. The first version of this kept only the most
            // recent hit, and two runs disagreed -- leaves in one, MISS in the other -- which was
            // read as "MISS dominates" on the strength of a single snapshot. It is not knowable
            // that way. Count the kinds instead, so the shares are the answer.
            if (r != null && r.getType() == HitResult.Type.BLOCK) {
                blockedBy = net.minecraft.registry.Registries.BLOCK.getId(
                        world.getBlockState(((BlockHitResult) r).getBlockPos()).getBlock()).toString();
                if (world.getBlockState(((BlockHitResult) r).getBlockPos())
                        .isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                    rayLeaves++;
                    // Remember WHERE, not just what: the caller's fix is to fell the obstruction,
                    // and it needs a position to aim at.
                    blockedPos = ((BlockHitResult) r).getBlockPos();
                } else {
                    rayOtherBlock++;
                    blockedPos = null;
                }
            } else {
                blockedBy = r == null ? "null" : String.valueOf(r.getType());
                rayMiss++;
                blockedPos = null;
            }
        } catch (Throwable t) {
            blockedBy = "err";
        }
        return Optional.empty();
    }

    /** Block the centre reach ray last hit instead of the wanted one; read over py4j. */
    public static volatile String blockedBy = "-";
    /** Shares of what the failed reach ray hit: leaves, some other block, or nothing at all. */
    public static volatile int rayLeaves, rayOtherBlock, rayMiss;
    /** Where the leaves that stopped the last reach ray are, or null if it was not leaves. */
    public static volatile net.minecraft.util.math.BlockPos blockedPos;

    /**
     * RotationUtils.java:233-248. Note which eye is used: the SNEAKING eye when {@code wouldSneak},
     * so the answer is about the tick in which the bot will actually be sneaking.
     */
    public static Optional<Rotation> reachableOffset(PlayerEntity player, BlockPos pos, Vec3d offsetPos, double blockReachDistance, boolean wouldSneak) {
        Vec3d eyes = wouldSneak ? inferSneakingEyePosition(player) : player.getCameraPosVec(1.0F);
        Rotation rotation = calcRotationFromVec3d(eyes, offsetPos, playerRotations(player));
        Rotation actualRotation = peekRotation(rotation);
        HitResult result = rayTraceTowards(player, actualRotation, blockReachDistance, wouldSneak);
        if (result != null && result.getType() == HitResult.Type.BLOCK) {
            if (((BlockHitResult) result).getBlockPos().equals(pos)) {
                return Optional.of(rotation);
            }
            if (player.getEntityWorld().getBlockState(pos).getBlock() instanceof AbstractFireBlock && ((BlockHitResult) result).getBlockPos().equals(pos.down())) {
                return Optional.of(rotation);
            }
        }
        return Optional.empty();
    }

    /** RotationUtils.java:259-261. */
    public static Optional<Rotation> reachableCenter(PlayerEntity player, BlockPos pos, double blockReachDistance, boolean wouldSneak) {
        return reachableOffset(player, pos, calculateBlockCenter(player.getEntityWorld(), pos), blockReachDistance, wouldSneak);
    }

    // ------------------------------------------------------------------------------------------
    // RayTraceUtils
    // ------------------------------------------------------------------------------------------

    /** RayTraceUtils.java:45-47. */
    public static HitResult rayTraceTowards(Entity entity, Rotation rotation, double blockReachDistance) {
        return rayTraceTowards(entity, rotation, blockReachDistance, false);
    }

    /**
     * RayTraceUtils.java:49-64 — the trace tungsten forged and now does properly. {@code
     * ShapeType.OUTLINE} and {@link #fluidHandling} {@code NONE} are what make the result agree
     * with what vanilla's own trace will produce when the use key is pressed, which is the entire
     * reason the placement gate can be honest.
     */
    public static HitResult rayTraceTowards(Entity entity, Rotation rotation, double blockReachDistance, boolean wouldSneak) {
        Vec3d start;
        if (wouldSneak) {
            start = inferSneakingEyePosition(entity);
        } else {
            start = entity.getCameraPosVec(1.0F); // do whatever is correct
        }

        Vec3d direction = calcLookDirectionFromRotation(rotation);
        Vec3d end = start.add(
                direction.x * blockReachDistance,
                direction.y * blockReachDistance,
                direction.z * blockReachDistance
        );
        // shredder RayTraceUtils.java:63 — getEntityWorld(), not getWorld(), in this MC version
        return entity.getEntityWorld().raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, fluidHandling, entity));
    }

    /**
     * RayTraceUtils.java:66-68. The sneaking eye is 1.27 above the feet, hardcoded, and is used one
     * tick before the pose actually changes — that one-tick lead is the whole trick behind the
     * backplace (docs/BARITONE-PORT-SPEC.md, "The sneak KEY is not the sneak POSE").
     */
    public static Vec3d inferSneakingEyePosition(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY() + eyeHeight(true), entity.getZ());
    }

    // ------------------------------------------------------------------------------------------
    // VecUtils — the two centres, which are NOT interchangeable
    // ------------------------------------------------------------------------------------------

    /**
     * VecUtils.java:45-79 — the centre of the block's COLLISION shape. This is the one to aim at
     * when you want to hit a block (break it, or click its face); for a slab or a fence it is not
     * the cell centre.
     */
    public static Vec3d calculateBlockCenter(WorldView world, BlockPos pos) {
        BlockState b = world.getBlockState(pos);
        VoxelShape shape = b.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            return getBlockPosCenter(pos);
        }
        double xDiff = (shape.getMin(Direction.Axis.X) + shape.getMax(Direction.Axis.X)) / 2;
        double yDiff = (shape.getMin(Direction.Axis.Y) + shape.getMax(Direction.Axis.Y)) / 2;
        double zDiff = (shape.getMin(Direction.Axis.Z) + shape.getMax(Direction.Axis.Z)) / 2;
        if (Double.isNaN(xDiff) || Double.isNaN(yDiff) || Double.isNaN(zDiff)) {
            throw new IllegalStateException(b + " " + pos + " " + shape);
        }
        if (b.getBlock() instanceof AbstractFireBlock) { //look at bottom of fire when putting it out
            yDiff = 0;
        }
        return new Vec3d(
                pos.getX() + xDiff,
                pos.getY() + yDiff,
                pos.getZ() + zDiff
        );
    }

    /**
     * VecUtils.java:76-78 — the geometric cell centre. This is the one to aim at when walking
     * towards a cell or placing against it.
     */
    public static Vec3d getBlockPosCenter(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
