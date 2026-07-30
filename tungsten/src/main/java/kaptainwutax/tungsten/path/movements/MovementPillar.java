package kaptainwutax.tungsten.path.movements;

import com.google.common.collect.ImmutableSet;
import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.path.PlaceRules;
import kaptainwutax.tungsten.path.calculators.ActionCosts;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.block.AirBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.Set;

/**
 * Copy of {@code baritone/src/main/java/baritone/pathing/movement/movements/MovementPillar.java}
 * (the whole file: {@code cost()}, both vine helpers, {@code updateState()}, {@code prepared()}),
 * package changed and the baritone services swapped for {@link Movement}'s adapters plus the four
 * thin ones at the bottom. Unit 3 of docs/BARITONE-PORT-SPEC.md. {@code baritone/} is not compiled
 * and shredder owns the {@code baritone.*} package, so nothing here may be imported from there —
 * every line is copied, not called.
 *
 * <p>What the move is: tower up one block. {@code src} = the feet cell, {@code dest = src.above()},
 * {@code positionToPlace = src}, {@code positionsToBreak = {src.above(2)}} (MovementPillar.java:51).
 * The block goes into the cell the body currently occupies, onto the TOP face of {@code src.below()},
 * from above, once the body has vacated. There is no side face anywhere in this move, which is why a
 * pillar is geometrically possible where a same-cell side place is not (spec pitfall P4).
 *
 * <p>Two constraints set the whole design, and neither is tunable:
 * <ul>
 *   <li>Vanilla {@code World.canPlace} ends in {@code isSpaceEmpty(null, shape.offset(pos))}, so a
 *       full cube into the cell your own 0.6x1.8 box occupies is refused by both the server and the
 *       client predictor. Hence the {@code y > dest.getY() + 0.1} gate on the right click
 *       (MovementPillar.java:265): clicking any earlier is a guaranteed no-op that still burns the
 *       executor's 4-tick place cooldown.
 *   <li>A vanilla jump crosses that height around tick 5 of 12 and drops back below it around tick 7
 *       — a ~3-tick click window. Everything that has to be true at click time (pitch converged,
 *       sneaking POSE already active, throwaway equipped) is arranged on the ground, before take-off,
 *       by the branch order below. Do not reorder it.
 * </ul>
 *
 * <p>Note what is NOT here: no height loop, no placed counter, no stuck timer, no instance state at
 * all. A multi-block tower is N MovementPillar objects, one per block of height — the queue advances
 * {@code src} upward on SUCCESS — exactly as upstream's path is a list of them. And when
 * {@code dist <= 0.17} while {@code flatMotion >= 0.05}, NEITHER input is set and the bot coasts to a
 * stop; that is the intended behaviour, not a stall, and a stuck timer bolted on here is precisely
 * the reactive patch AGENTS.md rule 6 forbids.
 *
 * <p>Three things that look like bugs and are copied as they are. {@code fromDown} in
 * {@link #updateState} reads {@code src}, not {@code src.below()} — the name is off by one relative
 * to {@link #cost}, the value is what the branch wants. {@code blockIsThere = false;} at
 * MovementPillar.java:264 assigns a value it already holds. And the sneak gate
 * ({@code y > dest.getY()}) deliberately fires one notch before the click gate
 * ({@code y > dest.getY() + 0.1}): that is the documented one-tick delay so the sneaking POSE exists
 * before the click, because a click in the key-but-not-yet-pose tick raytraces from the standing eye
 * (1.62) instead of the sneaking one (1.27) and misses the face.
 *
 * <h2>Substitutions</h2>
 * <ul>
 *   <li>{@code IPlayerContext ctx} → {@link Movement.PlayerCtx}, the base's stand-in, so every
 *       {@code ctx.playerFeet()} / {@code ctx.playerHead()} / {@code ctx.playerRotations()} /
 *       {@code ctx.isLookingAt(pos)} / {@code ctx.blockReachDistance()} below is upstream's own line.
 *   <li>{@code BlockStateInterface.get(ctx, pos)} → {@code ctx.world().getBlockState(pos)};
 *       {@code context.get(x, y, z)} → {@link #stateAt} with a per-call {@link BlockPos.Mutable} so
 *       the planner's hot loop does not allocate a position per read.
 *   <li>{@code CalculationContext} → {@code (WorldView world, PlayerEntity player)}, the signature
 *       unit 3 of the spec specifies, matching {@code MovementTraverse.cost}.
 *   <li>{@code InventoryBehavior.selectThrowawayForLocation(true, x, y, z)} → {@link #equipThrowaway}
 *       (altoclef owns the inventory; tungsten only has the hook and the main hand).
 *   <li>{@code context.costOfPlacingAt} → {@link #costOfPlacingAt} (CalculationContext.java:186-200).
 *   <li>Settings, hardcoded at upstream's defaults: {@code jumpPenalty}=2.0 (Settings.java:122),
 *       {@code blockPlacementPenalty}=20.0 (Settings.java:110), {@code assumeWalkOnWater}=false
 *       (Settings.java:148).
 *   <li>{@code COST_INF} is declared HERE as +1000000. {@code path.calculators.ActionCosts.COST_INF}
 *       is a mutable public static that was NEGATIVE until 2026-07-30, and every line below reads
 *       {@code if (x >= COST_INF) return COST_INF;} — which inverts silently against a negative
 *       constant. The only thing used from that class is {@code distanceToTicks}, a pure function and
 *       itself a verbatim copy of upstream's, kept so JUMP_ONE_BLOCK_COST stays a derivation rather
 *       than a magic 3.1633.
 *   <li>tungsten's {@link BetterBlockPos} is the mojmap-named port: upstream's
 *       {@code up()/down()/offset(dir)} are spelled {@code above()/below()/relative(dir)} here.
 *       Calling the inherited yarn {@code BlockPos.up()} would compile and return the wrong TYPE, so
 *       these renames are mandatory, not cosmetic.
 *   <li>{@code logDirect} → {@code Debug.logMessage}.
 * </ul>
 */
