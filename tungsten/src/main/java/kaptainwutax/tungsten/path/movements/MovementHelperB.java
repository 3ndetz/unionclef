package kaptainwutax.tungsten.path.movements;

import java.util.Optional;
import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.path.BreakRules;
import kaptainwutax.tungsten.path.PlaceRules;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.Ternary;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.AbstractSkullBlock;
import net.minecraft.block.AirBlock;
import net.minecraft.block.AmethystClusterBlock;
import net.minecraft.block.AzaleaBlock;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.CauldronBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.FrostedIceBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.LilyPadBlock;
import net.minecraft.block.PistonExtensionBlock;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.ScaffoldingBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.StainedGlassBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.StairShape;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.pathing.NavigationType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.fluid.WaterFluid;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

/**
 * The placement and breaking half of baritone's {@code MovementHelper}, copied into tungsten.
 *
 * <p>Provenance, per method, from {@code
 * baritone/src/main/java/baritone/pathing/movement/MovementHelper.java}: {@code avoidBreaking}
 * :96-144, the {@code canWalkThrough} family :150-272, {@code avoidWalkingInto} :420-431, the
 * {@code canWalkOn} family :447-555, {@code canPlaceAgainst} :625-647, {@code
 * getMiningDurationTicks} :649-685, {@code switchToBestToolFor} :692-713, the liquid predicates
 * :731-786, {@code isBlockNormalCube} :788-804, and {@code attemptToPlaceABlock} plus {@code
 * PlaceResult} :806-864. Branch order, constants and the deliberately redundant locals are
 * upstream's; nothing was re-derived, simplified or re-tuned. {@code
 * HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP} is Movement.java:36 and lives here
 * because {@code attemptToPlaceABlock} is its first caller.
 *
 * <p>{@code baritone/} is not compiled and shredder owns the {@code baritone.*} package
 * (AGENTS.md), so this file references only tungsten and Minecraft. Where the reference tree's yarn
 * names have drifted, the compiled shredder copy of the same file is the oracle (its only
 * difference in this file is {@code getInventory().setSelectedSlot(...)} instead of assigning
 * {@code selectedSlot}, shredder MovementHelper.java:712).
 *
 * <h2>Substitutions and adapters</h2>
 * <ul>
 *   <li>{@code BlockStateInterface} / {@code CalculationContext} -> a plain {@link WorldView}; every
 *       {@code context.get(x,y,z)} is {@link #get} (a thread-local scratch {@code BlockPos.Mutable},
 *       because tungsten searches off the client thread). Positions that ESCAPE to a policy hook are
 *       freshly allocated, immutable {@code BlockPos} — an external predicate may retain them.</li>
 *   <li>{@code context.toolSet.getStrVsBlock(state)} -> {@link #strVsBlock}, i.e. the vanilla break
 *       progress of the CURRENTLY HELD item, exactly as {@code BlockNode.breakTicks}
 *       (BlockNode.java:725-731) already does. Knowingly wrong by up to ~25x when the right tool is
 *       in another slot; that is the ToolSet finding in docs/BARITONE-PORT.md, not this port.</li>
 *   <li>{@code context.breakCostMultiplierAt} -> {@link #breakCostMultiplierAt}: COST_INF when
 *       {@code BreakRules} refuses, otherwise {@code TungstenConfig.breakCostMultiplier}.</li>
 *   <li>{@code AltoClefSettings.shouldAvoidBreaking} / {@code shouldAvoidPlacingAt} -> {@code
 *       BreakRules.canBreak} / {@code PlaceRules.allowedByPolicy}, per the spec's substitution
 *       table. {@code shouldAvoidWalkThroughForce}, {@code canSwimThroughLava}, {@code
 *       isCanWalkOnEndPortal} and {@code isInteractionPaused} have no tungsten equivalent; those
 *       early-returns are dropped and marked DROPPED below.</li>
 *   <li>{@code bsi.worldBorder.canPlaceAt(x,z)} -> {@link #worldBorderCanPlaceAt}, the body of
 *       {@code BetterWorldBorder.canPlaceAt} (BetterWorldBorder.java:44-49) read off {@code
 *       world.getWorldBorder()}.</li>
 *   <li>{@code ToolSet} / {@code InventoryBehavior} do not exist in tungsten. {@link
 *       #switchToBestToolFor} defers to {@code TungstenModDataContainer.equipToolHook}, and the
 *       throwaway selector is {@link #selectThrowaway} — see its javadoc, it is deliberately
 *       minimal.</li>
 *   <li>Baritone settings are hardcoded at upstream's DEFAULTS and named in comments (see the
 *       constants block).</li>
 * </ul>
 *
 * <h2>What this file assumes the movement substrate declares in this package</h2>
 * Only four symbols, all in {@link #attemptToPlaceABlock}: {@code MovementState} with {@code
 * setTarget} / {@code setInput} / {@code setStatus}, the nested {@code
 * MovementState.MovementTarget(Rotation, boolean)}, {@code MovementStatus.UNREACHABLE} and {@code
 * Input.SNEAK}. If the substrate nests {@code MovementStatus} or {@code Input} inside {@code
 * MovementState}, only the qualifiers in that one method change. {@code Rotation} is the
 * package-private type at the bottom of {@link RotationHelper}.
 */
public final class MovementHelperB {

    private MovementHelperB() {}

    /**
     * baritone ActionCosts.java:46. Declared HERE on purpose: tungsten's own {@code
     * path.calculators.ActionCosts.COST_INF} is NEGATIVE (-1000000), and every line copied from
     * baritone reads {@code if (x >= COST_INF) return COST_INF;}, which silently inverts against
     * that constant. Never read the other one from this package.
     */
    public static final double COST_INF = 1_000_000;

