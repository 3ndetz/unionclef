package kaptainwutax.tungsten.helpers.movement;

import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.helpers.MovementHelper;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

public class CornerJumpMovementHelper {

	public static boolean isPossible(WorldView world, BlockPos startPos, BlockPos endPos, boolean shouldRender, boolean shouldSlow) {
		
		boolean isJumpingUp = endPos.getY() - startPos.getY() == 1;

    	int dx = startPos.getX() - endPos.getX();
    	int dz = startPos.getZ() - endPos.getZ();
    	double distance = Math.sqrt(dx * dx + dz * dz);
		boolean isJumpingOneBlock = distance == 1;
		
	    PathNavigator navigator = new PathNavigator(world, isJumpingUp, isJumpingOneBlock,  shouldRender, shouldSlow);

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
			boolean isEdgeOnX = Math.abs(endX - x) < 2;
			boolean isEdgeOnZ = Math.abs(endZ - z) < 2;

	        BlockPos.Mutable currPos = new BlockPos.Mutable();
	        TungstenModRenderContainer.TEST.clear(); // Clear visual markers
	        if (!isEdgeOnX && !isEdgeOnZ) return false;
//	        if (isEdgeOnX && isEdgeOnZ) {
//	        	
//	        	return false;
//	        }
	        
	        while (x != endX || y != endY || z != endZ) {
	            if (TungstenModDataContainer.PATHFINDER.stop.get()) return false;
	            // Move x or z based on conditions
	            if ((isEdgeOnZ || (isEdgeOnX && z == endZ)) && x != endX) {
	                x = moveCoordinate(x, endX);
	            } else if ((isEdgeOnX || (isEdgeOnZ && x == endX)) && z != endZ) {
	                z = moveCoordinate(z, endZ);
	            }

	            currPos.set(x, y, z);

	            if (!processStep(currPos)) {
	            	return false; // Path obstructed
	            }
	            
	            y = moveCoordinate(y, endY);

	            currPos.set(x, y, z);

	            if (!processStep(currPos)) {
	                return false; // Path obstructed
	            }
	        }
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