public class MovementPillar extends Movement {

    /**
     * "Impossible", POSITIVE as upstream has it (ActionCosts.java:46). See the class javadoc for why
     * this is declared locally instead of read from {@code path.calculators.ActionCosts}.
     */
    private static final double COST_INF = 1000000;

    /** ActionCosts.java:41 — {@code 20 / 2.35} ticks to climb one ladder or vine block. */
    private static final double LADDER_UP_ONE_COST = 20 / 2.35;

    /**
     * ActionCosts.java:52-54 — {@code FALL_1_25_BLOCKS_COST - FALL_0_25_BLOCKS_COST} = 3.1633 ticks.
     * Derived, not tuned: the up leg of a vanilla jump is the 1.25-block fall minus the 0.25 the body
     * would have fallen anyway. With the two penalties below, a plain pillar step over solid ground
     * prices at 25.163 and 25.263 over air — the numbers the spec says to check the port against.
     */
    private static final double JUMP_ONE_BLOCK_COST =
            ActionCosts.distanceToTicks(1.25) - ActionCosts.distanceToTicks(0.25);

    /** Settings.java:122 {@code jumpPenalty} default — CalculationContext.java:158. */
    private static final double JUMP_PENALTY = 2.0D;

    /** Settings.java:110 {@code blockPlacementPenalty} default — CalculationContext.java:109. */
    private static final double BLOCK_PLACEMENT_PENALTY = 20.0D;

    /** Settings.java:148 {@code assumeWalkOnWater} default — CalculationContext.java:116. */
    private static final boolean ASSUME_WALK_ON_WATER = false;

    public MovementPillar(BetterBlockPos start, BetterBlockPos end) {
        // positionsToBreak = src.up(2), the cell above our head that we tower into;
        // positionToPlace = src, the cell our feet are in right now. MovementPillar.java:51.
        super(start, end, new BetterBlockPos[]{start.above(2)}, start);
    }