    /**
     * Movement.java:36, read off {@link Movement} rather than re-declared — upstream's
     * MovementHelper does exactly that. The order is load-bearing: DOWN last is what makes
     * {@code preferDown} work (it keeps the last match instead of the first), and NORTH-first is
     * what makes a side place deterministic, so there must be exactly one array that can drift.
     */
    private static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP =
            Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;

    /** baritone Settings.java:117 {@code blockBreakAdditionalPenalty}, default 2. */
    private static final double BREAK_BLOCK_ADDITIONAL_COST = 2.0;

    /** baritone Settings.java:148 {@code assumeWalkOnWater}, default false. */
    private static final boolean ASSUME_WALK_ON_WATER = false;

    /** baritone Settings.java:153 {@code assumeWalkOnLava}, default false. */
    private static final boolean ASSUME_WALK_ON_LAVA = false;

    /** baritone Settings.java:332 {@code allowWalkOnBottomSlab}, default true. */
    private static final boolean ALLOW_WALK_ON_BOTTOM_SLAB = true;

    /** baritone Settings.java:326 {@code allowVines}, default false. */
    private static final boolean ALLOW_VINES = false;

    /** baritone Settings.java:318 {@code avoidUpdatingFallingBlocks}, default true. */
    private static final boolean AVOID_UPDATING_FALLING_BLOCKS = true;

    /** baritone Settings.java:134 {@code strictLiquidCheck}, default false. */
    private static final boolean STRICT_LIQUID_CHECK = false;

    /** baritone Settings.java:102 {@code autoTool} (true) and :97 {@code assumeExternalAutoTool} (false). */
    private static final boolean AUTO_TOOL = true;
    private static final boolean ASSUME_EXTERNAL_AUTO_TOOL = false;

    /**
     * {@code context.get(x,y,z)} / {@code bsi.get0(x,y,z)}. Thread-local because the tungsten
     * search does not run on the client thread; the mutable never escapes this method.
     */
    private static final ThreadLocal<BlockPos.Mutable> SCRATCH = ThreadLocal.withInitial(BlockPos.Mutable::new);

    private static BlockState get(WorldView world, int x, int y, int z) {
        return world.getBlockState(SCRATCH.get().set(x, y, z));
    }

    // ------------------------------------------------------------------------------------------
    // avoidBreaking — MovementHelper.java:96-144
    // ------------------------------------------------------------------------------------------

    public static boolean avoidBreaking(WorldView world, int x, int y, int z, BlockState state) {
        if (get(world, x, y + 1, z).getBlock() instanceof EndPortalFrameBlock) {
            return true;
        }
        // AltoClefSettings.shouldAvoidBreaking(pos) -> BreakRules.canBreak, which also covers
        // baritone's blocksToDisallowBreaking (EMPTY by default, Settings.java:226) via its
        // configurable breakDenyBlocks / breakDenyZones and the altoclef protection hook.
        if (!BreakRules.canBreak(world, new BlockPos(x, y, z), state)) {
            return true;
        }

        if (!worldBorderCanPlaceAt(world, x, z)) {
            return true;
        }
        Block b = state.getBlock();
        return b instanceof EndPortalFrameBlock
                || b == Blocks.ICE // ice becomes water, and water can mess up the path
                // call get directly with x,y,z. no need to make 5 new BlockPos for no reason
                || avoidAdjacentBreaking(world, x, y + 1, z, true)
                || avoidAdjacentBreaking(world, x + 1, y, z, false)
                || avoidAdjacentBreaking(world, x - 1, y, z, false)
                || avoidAdjacentBreaking(world, x, y, z + 1, false)
                || avoidAdjacentBreaking(world, x, y, z - 1, false);
    }

