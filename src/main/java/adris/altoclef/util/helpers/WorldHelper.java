package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.mixins.ClientConnectionAccessor;
import adris.altoclef.multiversion.DimensionVer;
import adris.altoclef.multiversion.MethodWrapper;
import adris.altoclef.multiversion.world.WorldVer;
import adris.altoclef.util.Dimension;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.ChestType;
import kaptainwutax.tungsten.path.movements.MovementHelperB;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.ClientConnection;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

//#if MC >= 11802
import net.minecraft.registry.entry.RegistryEntry;
//#endif
//#if MC <= 12006
//$$ import adris.altoclef.mixins.EntityAccessor;
//#endif

import java.util.*;

/**
 * Super useful helper functions for getting information about the world.
 */
public interface WorldHelper {

    /**
     * Is this block lava? Asked of the STATE, with no pathfinder attached.
     *
     * <p>Part of G-0, cutting altoclef's baritone imports: this was
     * {@code baritone.pathing.movement.MovementHelper.isLava}, a one-line vanilla check wearing a
     * pathfinder's coat -- two task files pulled in the whole of MovementHelper for it. The fluid
     * TAG is what the game itself tests, so flowing lava counts exactly as it should.
     */
    static boolean isLavaState(net.minecraft.block.BlockState state) {
        return state != null && state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA);
    }

    /** Is this block water? The same one-line check, for the same reason. */
    static boolean isWaterState(net.minecraft.block.BlockState state) {
        return state != null && state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }


    /**
     * Get the number of in-game ticks the game/world has been active for.
     */
    static int getTicks() {
        ClientConnection con = Objects.requireNonNull(MinecraftClient.getInstance().getNetworkHandler()).getConnection();
        return ((ClientConnectionAccessor) con).getTicks();
    }

    static Vec3d toVec3d(BlockPos pos) {
        if (pos == null) return null;
        return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    static Vec3d toVec3d(Vec3i pos) {
        return new Vec3d(pos.getX(), pos.getY(), pos.getZ());
    }

    static Vec3i toVec3i(Vec3d pos) {
        return new Vec3i((int) pos.getX(), (int) pos.getY(), (int) pos.getZ());
    }

    static BlockPos toBlockPos(Vec3d pos) {
        return new BlockPos((int) pos.getX(), (int) pos.getY(), (int) pos.getZ());
    }

    static boolean isSourceBlock(BlockPos pos, boolean onlyAcceptStill) {
        ClientWorld world = AltoClef.getInstance().getWorld();

        BlockState s = world.getBlockState(pos);
        if (s.getBlock() instanceof FluidBlock) {
            // Only accept still fluids.
            if (!s.getFluidState().isStill() && onlyAcceptStill) return false;
            int level = s.getFluidState().getLevel();
            // Ignore if there's liquid above, we can't tell if it's a source block or not.
            BlockState above = world.getBlockState(pos.up());
            if (above.getBlock() instanceof FluidBlock) return false;
            return level == 8;
        }
        return false;
    }

    static double distanceXZSquared(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        return (delta.x * delta.x) + (delta.z * delta.z);
    }

    static double distanceXZ(Vec3d from, Vec3d to) {
        return Math.sqrt(distanceXZSquared(from, to));
    }

    static boolean inRangeXZ(Vec3d from, Vec3d to, double range) {
        return distanceXZSquared(from, to) < range * range;
    }

    static boolean inRangeXZ(BlockPos from, BlockPos to, double range) {
        return inRangeXZ(toVec3d(from), toVec3d(to), range);
    }

    static boolean inRangeXZ(Entity entity, Vec3d to, double range) {
        return inRangeXZ(entity.getPos(), to, range);
    }

    static boolean inRangeXZ(Entity entity, BlockPos to, double range) {
        return inRangeXZ(entity, toVec3d(to), range);
    }

    static boolean inRangeXZ(Entity entity, Entity to, double range) {
        return inRangeXZ(entity, to.getPos(), range);
    }

    static Dimension getCurrentDimension() {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return Dimension.OVERWORLD;
        if (DimensionVer.isUltrawarm(world.getDimension())) return Dimension.NETHER;
        if (DimensionVer.isNatural(world.getDimension())) return Dimension.OVERWORLD;
        return Dimension.END;
    }

    /**
     * WARNING: this method checks if the block at the given position is a SOLID BLOCK
     * things like ice, dirtPaths, soulSand... don't count into this
     * if you just want to check if a block is solid use `BlockState.isSolid()`
     * (which includes more variety of blocks including the mentioned ones, signs, pressure plates...)
     *
     * better method for blocks that can be walked on should be created instead
     */
    static boolean isSolidBlock(BlockPos pos) {
        ClientWorld world = AltoClef.getInstance().getWorld();

        return world.getBlockState(pos).isSolidBlock(world, pos);
    }

    /**
     * Get the "head" of a block with a bed, if the block is a bed.
     */
    static BlockPos getBedHead(BlockPos posWithBed) {
        ClientWorld world = AltoClef.getInstance().getWorld();

        BlockState state = world.getBlockState(posWithBed);
        if (state.getBlock() instanceof BedBlock) {
            Direction facing = state.get(BedBlock.FACING);
            if (world.getBlockState(posWithBed).get(BedBlock.PART).equals(BedPart.HEAD)) {
                return posWithBed;
            }
            return posWithBed.offset(facing);
        }
        return null;
    }

    /**
     * Get the "foot" of a block with a bed, if the block is a bed.
     */
    static BlockPos getBedFoot(BlockPos posWithBed) {
        ClientWorld world = AltoClef.getInstance().getWorld();

        BlockState state = world.getBlockState(posWithBed);
        if (state.getBlock() instanceof BedBlock) {
            Direction facing = state.get(BedBlock.FACING);
            if (world.getBlockState(posWithBed).get(BedBlock.PART).equals(BedPart.FOOT)) {
                return posWithBed;
            }
            return posWithBed.offset(facing.getOpposite());
        }
        return null;
    }

    // Get the left side of a chest, given a block pos.
    // Used to consistently identify whether a double chest is part of the same chest.
    static BlockPos getChestLeft(BlockPos posWithChest) {
        BlockState state = AltoClef.getInstance().getWorld().getBlockState(posWithChest);

        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.get(ChestBlock.CHEST_TYPE);
            if (type == ChestType.SINGLE || type == ChestType.LEFT) {
                return posWithChest;
            }
            Direction facing = state.get(ChestBlock.FACING);
            return posWithChest.offset(facing.rotateYCounterclockwise());
        }
        return null;
    }

    static boolean isChestBig(BlockPos posWithChest) {
        BlockState state = AltoClef.getInstance().getWorld().getBlockState(posWithChest);
        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.get(ChestBlock.CHEST_TYPE);
            return (type == ChestType.RIGHT || type == ChestType.LEFT);
        }
        return false;
    }

    static int getGroundHeight(int x, int z) {
        ClientWorld world = AltoClef.getInstance().getWorld();

        for (int y = world.getTopY(); y >= world.getBottomY(); --y) {
            BlockPos check = new BlockPos(x, y, z);
            if (isSolidBlock(check)) return y;
        }
        return -1;
    }

    static BlockPos getADesertTemple() {
        ClientWorld world = AltoClef.getInstance().getWorld();

        List<BlockPos> stonePressurePlates = AltoClef.getInstance().getBlockScanner().getKnownLocations(Blocks.STONE_PRESSURE_PLATE);
        if (!stonePressurePlates.isEmpty()) {
            for (BlockPos pos : stonePressurePlates) {
                if (world.getBlockState(pos).getBlock() == Blocks.STONE_PRESSURE_PLATE && // Duct tape
                        world.getBlockState(pos.down()).getBlock() == Blocks.CUT_SANDSTONE &&
                        world.getBlockState(pos.down(2)).getBlock() == Blocks.TNT) {
                    return pos;
                }
            }
        }
        return null;
    }

    static boolean isUnopenedChest(BlockPos pos) {
        return AltoClef.getInstance().getItemStorage().getContainerAtPosition(pos).isEmpty();
    }

    static int getGroundHeight(int x, int z, Block... groundBlocks) {
        ClientWorld world = AltoClef.getInstance().getWorld();

        Set<Block> possibleBlocks = new HashSet<>(Arrays.asList(groundBlocks));
        for (int y = world.getTopY(); y >= world.getBottomY(); --y) {
            BlockPos check = new BlockPos(x, y, z);
            if (possibleBlocks.contains(world.getBlockState(check).getBlock())) return y;

        }
        return -1;
    }

    /** Which term of {@link #canBreak} refused, counted by name. Read as cb=hard/avoid/plaus/reach. */
    final class BreakStats {
        public static volatile int cbHardness, cbAvoid, cbPlausible, cbReach;
        private BreakStats() {}
    }

    static boolean canBreak(BlockPos pos) {
        AltoClef altoClef = AltoClef.getInstance();

        // JANK: Temporarily check if we can break WITHOUT paused interactions.
        // Not doing this creates bugs where we loop back and forth through the nether portal and stuff.
        boolean prevInteractionPaused = altoClef.getExtraBaritoneSettings().isInteractionPaused();

        altoClef.getExtraBaritoneSettings().setInteractionPaused(false);

        // SPLIT INTO FOUR COUNTERS, BECAUSE FOUR THEORIES HAVE ALREADY BEEN WRONG TODAY.
        // The block filter rejects every candidate on a failing chop_canopy run -- scan=0/0/18384
        // against scan=914/0/0 when the bot is near its target -- and reading picked the wrong
        // term once already (canReach, which the unreachable counter had shown returns true).
        // One run with these will name the term instead.
        boolean okHardness = altoClef.getWorld().getBlockState(pos).getHardness(altoClef.getWorld(), pos) >= 0;
        // ⛔ AND THE BLOCK-TYPE LIST, WHICH G-0 ORPHANED. avoidBreaking(Block...) is altoclef's
        // own API -- the nether portal, an iron golem's blocks -- and its list used to be consulted
        // by the deleted pathfinder. Moving the storage into AltoClef kept the setters working and
        // left the list with NO READER, so 'do not break this' silently stopped meaning anything.
        // Craft fell from 22/22 to 15/22 after that change, with mine_coal, mine_diamond,
        // pickup_flat and wander_recovery among the failures.
        boolean okAvoid = !altoClef.getExtraBaritoneSettings().shouldAvoidBreaking(pos)
                && !altoClef.shouldAvoidBreaking(altoClef.getWorld().getBlockState(pos).getBlock());
        boolean okPlausible = okHardness && okAvoid && plausibleToBreak(altoClef.getWorld(), pos);
        boolean okReach = okPlausible && canReach(pos);
        if (!okHardness) {
            BreakStats.cbHardness++;
        } else if (!okAvoid) {
            BreakStats.cbAvoid++;
        } else if (!okPlausible) {
            BreakStats.cbPlausible++;
        } else if (!okReach) {
            BreakStats.cbReach++;
        }
        boolean canBreak = okReach;

        altoClef.getExtraBaritoneSettings().setInteractionPaused(prevInteractionPaused);

        return canBreak;
    }

    /**
     * Is breaking this block even worth planning around?
     *
     * <p>{@code MineProcess.plausibleToBreak}, ported (G-0). It answered the same question with a
     * whole CalculationContext built per call — a pathfinder object constructed to ask "is this
     * minable", from {@link #canBreak} which the block scanner runs over every candidate.
     *
     * <p>Unchanged in substance: portal frames, the portal itself and lava are always worth
     * breaking through (upstream's special cases), anything whose mining time is infinite is not,
     * and bedrock both above and below means we could never get at it anyway.
     */
    private static boolean plausibleToBreak(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.block.EndPortalFrameBlock
                || state.getBlock() instanceof net.minecraft.block.EndPortalBlock
                || state.getBlock() == Blocks.LAVA) {
            return true;
        }
        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();
        double ticks = MovementHelperB.getMiningDurationTicks(world, player, pos.getX(), pos.getY(),
                pos.getZ(), state, true);
        if (!Double.isFinite(ticks)
                || ticks >= kaptainwutax.tungsten.path.calculators.ActionCosts.COST_INF) {
            return false;
        }
        return !(world.getBlockState(pos.up()).getBlock() == Blocks.BEDROCK
                && world.getBlockState(pos.down()).getBlock() == Blocks.BEDROCK);
    }

    static boolean isInNetherPortal() {
        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();

        if (player == null)
            return false;
        return adris.altoclef.multiversion.entity.EntityHelper.isInNetherPortal(player);
    }

    static boolean dangerousToBreakIfRightAbove(BlockPos toBreak) {
        AltoClef altoClef = AltoClef.getInstance();

        // There might be mumbo jumbo next to it, we fall and we get killed by lava or something.
        if (MovementHelperB.avoidBreaking(altoClef.getWorld(), toBreak.getX(), toBreak.getY(),
                toBreak.getZ(), altoClef.getWorld().getBlockState(toBreak))) {
            return true;
        }
        // Fall down
        for (int dy = 1; dy <= toBreak.getY() - altoClef.getWorld().getBottomY(); ++dy) {
            BlockPos check = toBreak.down(dy);
            BlockState s = altoClef.getWorld().getBlockState(check);
            // G-0: maxFallHeightNoWater was the legacy engine's limit; vanilla takes no damage
            // below four blocks, which is the number this check always wanted.
            boolean tooFarToFall = dy > 3;
            // Don't fall in lava
            if (isLavaState(s))
                return true;
            // Always fall in water
            // TODO: If there's a 1 meter thick layer of water and then a massive drop below, the bot will think it is safe.
            if (isWaterState(s))
                return true;
            // We hit ground, depends
            if (WorldHelper.isSolidBlock(check)) {
                return tooFarToFall;
            }
        }
        // At this point we probably fall through the void, so not safe.
        return true;
    }

    static boolean canPlace(BlockPos pos) {
        return !AltoClef.getInstance().getExtraBaritoneSettings().shouldAvoidPlacingAt(pos)
                && canReach(pos);
    }

    /**
     * Can a block be placed against this one -- i.e. can we look at the centre of one of its side
     * faces and expect the placement to take?
     *
     * <p>Ported off baritone's MovementHelper.canPlaceAgainst, which is three checks and no more:
     * altoclef's own avoid-list, the world border, and "is this a full cube (or glass)". None of
     * the three needs baritone -- the avoid-list is ours, the border is on the vanilla world, and
     * the cube test is a vanilla collision shape. The only thing the baritone version added was a
     * BlockStateInterface to read the state through, and the world reads it just as well.
     *
     * <p>The exclusion list is kept verbatim rather than trusted to the collision shape: bamboo,
     * piston heads, scaffolding, shulker boxes, dripstone and amethyst can all report a full cube
     * in some state while being useless to place against in practice.
     *
     * @see #canPlace(BlockPos) which asks a different question -- whether we may place AT a
     *      position and can reach it -- and is not interchangeable with this one.
     */
    static boolean canPlaceAgainst(BlockPos pos) {
        if (AltoClef.getInstance().getExtraBaritoneSettings().shouldAvoidPlacingAt(pos)) return false;

        net.minecraft.world.World world = AltoClef.getInstance().getWorld();
        if (world == null) return false;
        if (!world.getWorldBorder().contains(pos)) return false;

        net.minecraft.block.BlockState state = world.getBlockState(pos);
        net.minecraft.block.Block block = state.getBlock();
        if (block instanceof net.minecraft.block.BambooBlock
                || block instanceof net.minecraft.block.PistonExtensionBlock
                || block instanceof net.minecraft.block.ScaffoldingBlock
                || block instanceof net.minecraft.block.ShulkerBoxBlock
                || block instanceof net.minecraft.block.PointedDripstoneBlock
                || block instanceof net.minecraft.block.AmethystClusterBlock) {
            return false;
        }
        if (block == net.minecraft.block.Blocks.GLASS
                || block instanceof net.minecraft.block.StainedGlassBlock) {
            return true;
        }
        try {
            return net.minecraft.block.Block.isShapeFullCube(state.getCollisionShape(world, pos));
        } catch (Exception ignored) {
            // A state that cannot report a shape is one we should not lean a block against.
            return false;
        }
    }

    static boolean canReach(BlockPos pos) {
        AltoClef altoClef = AltoClef.getInstance();

        if (altoClef.getModSettings().shouldAvoidOcean()) {
            // 45 is roughly the ocean floor. We add 2 just cause why not.
            // This > 47 can clearly cause a stuck bug.
            if (altoClef.getPlayer().getY() > 47 && altoClef.getChunkTracker().isChunkLoaded(pos) && isOcean(altoClef.getWorld().getBiome(pos))) { // But if we stuck, add more oceans
                // Block is in an ocean biome. If it's below sea level...
                if (pos.getY() < 64 && getGroundHeight(pos.getX(), pos.getZ(), Blocks.WATER) > pos.getY()) {
                    return false;
                }
            }
        }
        return !altoClef.getBlockScanner().isUnreachable(pos);
    }

    //#if MC >= 11802
    static boolean isOcean(RegistryEntry<Biome> b) {
    //#else
    //$$ static boolean isOcean(Biome b) {
    //#endif
        return (WorldVer.isBiome(b,BiomeKeys.OCEAN)
                || WorldVer.isBiome(b,BiomeKeys.COLD_OCEAN)
                || WorldVer.isBiome(b,BiomeKeys.DEEP_COLD_OCEAN)
                || WorldVer.isBiome(b,BiomeKeys.DEEP_OCEAN)
                || WorldVer.isBiome(b,BiomeKeys.DEEP_FROZEN_OCEAN)
                || WorldVer.isBiome(b,BiomeKeys.DEEP_LUKEWARM_OCEAN)
                || WorldVer.isBiome(b,BiomeKeys.LUKEWARM_OCEAN)
                || WorldVer.isBiome(b,BiomeKeys.WARM_OCEAN)
                || WorldVer.isBiome(b,BiomeKeys.FROZEN_OCEAN));
    }

    static boolean isAir(BlockPos pos) {
        return AltoClef.getInstance().getBlockScanner().isBlockAtPosition(pos, Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR);
        //return state.isAir() || isAir(state.getBlock());
    }

    static boolean isAir(Block block) {
        return block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR;
    }

    static boolean isHellHole(BlockPos pos) {
        int x = pos.getX();
        int yThis = pos.getY();
        int z = pos.getZ();
        int count = 0;
        for (int y = yThis; y >= -64; --y) {
            BlockPos check = new BlockPos(x, y, z);
            count++;
            if (count > 40) return true;
            if (!isAir(check)) return false;
        }
        return true;
    }

    // Reliable overload: reads world directly (not BlockScanner) so deep/unloaded blocks are detected.
    static boolean isHellHole(AltoClef mod, BlockPos pos) {
        int x = pos.getX(), z = pos.getZ();
        int count = 0;
        for (int y = pos.getY(); y >= -64; y--) {
            net.minecraft.block.BlockState state = mod.getWorld().getBlockState(new BlockPos(x, y, z));
            if (!state.isAir()) return false;
            if (++count > 40) return true;
        }
        return true;
    }

    // Returns true when the area around pos is dangerous (void/lava below or very few supporting blocks).
    /**
     * ⛔ READ BEFORE TRUSTING THIS. Two real weaknesses, and one earlier claim about it withdrawn.
     *
     * <p>WITHDRAWN: this was noted during the mob_skeleton work as having a "one-block-down bug".
     * Reading it, that is WRONG -- the scan is at {@code y - 1}, which is the floor beneath the
     * feet position its callers pass ({@code getPlayer().getBlockPos()}). The note is retracted
     * rather than left to mislead someone.
     *
     * <p>WHAT IS REAL: {@code !isAir()} counts WATER and TALL GRASS as floor, the same weakness
     * {@code Nav.isSafeToCancel} carries -- lava is at least handled by the explicit test above it.
     * And the threshold is very lenient: 4 or fewer solid cells out of 25 before anywhere counts as
     * dangerous, so a three-wide bridge scores about 15 and never registers. Whether that leniency
     * is wrong depends on the caller -- the dodge branch uses it to choose between a pathfinding
     * dodge and a raw sidestep, and a bridge arguably IS the case that wants the careful one.
     *
     * <p>Neither is fixed here. Both want a course with a real drop to measure against, and this
     * predicate feeds behaviour on courses that currently have none.
     */
    static boolean isDangerZone(AltoClef mod, BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int safeBlockCount = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos checkPos = new BlockPos(x + dx, y - 1, z + dz);
                if (mod.getWorld().getBlockState(checkPos).getBlock() == Blocks.LAVA) return true;
                if (isHellHole(mod, checkPos)) return true;
                if (!mod.getWorld().getBlockState(checkPos).isAir()) safeBlockCount++;
            }
        }
        return safeBlockCount <= 4;
    }

    static boolean isInteractableBlock(BlockPos pos) {
        Block block = AltoClef.getInstance().getWorld().getBlockState(pos).getBlock();
        return (block instanceof ChestBlock
                || block instanceof EnderChestBlock
                || block instanceof CraftingTableBlock
                || block instanceof AbstractFurnaceBlock
                || block instanceof LoomBlock
                || block instanceof CartographyTableBlock
                || block instanceof EnchantingTableBlock
                || block instanceof RedstoneOreBlock
                || block instanceof BarrelBlock
        );
    }

    static boolean isInsidePlayer(BlockPos pos) {
        return pos.isWithinDistance(AltoClef.getInstance().getPlayer().getPos(), 2);
    }

    static Iterable<BlockPos> getBlocksTouchingPlayer() {
        return getBlocksTouchingBox(AltoClef.getInstance().getPlayer().getBoundingBox());
    }

    static Iterable<BlockPos> getBlocksTouchingBox(Box box) {
        BlockPos min = new BlockPos((int) box.minX, (int) box.minY, (int) box.minZ);
        BlockPos max = new BlockPos((int) box.maxX, (int) box.maxY, (int) box.maxZ);
        return scanRegion(min, max);
    }

    static Iterable<BlockPos> scanRegion(BlockPos start, BlockPos end) {
        return () -> new Iterator<>() {
            int x = start.getX(), y = start.getY(), z = start.getZ();

            @Override
            public boolean hasNext() {
                return y <= end.getY() && z <= end.getZ() && x <= end.getX();
            }

            @Override
            public BlockPos next() {
                BlockPos result = new BlockPos(x, y, z);
                ++x;
                if (x > end.getX()) {
                    x = start.getX();
                    ++z;
                    if (z > end.getZ()) {
                        z = start.getZ();
                        ++y;
                    }
                }
                return result;
            }
        };
    }

    static boolean fallingBlockSafeToBreak(BlockPos pos) {
        World w = MinecraftClient.getInstance().world;
        assert w != null;
        while (isFallingBlock(pos)) {
            if (MovementHelperB.avoidBreaking(w, pos.getX(), pos.getY(), pos.getZ(), w.getBlockState(pos)))
                return false;
            pos = pos.up();
        }
        return true;
    }

    static boolean isFallingBlock(BlockPos pos) {
        World w = MinecraftClient.getInstance().world;
        assert w != null;
        return w.getBlockState(pos).getBlock() instanceof FallingBlock;
    }

    static Entity getSpawnerEntity(BlockPos pos) {
        ClientWorld world = AltoClef.getInstance().getWorld();

        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof SpawnerBlock) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof MobSpawnerBlockEntity blockEntity) {
                return MethodWrapper.getRenderedEntity(blockEntity.getLogic(), world, pos);
            }
        }
        return null;
    }

    static Vec3d getOverworldPosition(Vec3d pos) {
        if (getCurrentDimension() == Dimension.NETHER) {
            pos = pos.multiply(8.0, 1, 8.0);
        }
        return pos;
    }

    static BlockPos getOverworldPosition(BlockPos pos) {
        if (getCurrentDimension() == Dimension.NETHER) {
            pos = new BlockPos(pos.getX() * 8, pos.getY(), pos.getZ() * 8);
        }
        return pos;
    }

    static boolean isChest(BlockPos block) {
        Block b = AltoClef.getInstance().getWorld().getBlockState(block).getBlock();
        return isChest(b);
    }

    static boolean isChest(Block b) {
        return b instanceof ChestBlock || b instanceof EnderChestBlock;
    }

    static boolean isBlock(BlockPos pos, Block block) {
        return AltoClef.getInstance().getWorld().getBlockState(pos).getBlock() == block;
    }

    static boolean canSleep() {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world != null) {
            // You can sleep during thunderstorms
            if (world.isThundering() && world.isRaining())
                return true;

            int time = getTimeOfDay();
            // https://minecraft.fandom.com/wiki/Daylight_cycle
            return 12542 <= time && time <= 23992;
        }

        return false;
    }

    static int getTimeOfDay() {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world != null) {
            // You can sleep during thunderstorms
            return (int) (world.getTimeOfDay() % 24000);
        }
        return 0;
    }

    static boolean isVulnerable() {
        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();

        int armor = player.getArmor();
        float health = player.getHealth();

        if (armor <= 15 && health < 3) return true;
        if (armor < 10 && health < 10) return true;

        return armor < 5 && health < 18;
    }

    static boolean isSurroundedByHostiles() {
        List<LivingEntity> hostiles = AltoClef.getInstance().getEntityTracker().getHostiles();
        return isSurrounded(hostiles);
    }

    // Function to check if the player is surrounded on two or more sides
    static boolean isSurrounded(List<LivingEntity> entities) {
        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();

        BlockPos playerPos = player.getBlockPos();

        // Minimum number of sides to consider the origin surrounded
        final int MIN_SIDES_TO_SURROUND = 2;

        // Count the number of unique sides based on angles
        List<Direction> uniqueSides =  new ArrayList<Direction>();

        // Iterate through each point and calculate the angle with the origin
        for (Entity entity : entities) {
            if(!entity.isInRange(player, 8)) continue;
            BlockPos entityPos = entity.getBlockPos();
            double angle = calculateAngle(playerPos, entityPos);

            // Check if the angle is unique
            boolean isUnique = !uniqueSides.contains(getHorizontalDirectionFromYaw(angle));

            // If the angle is unique, increment the uniqueSides count
            if (isUnique) {
                uniqueSides.add(getHorizontalDirectionFromYaw(angle));
            }
        }

        // Check if the origin is surrounded on two or more sides
        return uniqueSides.size() >= MIN_SIDES_TO_SURROUND;
    }

    private static double calculateAngle(BlockPos origin, BlockPos target) {
        double translatedX = target.getX() - origin.getX();
        double translatedZ = target.getZ() - origin.getZ();
        double angleRad = Math.atan2(translatedZ, translatedX);
        double angleDeg = Math.toDegrees(angleRad);
        angleDeg -= 90;
        if (angleDeg < 0) {
            angleDeg += 360;
        }
        return angleDeg;
    }

    private static Direction getHorizontalDirectionFromYaw(double yaw) {
        yaw %= 360.0F;
        if (yaw < 0) {
            yaw += 360.0F;
        }

        if ((yaw >= 45 && yaw < 135) || (yaw >= -315 && yaw < -225)) {
            return Direction.WEST;
        } else if ((yaw >= 135 && yaw < 225) || (yaw >= -225 && yaw < -135)) {
            return Direction.NORTH;
        } else if ((yaw >= 225 && yaw < 315) || (yaw >= -135 && yaw < -45)) {
            return Direction.EAST;
        } else {
            return Direction.SOUTH;
        }
    }


}
