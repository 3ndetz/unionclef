package kaptainwutax.tungsten.helpers;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

/**
 * Helper class for a few standalone movement predicates.
 *
 * <p>⛔ REMOVED 2026-09-05, ~800 LINES OF DEAD CODE: this file used to also carry
 * {@code canWalkOnBlockState}, {@code wasCleared} (two overloads) and {@code isNeoPossible} (two
 * overloads) — together the bulk of the file. Grepped every call site in the whole codebase
 * (`src/main` and `tungsten/src/main`): none of the three had a single external caller.
 * {@code wasCleared} only called its own overload and {@code isNeoPossible}; {@code isNeoPossible}
 * was only called from {@code wasCleared}; {@code canWalkOnBlockState} had no caller at all (the
 * identically-named method in {@code MovementHelperB} is a separate, unrelated method in a
 * different class, not a call to this one).
 *
 * <p>The block-space search does NOT go through any of this. {@code BlockNode.wasCleared()} — a
 * same-named but entirely separate static method in {@code BlockNode} itself — is what every real
 * caller in {@code BlockNode.java} actually invokes (unqualified calls inside that class resolve
 * to its own method, not this file's), and it delegates to {@code StreightMovementHelper},
 * {@code NeoMovementHelper} and {@code CornerJumpMovementHelper} instead. This file's copies were
 * a superseded, never-wired-in earlier implementation of the same corner-cut / neo-jump logic —
 * exactly the "no duplicates" risk this project's own charter warns about: two implementations of
 * one idea that can silently drift, with nothing marking the unused one as unused. It had already
 * cost real effort this session before the duplication was found: a real bug (an unused
 * {@code isSlab} local) was fixed in the {@code isObscured} method below while it was still live,
 * and a since-superseded open question about an asymmetry inside the dead {@code isNeoPossible}
 * was raised and is now moot, because that code never executes.
 *
 * <p>{@code isObscured}, {@code getSlimeBounceHeight} and {@code isSlimeColumnBelow} below are
 * genuinely live — confirmed by the same grep, called from {@code StreightMovementHelper},
 * {@code CornerJumpMovementHelper}, {@code NeoMovementHelper}, {@code PathFinder} and
 * {@code BlockNode} — and are the only things kept.
 */
public class MovementHelper {

    public static boolean isObscured(WorldView world, BlockPos pos, boolean isJumpingUp, boolean isJumpingOneBlock) {
    	BlockState stateBelow = world.getBlockState(pos.down());
    	BlockState state = world.getBlockState(pos);
	    BlockState aboveState = world.getBlockState(pos.up());

	    Block belowBlock = stateBelow.getBlock();
	    Block block = state.getBlock();
	    Block aboveBlock = aboveState.getBlock();

	    boolean isSlabBelow = belowBlock instanceof SlabBlock;

	    boolean isFullCube = state.isFullCube(world, pos);
	    boolean isLeaves = block instanceof LeavesBlock;
	    boolean isStairs = block instanceof StairsBlock;
	    boolean isLava = block == Blocks.LAVA;

	    boolean isBlockConnected = BlockStateChecker.isConnected(pos, world);

        boolean isAboveFullCube = aboveState.isFullCube(world, pos.up());
        boolean isAboveSlab = aboveBlock instanceof SlabBlock;
        boolean isAboveLeaves = aboveBlock instanceof LeavesBlock;
	    boolean isAboveStairs = aboveBlock instanceof StairsBlock;
	    boolean isAboveBlockConnected = BlockStateChecker.isConnected(pos.up(), world);

	    boolean isAboveX2Leaves =  world.getBlockState(pos.up(2)).getBlock() instanceof LeavesBlock;

    	if (isJumpingUp && !world.getBlockState(pos.up(2)).isAir()) return true;

	    if (isJumpingUp && isJumpingOneBlock && BlockStateChecker.isBottomSlab(stateBelow) && state.isAir() && aboveState.isAir()) return false;

	    if (isJumpingUp && isJumpingOneBlock && isStairs && aboveState.isAir() && !isAboveLeaves && !isAboveX2Leaves && world.getBlockState(pos.up(2)).isAir()) return false;
	    if (isJumpingUp && isJumpingOneBlock && isFullCube && aboveState.isAir() && world.getBlockState(pos.up(2)).isAir()) return false;


	    if (isLava || isLeaves || isAboveLeaves || isFullCube || isAboveFullCube
	    		|| isStairs || isAboveStairs) return true;

	    // TODO: fix corner jump issue from slab to slab, removing the line below causes bot to think it can go through a wall made of slabs
//		    if (isSlabBelow || isAboveSlab) return true;

	    if (isBlockConnected || isAboveBlockConnected) return true;
//		    if (!state.isAir() || !aboveState.isAir()) return true;
	    if (isLeaves || isAboveLeaves) return true;


	    return false;
    }

    public static double getSlimeBounceHeight(double startHeight) {
    	return -0.0011 * Math.pow(startHeight, 2) + 0.43529 * startHeight + 1.7323;
    }

    /**
     * Scans straight down from {@code feet} and returns true if the first
     * collidable block in the column is a slime block. Landing there is
     * fall-damage-free (bounce), so fall-height pruning must not apply.
     */
    public static boolean isSlimeColumnBelow(WorldView world, BlockPos feet, int maxScan) {
    	BlockPos pos = feet;
    	for (int i = 0; i < maxScan; i++) {
    		BlockState state = world.getBlockState(pos);
    		if (!state.isAir() && BlockShapeChecker.getShapeVolume(pos, world) > 0) {
    			return state.getBlock() instanceof net.minecraft.block.SlimeBlock;
    		}
    		pos = pos.down();
    	}
    	return false;
    }

}