    public static boolean avoidAdjacentBreaking(WorldView world, int x, int y, int z, boolean directlyAbove) {
        // returns true if you should avoid breaking a block that's adjacent to this one (e.g. lava that will start flowing if you give it a path)
        // this is only called for north, south, east, west, and up. this is NOT called for down.
        // we assume that it's ALWAYS okay to break the block thats ABOVE liquid
        BlockState state = get(world, x, y, z);
        Block block = state.getBlock();
        if (!directlyAbove // it is fine to mine a block that has a falling block directly above, this (the cost of breaking the stacked fallings) is included in cost calculations
                // therefore if directlyAbove is true, we will actually ignore if this is falling
                && block instanceof FallingBlock // obviously, this check is only valid for falling blocks
                && AVOID_UPDATING_FALLING_BLOCKS // and if the setting is enabled
                && FallingBlock.canFallThrough(get(world, x, y - 1, z))) { // and if it would fall (i.e. it's unsupported)
            return true; // dont break a block that is adjacent to unsupported gravel because it can cause really weird stuff
        }
        // only pure liquids for now
        // waterlogged blocks can have closed bottom sides and such
        if (block instanceof FluidBlock) {
            if (directlyAbove || STRICT_LIQUID_CHECK) {
                return true;
            }
            int level = state.get(FluidBlock.LEVEL);
            if (level == 0) {
                return true; // source blocks like to flow horizontally
            }
            // everything else will prefer flowing down
            return !(get(world, x, y - 1, z).getBlock() instanceof FluidBlock); // assume everything is in a static state
        }
        return !state.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // canWalkThrough — MovementHelper.java:146-272
    // ------------------------------------------------------------------------------------------

    public static boolean canWalkThrough(WorldView world, BlockPos pos) {
        return canWalkThrough(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean canWalkThrough(WorldView world, int x, int y, int z) {
        return canWalkThrough(world, x, y, z, get(world, x, y, z));
    }

    /**
     * MovementHelper.java:162-180. DROPPED: {@code AltoClefSettings.canSwimThroughLava()} and
     * {@code shouldAvoidWalkThroughForce(x,y,z)} — no tungsten equivalent (spec substitution table).
     */
    public static boolean canWalkThrough(WorldView world, int x, int y, int z, BlockState state) {
        Ternary canWalkThrough = canWalkThroughBlockState(state);
        if (canWalkThrough == Ternary.YES) {
            return true;
        }
        if (canWalkThrough == Ternary.NO) {
            return false;
        }
        return canWalkThroughPosition(world, x, y, z, state);
    }

    /**
     * MovementHelper.java:182-230. DROPPED: {@code Baritone.settings().blocksToAvoid} — EMPTY by
     * default (Settings.java:219), so the branch is a no-op.
     */
    public static Ternary canWalkThroughBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return Ternary.YES;
        }
        if (block instanceof AbstractFireBlock || block == Blocks.TRIPWIRE || block == Blocks.COBWEB || block == Blocks.END_PORTAL || block == Blocks.COCOA || block instanceof AbstractSkullBlock || block == Blocks.BUBBLE_COLUMN || block instanceof ShulkerBoxBlock || block instanceof SlabBlock || block instanceof TrapdoorBlock || block == Blocks.HONEY_BLOCK || block == Blocks.END_ROD || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POINTED_DRIPSTONE || block instanceof AmethystClusterBlock || block instanceof AzaleaBlock) {
            return Ternary.NO;
        }
        if (block == Blocks.BIG_DRIPLEAF) {
            return Ternary.NO;
        }
        if (block == Blocks.POWDER_SNOW) {
            return Ternary.NO;
        }
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
            // TODO this assumes that all doors in all mods are openable
            if (block == Blocks.IRON_DOOR) {
                return Ternary.NO;
            }
            return Ternary.YES;
        }
        if (block instanceof CarpetBlock) {
            return Ternary.MAYBE;
        }
        if (block instanceof SnowBlock) {
            // snow layers cached as the top layer of a packed chunk have no metadata, we can't make a decision based on their depth here
            // it would otherwise make long distance pathing through snowy biomes impossible
            return Ternary.MAYBE;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (fluidState.getFluid().getLevel(fluidState) != 8) {
                return Ternary.NO;
            } else {
                return Ternary.MAYBE;
            }
        }
        if (block instanceof CauldronBlock) {
            return Ternary.NO;
        }
        if (state.canPathfindThrough(NavigationType.LAND)) {
            return Ternary.YES;
        } else {
            return Ternary.NO;
        }
    }

    /** MovementHelper.java:232-272. */
    public static boolean canWalkThroughPosition(WorldView world, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();

        if (block instanceof CarpetBlock) {
            return canWalkOn(world, x, y - 1, z);
        }

        if (block instanceof SnowBlock) {
            // if they're cached as a top block, we don't know their metadata
            // default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!worldContainsLoadedChunk(world, x, z)) {
                return true;
            }
            // the check in BlockSnow.isPassable is layers < 5
            // while actually, we want < 3 because 3 or greater makes it impassable in a 2 high ceiling
            if (state.get(SnowBlock.LAYERS) >= 3) {
                return false;
            }
            // ok, it's low enough we could walk through it, but is it supported?
            return canWalkOn(world, x, y - 1, z);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (isFlowing(x, y, z, state, world)) {
                return false;
            }
            // Everything after this point has to be a special case as it relies on the water not being flowing, which means a special case is needed.
            if (ASSUME_WALK_ON_WATER) {
                return false;
            }

            BlockState up = get(world, x, y + 1, z);
            if (!up.getFluidState().isEmpty() || up.getBlock() instanceof LilyPadBlock) {
                return false;
            }
            return fluidState.getFluid() instanceof WaterFluid;
        }

