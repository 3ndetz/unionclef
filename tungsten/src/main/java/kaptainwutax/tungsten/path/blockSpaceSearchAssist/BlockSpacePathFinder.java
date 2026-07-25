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
		// The search must never win CPU against the client thread. On a
		// container with few cores this thread starved the renderer down to
		// ~1 fps on generated terrain, and a bot that gets one frame per second
		// cannot move at all — the route was planned and the body stood still.
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.setDaemon(true);
		thread.start();
	}
	
	public static Optional<List<BlockNode>> search(WorldView world, Vec3d target, PlayerEntity player) {
		return search(world, target, false, player);
	}

	public static Optional<List<BlockNode>> search(WorldView world, BlockNode start, Vec3d target, PlayerEntity player) {
		return search(world, start, target, false, player);
	}
	
	private static Optional<List<BlockNode>> search(WorldView world, Vec3d target, boolean generateDeep, PlayerEntity player) {
		BlockPos startPos = player.getBlockPos();
		Goal goal = new Goal((int) target.x, (int) target.y, (int) target.z);

		// If standing inside a non-air block (fence, pane, chain, etc.),
		// find the nearest air block to start from — not just "up 1".
		if (!world.getBlockState(startPos).isAir()
				&& BlockShapeChecker.getShapeVolume(startPos, world) != 0
				&& BlockShapeChecker.getBlockHeight(startPos, world) > 0.5) {
			startPos = findNearestAirStart(world, startPos);
		}

		// SUPPORT SNAP — if the player can stand there, the search MUST be able to
		// start there. getBlockPos() floors the entity CENTRE, but the collision box
		// is 0.6 wide: standing on the EDGE of a block over a void (bedwars rim,
		// bridge lip) floors into the NEIGHBOURING column whose floor is air, so the
		// start node looked "in mid-air", generated no children and the search died
		// with "Ran out of nodes" while the bot stood perfectly still on solid ground.
		startPos = snapToSupport(world, player, startPos);

		return search(world, new BlockNode(startPos, goal, player, world), target, player);
	}

	/**
	 * Snap a start cell that has no floor onto the cell that actually supports the
	 * player (or, while airborne, onto the cell it is about to land on).
	 * Order: the footprint cells the collision box overlaps (nearest first) ->
	 * straight down to the landing cell -> a small radius sweep. Returns the input
	 * unchanged when it is already standable or nothing better exists.
	 */
	private static BlockPos snapToSupport(WorldView world, PlayerEntity player, BlockPos startPos) {
		if (isStandable(world, startPos)) return startPos;

		// 1) cells under the collision-box FOOTPRINT (the edge-standing case)
		net.minecraft.util.math.Box box = player.getBoundingBox();
		double[][] corners = {
			{box.minX, box.minZ}, {box.minX, box.maxZ},
			{box.maxX, box.minZ}, {box.maxX, box.maxZ},
		};
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (double[] c : corners) {
			BlockPos cand = BlockPos.ofFloored(c[0], startPos.getY(), c[1]);
			if (cand.equals(startPos) || !isStandable(world, cand)) continue;
			double d = cand.toCenterPos().squaredDistanceTo(player.getEntityPos());
			if (d < bestDist) { bestDist = d; best = cand; }
		}
		if (best != null) return best;

		// 2) airborne (falling / jumping): the cell we are about to land on
		for (int dy = 1; dy <= 8; dy++) {
			BlockPos cand = startPos.down(dy);
			if (isStandable(world, cand)) return cand;
		}

		// 3) last resort: nearest standable cell in a small sweep (+-2 xz, +-2 y)
		for (int r = 1; r <= 2; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					for (int dy = 1; dy >= -2; dy--) {
						BlockPos cand = startPos.add(dx, dy, dz);
						if (isStandable(world, cand)) return cand;
					}
				}
			}
		}
		return startPos;
	}

	/** Solid floor below + room for the body — the block-space notion of "can stand here". */
	private static boolean isStandable(WorldView world, BlockPos pos) {
		return !world.getBlockState(pos.down()).getCollisionShape(world, pos.down()).isEmpty()
				&& world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
				&& world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty();
	}

	/**
	 * Find the nearest passable start position by checking cardinal
	 * directions first, then up/down. Returns original pos if nothing found.
	 */
	private static BlockPos findNearestAirStart(WorldView world, BlockPos pos) {
		// Cardinal directions first (escape sideways from thin blocks)
		BlockPos[] candidates = {
			pos.north(), pos.south(), pos.east(), pos.west(),
			pos.up(), pos.down()
		};
		for (BlockPos candidate : candidates) {
			if (world.getBlockState(candidate).isAir()
					|| BlockShapeChecker.getShapeVolume(candidate, world) == 0) {
				return candidate;
			}
		}
		// Second ring
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockPos candidate = pos.add(dx, 0, dz);
				if (world.getBlockState(candidate).isAir()
						|| BlockShapeChecker.getShapeVolume(candidate, world) == 0) {
					return candidate;
				}
			}
		}
		return pos.up(); // fallback: original behavior
	}
	
	private static Optional<List<BlockNode>> search(WorldView world, BlockNode start, Vec3d target, boolean generateDeep, PlayerEntity player) {
		Goal goal = new Goal((int) target.x, (int) target.y, (int) target.z);
		boolean failing = true;
        int numNodes = 0;
        int timeCheckInterval = 1 << 6;
        long startTime = System.currentTimeMillis();
        long primaryTimeoutTime = startTime + (generateDeep ? 4800L : 480L);
		
        TungstenModRenderContainer.RENDERERS.clear();
		if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) Debug.logMessage("Searchin...");
		start = new BlockNode(start.getBlockPos(), goal, player, world);

		// Near-goal completion (smartMoves): the `failing` flag forces MIN_DIST_PATH
		// progress from the start before isPathComplete may fire. But when the goal is
		// ALREADY within MIN_DIST_PATH (a short receding-horizon re-plan next to the
		// target), no node ever gets 5 blocks away, so `failing` stays true forever and
		// the search "runs out of nodes" standing next to the goal. Reaching a close goal
		// IS completion — clear failing up front. (The legacy buggy distances hid this by
		// flipping failing instantly; gated so the legacy blind scan is untouched.)
		if (kaptainwutax.tungsten.TungstenConfig.get().smartMoves
				&& start.getPos().squaredDistanceTo(target) <= MIN_DIST_PATH * MIN_DIST_PATH) {
			failing = false;
		}

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
			numNodes++;
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

				for (int i = 0; i < COEFFICIENTS.length; i++) {
					double heuristic = child.estimatedCostToGoal + child.cost / COEFFICIENTS[i];
					if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
						bestHeuristicSoFar[i] = heuristic;
						bestSoFar[i] = child;
						if (failing && getDistFromStartSq(child, start.getPos()) > MIN_DIST_PATH * MIN_DIST_PATH) {
							failing = false;
						}
					}
				}
			}
		}

		if (openSet.isEmpty()) {
			if (!generateDeep) {
				return search(world, start, target, true, player);
			}
			// openSet exhausted — NO move sequence reaches the goal (a move-generation
			// reachability gap: the terrain needs a move type SmartMoves doesn't emit, e.g.
			// a slime bounce or a jump wider than parkour). Rather than return empty and let
			// the bot STAND STILL ("Ran out of nodes"), hand back the FURTHEST-progressed
			// partial path so it advances toward the goal and re-searches from there
			// (graceful degradation). bestSoFar only returns a path when there's real
			// forward progress (>1 block / >MIN_DIST_PATH), so a zero-progress search still
			// gives up cleanly here — no oscillation in place. (#67, user 2026-07-24)
			Optional<List<BlockNode>> partial = bestSoFar(true, numNodes, start, world);
			if (partial.isPresent()) {
				Debug.logMessage("Partial path (goal unreachable via move-gen) — advancing "
						+ partial.get().size() + " nodes toward goal");
				return partial;
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
        if (kaptainwutax.tungsten.TungstenConfig.get().smartMoves) {
            // SmartMoves rework: return the FURTHEST-progressed heuristically-best node
            // as a partial path (graceful degradation) — correct, unlike the legacy
            // inverted selection below which relies on the buggy distances.
            BlockNode best = null;
            double bd = -1;
            for (int i = 0; i < COEFFICIENTS.length; i++) {
                if (bestSoFar[i] == null) continue;
                double dist = getDistFromStartSq(bestSoFar[i], startNode.getPos());
                if (dist > bd) { bd = dist; best = bestSoFar[i]; }
            }
            if (best != null && bd > 1.0) {
                List<BlockNode> path = generatePath(best, world);
                if (path.size() > 1) return Optional.of(path);
            }
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
		double xzMultiplier = 1/*.2*/;
	    double dx = (target.x - position.x)*xzMultiplier;
	    double dy = 0;
	    double dz = (target.z - position.z)*xzMultiplier;
	    if (BlockStateChecker.isAnyWater(world.getBlockState(new BlockPos((int) position.x, (int) position.y, (int) position.z)))) {
	    	dy = (target.y - position.y)*1.8;
	    } else if (DistanceCalculator.getHorizontalManhattanDistance(position, target) < 32) {
	    	dy = (target.y - position.y)*1.5;
	    }
	    return (Math.sqrt(dx * dx + dy * dy + dz * dz)) /** 3*/;
	}
	
	private static void updateNode(BlockNode current, BlockNode child, Vec3d target, WorldView world) {
	    Vec3d childPos = child.getPos();
	    double tentativeCost = child.cost + 1; // Assuming uniform cost for each step

		if (BlockStateChecker.isAnyWater(child.getBlockState(world))) {
			tentativeCost += 1.8;
		}
		if (BlockStateChecker.isAnyWater(world.getBlockState(child.getBlockPos().up()))) {
			tentativeCost += 5.8;
		}

//	    tentativeCost += BlockStateChecker.isAnyWater(TungstenMod.mc.world.getBlockState(child.getBlockPos())) ? 50 : 0; // Assuming uniform cost for each step

	    double estimatedCostToGoal = computeHeuristic(childPos, target, world);

	    child.previous = current;
	    child.cost = tentativeCost;
	    child.estimatedCostToGoal = estimatedCostToGoal;
	    child.combinedCost = child.cost + estimatedCostToGoal;
	}
	
	private static double getDistFromStartSq(BlockNode n, Vec3d start) {
        // The original computed the Y and Z diffs from start.x (copy-paste bug) —
        // garbage distances. Correcting it alone regresses the blind-scan search
        // (course A depends on the garbage-driven partial paths), so the correct
        // form is gated behind smartMoves: the SmartMoves rework uses correct
        // distances, the legacy blind scan keeps the (buggy but working) behaviour.
        boolean fix = kaptainwutax.tungsten.TungstenConfig.get().smartMoves;
        double xDiff = start.x - n.getPos().x;
        double yDiff = (fix ? start.y : start.x) - n.getPos().y;
        double zDiff = (fix ? start.z : start.x) - n.getPos().z;
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
//				if (heightDiff <= 0 && lastN.getPos(true).distanceTo(n.getPos(true)) <= 1.44) path = stringPull(path);

			n = n.previous;
		}
		path.add(n);

		Collections.reverse(path);
		stringPull(path);


		return path;
	}


	public static void stringPull(List<BlockNode> path) {
		int i = 0, j = 2;
		while (j < path.size()) {
			BlockNode pi = path.get(i);
			BlockNode pj = path.get(j);
			BlockNode p = path.get(j-1);

	        boolean canGetFromLastNToCurrent = StreightMovementHelper.isPossible(TungstenModDataContainer.world, pi.getBlockPos(), pj.getBlockPos());
	        double heightDiff = p.previous == null ? 0 : DistanceCalculator.getJumpHeight(p.previous.getPos(true).getY(), p.getPos(true).getY());

	        if (canGetFromLastNToCurrent && !p.isDoingJump && !p.previous.isDoingJump && heightDiff == 0) {
	        	path.remove(j-1);
	        } else {
	        	i = j-1;
				j++;
	        }
		}
	}
	
	
}