    @Override
    public double calculateCost(WorldView world, ClientPlayerEntity player) {
        return cost(world, player, src.x, src.y, src.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest);
    }

    public static double cost(WorldView world, PlayerEntity player, int x, int y, int z) {
        BlockPos.Mutable scratch = new BlockPos.Mutable();
        BlockState fromState = stateAt(world, scratch, x, y, z);
        Block from = fromState.getBlock();
        boolean ladder = from == Blocks.LADDER || from == Blocks.VINE;
        BlockState fromDown = stateAt(world, scratch, x, y - 1, z);
        if (!ladder) {
            if (fromDown.getBlock() == Blocks.LADDER || fromDown.getBlock() == Blocks.VINE) {
                return COST_INF; // can't pillar from a ladder or vine onto something that isn't also climbable
            }
            if (fromDown.getBlock() instanceof SlabBlock && fromDown.get(SlabBlock.TYPE) == SlabType.BOTTOM) {
                return COST_INF; // can't pillar up from a bottom slab onto a non ladder
            }
        }
        if (from == Blocks.VINE && !hasAgainst(world, x, y, z)) { // TODO this vine can't be climbed, but we could place a pillar still since vines are replacable, no? perhaps the pillar jump would be impossible because of the slowdown actually.
            return COST_INF;
        }
        BlockState toBreak = stateAt(world, scratch, x, y + 2, z);
        Block toBreakBlock = toBreak.getBlock();
        if (toBreakBlock instanceof FenceGateBlock) { // see issue #172
            return COST_INF;
        }
        BlockState srcUp = null;
        if (MovementHelperB.isWater(toBreak) && MovementHelperB.isWater(fromState)) { // TODO should this also be allowed if toBreakBlock is air?
            srcUp = stateAt(world, scratch, x, y + 1, z);
            if (MovementHelperB.isWater(srcUp)) {
                return LADDER_UP_ONE_COST; // allow ascending pillars of water, but only if we're already in one
            }
        }
        double placeCost = 0;
        if (!ladder) {
            // we need to place a block where we started to jump on it
            placeCost = costOfPlacingAt(world, player, x, y, z, fromState);
            if (placeCost >= COST_INF) {
                return COST_INF;
            }
            if (fromDown.getBlock() instanceof AirBlock) {
                placeCost += 0.1; // slightly (1/200th of a second) penalize pillaring on what's currently air
            }
        }
        if ((MovementHelperB.isLiquid(fromState) && !MovementHelperB.canPlaceAgainst(world, x, y - 1, z, fromDown)) || (MovementHelperB.isLiquid(fromDown) && ASSUME_WALK_ON_WATER)) {
            // otherwise, if we're standing in water, we cannot pillar
            // if we're standing on water and assumeWalkOnWater is true, we cannot pillar
            // if we're standing on water and assumeWalkOnWater is false, we must have ascended to here, or sneak backplaced, so it is possible to pillar again
            return COST_INF;
        }
        if ((from == Blocks.LILY_PAD || from instanceof CarpetBlock) && !fromDown.getFluidState().isEmpty()) {
            // to ascend here we'd have to break the block we are standing on
            return COST_INF;
        }
        double hardness = MovementHelperB.getMiningDurationTicks(world, player, x, y + 2, z, toBreak, true);
        if (hardness >= COST_INF) {
            return COST_INF;
        }
        if (hardness != 0) {
            if (toBreakBlock == Blocks.LADDER || toBreakBlock == Blocks.VINE) {
                hardness = 0; // we won't actually need to break the ladder / vine because we're going to use it
            } else {
                BlockState check = stateAt(world, scratch, x, y + 3, z); // the block on top of the one we're going to break, could it fall on us?
                if (check.getBlock() instanceof FallingBlock) {
                    // see MovementAscend's identical check for breaking a falling block above our head
                    if (srcUp == null) {
                        srcUp = stateAt(world, scratch, x, y + 1, z);
                    }
                    if (!(toBreakBlock instanceof FallingBlock) || !(srcUp.getBlock() instanceof FallingBlock)) {
                        return COST_INF;
                    }
                }
                // this is commented because it may have had a purpose, but it's very unclear what it was. it's from the minebot era.
                //if (!MovementHelper.canWalkOn(context, chkPos, check) || MovementHelper.canWalkThrough(context, chkPos, check)) {//if the block above where we want to break is not a full block, don't do it
                // TODO why does canWalkThrough mean this action is COST_INF?
                // FallingBlock makes sense, and !canWalkOn deals with weird cases like if it were lava
                // but I don't understand why canWalkThrough makes it impossible
                //    return COST_INF;
                //}
            }
        }
        if (ladder) {
            return LADDER_UP_ONE_COST + hardness * 5;
        } else {
            return JUMP_ONE_BLOCK_COST + placeCost + JUMP_PENALTY + hardness;
        }
    }

