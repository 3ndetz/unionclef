package kaptainwutax.tungsten.path.blockSpaceSearchAssist;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.helpers.BlockShapeChecker;
import kaptainwutax.tungsten.helpers.BlockStateChecker;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.helpers.MovementHelper;
import kaptainwutax.tungsten.helpers.blockPath.BlockPosShifter;
import kaptainwutax.tungsten.helpers.movement.CornerJumpMovementHelper;
import kaptainwutax.tungsten.helpers.movement.NeoMovementHelper;
import kaptainwutax.tungsten.helpers.movement.StreightMovementHelper;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.calculators.ActionCosts;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.world.BetterBlockPos;
import net.minecraft.block.AirBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LilyPadBlock;
import net.minecraft.block.SeaPickleBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.SlimeBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.WorldView;

public class BlockNode {
	
	private PlayerEntity player;

	/**
	 * The position of this node
	 */
	public final int x;
	public final int y;
	public final int z;
	

	public Vec3d chachedPos = null;
	public Vec3d chachedWithOffsetPos = null;
	public BlockState chachedBlockState = null;
	public BlockPos chachedBlockPos = null;
	
	public Boolean isDoingLongJump = null;

	/**
	 * Cached, should always be equal to goal.heuristic(pos)
	 */
	public double estimatedCostToGoal;

	/**
	 * Total cost of getting from start to here Mutable and changed by PathFinder
	 */
	public double cost;

	/**
	 * Should always be equal to estimatedCosttoGoal + cost Mutable and changed by
	 * PathFinder
	 */
	public double combinedCost;

	/**
	 * In the graph search, what previous node contributed to the cost Mutable and
	 * changed by PathFinder
	 */
	public BlockNode previous;

	private boolean wasOnSlime;
	private boolean wasOnLadder;
	private boolean isDoingNeo = false;
	private Direction neoSide;
	private boolean isDoingCornerJump = false;

	/**
	 * Where is this node in the array flattenization of the binary heap? Needed for
	 * decrease-key operations.
	 */
	public int heapPosition;

	public BlockNode(BlockPos pos, Goal goal, PlayerEntity player, WorldView world) {
		this.player = player;
		this.previous = null;
		this.cost = ActionCosts.COST_INF;
		this.estimatedCostToGoal = goal.heuristic(pos.getX(), pos.getY(), pos.getZ());
		if (Double.isNaN(estimatedCostToGoal)) {
			throw new IllegalStateException(goal + " calculated implausible heuristic");
		}
		this.heapPosition = -1;
		this.x = pos.getX();
		this.y = pos.getY();
		this.z = pos.getZ();
		this.wasOnSlime = world.getBlockState(pos.down()).getBlock() instanceof SlimeBlock;
		this.wasOnLadder = world.getBlockState(pos).getBlock() instanceof LadderBlock;
	}

	public BlockNode(int x, int y, int z, Goal goal, PlayerEntity player) {
		this.player = player;
		this.previous = null;
		this.cost = ActionCosts.COST_INF;
		this.estimatedCostToGoal = goal.heuristic(x, y, z);
		if (Double.isNaN(estimatedCostToGoal)) {
			throw new IllegalStateException(goal + " calculated implausible heuristic");
		}
		this.heapPosition = -1;
		this.x = x;
		this.y = y;
		this.z = z;
		this.wasOnSlime = player.getWorld().getBlockState(new BlockPos(x, y - 1, z))
				.getBlock() instanceof SlimeBlock;
		this.wasOnLadder = player.getWorld().getBlockState(new BlockPos(x, y, z)).getBlock() instanceof LadderBlock;
	}

