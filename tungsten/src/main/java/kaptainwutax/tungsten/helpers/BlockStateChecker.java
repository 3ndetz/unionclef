package kaptainwutax.tungsten.helpers;

import static kaptainwutax.tungsten.path.blockSpaceSearchAssist.Ternary.NO;
import static kaptainwutax.tungsten.path.blockSpaceSearchAssist.Ternary.YES;

import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.Ternary;
import net.minecraft.block.AirBlock;
import net.minecraft.block.AzaleaBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FireBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SkullBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.WallShape;
import net.minecraft.fluid.Fluids;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

/**
 * Helper class to easily check block state.
 */
public class BlockStateChecker {
	
	public static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) { // early return for most common case
            return YES;
        }
        // exceptions - blocks that are isPassable true, but we can't actually jump through
        if (block instanceof FireBlock
                || block == Blocks.TRIPWIRE
                || block == Blocks.COBWEB
                || block == Blocks.VINE
                || block == Blocks.LADDER
                || block == Blocks.COCOA
                || block instanceof AzaleaBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || !state.getFluidState().isEmpty()
                || block instanceof TrapdoorBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                || block instanceof ShulkerBoxBlock) {
            return NO;
        }
        return YES;
    }
	
	/**
     * Checks if a block is connected to another of its type.
     * 
     * @param pos Position of the block
     * @return true if a block is connected to another of its type. Example: fence to fence
     */
	public static boolean isConnected(BlockPos pos, WorldView world) {
	    BlockState state = world.getBlockState(pos);
	    Block block = state.getBlock();

	    // Check for Fence connections
	    if (block instanceof FenceBlock) {
	        return isFenceConnected(state, world, pos);
	    }

	    // Check for Wall connections
	    if (block instanceof WallBlock) {
	        return isWallConnected(state);
	    }

	    // Check for Glass Pane connections
	    if (block instanceof PaneBlock) {
	        return isGlassPaneConnected(state, world, pos);
	    }

	    return false;
	}

	/**
     * Checks if a block is connected to another of its type.
     * 
     * @param state BlockState for this block
     * @param world
     * @param pos Position of the block
     * @return true if a block is connected to another of its type. Example: fence to fence
     */
	public static boolean isFenceConnected(BlockState state, WorldView world, BlockPos pos) {
	    return state.get(Properties.NORTH) && isFence(world, pos.north())
	        || state.get(Properties.SOUTH) && isFence(world, pos.south())
	        || state.get(Properties.EAST) && isFence(world, pos.east())
	        || state.get(Properties.WEST) && isFence(world, pos.west());
	}

	public static boolean isFence(WorldView world, BlockPos pos) {
	    Block block = world.getBlockState(pos).getBlock();
	    return block instanceof FenceBlock;
	}

	/**
     * Checks if a wall block is connected to anything.
     * 
     * @param state BlockState for this block
     * @return true if wall block is connected to anything.
     */
	public static boolean isWallConnected(BlockState state) {
	    return state.get(Properties.NORTH_WALL_SHAPE) != WallShape.NONE
	        || state.get(Properties.SOUTH_WALL_SHAPE) != WallShape.NONE
	        || state.get(Properties.EAST_WALL_SHAPE) != WallShape.NONE
	        || state.get(Properties.WEST_WALL_SHAPE) != WallShape.NONE;
	}

	public static boolean isGlassPaneConnected(BlockState state, WorldView world, BlockPos pos) {
	    return state.get(Properties.NORTH)
	        || state.get(Properties.SOUTH)
	        || state.get(Properties.EAST)
	        || state.get(Properties.WEST);
	}

	public static boolean isPane(WorldView world, BlockPos pos) {
	    Block block = world.getBlockState(pos).getBlock();
	    return block instanceof PaneBlock;
	}
	
	public static boolean isSlab(WorldView world, BlockPos pos) {
	    return world.getBlockState(pos).getBlock() instanceof SlabBlock;
	}

	/**
     * Checks if a block is a double slab.
     * 
     * @param world
     * @param pos Position of the block
     * @return true if a block is a double slab.
     */
	public static boolean isDoubleSlab(WorldView world, BlockPos pos) {
	    BlockState state = world.getBlockState(pos);
	    Block block = state.getBlock();
	    return block instanceof SlabBlock && state.get(Properties.SLAB_TYPE) == SlabType.DOUBLE;
	}
	
	public static boolean isTrapdoor(BlockState state) {
        return state.getBlock() instanceof TrapdoorBlock;
    }
	
	public static boolean isTrapdoor(Block block) {
        return block instanceof TrapdoorBlock;
    }

    // Helper method to check if the block is an open trapdoor
	public static boolean isOpenTrapdoor(BlockState state) {
        return isTrapdoor(state) && state.get(Properties.OPEN);
    }
	
	public static boolean isClosedBottomTrapdoor(BlockState state) {
        return isTrapdoor(state) && state.get(Properties.BLOCK_HALF) == BlockHalf.BOTTOM && !state.get(Properties.OPEN);
    }

    // Helper method to check if the block is a bottom slab
	public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock && state.get(Properties.SLAB_TYPE) == SlabType.BOTTOM;
    }
	
    // Helper method to check if the block is a top slab
	public static boolean isTopSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock && state.get(Properties.SLAB_TYPE) == SlabType.TOP;
    }
	
    // Helper method to check if the block contains water
	public static boolean isWater(BlockState state) {
        return state.isOf(Blocks.WATER) || state.getFluidState().isOf(Fluids.WATER);
    }
	
    // Helper method to check if the block is flowing water
	public static boolean isFlowingWater(BlockState state) {
        return state.getFluidState().isOf(Fluids.FLOWING_WATER);
    }
	
	// Helper method to check if the block is water. Either flowing or source
	public static boolean isAnyWater(BlockState state) {
        return isWater(state) || isFlowingWater(state);
    }
	

    // Helper method to check if the block is water logged
	public static boolean isWaterLogged(BlockState state) {
        return state.getProperties().contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED) == true;
    }

    // Helper method to check if the block is lava
	static boolean isLava(BlockState state) {
        return state.isOf(Blocks.LAVA) || state.getFluidState().isOf(Fluids.LAVA);
    }

    // Helper method to check if the block is flowing lava
	static boolean isFlowingLava(BlockState state) {
        return state.getFluidState().isOf(Fluids.FLOWING_LAVA);
    }

	// Helper method to check if the block is lava. Either flowing or source
	static boolean isAnyLava(BlockState state) {
        return isLava(state) || isFlowingLava(state);
    }


}