    public static boolean hasAgainst(WorldView world, int x, int y, int z) {
        BlockPos.Mutable scratch = new BlockPos.Mutable();
        return MovementHelperB.isBlockNormalCube(stateAt(world, scratch, x + 1, y, z)) ||
                MovementHelperB.isBlockNormalCube(stateAt(world, scratch, x - 1, y, z)) ||
                MovementHelperB.isBlockNormalCube(stateAt(world, scratch, x, y, z + 1)) ||
                MovementHelperB.isBlockNormalCube(stateAt(world, scratch, x, y, z - 1));
    }

    public static BlockPos getAgainst(WorldView world, BetterBlockPos vine) {
        if (MovementHelperB.isBlockNormalCube(world.getBlockState(vine.north()))) {
            return vine.north();
        }
        if (MovementHelperB.isBlockNormalCube(world.getBlockState(vine.south()))) {
            return vine.south();
        }
        if (MovementHelperB.isBlockNormalCube(world.getBlockState(vine.east()))) {
            return vine.east();
        }
        if (MovementHelperB.isBlockNormalCube(world.getBlockState(vine.west()))) {
            return vine.west();
        }
        return null;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ctx.playerFeet().y < src.y) {
            // We fell out of the column. Nothing here can recover it — the queue replans.
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        ClientPlayerEntity player = ctx.player();
        BlockState fromDown = ctx.world().getBlockState(src); // NB: reads src, not src.below(); upstream's name is off by one, the value is right
        if (MovementHelperB.isWater(fromDown) && MovementHelperB.isWater(ctx.world().getBlockState(dest))) {
            // stay centered while swimming up a water column
            state.setTarget(new MovementState.MovementTarget(RotationHelper.calcRotationFromVec3d(ctx.playerHead(), RotationHelper.getBlockPosCenter(dest), ctx.playerRotations()), false));
            Vec3d destCenter = RotationHelper.getBlockPosCenter(dest);
            if (Math.abs(player.getEntityPos().x - destCenter.x) > 0.2 || Math.abs(player.getEntityPos().z - destCenter.z) > 0.2) {
                state.setInput(Input.MOVE_FORWARD, true);
            }
            if (ctx.playerFeet().equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            return state;
        }
        boolean ladder = fromDown.getBlock() == Blocks.LADDER || fromDown.getBlock() == Blocks.VINE;
        boolean vine = fromDown.getBlock() == Blocks.VINE;
        Rotation rotation = RotationHelper.calcRotationFromVec3d(ctx.playerHead(),
                RotationHelper.getBlockPosCenter(positionToPlace), // positionToPlace == src
                ctx.playerRotations());
        if (!ladder) {
            // Keep the yaw we already have and take only the pitch. The pitch is what has to have
            // converged before the ~3-tick click window opens; swinging the yaw as well would walk the
            // body off the column it is about to place under.
            state.setTarget(new MovementState.MovementTarget(ctx.playerRotations().withPitch(rotation.getPitch()), true));
        }

        boolean blockIsThere = MovementHelperB.canWalkOn(ctx.world(), src) || ladder;
        if (ladder) {
            BlockPos against = vine ? getAgainst(ctx.world(), src) : src.relative(fromDown.get(LadderBlock.FACING).getOpposite());
            if (against == null) {
                Debug.logMessage("Unable to climb vines. Consider disabling allowVines.");
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            if (ctx.playerFeet().equals(against.up()) || ctx.playerFeet().equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (MovementHelperB.isBottomSlab(ctx.world().getBlockState(src.below()))) {
                state.setInput(Input.JUMP, true);
            }
            /*
            if (thePlayer.getPosition0().getX() != from.getX() || thePlayer.getPosition0().getZ() != from.getZ()) {
                Baritone.moveTowardsBlock(from);
            }
             */

            // Climbing is walking INTO the block the ladder hangs on, not jumping: the soft (unforced)
            // look plus MOVE_FORWARD, current pitch preserved (MovementHelper.java:715-722).
            MovementHelperB.moveTowards(player, state, against);
            return state;
        } else {
            // Get ready to place a throwaway block
            if (!equipThrowaway(player, src.x, src.y, src.z)) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }


            state.setInput(Input.SNEAK, player.getEntityPos().y > dest.getY() || player.getEntityPos().y < src.getY() + 0.2D); // delay placement by 1 tick for ncp compatibility
            // since (lower down) we only right click once player.isSneaking, and that happens the tick after we request to sneak

            double diffX = player.getEntityPos().x - (dest.getX() + 0.5);
            double diffZ = player.getEntityPos().z - (dest.getZ() + 0.5);
            double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
            double flatMotion = Math.sqrt(player.getVelocity().x * player.getVelocity().x + player.getVelocity().z * player.getVelocity().z);
            if (dist > 0.17) {//why 0.17? because it seemed like a good number, that's why
                //[explanation added after baritone port lol] also because it needs to be less than 0.2 because of the 0.3 sneak limit
                //and 0.17 is reasonably less than 0.2

                // If it's been more than forty ticks of trying to jump and we aren't done yet, go forward, maybe we are stuck
                state.setInput(Input.MOVE_FORWARD, true);

                // revise our target to both yaw and pitch if we're going to be moving forward
                state.setTarget(new MovementState.MovementTarget(rotation, true));
            } else if (flatMotion < 0.05) {
                // If our Y coordinate is above our goal, stop jumping
                state.setInput(Input.JUMP, player.getEntityPos().y < dest.getY());
            }


            if (!blockIsThere) {
                BlockState frState = ctx.world().getBlockState(src);
                Block fr = frState.getBlock();
                // TODO: Evaluate usage of getMaterial().isReplaceable()
                if (!(fr instanceof AirBlock || frState.isReplaceable())) {
                    RotationHelper.reachable(player, src, ctx.blockReachDistance())
                            .map(rot -> new MovementState.MovementTarget(rot, true))
                            .ifPresent(state::setTarget);
                    state.setInput(Input.JUMP, false); // breaking is like 5x slower when you're jumping
                    state.setInput(Input.CLICK_LEFT, true);
                    blockIsThere = false;
                } else if (player.isInSneakingPose() && (ctx.isLookingAt(src.below()) || ctx.isLookingAt(src)) && player.getEntityPos().y > dest.getY() + 0.1) {
                    // The sneaking POSE, not the sneak key: the pose lags the key by a tick and only
                    // then does the eye sit at 1.27 instead of 1.62, which is what the crosshair test
                    // above is raytraced from. And +0.1 above dest is the body having left the cell —
                    // vanilla refuses a cube into a cell your own hitbox occupies.
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
        if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.below())) {
            Block block = ctx.world().getBlockState(src.below()).getBlock();
            if (block == Blocks.LADDER || block == Blocks.VINE) {
                // Sneak so the break pass doesn't slide us off the ladder we are standing on.
                state.setInput(Input.SNEAK, true);
            }
        }
        if (MovementHelperB.isWater(ctx.world().getBlockState(dest.above()))) {
            // Swimming up: there is nothing to break, and the break pass would aim at water forever.
            return true;
        }
        return super.prepared(state);
    }

    // ------------------------------------------------------------------------------------------
    // Adapters. One baritone service each, nothing more; private so a sibling movement porting the
    // same service cannot collide with these.
    // ------------------------------------------------------------------------------------------

    /**
     * {@code context.get(x, y, z)}. The scratch position is caller-owned and reused within a single
     * call; only BlockStates escape, never the position itself.
     */
    private static BlockState stateAt(WorldView world, BlockPos.Mutable scratch, int x, int y, int z) {
        return world.getBlockState(scratch.set(x, y, z));
    }

    /**
     * {@code InventoryBehavior.selectThrowawayForLocation(true, x, y, z)} — "equip something
     * disposable to place here, and tell me whether I have one". Tungsten never touches the inventory
     * itself: altoclef registers {@code equipBlockHook} and does the equipping
     * (TungstenModDataContainer.java:36), and then we test the main hand.
     *
     * <p>DIVERGENCE, recorded rather than faked: the tungsten hook takes no location, so upstream's
     * per-location item filter has no equivalent. The x/y/z parameters are kept so the call site
     * stays upstream's line.
     *
     * <p>Returning false here is the whole "no block in inventory" path — the movement goes
     * UNREACHABLE (MovementPillar.java:227-229) and the queue replans. No timeout is involved in
     * that, and adding one would hide a case {@link #costOfPlacingAt} already prices as COST_INF.
     */
    private static boolean equipThrowaway(ClientPlayerEntity player, int x, int y, int z) {
        Runnable hook = TungstenModDataContainer.equipBlockHook;
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable ignored) {
                // an inventory-side failure must not take the movement down; the main-hand test decides
            }
        }
        return hasThrowaway(player);
    }

    /**
     * {@code CalculationContext.hasThrowaway} (CalculationContext.java:106) and the non-mutating
     * {@code selectThrowawayForLocation(false, …)}. Upstream asks the whole inventory
     * ({@code hasGenericThrowaway()}); tungsten can only see the main hand, because equipping is
     * altoclef's side of the hook. That is narrower than upstream and never wider: it can refuse a
     * pillar upstream would have planned, and cannot plan one it could not execute.
     */
    private static boolean hasThrowaway(PlayerEntity player) {
        return TungstenConfig.get().allowPlace && player.getMainHandStack().getItem() instanceof BlockItem;
    }

    /**
     * {@code context.costOfPlacingAt(x, y, z, current)} (CalculationContext.java:186-200): one place,
     * and the reasons to refuse. {@code current} goes unused here exactly as it does upstream — it is
     * a parameter because the call site has it to hand.
     *
     * <p>{@code PlaceRules.canPlace} folds together upstream's {@code isPossiblyProtected}, the world
     * border and {@code shouldAvoidPlacingAt}; it additionally tests that the cell is replaceable,
     * which upstream leaves to the caller. For a pillar the cell is the one our feet are in, so the
     * two agree in practice — noted because it is one refusal upstream does not have.
     */
    private static double costOfPlacingAt(WorldView world, PlayerEntity player, int x, int y, int z, BlockState current) {
        if (!hasThrowaway(player)) { // only true if allowPlace is true, see hasThrowaway
            return COST_INF;
        }
        if (!PlaceRules.canPlace(world, new BlockPos(x, y, z))) {
            return COST_INF;
        }
        return BLOCK_PLACEMENT_PENALTY * TungstenConfig.get().placeCostMultiplier;
    }
}