	public BlockNode(int x, int y, int z, Goal goal, BlockNode parent, double cost, PlayerEntity player) {
		this.player = player;
		this.previous = parent;
		this.wasOnSlime = player.getWorld().getBlockState(new BlockPos(x, y - 1, z))
				.getBlock() instanceof SlimeBlock;
		this.wasOnLadder = player.getWorld().getBlockState(new BlockPos(x, y, z)).getBlock() instanceof LadderBlock;
		this.cost = parent != null ? 0 : ActionCosts.COST_INF;
		this.estimatedCostToGoal = goal.heuristic(x, y, z);
		if (Double.isNaN(estimatedCostToGoal)) {
			throw new IllegalStateException(goal + " calculated implausible heuristic");
		}
		this.heapPosition = -1;
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public boolean isOpen() {
		return heapPosition != -1;
	}

	/**
	 * TODO: Possibly reimplement hashCode and equals. They are necessary for this
	 * class to function but they could be done better
	 *
	 * @return The hash code value for this {@link PathNode}
	 */
	@Override
	public int hashCode() {
		return (int) BetterBlockPos.longHash(x, y, z);
	}

	public Vec3d getPos() {
		return getPos(false, TungstenModDataContainer.world);
	}
	

	public Vec3d getPos(boolean shift) {
		return getPos(shift, TungstenModDataContainer.world);
	}
	
	public Vec3d getPos(WorldView world) {
		return getPos(false, world);
	}

	public Vec3d getPos(boolean shift, WorldView world) {
		if (!shift && chachedPos != null) return chachedPos;
		if (shift) {
			if (isDoingNeo && !(this.getBlockState(world).getBlock() instanceof LadderBlock)) {
				chachedWithOffsetPos = BlockPosShifter.shiftForStraightNeo(this, neoSide);
//				chachedWithOffsetPos = BlockPosShifter.getPosOnLadder(this, world);
				return chachedWithOffsetPos;
			}
			if (chachedWithOffsetPos != null) return chachedWithOffsetPos;
			if (isDoingNeo) {
				chachedWithOffsetPos = BlockPosShifter.shiftForStraightNeo(this, neoSide);
				return chachedWithOffsetPos;
			}
			if (BlockShapeChecker.getBlockHeight(this.getBlockPos().down(), world) > 1) {
				chachedWithOffsetPos = BlockPosShifter.getPosOnLadder(this, world);
				chachedWithOffsetPos = chachedWithOffsetPos.add(0, BlockShapeChecker.getBlockHeight(this.getBlockPos().down(), world)-1, 0);
				if (isDoingNeo) chachedWithOffsetPos = BlockPosShifter.shiftForStraightNeo(this, neoSide);
				return chachedWithOffsetPos;
			}
			if (BlockShapeChecker.getBlockHeight(this.getBlockPos(), world) > 0) {
				chachedWithOffsetPos = BlockPosShifter.getPosOnLadder(this, world);
				double blockVolume = BlockShapeChecker.getShapeVolume(this.getBlockPos(), world);
				chachedWithOffsetPos = chachedWithOffsetPos.add(0, BlockShapeChecker.getBlockHeight(this.getBlockPos(), world), 0);
				if (isDoingNeo) chachedWithOffsetPos = BlockPosShifter.shiftForStraightNeo(this, neoSide);
				return chachedWithOffsetPos;
			}
			chachedWithOffsetPos = BlockPosShifter.getPosOnLadder(this, world);
			if (isDoingNeo) chachedWithOffsetPos = BlockPosShifter.shiftForStraightNeo(this, neoSide);
			return chachedWithOffsetPos;
		}
		chachedPos = new Vec3d(x, y, z);
		return chachedPos;
	}

	public boolean isDoingLongJump(WorldView world) {
		if (isDoingLongJump != null) return isDoingLongJump;
		if (this.previous != null) {
			double distance = DistanceCalculator.getHorizontalEuclideanDistance(this.previous.getPos(world), this.getPos(world));
			if (distance >= 4 && distance < 6) {
				isDoingLongJump = true;
				return true;
			}
		}
		isDoingLongJump = false;
		return false;
	}

	public boolean isDoingNeo() {
		return this.isDoingNeo;
	}
	
	public Direction getNeoSide() {
		return this.neoSide;
	}

	public boolean isDoingCornerJump() {
		return this.isDoingCornerJump;
	}

	public BlockState getBlockState(WorldView world) {
		if (chachedBlockState != null) return chachedBlockState;
		chachedBlockState = world.getBlockState(getBlockPos());
		return chachedBlockState;
	}

	public BlockPos getBlockPos() {
		if (chachedBlockPos != null) return chachedBlockPos;
		chachedBlockPos = new BlockPos(x, y, z);
		return chachedBlockPos;
	}

	@Override
	public boolean equals(Object obj) {

		final BlockNode other = (BlockNode) obj;

		return x == other.x && y == other.y && z == other.z;
	}

	public List<BlockNode> getChildren(WorldView world, Goal goal, boolean generateDeep) {

		List<BlockNode> nodes = getNodesIn3DCircule(8, this, goal, generateDeep);
//		nodes.removeIf((child) -> {
//			return shouldRemoveNode(world, child);
//		});
		
		 List<BlockNode> filtered = nodes.parallelStream()
			        .filter(node -> !shouldRemoveNode(world, node))
			        .collect(Collectors.toList());

		TungstenModRenderContainer.TEST.clear();

		return filtered;

	}

	public static boolean wasCleared(WorldView world, BlockPos start, BlockPos end) {
		return wasCleared(world, start, end, null, null);
	}

	public static boolean wasCleared(WorldView world, BlockPos start, BlockPos end, BlockNode startNode,
			BlockNode endNode) {

		TungstenModRenderContainer.TEST.clear();
		boolean shouldRender = false;
		boolean shouldSlow = false;
		

		boolean isStreightPossible = StreightMovementHelper.isPossible(world, start, end, shouldRender, shouldSlow);
		
		if (isStreightPossible) return true;
		if (endNode == null) return false;
		
		// When running bot in normal environment instead of parkour you need to turn on Neo and Corner jump checks to avoid cases where it can get stuck
		boolean shouldCheckNeo = !start.isWithinDistance(end, 1.2) && start.isWithinDistance(end, 4.2) && true;
		if (shouldCheckNeo) {
			Direction neoDirection = NeoMovementHelper.getNeoDirection(world, start, end, shouldRender, shouldSlow);
			if (neoDirection != null) {
				endNode.isDoingNeo = true;
				endNode.neoSide = neoDirection;
				endNode.isDoingCornerJump = false;
				return true;
			}
		}
		boolean isCornerJumpPossible = CornerJumpMovementHelper.isPossible(world, start, end, shouldRender, shouldSlow);
		if (isCornerJumpPossible) {
			endNode.isDoingNeo = false;
			endNode.isDoingCornerJump = true;
			return true;
		}

		return false;
	}

	private List<BlockNode> getNodesIn3DCircule(int d, BlockNode parent, Goal goal, boolean generateDeep) {
		ConcurrentLinkedQueue<BlockNode> nodes = new ConcurrentLinkedQueue<>();

	    double g = 32.656;
	    double v_sprint = 5.8;

	    double yMax = (parent.wasOnSlime && parent.previous != null && parent.previous.y - parent.y < 0)
	        ? MovementHelper.getSlimeBounceHeight(parent.previous.y - parent.y) - 0.5
	        : generateDeep ? 4 : 2;

	    if (parent.wasOnSlime && parent.previous != null && parent.previous.y - parent.y < 0) {
	    	TungstenModRenderContainer.BLOCK_PATH_RENDERER.add(new Cuboid(
	                new Vec3d(parent.getBlockPos().getX(), parent.getBlockPos().getY(), parent.getBlockPos().getZ()),
	                new Vec3d(0.2D, 0.2D, 0.2D), Color.GREEN));
	        try {
	            Thread.sleep(250); // Optional debug delay
	        } catch (InterruptedException e) {
	            e.printStackTrace();
	        }
	    }

	    int distanceWanted = d;
	    int finalYMax = (int) Math.ceil(yMax);

        IntStream.range(generateDeep ? -64 : -4, finalYMax).parallel().forEach(py -> {
            int localD;

            if (py < 0 && py < -5) {
                double t = Math.sqrt((2 * py * -1) / g);
                localD = (int) Math.ceil(v_sprint * t);
            } else {
                localD = distanceWanted + 1;
            }

            // Center node
            nodes.add(new BlockNode(this.x, this.y + py, this.z, goal, this, ActionCosts.WALK_ONE_BLOCK_COST, this.player));

            for (int id = 1; id <= localD; id++) {
                int px = id, pz = 0;
                int dx = -1, dz = 1;
                int n = id * 4;

                for (int i = 0; i < n; i++) {
                    if (px == id && dx > 0) dx = -1;
                    else if (px == -id && dx < 0) dx = 1;

                    if (pz == id && dz > 0) dz = -1;
                    else if (pz == -id && dz < 0) dz = 1;

                    px += dx;
                    pz += dz;

                    BlockNode newNode = new BlockNode(this.x + px, this.y + py, this.z + pz, goal, this,
                            ActionCosts.WALK_ONE_BLOCK_COST, this.player);
                    nodes.add(newNode);
                }
            }
        });

	    return new ArrayList<>(nodes);
	}

	private boolean shouldRemoveNode(WorldView world, BlockNode child) {
		if (TungstenModDataContainer.PATHFINDER.stop.get())
			return true;

		BlockState currentBlockState = world.getBlockState(getBlockPos());
		BlockState currentBlockBelowState = world.getBlockState(getBlockPos().down());
		BlockState childAboveState = world.getBlockState(child.getBlockPos().up());
		BlockState childState = world.getBlockState(child.getBlockPos());
		BlockState childBelowState = world.getBlockState(child.getBlockPos().down());
		Block currentBlock = currentBlockState.getBlock();
		Block childBlock = childState.getBlock();
		Block childBelowBlock = childBelowState.getBlock();
		boolean isAboveChildSolid = BlockShapeChecker.getShapeVolume(child.getBlockPos().up(), world) > 0;
		boolean isAboveChildSolid2 = BlockShapeChecker.getShapeVolume(child.getBlockPos().up(2), world) > 0;
		


		// Specific block checks
		if (childState.isOf(Blocks.LAVA))
			return true;
		if (childBelowBlock instanceof LilyPadBlock)
			return true;
		if (childBelowBlock instanceof CarpetBlock)
			return true;
		if (childBelowBlock instanceof DaylightDetectorBlock)
			return true;
		if (childBlock instanceof StairsBlock)
			return true;
		if (childBelowBlock instanceof SeaPickleBlock)
			return true;
		if (childBelowBlock instanceof CropBlock)
			return true;
		if (BlockShapeChecker.isBlockNormalCube(childState))
			return true;
		if (BlockStateChecker.isBottomSlab(childBelowState))
			return true;
		if (BlockStateChecker.isBottomSlab(childState) && BlockStateChecker.isTopSlab(childAboveState))
			return true;
		if (BlockStateChecker.isTopSlab(childBelowState) && BlockStateChecker.isBottomSlab(childAboveState))
			return true;

		if (isAboveChildSolid && BlockStateChecker.isBottomSlab(childState)) {
			return true;
		}
		
		
		// Check for air below
		if (childBelowState.isAir() && !(childBlock instanceof LadderBlock) && !BlockStateChecker.isBottomSlab(childState)) {
			if (BlockShapeChecker.getShapeVolume(child.getBlockPos(), world) == 0)
				return true;
		}

		
		double heightDiff = getJumpHeight(this.getPos(true).y, child.getPos(true).y); // positive is going up and negative is going down
		double distance = DistanceCalculator.getHorizontalEuclideanDistance(getPos(true, world), child.getPos(true, world));
//		if (BlockStateChecker.isAnyWater(currentBlockState)) {
//			if (distance > 1) return true;
//			if (!wasCleared(world, getBlockPos(), child.getBlockPos())) return true;
//			return false;
//		}
		// Search for a path without fall damage
		if (!TungstenModDataContainer.ignoreFallDamage) {
			if (!BlockStateChecker.isAnyWater(childState)) {
				if (heightDiff < -2) return true;
			}
		}
		if (BlockStateChecker.isAnyWater(childState)) {
			if (distance > 1 || heightDiff > 1) return true;
			if (!wasCleared(world, getBlockPos(), child.getBlockPos())) return true;
			return false;
		}
		if (BlockStateChecker.isAnyWater(childState) && !childAboveState.isAir()) return true;
		
		if (BlockStateChecker.isDoubleSlab(world, getBlockPos()) || childBelowBlock instanceof SnowBlock)
			return true;

//        if (BlockStateChecker.isConnected(child.getBlockPos())) {
//    		TungstenMod.TEST.add(new Cuboid(new Vec3d(child.getBlockPos().getX(), child.getBlockPos().getY(), child.getBlockPos().getZ()), new Vec3d(1.0D, 1.0D, 1.0D), Color.WHITE));
//    		try {
//    			Thread.sleep(250);
//    		} catch (InterruptedException ignored) {}
//        }

		// Check for water
//        if (isWater(childState) && wasCleared(world, getBlockPos(), child.getBlockPos())) return false;

		// Vine checks
		if ((childBelowBlock instanceof VineBlock || childBlock instanceof VineBlock)
				&& wasCleared(world, getBlockPos(), child.getBlockPos()) && distance < 6.3 && heightDiff >= -1) {
			return false;
		}
		if ((childBelowBlock instanceof VineBlock || childBlock instanceof VineBlock)
				&& distance < 2.3 && heightDiff == 0) {
			return false;
		}

		// Ladder checks
		if ((childBlock instanceof LadderBlock || childBelowBlock instanceof LadderBlock) && distance > 5) 
			return true;
			
		if ((childBlock instanceof LadderBlock || childBelowBlock instanceof LadderBlock) && heightDiff > 3) {
			return true;
		}
		if (BlockStateChecker.isBottomSlab(currentBlockBelowState) && (childBlock instanceof LadderBlock || childBelowBlock instanceof LadderBlock) && heightDiff > 0) {
			return true;
		}
		if ((currentBlock instanceof LadderBlock) && distance > 2.3) {
			return true;
		}
		if ((childBelowBlock instanceof LadderBlock || childBlock instanceof LadderBlock) && distance > 6.3) {
			return true;
		}
		if ((childBelowBlock instanceof LadderBlock && !(childState.isAir() || childBlock instanceof LadderBlock))) {
			return true;
		}
		if ((childBelowBlock instanceof LadderBlock || childBlock instanceof LadderBlock) && distance < 1 && heightDiff >= -1) {
			return false;
		}
		if ((childBelowBlock instanceof LadderBlock || childBlock instanceof LadderBlock)
				&& wasCleared(world, getBlockPos(), child.getBlockPos(), this, child) && distance < 5.3 && heightDiff >= -1) {
			return false;
		}
		if (childBlock instanceof LadderBlock) {
			return false;
		}
		if (childBelowBlock instanceof LadderBlock) {
			return false;
		}


		if (heightDiff >= -1 && heightDiff <= 0 && distance > 6.3) 
			return true;

		// General height and distance checks
//        if (previous != null && (previous.y - y < 1 && wasOnSlime || (!wasOnSlime && child.y - y > 1))) return true;
		if (distance >= 7)
			return true;

		// Slab checks
//		if (BlockStateChecker.isBottomSlab(childState) != BlockStateChecker.isBottomSlab(currentBlockState))
//			if (BlockStateChecker.isBottomSlab(childState) && this.getBlockPos().getY() != child.getBlockPos().getY())
//				return true;
//        if (isBottomSlab(childState) && !hasBiggerCollisionShapeThanAbove(world, child.getBlockPos())) return true;

		boolean canStandOn = BlockShapeChecker.hasBiggerCollisionShapeThanAbove(world, child.getBlockPos().down());

		// Collision shape and block exceptions
		if (!BlockStateChecker.isBottomSlab(childState))
		if (!canStandOn && (!(childBlock instanceof DaylightDetectorBlock) && !(childBlock instanceof CarpetBlock)
				&& !(childBelowBlock instanceof SlabBlock) && !(childBelowBlock instanceof LanternBlock)
				&& !(childBelowBlock == Blocks.SNOW))
//                || (childBelowBlock instanceof StairsBlock)
//                && !(childBelowBlock instanceof LanternBlock)
//                && !(childBelowBlock == Blocks.SNOW)
//                && getShapeVolume(childState.getCollisionShape(world, child.getBlockPos())) >= 1
		) {
			return true;
		}

		if (isJumpImpossible(world, child))
			return true;
		
		// TODO: Fix bottom slab under fence thing
		if (!wasCleared(world, getBlockPos(), child.getBlockPos(), this, child)) {
			return true;
		}

		if (BlockStateChecker.isBottomSlab(childState) && isAboveChildSolid2)
			child.cost += 20;
		
		return false;
	}
	
	/**
	 * Returns jump height.
	 * 
	 * @param from
	 * @param to
	 * @return positive is going up and negative is going down
	 */
	public double getJumpHeight(double from, double to) {
		
		double diff = to - from;
		
		// if `to` is higher then `from` return value should be positive
		if (to > from) {
			return diff > 0 ? diff : diff * -1;
		}
		return diff > 0 ? diff * -1 : diff;
	}

	private boolean isJumpImpossible(WorldView world, BlockNode child) {
		double heightDiff = getJumpHeight(this.getPos(true).y, child.getPos(true).y); // positive is going up and negative is going down
		double distance = DistanceCalculator.getHorizontalEuclideanDistance(getPos(true, world), child.getPos(true, world));
		
		BlockState childBlockState = world.getBlockState(child.getBlockPos());
		Block childBlock = childBlockState.getBlock();
		BlockState belowChildBlockState = world.getBlockState(child.getBlockPos().down());
		BlockState currentBlockState = world.getBlockState(getBlockPos().down());
		Block belowChildBlock = belowChildBlockState.getBlock();
        double closestBlockBelowHeight = BlockShapeChecker.getBlockHeight(child.getBlockPos().down(), world);
		boolean isBlockBelowTall = closestBlockBelowHeight > 1.3;
		if (heightDiff > 1.4 && childBlock != Blocks.LADDER) return true;


//    	if (world.getBlockState(child.getBlockPos().down()).getBlock() instanceof TrapdoorBlock) {
//			System.out.println(!world.getBlockState(child.getBlockPos().down()).get(Properties.OPEN));
//    	}
		boolean isAboveChildSolid = BlockShapeChecker.getShapeVolume(child.getBlockPos().up(), world) > 0;
		boolean isAboveChildSolid2 = BlockShapeChecker.getShapeVolume(child.getBlockPos().up(2), world) > 0;
		boolean isAboveSolid2 = BlockShapeChecker.getShapeVolume(getBlockPos().up(2), world) > 0;
		if (isAboveSolid2 && distance > 3) {
			return true;
		}
		if (heightDiff > 0.6 && distance > 6.3 && childBlock != Blocks.LADDER) {
			return true;
		}

		VoxelShape blockShape = belowChildBlockState.getCollisionShape(world, child.getBlockPos().down());
		VoxelShape currentBlockShape = currentBlockState.getCollisionShape(world, getBlockPos().down());

		double childBlockHeight = BlockShapeChecker.getBlockHeight(blockShape);
		double currentBlockHeight = BlockShapeChecker.getBlockHeight(currentBlockShape);

		double blockHeightDiff = currentBlockHeight - childBlockHeight; // Negative values means currentBlockHeight is
																		// lower, and positive means currentBlockHeight
		if (player.getHungerManager().getFoodLevel() < 6) {

			if (distance >= 4) return true;
		}

		if (BlockStateChecker.isBottomSlab(childBlockState) && isAboveChildSolid2  && distance > 2.3) {
			return true;
		}
		
		if (BlockStateChecker.isBottomSlab(currentBlockState) && childBlockHeight == 1 && heightDiff > 0.5) {
			return true;
		}
		

		if (BlockStateChecker.isAnyWater(currentBlockState)) {
			if (distance >= 2) return true;
			return false;
		}
		if (childBlockHeight == 1.5 && currentBlockHeight == 1.5 && heightDiff <= 1) {
			if (distance <= 4) return false;
		}

		if (isBlockBelowTall && heightDiff > 0.5) return true;
								
		// VoxelShape-based checks
		if (!Double.isInfinite(blockHeightDiff) && !Double.isNaN(blockHeightDiff)) {
			
			// Slab and ladder checks
			if (heightDiff <= 0 && (BlockStateChecker.isBottomSlab(belowChildBlockState)
					|| (!wasOnLadder && belowChildBlock instanceof LadderBlock)) && distance >= 4.5) {
				return true;
			}
			if (belowChildBlock instanceof SlabBlock && belowChildBlockState.get(Properties.SLAB_TYPE) == SlabType.TOP
					&& !world.getBlockState(child.getBlockPos()).isAir()) {
				return true;
			}

			if (BlockStateChecker.isClosedBottomTrapdoor(belowChildBlockState)) {
				if (heightDiff <= 1 && distance <= 6.4) return false;
				if (heightDiff == 2 && distance <= 4.4) return false;
			}
			
			if (BlockStateChecker.isClosedBottomTrapdoor(currentBlockState)) {
				if (heightDiff <= 0 && distance <= 6.4) return false;
				if (heightDiff > 0 && BlockStateChecker.isTopSlab(belowChildBlockState)) return true;
			}
			
			if (blockHeightDiff != 0) {
				
				
				if (Math.abs(blockHeightDiff) > 0.5 && Math.abs(blockHeightDiff) <= 1.0) {
					if (heightDiff > 0 && (blockShape.getMin(Axis.Y) == 0.0 && currentBlockHeight <= 1.0))
						return true;
					if (heightDiff == 2 && distance <= 5.3)
						return false;
					if (heightDiff >= 0 && distance <= 5.3)
						return false;
				}
				
				if (Math.abs(blockHeightDiff) <= 0.5 && (blockShape.getMin(Axis.Y) == 0.0 && childBlockHeight == 1.0)) {
					if (heightDiff == 0 && distance <= 5.4)
						return false;
				}

				if (Math.abs(blockHeightDiff) <= 0.5 && (blockShape.getMin(Axis.Y) == 0.0 || childBlockHeight > 1.0)) {
					if (blockHeightDiff == 0.5 && heightDiff <= -1)
						return true;
					if (heightDiff == 0 && distance >= 4.4)
						return true;
					if (heightDiff <= 1 && distance <= 7.4)
						return false;
				}

				if (Math.abs(blockHeightDiff) >= 0.5 && (blockShape.getMin(Axis.Y) == 0.0 || childBlockHeight > 1.0))
					return true;
				
			}
		}

		if (!wasOnSlime || this.previous.y - this.y >= 0) {
			// Basic height and distance checks
			if (heightDiff >= 2)
				return true;
			if (heightDiff == 1 && distance > 5.24)
				return true;
			if (heightDiff == -1 && distance > 5.3)
				return true;
			if (heightDiff == -2 && distance > 6.2)
				return true;
			if (heightDiff == 1 && distance >= 4.5)
				return true;
			if ((heightDiff == 0) && distance >= 5.3)
				return true;
			if (heightDiff >= -3 && distance >= 6.3)
				return true;
			if (heightDiff < -2 && distance >= 6.3)
				return true;

			// Trapdoor checks
			if (heightDiff == 1 && BlockStateChecker.isOpenTrapdoor(belowChildBlockState) && distance > 5)
				return true;
			if (heightDiff <= -2 && BlockStateChecker.isOpenTrapdoor(belowChildBlockState) && distance > 6)
				return true;
		}

		// Large height drop
		if (heightDiff > -1 && distance >= 6)
			return true;

		// Bottom slab checks
		if (currentBlockHeight <= 0.5 && heightDiff > 0.5 && childBlockHeight > 0.5) {
			return true;
		}

		return false;
	}

}
