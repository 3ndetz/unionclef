package kaptainwutax.tungsten.helpers.movement;

import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.helpers.MovementHelper;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

public class StreightMovementHelper {
	
	public static boolean isPossible(WorldView world, BlockPos startPos, BlockPos endPos) {
		return isPossible(world, startPos, endPos, false, false);
	}
	
	public static boolean isPossible(WorldView world, BlockPos startPos, BlockPos endPos, boolean shouldRender, boolean shouldSlow) {
		
		boolean isJumpingUp = endPos.getY() - startPos.getY() == 1;

    	int dx = startPos.getX() - endPos.getX();
    	int dz = startPos.getZ() - endPos.getZ();
    	double distance = Math.sqrt(dx * dx + dz * dz);
		boolean isJumpingOneBlock = distance == 1;
	    PathNavigator navigator = new PathNavigator(world, isJumpingUp, isJumpingOneBlock, shouldRender, shouldSlow);

	    return navigator.traversePath(startPos, endPos);
	}

	private static class PathNavigator {
	    private final WorldView world;
	    private final boolean isJumpingUp;
	    private final boolean isJumpingOneBlock;
	    private final boolean shouldRender;
	    private final boolean shouldSlow;

	    public PathNavigator(WorldView world, boolean isJumpingUp, boolean isJumpingOneBlock, boolean shouldRender, boolean shouldSlow) {
	        this.world = world;
	        this.isJumpingUp = isJumpingUp;
	        this.isJumpingOneBlock = isJumpingOneBlock;
	        this.shouldRender = shouldRender;
	        this.shouldSlow = shouldSlow;
	    }

	    public boolean traversePath(BlockPos startPos, BlockPos endPos) {
	        int x = startPos.getX();
	        int y = startPos.getY();
	        int z = startPos.getZ();
	        int endX = endPos.getX();
	        int endY = endPos.getY();
	        int endZ = endPos.getZ();

	        BlockPos.Mutable currPos = new BlockPos.Mutable();
	        // TODOS.md C3.3: this ran unconditionally, on every candidate-move check across the
	        // search's ForkJoinPool threads, contending TEST's synchronized-collection lock even
	        // with rendering off (renderBlock below already gates its own .add() on shouldRender --
	        // the clear is the other half of the same visualization and belongs behind the same gate).
	        if (shouldRender) TungstenModRenderContainer.TEST.clear(); // Clear visual markers
	        renderBlock(endPos, Color.BLUE);
	        
	        boolean isOneBlockAway = DistanceCalculator.getHorizontalEuclideanDistance(startPos, endPos) <= 1;
	        
	        if (isOneBlockAway) {
	        	Direction dir = DirectionHelper.getHorizontalDirectionFromPos(startPos, endPos);
	        	int offsetX = dir.getOffsetX();
	        	int offsetZ = dir.getOffsetZ();

	            currPos.set(x, y + 2, z);
	            
	            if (!processStep(currPos)) {
		            currPos.set(x + offsetX, y, z + offsetZ);
		            if (!processStep(currPos)) {
		                return false; // Path obstructed
		            }
	            }
	        	
	        }

	        while (x != endX || y != endY || z != endZ) {
	            if (TungstenModDataContainer.PATHFINDER.stop.get()) return false;

	            currPos.set(x, y, z);

	            if (!processStep(currPos)) {
	                return false; // Path obstructed
	            }

	            z = moveCoordinate(z, endZ);
	            

	            currPos.set(x, y, z);

	            if (!processStep(currPos)) {
	                return false; // Path obstructed
	            }
	            
	            x = moveCoordinate(x, endX);

	            currPos.set(x, y, z);

	            if (!processStep(currPos)) {
	                return false; // Path obstructed
	            }
	            
	            y = moveCoordinate(y, endY);

	        }
	        // ⛔ FIXED 2026-09-05: every coordinate update inside the loop above is followed by a
	        // processStep() check EXCEPT this one's y-move -- z gets one at line ~98, x at ~106, but
	        // y's result was only ever picked up at the TOP of the NEXT iteration. Whenever y is the
	        // last coordinate to reach its target (any climb, descend or pillar-shaped move with no
	        // further horizontal step left to take), the loop exits on that same y-move and the
	        // FINAL landing cell was never checked at all -- confirmed by comparing against
	        // CornerJumpMovementHelper's sibling traversePath(), which checks immediately after
	        // every move including y and does not have this gap. Explicit final check restores that.
	        currPos.set(endX, endY, endZ);
	        if (!processStep(currPos)) {
	            return false; // Path obstructed
	        }
	        renderBlock(endPos, Color.BLUE);
	        slowDownIfNeeded();
	        return true; // Successfully navigated the path
	    }

	    private boolean processStep(BlockPos.Mutable position) {
	        if (MovementHelper.isObscured(world, position, isJumpingUp, isJumpingOneBlock)) {
	            renderBlock(position, Color.RED);
	            slowDownIfNeeded();
	            return false;
	        } else {
	            renderBlock(position, Color.WHITE);
	            return true;
	        }
	    }

	    private void renderBlock(BlockPos position, Color color) {
	        if (shouldRender) {
	        	TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(position.getX(), position.getY(), position.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), color));
	        	TungstenModRenderContainer.TEST.add(new Cuboid(new Vec3d(position.getX(), position.getY() + 1, position.getZ()), new Vec3d(1.0D, 1.0D, 1.0D), color));
	        }
	    }

	    private void slowDownIfNeeded() {
	        if (shouldSlow) {
	            try {
	                Thread.sleep(450);
	            } catch (InterruptedException ignored) {}
	        }
	    }

	    private int moveCoordinate(int current, int target) {
	        if (current < target) return current + 1;
	        if (current > target) return current - 1;
	        return current;
	    }
	}
}
