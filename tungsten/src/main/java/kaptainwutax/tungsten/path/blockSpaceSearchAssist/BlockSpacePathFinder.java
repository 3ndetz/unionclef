package kaptainwutax.tungsten.path.blockSpaceSearchAssist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.helpers.BlockShapeChecker;
import kaptainwutax.tungsten.helpers.BlockStateChecker;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.helpers.movement.StreightMovementHelper;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.calculators.ActionCosts;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

public class BlockSpacePathFinder {
	
	public static boolean active = false;
	public static Thread thread = null;
	protected static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};
	protected static final BlockNode[] bestSoFar = new BlockNode[COEFFICIENTS.length];
	private static final double minimumImprovement = 0.21;
	protected static final double MIN_DIST_PATH = 5;
	
	
	public static void find(WorldView world, Vec3d target, PlayerEntity player) {
		if(active)return;
		active = true;

		thread = new Thread(() -> {
			try {
				search(world, target, player);
			} catch(Exception e) {
				e.printStackTrace();
			}

			active = false;
		});
		thread.setName("BlockSpacePathFinder");
		thread.start();
	}
	
	public static Optional<List<BlockNode>> search(WorldView world, Vec3d target, PlayerEntity player) {
		return search(world, target, false, player);
	}

	public static Optional<List<BlockNode>> search(WorldView world, BlockNode start, Vec3d target, PlayerEntity player) {
		return search(world, start, target, false, player);
	}
	
	private static Optional<List<BlockNode>> search(WorldView world, Vec3d target, boolean generateDeep, PlayerEntity player) {
		if (!world.getBlockState(player.getBlockPos()).isAir() && BlockShapeChecker.getShapeVolume(player.getBlockPos(), world) != 0 && BlockShapeChecker.getBlockHeight(player.getBlockPos(), world) > 0.5) {
			return search(world, new BlockNode(player.getBlockPos().up(), new Goal((int) target.x, (int) target.y, (int) target.z), player, world), target, player);
		}
		return search(world, new BlockNode(player.getBlockPos(), new Goal((int) target.x, (int) target.y, (int) target.z), player, world), target, player);
	}
	
	private static Optional<List<BlockNode>> search(WorldView world, BlockNode start, Vec3d target, boolean generateDeep, PlayerEntity player) {
		Goal goal = new Goal((int) target.x, (int) target.y, (int) target.z);
		boolean failing = true;
        int numNodes = 0;
        int timeCheckInterval = 1 << 6;
        long startTime = System.currentTimeMillis();
        long primaryTimeoutTime = startTime + (generateDeep ? 4800L : 480L);
		
        TungstenModRenderContainer.RENDERERS.clear();
		Debug.logMessage("Searchin...");
		start = new BlockNode(start.getBlockPos(), goal, player, world);
		
		double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];//keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
		for (int i = 0; i < COEFFICIENTS.length; i++) {
            bestHeuristicSoFar[i] = computeHeuristic(start.getPos(), target, world);
            bestSoFar[i] = start;
        }

		BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
		Set<BlockNode> closed = new HashSet<>();
		openSet.insert(start);
		target = target.subtract(0.5, 0, 0.5);
		while(!openSet.isEmpty()) {
			if (TungstenModDataContainer.PATHFINDER.stop.get()) {
				RenderHelper.clearRenderers();
				break;
			}
			TungstenModRenderContainer.RENDERERS.clear();
			if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
                long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
                if ((!failing && now - primaryTimeoutTime >= 0)) {
                    break;
                }
            }
			BlockNode next = openSet.removeLowest();
			
			if (closed.contains(next)) continue;
			
			closed.add(next);
			if(isPathComplete(next, target, failing)) {
				TungstenModRenderContainer.RENDERERS.clear();
				List<BlockNode> path = generatePath(next, world);

				Debug.logMessage("Found rought path!");
				
				return Optional.of(path);
			}
			
			if(TungstenModRenderContainer.RENDERERS.size() > 3000) {
				TungstenModRenderContainer.RENDERERS.clear();
			}
			 RenderHelper.renderPathSoFar(next);
			
			for(BlockNode child : next.getChildren(world, goal, generateDeep)) {
				if (TungstenModDataContainer.PATHFINDER.stop.get()) return Optional.empty();
//				if (closed.contains(child)) continue;
				

				updateNode(next, child, target, world);
				
                if (child.isOpen()) {
                    openSet.update(child);
                } else {
                    openSet.insert(child);//dont double count, dont insert into open set if it's already there
                }
			}
            
            
            failing = updateBestSoFar(next, bestHeuristicSoFar, target);
		}

		if (openSet.isEmpty()) {
			if (!generateDeep) {
				return search(world, start, target, true, player);
			}
			Debug.logWarning("Ran out of nodes");
			return Optional.empty();
		}
        Optional<List<BlockNode>> result = bestSoFar(true, numNodes, start, world);
		return result;
	}
	
	protected static Optional<List<BlockNode>> bestSoFar(boolean logInfo, int numNodes, BlockNode startNode, WorldView world) {
        if (startNode == null) {
            return Optional.empty();
        }
        double bestDist = 0;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            if (bestSoFar[i] == null) {
                continue;
            }
            double dist = getDistFromStartSq(bestSoFar[i], startNode.getPos());
            if (dist > bestDist) {
                bestDist = dist;
                continue;
            }
            if (dist > MIN_DIST_PATH * MIN_DIST_PATH) { // square the comparison since distFromStartSq is squared
                BlockNode n = bestSoFar[i];
				List<BlockNode> path = generatePath(n, world);
				if (path.size() > 1) return Optional.of(path);
            }
        }
        return Optional.empty();
    }
	
	private static double computeHeuristic(Vec3d position, Vec3d target, WorldView world) {
		double xzMultiplier = 1.2;
	    double dx = (position.x - target.x)*xzMultiplier;
	    double dy = 0;
	    double dz = (position.z - target.z)*xzMultiplier;
	    if (BlockStateChecker.isAnyWater(world.getBlockState(new BlockPos((int) position.x, (int) position.y, (int) position.z)))) {
	    	dy = (position.y - target.y)*1.8;
	    } else if (DistanceCalculator.getHorizontalManhattanDistance(position, target) < 32) {
	    	dy = (position.y - target.y)*1.5;
	    } else {
	    	dy = (position.y - target.y)*0.5;
	    }
	    return (Math.sqrt(dx * dx + dy * dy + dz * dz)) * 3;
	}
	
	private static void updateNode(BlockNode current, BlockNode child, Vec3d target, WorldView world) {
	    Vec3d childPos = child.getPos();
	    Block childBlock = child.getBlockState(world).getBlock();
//	    double tentativeCost = (childBlock instanceof LadderBlock || childBlock instanceof VineBlock ? 12.2 : 0) + ActionCosts.WALK_ONE_BLOCK_COST; // Assuming uniform cost for each step
//	    tentativeCost += BlockStateChecker.isAnyWater(TungstenMod.mc.world.getBlockState(child.getBlockPos())) ? 50 : 0; // Assuming uniform cost for each step

	    double estimatedCostToGoal = computeHeuristic(childPos, target, world) + DistanceCalculator.getHorizontalEuclideanDistance(current.getPos(true), child.getPos(true)) * 8 + (current.getBlockPos().getY() != child.getBlockPos().getY() ? 2.8 : 0);

	    child.previous = current;
//	    child.cost = tentativeCost;
	    child.estimatedCostToGoal = estimatedCostToGoal;
	    child.combinedCost = child.cost + estimatedCostToGoal;
	}
	
	private static boolean updateBestSoFar(BlockNode child, double[] bestHeuristicSoFar, Vec3d target) {
		boolean failing = false;
		if (child.previous == null) return false;
	    for (int i = 0; i < COEFFICIENTS.length; i++) {
	        double heuristic = child.combinedCost / COEFFICIENTS[i];
	        if (bestHeuristicSoFar[i] - heuristic > minimumImprovement && bestHeuristicSoFar[i] != heuristic) {
//		        Debug.logMessage((bestHeuristicSoFar[i] - heuristic) + "");
//		        	RenderHelper.renderPathSoFar(child);
//		        	try {
//						Thread.sleep(6);
//					} catch (InterruptedException e) {
//						// TODO Auto-generated catch block
//						e.printStackTrace();
//					}
	            bestSoFar[i] = child;
	            bestHeuristicSoFar[i] = heuristic;
	            if (failing && child.estimatedCostToGoal > MIN_DIST_PATH * MIN_DIST_PATH) {
                    failing = false;
                }
	        }
	    }
	    return failing;
	}
	
	private static double getDistFromStartSq(BlockNode n, Vec3d target) {
        double xDiff = n.getPos().x - target.x;
        double yDiff = n.getPos().y - target.y;
        double zDiff = n.getPos().z - target.z;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff;
    }

	private static boolean isPathComplete(BlockNode node, Vec3d target, boolean failing) {
        return node.getPos().squaredDistanceTo(target) < 1.0D && !failing;
    }
	
	private static List<BlockNode> generatePath(BlockNode node, WorldView world) {
		BlockNode n = node;
		List<BlockNode> path = new ArrayList<>();

		path.add(n);
		while(n.previous != null) {
//		        BlockState state = world.getBlockState(n.getBlockPos());
//		        boolean isWater = BlockStateChecker.isAnyWater(state);
		        BlockNode lastN = path.getLast();
//		        boolean canGetFromLastNToCurrent = StreightMovementHelper.isPossible(world, lastN.getBlockPos(), n.getBlockPos());
		        double heightDiff = DistanceCalculator.getJumpHeight(lastN.getPos(true).getY(), n.getPos(true).getY());
//				if (!canGetFromLastNToCurrent) {
						path.add(n);
//						if (n.previous != null) path.add(n.previous);
//				}
				if (heightDiff <= 0 && lastN.getPos(true).distanceTo(n.getPos(true)) <= 1.44) path = stringPull(path);
				    	
			n = n.previous;
		}
		path.add(n);
//		while(n.previous != null) {
//		        BlockState state = world.getBlockState(n.getBlockPos());
//		        boolean isWater = BlockStateChecker.isAnyWater(state);
//		        BlockNode lastN = path.getLast();
//		        boolean canGetFromLastNToCurrent = StreightMovementHelper.isPossible(world, lastN.getBlockPos(), n.getBlockPos());
//		        double heightDiff = DistanceCalculator.getJumpHeight(lastN.getPos(true).getY(), n.getPos(true).getY());
//				if (heightDiff != 0) {
//					if (isWater && n.previous.previous != null)
//					{
//						path.add(n);
//						path.add(n.previous);
//						path.add(n.previous.previous);
//					} else if (!isWater) {
//						path.add(n);
////						path.add(n.previous);
//					}
//				} else if (isWater && !canGetFromLastNToCurrent) {
//					path.add(n);
//				} else if (
//						!isWater &&
//						(DistanceCalculator.getHorizontalEuclideanDistance(n.previous.getBlockPos(), n.getBlockPos()) > 1.44 ||
//						!canGetFromLastNToCurrent)
//						) {
//						path.add(n);
//						if (n.previous != null) path.add(n.previous);
//				}
//			n = n.previous;
//		}

//		path.add(n);
		
		List<BlockNode> path2 = new ArrayList<>();

//    	path2.add(path.get(0));
//		for (int i = 1; i < path.size(); i++) {
//			BlockNode blockNode = path.get(i);
//			BlockNode lastBlockNode = path.get(i-1);
//	        boolean canGetFromLastNToCurrent = StreightMovementHelper.isPossible(world, lastBlockNode.getBlockPos(), blockNode.getBlockPos());
//	        double distanceFromLastToCurrentNode = lastBlockNode.getPos(true).distanceTo(blockNode.getPos(true));
//	        if (!canGetFromLastNToCurrent || BlockStateChecker.isAnyWater(world.getBlockState(lastBlockNode.getBlockPos()))) {
//	        	if (!path2.contains(lastBlockNode)) path2.add(lastBlockNode);
//	        	path2.add(blockNode);
//	        } else if (distanceFromLastToCurrentNode > 1.44 || canGetFromLastNToCurrent || lastBlockNode.getBlockPos().getY() - blockNode.getBlockPos().getY() != 0) {
//	        	path2.add(blockNode);
//	        }
//		}
//    	path2.add(path.getFirst());
		for (int i = 0; i < path.size(); i++) {
			BlockNode blockNode = path.get(i);
        	if (blockNode.previous != null) {
            	path2.add(blockNode);
    	        double heightDiff = DistanceCalculator.getJumpHeight(blockNode.previous.getPos(true).getY(), blockNode.getPos(true).getY());
    	        if (heightDiff < 0 && !path2.contains(blockNode.previous)) {
    	        	path2.add(blockNode.previous);
    	        }
        	} else {

            	path2.add(blockNode);
        	}
		}
		Collections.reverse(path2);
		
		
		return path2;
	}
	
	
	public static List<BlockNode> stringPull(List<BlockNode> path) {
		int i = 0, j = 2;
		while (j < path.size()) {
			BlockNode pi = path.get(i);
			BlockNode pj = path.get(j);
			BlockNode p = path.get(j-1);
	        boolean canGetFromLastNToCurrent = StreightMovementHelper.isPossible(TungstenModDataContainer.world, pi.getBlockPos(), pj.getBlockPos());
//	        boolean canGetFromLastNToCurrent = StreightMovementHelper.isPossible(world, lastN.getBlockPos(), n.getBlockPos());
	        double heightDiff = p.previous == null ? 0 : DistanceCalculator.getJumpHeight(p.previous.getPos(true).getY(), p.getPos(true).getY());
//	        double distanceFromLastToCurrentNode = lastBlockNode.getPos(true).distanceTo(blockNode.getPos(true));
	        if (canGetFromLastNToCurrent && heightDiff <= 0 && p.previous.getPos(true).distanceTo(p.getPos(true)) <= 1.44) {
//	        	RenderHelper.renderBlockPath(path, j-1);
//	        	try {
//					Thread.sleep(500);
//				} catch (InterruptedException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
	        	path.remove(j-1);
	        } else {
	        	i = j-1;
	        }
	        j++;
		}
		return path;
	}
	
	
}