        return state.canPathfindThrough(NavigationType.LAND);
    }

    // ------------------------------------------------------------------------------------------
    // avoidWalkingInto — MovementHelper.java:420-431
    // ------------------------------------------------------------------------------------------

    /**
     * The hazard list. This is the whole of {@code nav_hazard}'s subject matter: a movement that
     * walks while breaking checks BOTH cells against this before it presses forward.
     */
    public static boolean avoidWalkingInto(BlockState state) {
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

    // ------------------------------------------------------------------------------------------
    // isReplaceable — MovementHelper.java:340-367
    // ------------------------------------------------------------------------------------------

    /**
     * "Could a block be placed into this cell." Argument order is upstream's (the state fourth, the
     * world provider last) so {@code MovementTraverse.cost}'s copied call site lines up unchanged.
     *
     * <p>The snow branch defaults to TRUE for an unloaded chunk on purpose: refusing there would
     * make long-distance pathing through snowy biomes impossible, which is upstream's own comment.
     */
    public static boolean isReplaceable(int x, int y, int z, BlockState state, WorldView world) {
        // for MovementTraverse and MovementAscend
        // block double plant defaults to true when the block doesn't match, so don't need to check that case
        // all other overrides just return true or false
        // the only case to deal with is snow
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            // early return for common cases hehe
            return true;
        }
        if (block instanceof SnowBlock) {
            // as before, default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!worldContainsLoadedChunk(world, x, z)) {
                return true;
            }
            return state.get(SnowBlock.LAYERS) == 1;
        }
        if (block == Blocks.LARGE_FERN || block == Blocks.TALL_GRASS) {
            return true;
        }
        return state.isReplaceable();
    }

    // ------------------------------------------------------------------------------------------
    // doors and gates — MovementHelper.java:374-418
    // ------------------------------------------------------------------------------------------

    /**
     * {@code isDoorPassable(IPlayerContext, doorPos, playerPos)}. {@code playerPos} is the cell the
     * BODY is in; MovementTraverse deliberately passes {@code (src, dest)} for the head cell and
     * {@code (dest, src)} for the feet cell (MovementTraverse.java:226) and that pairing is
     * load-bearing — it is what decides which side of the door the bot is standing on.
     */
    public static boolean isDoorPassable(WorldView world, BlockPos doorPos, BlockPos playerPos) {
        if (playerPos.equals(doorPos)) {
            return false;
        }

        BlockState state = world.getBlockState(doorPos);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return true;
        }

        return isHorizontalBlockPassable(doorPos, state, playerPos, DoorBlock.OPEN);
    }

    public static boolean isGatePassable(WorldView world, BlockPos gatePos, BlockPos playerPos) {
        if (playerPos.equals(gatePos)) {
            return false;
        }

        BlockState state = world.getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return true;
        }

        return state.get(FenceGateBlock.OPEN);
    }

    /**
     * MovementHelper.java:398-418. The final {@code (facing == playerFacing) == open} is the whole
     * trick: a door whose hinge axis matches the approach axis is passable exactly when it is OPEN,
     * and one across the approach is passable exactly when it is CLOSED.
     */
    public static boolean isHorizontalBlockPassable(BlockPos blockPos, BlockState blockState, BlockPos playerPos, BooleanProperty propertyOpen) {
        if (playerPos.equals(blockPos)) {
            return false;
        }

        Direction.Axis facing = blockState.get(HorizontalFacingBlock.FACING).getAxis();
        boolean open = blockState.get(propertyOpen);

        Direction.Axis playerFacing;
        if (playerPos.north().equals(blockPos) || playerPos.south().equals(blockPos)) {
            playerFacing = Direction.Axis.Z;
        } else if (playerPos.east().equals(blockPos) || playerPos.west().equals(blockPos)) {
            playerFacing = Direction.Axis.X;
        } else {
            return true;
        }

        return (facing == playerFacing) == open;
    }

    // ------------------------------------------------------------------------------------------
    // canWalkOn — MovementHelper.java:447-555
    // ------------------------------------------------------------------------------------------

    /**
     * Can I walk on this block without anything weird happening like me falling through? Includes
     * water because we know that we automatically jump on water.
     */
    public static boolean canWalkOn(WorldView world, int x, int y, int z, BlockState state) {
        Ternary canWalkOn = canWalkOnBlockState(state);
        if (canWalkOn == Ternary.YES) {
            return true;
        }
        if (canWalkOn == Ternary.NO) {
            return false;
        }
        return canWalkOnPosition(world, x, y, z, state);
    }

    public static boolean canWalkOn(WorldView world, int x, int y, int z) {
        return canWalkOn(world, x, y, z, get(world, x, y, z));
    }

    public static boolean canWalkOn(WorldView world, BlockPos pos) {
        return canWalkOn(world, pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean canWalkOn(WorldView world, BlockPos pos, BlockState state) {
        return canWalkOn(world, pos.getX(), pos.getY(), pos.getZ(), state);
    }

    /**
     * MovementHelper.java:458-505. DROPPED: the {@code Blocks.END_PORTAL &&
     * AltoClefSettings.isCanWalkOnEndPortal()} branch (:464-466) — the altoclef toggle has no
     * tungsten equivalent and its default is off, so the block simply falls through to NO.
     */
    public static Ternary canWalkOnBlockState(BlockState state) {
        Block block = state.getBlock();
        //Extra blocks we may want to walk on.
        if (block instanceof EndPortalFrameBlock) {
            return Ternary.YES;
        }
        //*****************************************
        if (isBlockNormalCube(state) && block != Blocks.MAGMA_BLOCK && block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) {
            return Ternary.YES;
        }
        if (block instanceof AzaleaBlock) {
            return Ternary.YES;
        }
        if (block == Blocks.LADDER || (block == Blocks.VINE && ALLOW_VINES)) { // TODO reconsider this
            return Ternary.YES;
        }
        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH) {
            return Ternary.YES;
        }
        if (block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return Ternary.YES;
        }
        if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
            return Ternary.YES;
        }
        if (block instanceof StairsBlock) {
            return Ternary.YES;
        }
        if (isWater(state)) {
            return Ternary.MAYBE;
        }
        if (isLava(state) && ASSUME_WALK_ON_LAVA) {
            return Ternary.MAYBE;
        }
        if (block instanceof SlabBlock) {
            if (!ALLOW_WALK_ON_BOTTOM_SLAB) {
                if (state.get(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return Ternary.YES;
                }
                return Ternary.NO;
            }
            return Ternary.YES;
        }
        return Ternary.NO;
    }

    /** MovementHelper.java:507-531. */
    public static boolean canWalkOnPosition(WorldView world, int x, int y, int z, BlockState state) {
        if (isWater(state)) {
            // since this is called literally millions of times per second, the benefit of not allocating millions of useless "pos.up()"
            // BlockPos s that we'd just garbage collect immediately is actually noticeable. I don't even think its a decrease in readability
            BlockState upState = get(world, x, y + 1, z);
            Block up = upState.getBlock();
            if (up == Blocks.LILY_PAD || up instanceof CarpetBlock) {
                return true;
            }
            if (isFlowing(x, y, z, state, world) || upState.getFluidState().getFluid() == Fluids.FLOWING_WATER) {
                // the only scenario in which we can walk on flowing water is if it's under still water with jesus off
                return isWater(upState) && !ASSUME_WALK_ON_WATER;
            }
            // if assumeWalkOnWater is on, we can only walk on water if there isn't water above it
            // if assumeWalkOnWater is off, we can only walk on water if there is water above it
            return isWater(upState) ^ ASSUME_WALK_ON_WATER;
        }

        if (isLava(state) && !isFlowing(x, y, z, state, world) && ASSUME_WALK_ON_LAVA) { // if we get here it means that assumeWalkOnLava must be true, so put it last
            return true;
        }

        return false; // If we don't recognise it then we want to just return false to be safe.
    }

    // ------------------------------------------------------------------------------------------
    // canUseFrostWalker — MovementHelper.java:557-581
    // ------------------------------------------------------------------------------------------

    /**
     * {@code CalculationContext.frostWalker} (CalculationContext.java:119-131) — the highest Frost
     * Walker level across every equipment slot, 0 for none. ADAPTER: upstream caches this once per
     * search on the context; there is no context here, so it is recomputed. Same value, and the
     * enchantment cannot change mid-tick.
     */
    private static int frostWalkerLevel(PlayerEntity player) {
        int frostWalkerLevel = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantmentsComponent itemEnchantments = player.getEquippedStack(slot).getEnchantments();
            for (RegistryEntry<Enchantment> enchant : itemEnchantments.getEnchantments()) {
                if (enchant.matchesKey(Enchantments.FROST_WALKER)) {
                    frostWalkerLevel = itemEnchantments.getLevel(enchant);
                }
            }
        }
        return frostWalkerLevel;
    }

    /**
     * MovementHelper.java:557-561, the plan-time form ({@code context.frostWalker != 0}). The
     * identity comparison against {@code FrostedIceBlock.getMeltedState()} plus {@code LEVEL == 0}
     * is upstream's: only SOURCE water freezes under a frost walker, not flowing water.
     */
    public static boolean canUseFrostWalker(PlayerEntity player, BlockState state) {
        return frostWalkerLevel(player) != 0
                && state == FrostedIceBlock.getMeltedState()
                && state.get(FluidBlock.LEVEL) == 0;
    }

    /** MovementHelper.java:563-581, the run-time form (reads the state at {@code pos} itself). */
    public static boolean canUseFrostWalker(PlayerEntity player, BlockPos pos) {
        boolean hasFrostWalker = false;
        OUTER:
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantmentsComponent itemEnchantments = player.getEquippedStack(slot).getEnchantments();
            for (RegistryEntry<Enchantment> enchant : itemEnchantments.getEnchantments()) {
                if (enchant.matchesKey(Enchantments.FROST_WALKER)) {
                    hasFrostWalker = true;
                    break OUTER;
                }
            }
        }
        BlockState state = player.getEntityWorld().getBlockState(pos);
        return hasFrostWalker
                && state == FrostedIceBlock.getMeltedState()
                && state.get(FluidBlock.LEVEL) == 0;
    }

    // ------------------------------------------------------------------------------------------
    // mustBeSolidToWalkOn — MovementHelper.java:583-623
    // ------------------------------------------------------------------------------------------

    /**
     * If movements make us stand/walk on this block, will it have a top to walk on?
     *
     * <p>Needed by {@code MovementTraverse.cost}'s {@code standingOnABlock}, which is one of the
     * three backplace vetoes: you cannot sneak-backplace while swimming.
     */
    public static boolean mustBeSolidToWalkOn(WorldView world, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.LADDER || block == Blocks.VINE) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            // used for frostwalker so only includes blocks where we are still on ground when leaving them to any side
            if (block instanceof SlabBlock) {
                if (state.get(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return true;
                }
            } else if (block instanceof StairsBlock) {
                if (state.get(StairsBlock.HALF) == BlockHalf.TOP) {
                    return true;
                }
                StairShape shape = state.get(StairsBlock.SHAPE);
                if (shape == StairShape.INNER_LEFT || shape == StairShape.INNER_RIGHT) {
                    return true;
                }
            } else if (block instanceof TrapdoorBlock) {
                if (!state.get(TrapdoorBlock.OPEN) && state.get(TrapdoorBlock.HALF) == BlockHalf.TOP) {
                    return true;
                }
            } else if (block == Blocks.SCAFFOLDING) {
                return true;
            } else if (block instanceof LeavesBlock) {
                return true;
            }
            if (ASSUME_WALK_ON_WATER) {
                return false;
            }
            Block blockAbove = get(world, x, y + 1, z).getBlock();
            if (blockAbove instanceof FluidBlock) {
                return false;
            }
        }
        return true;
    }

    /** MovementHelper.java:687-690. */
    public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.get(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    // ------------------------------------------------------------------------------------------
    // moveTowards — MovementHelper.java:715-722
    // ------------------------------------------------------------------------------------------

    /**
     * The soft "walk that way" target. NOT interchangeable with a forced one: the target is
     * unforced, and {@code withPitch(playerRotations().getPitch())} PRESERVES the current pitch,
     * which is what keeps the camera level while walking. Aiming a click at this target would
     * raytrace along a pitch nobody chose.
     */
    public static void moveTowards(PlayerEntity player, MovementState state, BlockPos pos) {
        Rotation current = RotationHelper.playerRotations(player);
        state.setTarget(new MovementState.MovementTarget(
                RotationHelper.calcRotationFromVec3d(RotationHelper.playerHead(player),
                        RotationHelper.getBlockPosCenter(pos),
                        current).withPitch(current.getPitch()),
                false
        )).setInput(Input.MOVE_FORWARD, true);
    }

    // ------------------------------------------------------------------------------------------
    // canPlaceAgainst — MovementHelper.java:625-647
    // ------------------------------------------------------------------------------------------

    public static boolean canPlaceAgainst(WorldView world, int x, int y, int z) {
        return canPlaceAgainst(world, x, y, z, get(world, x, y, z));
    }

    public static boolean canPlaceAgainst(WorldView world, BlockPos pos) {
        return canPlaceAgainst(world, pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * MovementHelper.java:637-647 — the ONLY definition of "a face I can click". Deliberately
     * stricter than "has a collision shape": the question is whether looking at the centre of a
     * side face will actually place, and carpet and the like fail that in practice.
     *
     * <p>{@code AltoClefSettings.shouldAvoidPlacingAt} -> {@code PlaceRules.allowedByPolicy}: the
     * protection-only check, NOT {@code PlaceRules.canPlace}, whose replaceable test is about the
     * cell being placed INTO and would reject every solid face here.
     */
    public static boolean canPlaceAgainst(WorldView world, int x, int y, int z, BlockState state) {
        if (!PlaceRules.allowedByPolicy(new BlockPos(x, y, z))) {
            return false;
        }

        if (!worldBorderCanPlaceAt(world, x, z)) {
            return false;
        }
        // can we look at the center of a side face of this block and likely be able to place?
        // (thats how this check is used)
        // therefore dont include weird things that we technically could place against (like carpet) but practically can't
        return isBlockNormalCube(state) || state.getBlock() == Blocks.GLASS || state.getBlock() instanceof StainedGlassBlock;
    }

    // ------------------------------------------------------------------------------------------
    // getMiningDurationTicks — MovementHelper.java:649-685
    // ------------------------------------------------------------------------------------------

    /**
     * Returns 0 when nothing needs breaking, so ONE expression prices a walk and a dig; COST_INF is
     * what makes an unbreakable cell unplannable rather than a run-time surprise.
     */
    public static double getMiningDurationTicks(WorldView world, PlayerEntity player, int x, int y, int z, boolean includeFalling) {
        return getMiningDurationTicks(world, player, x, y, z, get(world, x, y, z), includeFalling);
    }

    public static double getMiningDurationTicks(WorldView world, PlayerEntity player, int x, int y, int z, BlockState state, boolean includeFalling) {
        if (!canWalkThrough(world, x, y, z, state)) {
            if (!state.getFluidState().isEmpty()) {
                return COST_INF;
            }
            double mult = breakCostMultiplierAt(world, x, y, z, state);
            if (mult >= COST_INF) {
                return COST_INF;
            }
            if (avoidBreaking(world, x, y, z, state)) {
                return COST_INF;
            }
            double strVsBlock = strVsBlock(world, player, x, y, z, state);
            if (strVsBlock <= 0) {
                return COST_INF;
            }
            // AltoClefSettings.shouldAvoidBreaking(x,y,z) is folded into avoidBreaking above
            // (BreakRules.canBreak), so the second copy of that check is not repeated here.
            double result = 1 / strVsBlock;
            result += BREAK_BLOCK_ADDITIONAL_COST;
            result *= mult;
            if (includeFalling) {
                BlockState above = get(world, x, y + 1, z);
                if (above.getBlock() instanceof FallingBlock) {
                    result += getMiningDurationTicks(world, player, x, y + 1, z, above, true);
                }
            }
            return result;
        }
        return 0; // we won't actually mine it, so don't check fallings above
    }

    // ------------------------------------------------------------------------------------------
    // switchToBestToolFor — MovementHelper.java:692-713
    // ------------------------------------------------------------------------------------------

    /**
     * AutoTool for a specific block. Upstream picks the best slot out of a precomputed {@code
     * ToolSet} and assigns it ({@code ts.getBestSlot(b.getBlock(), preferSilkTouch)}); tungsten has
     * no ToolSet and never touches the inventory itself, so this defers to the altoclef hook that
     * exists for exactly this job ({@code TungstenModDataContainer.equipToolHook},
     * TungstenModDataContainer.java:30). No hook registered -> no tool switch, which matches
     * baritone with {@code autoTool} off; the movement still mines, just slower.
     *
     * <p>Divergence: the position is a parameter here, because the hook is per-position while
     * upstream's ToolSet needs only the state.
     */
    public static void switchToBestToolFor(PlayerEntity player, BlockPos pos, BlockState state) {
        if (!AUTO_TOOL || ASSUME_EXTERNAL_AUTO_TOOL) {
            return;
        }
        java.util.function.BiConsumer<BlockPos, BlockState> hook = TungstenModDataContainer.equipToolHook;
        if (hook == null) {
            return;
        }
        try {
            hook.accept(pos.toImmutable(), state);
        } catch (Throwable ignored) {
            // an inventory-side failure must not stall the movement that asked for a tool
        }
    }

    /** Convenience overload for callers that only have the position. */
    public static void switchToBestToolFor(PlayerEntity player, WorldView world, BlockPos pos) {
        switchToBestToolFor(player, pos, world.getBlockState(pos));
    }

    // ------------------------------------------------------------------------------------------
    // liquids — MovementHelper.java:731-786
    // ------------------------------------------------------------------------------------------

    /** Water, regardless of whether it is flowing. */
    public static boolean isWater(BlockState state) {
        Fluid f = state.getFluidState().getFluid();
        return f == Fluids.WATER || f == Fluids.FLOWING_WATER;
    }

    public static boolean isWater(WorldView world, BlockPos bp) {
        return isWater(world.getBlockState(bp));
    }

    public static boolean isLava(BlockState state) {
        Fluid f = state.getFluidState().getFluid();
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }

    public static boolean isLiquid(WorldView world, BlockPos p) {
        return isLiquid(world.getBlockState(p));
    }

    public static boolean isLiquid(BlockState blockState) {
        return !blockState.getFluidState().isEmpty();
    }

    public static boolean possiblyFlowing(BlockState state) {
        FluidState fluidState = state.getFluidState();
        return fluidState.getFluid() instanceof FlowableFluid
                && fluidState.getFluid().getLevel(fluidState) != 8;
    }

    /**
     * MovementHelper.java:774-786. Parameter order is upstream's (the state before the world), so
     * copied call sites line up. A full-level fluid still counts as flowing when any of its four
     * horizontal neighbours is partial — that is the check that keeps the bot out of a current.
     */
    public static boolean isFlowing(int x, int y, int z, BlockState state, WorldView world) {
        FluidState fluidState = state.getFluidState();
        if (!(fluidState.getFluid() instanceof FlowableFluid)) {
            return false;
        }
        if (fluidState.getFluid().getLevel(fluidState) != 8) {
            return true;
        }
        return possiblyFlowing(get(world, x + 1, y, z))
                || possiblyFlowing(get(world, x - 1, y, z))
                || possiblyFlowing(get(world, x, y, z + 1))
                || possiblyFlowing(get(world, x, y, z - 1));
    }

    // ------------------------------------------------------------------------------------------
    // isBlockNormalCube — MovementHelper.java:788-804
    // ------------------------------------------------------------------------------------------

    public static boolean isBlockNormalCube(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BambooBlock
                || block instanceof PistonExtensionBlock
                || block instanceof ScaffoldingBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof AmethystClusterBlock) {
            return false;
        }
        try {
            // the (null, null) is upstream's: this overload must not consult the world, because it
            // is called from the search for states that may not be at any position
            return Block.isShapeFullCube(state.getCollisionShape(null, null));
        } catch (Exception ignored) {
            // if we can't get the collision shape, assume it's bad and add to blocksToAvoid
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // attemptToPlaceABlock — MovementHelper.java:806-864
    // ------------------------------------------------------------------------------------------

    /**
     * Three ways to get an aim — already looking at it; a side face whose predicted raytrace lands
     * on the right block AND the right face; nothing — and exactly ONE way to be told "click now":
     * the real crosshair. The two-part face test at :828 ({@code hit.getBlockPos().equals(against1)}
     * and {@code hit.getBlockPos().offset(hit.getSide()).equals(placeAt)}) is what stops the bot
     * from placing into the wrong cell, and it is why nothing here may forge a {@link
     * BlockHitResult}.
     *
     * <p>Note the {@code faceY} formula: {@code (placeAt.y + against1.y + 0.5) * 0.5} — the SIDE
     * face of a neighbour. The backplace in {@code MovementTraverse} uses {@code -1.0} instead,
     * because that is the vertical face between {@code src.down()} and {@code dest.down()}. Two
     * different geometries; do not unify them.
     *
     * @param preferDown keep the LAST matching face instead of the first (the DOWN entry is last in
     *                   {@link #HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP})
     * @param wouldSneak raytrace from the sneaking eye (1.27) and press SNEAK, because that is the
     *                   pose the click will happen in
     */
    public static PlaceResult attemptToPlaceABlock(MovementState state, PlayerEntity player, BlockPos placeAt, boolean preferDown, boolean wouldSneak) {
        WorldView world = player.getEntityWorld();
        double blockReachDistance = RotationHelper.blockReachDistance(player);
        Optional<Rotation> direct = RotationHelper.reachable(player, placeAt, wouldSneak); // we assume that if there is a block there, it must be replacable
        boolean found = false;
        if (direct.isPresent()) {
            state.setTarget(new MovementState.MovementTarget(direct.get(), true));
            found = true;
        }
        for (int i = 0; i < 5; i++) {
            BlockPos against1 = placeAt.offset(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
            if (canPlaceAgainst(world, against1)) {
                if (!selectThrowaway(player, false)) { // get ready to place a throwaway block
                    Debug.logMessage("bb pls get me some blocks. dirt, netherrack, cobble");
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
                double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
                double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
                Rotation place = RotationHelper.calcRotationFromVec3d(wouldSneak ? RotationHelper.inferSneakingEyePosition(player) : RotationHelper.playerHead(player), new Vec3d(faceX, faceY, faceZ), RotationHelper.playerRotations(player));
                Rotation actual = RotationHelper.peekRotation(place);
                HitResult res = RotationHelper.rayTraceTowards(player, actual, blockReachDistance, wouldSneak);
                if (res != null && res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1) && ((BlockHitResult) res).getBlockPos().offset(((BlockHitResult) res).getSide()).equals(placeAt)) {
                    state.setTarget(new MovementState.MovementTarget(place, true));
                    found = true;

                    if (!preferDown) {
                        // if preferDown is true, we want the last option
                        // if preferDown is false, we want the first
                        break;
                    }
                }
            }
        }
        // upstream reads ctx.getSelectedBlock() and ctx.objectMouseOver() separately
        // (MovementHelper.java:840-842); both are the same live raytrace, so trace once
        HitResult mouseOver = RotationHelper.liveHit(player);
        if (mouseOver != null && mouseOver.getType() == HitResult.Type.BLOCK) {
            BlockPos selectedBlock = ((BlockHitResult) mouseOver).getBlockPos();
            Direction side = ((BlockHitResult) mouseOver).getSide();
            // only way for selectedBlock.equals(placeAt) to be true is if it's replacable
            if (selectedBlock.equals(placeAt) || (canPlaceAgainst(world, selectedBlock) && selectedBlock.offset(side).equals(placeAt))) {
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                selectThrowaway(player, true);
                return PlaceResult.READY_TO_PLACE;
            }
        }
        if (found) {
            if (wouldSneak) {
                state.setInput(Input.SNEAK, true);
            }
            selectThrowaway(player, true);
            return PlaceResult.ATTEMPTING;
        }
        return PlaceResult.NO_OPTION;
    }

    public enum PlaceResult {
        READY_TO_PLACE, ATTEMPTING, NO_OPTION;
    }

    // ------------------------------------------------------------------------------------------
    // adapters for baritone services tungsten does not have
    // ------------------------------------------------------------------------------------------

    /**
     * Stand-in for {@code InventoryBehavior.selectThrowawayForLocation(select, x, y, z)}
     * (MovementHelper.java:817, :848, :856).
     *
     * <p>MINIMAL BY DESIGN, and it is the weakest piece of this port: it takes the first {@link
     * BlockItem} stack in the HOTBAR and, when {@code select} is true, makes that the selected slot.
     * Upstream differs in two ways that are recorded rather than faked:
     * <ul>
     *   <li>upstream filters by {@code acceptableThrowawayItems} (dirt / cobble / netherrack) and by
     *       the LOCATION (what the block would look like once placed there,
     *       InventoryBehavior.java:186); this takes any placeable block, so it will happily pave
     *       with a shulker box if that is what the hotbar holds;</li>
     *   <li>upstream can pull a stack out of the main inventory; this cannot, since only the hotbar
     *       is selectable without inventory manipulation. When the hotbar has nothing, it asks the
     *       brain via {@code TungstenModDataContainer.equipBlockHook} — the spec's substitution for
     *       the {@code select == true} case — and re-checks the main hand.</li>
     * </ul>
     * Returning false is not a failure to paper over: it is what turns into {@code NO_OPTION} +
     * {@code UNREACHABLE} at run time and COST_INF at plan time.
     */
    // Package-visible: MovementPillar aims and clicks by hand instead of going through
    // attemptToPlaceABlock, so it needs the same hotbar selector its siblings get for free.
    static boolean selectThrowaway(PlayerEntity player, boolean select) {
        if (player == null || !TungstenConfig.get().allowPlace) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (inventory.getStack(selected).getItem() instanceof BlockItem) {
            return true; // already holding something placeable, nothing to switch
        }
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (select) {
                inventory.setSelectedSlot(slot);
            }
            return true;
        }
        if (select) {
            Runnable hook = TungstenModDataContainer.equipBlockHook;
            if (hook != null) {
                try {
                    hook.run();
                } catch (Throwable ignored) {
                    // the brain failing to equip is a "no blocks" answer, not a crash
                }
                return player.getMainHandStack().getItem() instanceof BlockItem;
            }
        }
        return false;
    }

    /**
     * {@code CalculationContext.breakCostMultiplierAt} — COST_INF when the block is protected,
     * otherwise the configured multiplier. Tungsten's knob is {@code
     * TungstenConfig.breakCostMultiplier} (TungstenConfig.java:140) and its protection policy is
     * {@code BreakRules.canBreak}.
     */
    private static double breakCostMultiplierAt(WorldView world, int x, int y, int z, BlockState state) {
        if (!BreakRules.canBreak(world, new BlockPos(x, y, z), state)) {
            return COST_INF;
        }
        return TungstenConfig.get().breakCostMultiplier;
    }

    /**
     * {@code context.toolSet.getStrVsBlock(state)} — per-tick break progress, so {@code 1 /
     * strVsBlock} is the duration in ticks. Uses the CURRENTLY HELD item (there is no ToolSet in
     * tungsten), same as {@code BlockNode.breakTicks} (BlockNode.java:725-731).
     */
    private static double strVsBlock(WorldView world, PlayerEntity player, int x, int y, int z, BlockState state) {
        if (player == null) {
            return 0; // no player, no tool, no dig -> COST_INF at the call site
        }
        return state.calcBlockBreakingDelta(player, world, new BlockPos(x, y, z));
    }

    /**
     * {@code bsi.worldBorder.canPlaceAt(x, z)} — body copied from {@code
     * BetterWorldBorder.canPlaceAt} (baritone/utils/pathing/BetterWorldBorder.java:44-49): moved in
     * one block on all sides, because you cannot place at the very edge against a block outside the
     * border (vanilla refuses the right click).
     */
    // Public: also consulted directly by PlaceRules.canPlace, which cannot reach a place move
    // through avoidBreaking (that path is break-only) and had no border check of its own.
    public static boolean worldBorderCanPlaceAt(WorldView world, int x, int z) {
        if (world == null) {
            return true;
        }
        net.minecraft.world.border.WorldBorder border = world.getWorldBorder();
        double minX = border.getBoundWest();
        double maxX = border.getBoundEast();
        double minZ = border.getBoundNorth();
        double maxZ = border.getBoundSouth();
        return x > minX && x + 1 < maxX && z > minZ && z + 1 < maxZ;
    }

    /**
     * {@code bsi.worldContainsLoadedChunk(x, z)} — baritone asks its own chunk cache, because it
     * plans through packed chunks whose snow layers have no metadata. Tungsten reads the live world,
     * so this is the world's own answer. ({@code WorldView.isChunkLoaded} is deprecated in 1.21.11
     * and still the only direct answer; it is only ever consulted for snow layers.)
     */
    private static boolean worldContainsLoadedChunk(WorldView world, int x, int z) {
        return world != null && world.isChunkLoaded(x >> 4, z >> 4);
    }
}
