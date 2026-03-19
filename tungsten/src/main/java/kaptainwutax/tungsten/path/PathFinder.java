package kaptainwutax.tungsten.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;

import com.google.common.util.concurrent.AtomicDoubleArray;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.helpers.AgentChecker;
import kaptainwutax.tungsten.helpers.ArrayChunkSplitter;
import kaptainwutax.tungsten.helpers.BlockShapeChecker;
import kaptainwutax.tungsten.helpers.BlockStateChecker;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.helpers.blockPath.BlockPosShifter;
import kaptainwutax.tungsten.helpers.movement.StreightMovementHelper;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.path.calculators.BinaryHeapOpenSet;
import kaptainwutax.tungsten.render.Color;
import net.minecraft.block.BlockState;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.CobwebBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.StainedGlassPaneBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import javax.swing.*;

public class PathFinder {

	
	ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	public AtomicBoolean active = new AtomicBoolean(false);
	public AtomicBoolean stop = new AtomicBoolean(false);
	public Thread thread = null;
	private Set<Vec3d> closed = Collections.synchronizedSet(new HashSet<>());
	private AtomicDoubleArray bestHeuristicSoFar;
	private BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
	protected static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};
	protected static final AtomicReferenceArray<Node> bestSoFar = new AtomicReferenceArray<Node>(COEFFICIENTS.length);
	private static final double minimumImprovement = -500;
	private static Optional<List<BlockNode>> blockPath = Optional.empty();
	protected static final double MIN_DIST_PATH = 1.8;
	protected static AtomicInteger NEXT_CLOSEST_BLOCKNODE_IDX = new AtomicInteger(1);
	protected static AtomicInteger numNodesConsidered = new AtomicInteger(0);
	
	/** Max search time before emitting bestSoFar and continuing. Default: 112s (normal goto).
	 *  Set lower (e.g. 2000) for follow-entity to get fast partial paths. */
	public long searchTimeoutMs = 112000L;
	/** Minimum path length (nodes) required before a timeout partial-path can be emitted.
	 *  Default: 46 (~2.3s). Set lower (e.g. 5) for follow-entity close-range. */
	public int minPathSizeForTimeout = 46;

	/** Minimum path progress distance before bestSoFar can be accepted.
	 *  Default: MIN_DIST_PATH (1.8). Set near 0 for snap/dash mode (accept any path immediately). */
	public double minDistPath = MIN_DIST_PATH;

	private long startTime;
	private Node start;

	public Vec3d TARGET = new Vec3d(0.5D, 10.0D, 0.5D);
	
	synchronized public void find(WorldView world, Vec3d target, PlayerEntity player) {
		find(world, target, player, Optional.empty());
	}

    synchronized public void find(WorldView world, Vec3d target, PlayerEntity player, Optional<List<BlockNode>> blockPath) {

        if(active.get() || thread != null)return;
        active.set(true);
        stop.set(false);
        TARGET = target;
        PathFinder.blockPath = blockPath;
        numNodesConsidered.set(0);
        this.start = null;

        thread = new Thread(() -> {
            try {
                // Skip startup delays in aggressive close-range mode
                if (searchTimeoutMs > 500) {
                    while (!player.isOnGround() && !player.isTouchingWater()) {
                        if (stop.get()) break;
                        try {
                            Thread.sleep(500);
                        } catch(Exception e) {
                            e.printStackTrace();
                        }
                    }
                    Thread.sleep(500);
                }
                NEXT_CLOSEST_BLOCKNODE_IDX.set(1);
                if (blockPath.isPresent()) {
                    NEXT_CLOSEST_BLOCKNODE_IDX.set(findClosestPositionIDX(world, player.getBlockPos(), blockPath.get()));
                }
                search(world, target, player);
            } catch(Exception e) {
                e.printStackTrace();
            }

            active.set(false);
            this.thread = null;
            closed.clear();
            PathFinder.blockPath = Optional.empty();
            NEXT_CLOSEST_BLOCKNODE_IDX.set(1);

        });
        thread.setName("PathFinder");
        thread.setPriority(4);
        startTime = System.currentTimeMillis();
        thread.start();
    }
	
	private boolean checkForFallDamage(Node n, WorldView world) {
		if (TungstenModDataContainer.ignoreFallDamage) return false;
		if (BlockStateChecker.isAnyWater(world.getBlockState(n.agent.getBlockPos()))) return false;
		if (n.parent == null) return false;
		if (Thread.currentThread().isInterrupted()) return false;
		Node prev = null;
		do {
			if (Thread.currentThread().isInterrupted()) return false;
			if (stop.get()) break;
			if (prev == null) {
				prev = n.parent;
			} else {
				prev = prev.parent;
			}
			double currFallDist = DistanceCalculator.getJumpHeight(prev.agent.getPos().y, n.agent.getPos().y);
			if (currFallDist < -3) {
				return true;
			}
		} while (!prev.agent.onGround && !prev.agent.touchingWater);

		if (DistanceCalculator.getJumpHeight(prev.agent.getPos().y, n.agent.getPos().y) < -3) {
//			RenderHelper.clearRenderers();
//        	RenderHelper.renderNode(prev);
//        	TungstenMod.RENDERERS.add(new Cuboid(prev.agent.getPos().subtract(0.05D, 0.05D, 0.05D), new Vec3d(0.3D, 0.8D, 0.3D), prev.color));
//        	RenderHelper.renderNode(n);
//        	try {
// 				Thread.sleep(150);
// 			} catch (InterruptedException e) {
// 				// TODO Auto-generated catch block
// 				e.printStackTrace();
// 			}
			return true;
		}
		return false;
	}

	private void search(WorldView world, Vec3d target, PlayerEntity player) {
		search(world, null, target, player);
	}
	
	private void search(WorldView world, Node start, Vec3d target, PlayerEntity player) {
	    boolean failing = true;
	    TungstenModRenderContainer.RENDERERS.clear();
	
	    long startTime = System.currentTimeMillis();
	    long primaryTimeoutTime = startTime + searchTimeoutMs;
		numNodesConsidered.set(0);
	    int timeCheckInterval = 1 << 3;
	    double minVelocity = BlockStateChecker.isAnyWater(world.getBlockState(new BlockPos((int) target.getX(), (int) target.getY(), (int) target.getZ()))) ? 0.2 :  0.07;
	
	    if (player.getPos().distanceTo(target) < 1.0 && minDistPath >= MIN_DIST_PATH) {
	        Debug.logMessage("Already at target location!");
	        return;
	    }
	    if (start == null) {
		    	start = initializeStartNode(player, target);
		    	this.start = start;
	    }
	    if (blockPath.isEmpty()) {
		    long tBlock0 = TungstenConfig.get().debugTime ? System.nanoTime() : 0;
		    Optional<List<BlockNode>> blockPath = findBlockPath(world, target, player);
		    if (blockPath.isPresent()) {
	        	RenderHelper.renderBlockPath(blockPath.get(), NEXT_CLOSEST_BLOCKNODE_IDX.get());
	        	PathFinder.blockPath = blockPath;
	    	    NEXT_CLOSEST_BLOCKNODE_IDX.set(1);

				Debug.logMessage("Serching for inputs!");
	        }
	        if (TungstenConfig.get().debugTime) {
	            System.out.printf("Tungsten [blockSearch] %.1fms | found=%b | nodes=%d%n",
	                (System.nanoTime() - tBlock0) / 1_000_000.0,
	                blockPath.isPresent(),
	                blockPath.isPresent() ? blockPath.get().size() : 0);
	        }
	    }
	    if (blockPath.isEmpty() || blockPath.get().size() < 1) {
	    	Debug.logWarning("Failed! No block path");
	    	return;
	    }
	
	    bestHeuristicSoFar = initializeBestHeuristics(this.start);
	    openSet = new BinaryHeapOpenSet();
	    openSet.insert(this.start);
	    closed.clear();

	    while (!openSet.isEmpty()) {
		    if (blockPath.isEmpty() || blockPath.get().size() < 1) {
		    	return;
		    }
	        if (stop.get()) {
	        	RenderHelper.clearRenderers();
	            break;
	        }
	
	        if (blockPath.isPresent() && TungstenModRenderContainer.BLOCK_PATH_RENDERER.isEmpty()) {
	        	RenderHelper.renderBlockPath(blockPath.get(), NEXT_CLOSEST_BLOCKNODE_IDX.get());
	        }
	
	        Node next = openSet.removeLowest();
	        
            // Search for a path without fall damage
            if (checkForFallDamage(next, world)) {
            	continue;
            }
	
	        if (shouldSkipNode(next, target, closed, blockPath, world)) {
//	        	Debug.logMessage("Skipped");
	            continue;
	        }

	
	        if (isPathComplete(next, target, failing, world)) {
	            if (tryExecutePath(next, target, minVelocity)) {
	            	TungstenModRenderContainer.RENDERERS.clear();
	            	TungstenModRenderContainer.TEST.clear();
	    			closed.clear();
	    			PathFinder.blockPath = Optional.empty();
	                return;
	            }
	        } else if ((numNodesConsidered.get() & (timeCheckInterval - 1)) == 0 && blockPath.isPresent() && NEXT_CLOSEST_BLOCKNODE_IDX.get() == (blockPath.get().size()-1) && blockPath.get().getLast().getPos(true, world).distanceTo(target) > 5) {
    			BlockNode lastBlockNode = blockPath.get().getLast();
	        	if (setCurrentPath(TARGET, next, TungstenModDataContainer.player)) {
	        		TungstenModRenderContainer.RENDERERS.clear();
	        		TungstenModRenderContainer.TEST.clear();
	    			closed.clear();
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	    			while (TungstenModDataContainer.EXECUTOR.isRunning()) {
	    				if (stop.get()) return;
	    				if (TungstenModDataContainer.EXECUTOR.getPath().size() - TungstenModDataContainer.EXECUTOR.getCurrentTick() < 50) break;
						try {
							Thread.sleep(500);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}

	    		    primaryTimeoutTime = System.currentTimeMillis() + 1120L;
	        		if (blockPath.get().getLast().getPos(true, world).distanceTo(player.getPos()) < 20) {
		    			int attempt = 0;
		    			while (attempt < 3) {
                            if (stop.get()) break;
		    				PathFinder.blockPath = findBlockPath(world, lastBlockNode, target, player);
			    		    if (blockPath.isPresent()) {
			    		    	NEXT_CLOSEST_BLOCKNODE_IDX.set(1);
			    	        	RenderHelper.renderBlockPath(blockPath.get(), NEXT_CLOSEST_BLOCKNODE_IDX.get());
			    	        	break;
			    	        }
			    		    attempt++;
			    		    try {
								Thread.sleep(250);
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
		    		    if (blockPath.isEmpty()) {
		    	        	Debug.logMessage("Failed to find furhter path!");
		    		    }
	        		}
	    		    continue;
	            }
	        }
	
	        if (shouldResetSearch(numNodesConsidered.get(), blockPath, next, target)) {
	        	TungstenModDataContainer.EXECUTOR.cb = () -> {
		        	blockPath = resetSearch(next, world, blockPath, target, player);
	        	};
	            openSet = new BinaryHeapOpenSet();
	            this.start = initializeStartNode(next, target);
	            openSet.insert(this.start);
	            while (TungstenModDataContainer.EXECUTOR.isRunning()) {
                    if (stop.get()) break;
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
	            continue;
	        }

	        if ((numNodesConsidered.get() & (timeCheckInterval - 1)) == 0) {
	            if (handleTimeout(startTime, primaryTimeoutTime, next, target, start, player, closed)) {
	            	primaryTimeoutTime = System.currentTimeMillis() + 1120L;
	                continue;
	            }
	        }
	        
	        if (numNodesConsidered.get() % 20 == 0) {
	        	RenderHelper.renderPathSoFar(next);
	        }
	
	        failing = processNodeChildren(world, next, target, blockPath, openSet, closed);

	        numNodesConsidered.set(numNodesConsidered.get()+1);
	        if (updateNextClosestBlockNodeIDX(blockPath.get(), next, closed, world)) {
	        	primaryTimeoutTime = System.currentTimeMillis() + 1120L;
	        }
//        	if (numNodesConsidered % 5 == 0 && updateNextClosestBlockNodeIDX(blockPath.get(), next, closed)) {
//        		List<Node> path = constructPath(next);
//                TungstenModDataContainer.EXECUTOR.addPath(path);
//                Node n = path.getLast();
//                clearParentsForBestSoFar(n);
//                start = initializeStartNode(n, target);
//    			closed.clear();
//    			bestHeuristicSoFar = initializeBestHeuristics(start);
//    		    openSet = new BinaryHeapOpenSet();
//    		    openSet.insert(start);
//        	}
	        
//	        try {
//				Thread.sleep(250);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
	    }
	
	    if (stop.get()) {
	        if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) Debug.logMessage("stopped!");
	        stop.set(false);
	    } else if (openSet.isEmpty()) {
	        TungstenMod.LOG.info("[PathFinder] Ran out of nodes, trying partial path...");
	        // Instead of giving up, emit bestSoFar partial path
	        Optional<List<Node>> partial = PathFinder.bestSoFar(false, 0, this.start, TARGET);
	        if (partial.isPresent() && partial.get().size() >= 2) {
	            executePath(partial.get());
	            TungstenMod.LOG.info("[PathFinder] Emitted partial path: " + partial.get().size() + " nodes");
	        } else {
	            TungstenMod.LOG.info("[PathFinder] No usable partial path found.");
	        }
	    }
	    if (TungstenConfig.get().debugTime) {
	        long elapsed = System.currentTimeMillis() - startTime;
	        System.out.printf("Tungsten [search done] %dms total | %d nodes explored | openSet empty=%b | stopped=%b%n",
	            elapsed, numNodesConsidered.get(), openSet.isEmpty(), stop.get());
	    }
	    RenderHelper.clearRenderers();
		closed.clear();
		PathFinder.blockPath = Optional.empty();
	}
	protected static Optional<List<Node>> bestSoFar(boolean logInfo, int numNodes, Node startNode, Vec3d realTarget) {
        if (startNode == null) {
            return Optional.empty();
        }
        double bestDist = 0;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            if (bestSoFar.get(i) == null || bestSoFar.get(i).parent == null) {
                continue;
            }
            double dist = startNode.agent.getPos().squaredDistanceTo(bestSoFar.get(i).agent.getPos());
            if (dist > bestDist) {
                bestDist = dist;
            }
            if (bestDist > TungstenModDataContainer.PATHFINDER.minDistPath * TungstenModDataContainer.PATHFINDER.minDistPath) {
//                if (logInfo) {
//                    if (COEFFICIENTS[i] >= 3) {
//                        System.out.println("Warning: cost coefficient is greater than three! Probably means that");
//                        System.out.println("the path I found is pretty terrible (like sneak-bridging for dozens of blocks)");
//                        System.out.println("But I'm going to do it anyway, because yolo");
//                    }
//                    System.out.println("Path goes for " + Math.sqrt(dist) + " blocks");
//                }

                Node n = bestSoFar.get(i);
                if (!n.agent.onGround && !n.agent.touchingWater && !n.agent.isClimbing(TungstenModDataContainer.world)) continue;
                List<Node> path = new ArrayList<>();
				while(n.parent != null) {
					path.add(n);
					n = n.parent;
				}

				path.add(n);
				Collections.reverse(path);
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }
	
	private void clearParentsForBestSoFar(Node node) {
		for (int i = 0; i < COEFFICIENTS.length; i++) {
			bestSoFar.set(i, null);
		}
	}

	private boolean shouldSkipChild(Node child, Vec3d target, Set<Vec3d> closed, Optional<List<BlockNode>> blockPath, WorldView world) {
	    return child.agent.touchingWater && shouldSkipNode(child, target, closed, blockPath, world);
	}
	
	private boolean shouldSkipNode(Node node, Vec3d target, Set<Vec3d> closed, Optional<List<BlockNode>> blockPath, WorldView world) {
//	    BlockNode bN = blockPath.get().get(NEXT_CLOSEST_BLOCKNODE_IDX.get());
//	    BlockNode lBN = blockPath.get().get(NEXT_CLOSEST_BLOCKNODE_IDX.get()-1);
//	    boolean isBottomSlab = BlockStateChecker.isBottomSlab(TungstenMod.mc.world.getBlockState(bN.getBlockPos().down()));
//	    Vec3d agentPos = node.agent.getPos();
//	    Vec3d parentAgentPos = node.parent == null ? null : node.parent.agent.getPos();
//	    if (!isBottomSlab && !node.agent.onGround && agentPos.y < bN.y && lBN != null && lBN.y <= bN.y && parentAgentPos != null && parentAgentPos.y > agentPos.y) {
//	    	return true;
//	    }
	    // Clamp idx to valid range to prevent IndexOutOfBoundsException when
	    // NEXT_CLOSEST_BLOCKNODE_IDX reaches blockPath.size() at end of path.
	    int _idx = blockPath.isPresent()
	        ? Math.min(NEXT_CLOSEST_BLOCKNODE_IDX.get(), blockPath.get().size() - 1)
	        : 0;
	    int _prevIdx = Math.max(0, _idx - 1);
	    return shouldNodeBeSkipped(node, target, closed, true,
	        blockPath.isPresent() && (
	            blockPath.get().get(_idx).isDoingLongJump(world) ||
	            blockPath.get().get(_idx).isDoingNeo() ||
	            blockPath.get().get(_prevIdx).isDoingCornerJump()
	        ),
	        blockPath.isPresent() && !blockPath.get().get(_idx).isDoingNeo()
	    );
	}
	
	private static boolean shouldNodeBeSkipped(Node n, Vec3d target, Set<Vec3d> closed, boolean addToClosed, boolean isDoingLongJump, boolean shouldAddYaw) {

		int hashCode = n.hashCode(1, shouldAddYaw);
	    Vec3d agentPos = n.agent.getPos();
	    double distanceToTarget = agentPos.distanceTo(target);

	    // Determine scaling factors based on conditions
	    double xScale, yScale, zScale;
	    if (distanceToTarget < 1.0 /* || n.agent.isSubmergedInWater || n.agent.isClimbing(MinecraftClient.getInstance().world) */) {
	        xScale = 1000;
	        yScale = 1000;
	        zScale = 1000;
	    } else if (isDoingLongJump) {
	        xScale = 10;
	        yScale = 100;
	        zScale = 10;
	    } else if (n.agent.isClimbing(TungstenModDataContainer.world)) {
	        xScale = 1;
	        yScale = 10000;
	        zScale = 1;
	    } else if (n.agent.touchingWater) {
	        xScale = 1000;
	        yScale = 100;
	        zScale = 1000;
	    } else {
	        xScale = 1000;
	        yScale = 100;
	        zScale = 1000;
	    }

	    // Compute scaled position with hashCode offset
	    Vec3d scaledPos = computeScaledPosition(agentPos, hashCode, xScale, yScale, zScale);

	    // Check if the position is in the closed set
	    if (closed.contains(scaledPos)) {
	        return true;
	    }

	    // Optionally add the position to the closed set
	    if (addToClosed) {
	        closed.add(scaledPos);
	    }

	    return false;
	}
	
	private static Vec3d computeScaledPosition(Vec3d pos, int hashCode, double xScale, double yScale, double zScale) {
	    return new Vec3d(
	        Math.round(pos.x * xScale),
	        Math.round(pos.y * yScale),
	        Math.round(pos.z * zScale)
	    );
	}
	
	private static double computeHeuristic(Vec3d position, boolean onGround, Vec3d target, Vec3d realTarget) {
		double xzMultiplier = 1.3;
	    double dx = (position.x - target.x)*xzMultiplier;
	    double dy = 0;
	    if (target.y != Double.MIN_VALUE) {
		    dy = (position.y - target.y) * 4.8;//*16;
		    if (!onGround || dy < 1.6 && dy > -1.6) dy = 0;
	    }
	    double dz = (position.z - target.z)*xzMultiplier;
	    return (Math.sqrt(dx * dx + dy * dy + dz * dz) * 2.8
	    		 + (((blockPath.isPresent() ? blockPath.get().size() - NEXT_CLOSEST_BLOCKNODE_IDX.get() : 0)) * 40)
	    		+ (DistanceCalculator.getEuclideanDistance(position, realTarget) * 0.2)
	    		);
	}
	
	private static void updateNode(WorldView world, Node current, Node child, Vec3d target, Vec3d realTarget, List<BlockNode> blockPath, Set<Vec3d> closed) {
	    Vec3d childPos = child.agent.getPos();

	    double collisionScore = 0;
	    double tentativeCost = child.cost + 1; // Assuming uniform cost for each step
	    if (child.agent.horizontalCollision && child.agent.getPos().distanceTo(target) > 3) {
	        collisionScore += 25 + (Math.abs(0.3 - child.agent.velZ) + Math.abs(0.3 - child.agent.velX)) * (child.agent.blockY <= blockPath.get(NEXT_CLOSEST_BLOCKNODE_IDX.get()).getBlockPos().getY() ? 2 : 1);
	    }
	    
	    if (child.agent.touchingWater) {
//	    	collisionScore = 20000^20;
	    	if (BlockStateChecker.isAnyWater(world.getBlockState(blockPath.get(NEXT_CLOSEST_BLOCKNODE_IDX.get()).getBlockPos()))) collisionScore -= 20;
//	    	else collisionScore += 2000;
	    	
	    } else {
	    	float forwardSpeedScore = 0.98f - Math.abs(child.agent.forwardSpeed);
	    	float sidewaysSpeedScore = 0.98f - Math.abs(child.agent.sidewaysSpeed);
	    	collisionScore += 
//	    			(sidewaysSpeedScore > 1e-8 || sidewaysSpeedScore < -1e-8 ? 5 : 0 ) 
	    			 (forwardSpeedScore > 1e-8 || forwardSpeedScore < -1e-8 ? 15 : 0 )
	    			 + (forwardSpeedScore );
//	        collisionScore += (Math.abs(0.3 - child.agent.velZ) + Math.abs(0.3 - child.agent.velX)) * (child.agent.blockY <= blockPath.get(NEXT_CLOSEST_BLOCKNODE_IDX.get()).getBlockPos().getY() ? 4 : 3);
	    }
//	    if (child.agent.isClimbing(world)) {
////	    	collisionScore *= 20000;
//	    	collisionScore += 12;
//	    }
	    if (world.getBlockState(child.agent.getBlockPos()).getBlock() instanceof CobwebBlock) {
	    	collisionScore += 20000;
	    }
//	    if (child.agent.slimeBounce) {
//	    	collisionScore -= 20000;
//	    }

	    double estimatedCostToGoal = /*computeHeuristic(childPos, child.agent.onGround, target) - 200 +*/ collisionScore;
	    if (blockPath != null) {
//	    		updateNextClosestBlockNodeIDX(blockPath, child, closed);
		    	Vec3d posToGetTo = BlockPosShifter.getPosOnLadder(blockPath.get(NEXT_CLOSEST_BLOCKNODE_IDX.get()), world);
		    	
		    	if (child.agent.getPos().squaredDistanceTo(target) <= 2.0D) {
		    		posToGetTo = target;
		    	}
		    	
	    	estimatedCostToGoal +=  computeHeuristic(childPos, child.agent.onGround || child.agent.slimeBounce, posToGetTo, realTarget);
	    }

	    child.parent = current;
	    child.cost = tentativeCost;
	    child.estimatedCostToGoal = estimatedCostToGoal;
	    child.combinedCost = tentativeCost + estimatedCostToGoal;
	}
	
	private static int findClosestPositionIDX(WorldView world, BlockPos current, List<BlockNode> positions) {
        if (positions == null || positions.isEmpty()) {
            throw new IllegalArgumentException("The list of positions must not be null or empty.");
        }

        int closestIDX = NEXT_CLOSEST_BLOCKNODE_IDX.get();
        BlockNode currentNode = positions.get(closestIDX);
        boolean isCurrentNodeLadder = currentNode.getBlockState(world).getBlock() instanceof LadderBlock;
        BlockNode closest = positions.get(closestIDX);
        boolean isClosestNodeLadder = closest.getBlockState(world).getBlock() instanceof LadderBlock;
        double minDistance = current.getSquaredDistance(closest.getPos(true, world))/* + Math.abs(closest.y - current.getY()) * 160*/;
        int maxLoop = Math.min(closestIDX+20, positions.size());
        for (int i = closestIDX+1; i < maxLoop; i++) {
        	BlockNode position = positions.get(i);
//			if (i % 5 != 0) {
//        		continue;
//        	}
            double distance = current.getSquaredDistance(position.getPos(true, world))/* + Math.abs(position.y - current.getY()) * 160*/;
            double heightDiff = closest.getJumpHeight(currentNode.getPos(true).y, closest.getPos(true).y);
//            if ( distance < 1 && closestIDX < i-1) continue;
            if (distance < minDistance/* && (heightDiff <= 0 || isCurrentNodeLadder || isClosestNodeLadder)*/) {
                minDistance = distance;
                closest = position;
                closestIDX = i;
                isClosestNodeLadder = closest.getBlockState(world).getBlock() instanceof LadderBlock;
            }
		}
        return closestIDX;
    }
	
	private static boolean updateBestSoFar(Node child, Vec3d target, AtomicDoubleArray bestHeuristicSoFar) {
		boolean failing = true;
	    for (int i = 0; i < COEFFICIENTS.length; i++) {
	        double heuristic = child.combinedCost / COEFFICIENTS[i];
	        if (bestHeuristicSoFar.get(i) - heuristic > minimumImprovement && bestHeuristicSoFar.get(i) != heuristic) {
	            bestHeuristicSoFar.set(i, heuristic);
	            bestSoFar.set(i, child);
//	            if (failing && getDistFromStartSq(child, target) > MIN_DIST_PATH * MIN_DIST_PATH) {
                    failing = false;
//                }
	        }
	    }
	    return failing;
	}
	
	protected static double getDistFromStartSq(Node n, Vec3d target) {
        double xDiff = n.agent.getPos().x - target.x;
        double yDiff = n.agent.getPos().y - target.y;
        double zDiff = n.agent.getPos().z - target.z;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff;
    }
	
	private Node initializeStartNode(Node node, Vec3d target) {
        Node start = new Node(null,  Agent.of(node.agent, node.agent.input.toPathInput()), new Color(255, 255, 255), 0);
        start.agent.tick(TungstenModDataContainer.world);
        start.combinedCost = computeHeuristic(start.agent.getPos(), start.agent.onGround, target, TARGET);
        return start;
    }

	
	private Node initializeStartNode(PlayerEntity player, Vec3d target) {
        Node start = new Node(null, Agent.of(player), new Color(255, 255, 255), 0);
        start.combinedCost = computeHeuristic(start.agent.getPos(), start.agent.onGround, target, TARGET);
        return start;
    }

    private Optional<List<BlockNode>> findBlockPath(WorldView world, Vec3d target, PlayerEntity player) {
        return kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockSpacePathFinder.search(world, target, player);
    }
    
    private Optional<List<BlockNode>> findBlockPath(WorldView world, BlockNode start, Vec3d target, PlayerEntity player) {
        return kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockSpacePathFinder.search(world, start, target, player);
    }

    private AtomicDoubleArray initializeBestHeuristics(Node start) {
    	AtomicDoubleArray bestHeuristicSoFar = new AtomicDoubleArray(COEFFICIENTS.length);
        for (int i = 0; i < bestHeuristicSoFar.length(); i++) {
            bestHeuristicSoFar.set(i, start.combinedCost / COEFFICIENTS[i]);
            bestSoFar.set(i, start);
        }
        return bestHeuristicSoFar;
    }
    
    private boolean isPathComplete(Node node, Vec3d target, boolean failing, WorldView world) {
    	if (BlockStateChecker.isAnyWater(world.getBlockState(new BlockPos((int) target.getX(), (int) target.getY(), (int) target.getZ()))))
    		return node.agent.getPos().squaredDistanceTo(target) <= 0.9D;
    	if (world.getBlockState(new BlockPos((int) target.getX(), (int) target.getY(), (int) target.getZ())).getBlock() instanceof LadderBlock)
    		return node.agent.getPos().squaredDistanceTo(target) <= 0.9D;
        return node.agent.getPos().squaredDistanceTo(target) <= 0.2D;
    }

    private boolean tryExecutePath(Node node, Vec3d target, double minVelocity) {
    	TungstenModRenderContainer.TEST.clear();
    	RenderHelper.renderPathSoFar(node);
//    	while (TungstenModDataContainer.EXECUTOR.isRunning()) {
//    		try {
//				Thread.sleep(50);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//    	}
        if (AgentChecker.isAgentStationary(node.agent, minVelocity) || 
        		TungstenModDataContainer.world.getBlockState(new BlockPos((int) target.getX(), (int) target.getY(), (int) target.getZ())).getBlock() instanceof LadderBlock) {
            List<Node> path = constructPath(node);
            executePath(path);
            return true;
        }
        return false;
    }

    private List<Node> constructPath(Node node) {
        List<Node> path = new ArrayList<>();
        TungstenModRenderContainer.RUNNING_PATH_RENDERER.clear();
        while (node.parent != null) {
            path.add(node);
            RenderHelper.renderNodeConnection(node, node.parent);
            node = node.parent;
        }
        path.add(node);
        Collections.reverse(path);
        return path;
    }

    private void executePath(List<Node> path) {
        TungstenModDataContainer.EXECUTOR.cb = () -> {
            Debug.logMessage("Finished!");
            RenderHelper.clearRenderers();
        };
        if (TungstenModDataContainer.EXECUTOR.isRunning()) {
            TungstenModDataContainer.EXECUTOR.addPath(path);
            TungstenModDataContainer.EXECUTOR.blockPath = blockPath.orElseGet(null);
        } else {        	
        	TungstenModDataContainer.EXECUTOR.setPath(path);
            TungstenModDataContainer.EXECUTOR.blockPath = blockPath.orElseGet(null);
        }
		long endTime = System.currentTimeMillis();
		long elapsedTime = endTime - startTime;
		long minutes = (elapsedTime / 1000) / 60;
        long seconds = (elapsedTime / 1000) % 60;
        long milliseconds = elapsedTime % 1000;
        
        Debug.logMessage("Time taken to find path: " + minutes + " minutes, " + seconds + " seconds, " + milliseconds + " milliseconds");
    }

    private boolean shouldResetSearch(int numNodesConsidered, Optional<List<BlockNode>> blockPath, Node next, Vec3d target) {
        return (numNodesConsidered & (8 - 1)) == 0 &&
               NEXT_CLOSEST_BLOCKNODE_IDX.get() > blockPath.get().size() - 10 &&
               !TungstenModDataContainer.EXECUTOR.isRunning() &&
               blockPath.get().get(blockPath.get().size() - 1).getPos().squaredDistanceTo(next.agent.getPos()) < 3.0D &&
               blockPath.get().get(blockPath.get().size() - 1).getPos().squaredDistanceTo(target) > 1.0D &&
               AgentChecker.isAgentStationary(next.agent, 0.08);
    }

    private Optional<List<BlockNode>> resetSearch(Node next, WorldView world, Optional<List<BlockNode>> blockPath, Vec3d target, PlayerEntity player) {
    	BlockNode lastNode = blockPath.get().getLast();
    	lastNode.previous = null;
        blockPath = findBlockPath(world, lastNode, target, player);
        if (blockPath.isPresent()) {
            List<Node> path = constructPath(next);
            TungstenModDataContainer.EXECUTOR.setPath(path);
            TungstenModDataContainer.EXECUTOR.blockPath = blockPath.orElseGet(null);
            NEXT_CLOSEST_BLOCKNODE_IDX.set(1);
        	RenderHelper.renderBlockPath(blockPath.get(), NEXT_CLOSEST_BLOCKNODE_IDX.get());
        	return blockPath;
        }
        Debug.logWarning("Failed!");
        stop.set(true);
        return Optional.empty();
    }

    private boolean handleTimeout(long startTime, long primaryTimeoutTime, Node next, Vec3d target, Node start, PlayerEntity player, Set<Vec3d> closed) {
        long now = System.currentTimeMillis();
        if (now < primaryTimeoutTime) return false;
        Optional<List<Node>> result = PathFinder.bestSoFar(true, 0, start, TungstenModDataContainer.PATHFINDER.TARGET);

	      // Emit partial path if: result exists, long enough, last node is stable (on ground or in water),
	      // not climbing (mid-climb is unsafe to cut), and path covers meaningful distance.
	      // Bug fix: was (onGround && touchingWater) — nearly impossible, now (onGround || touchingWater).
	      boolean aggressive = TungstenModDataContainer.PATHFINDER.minDistPath < MIN_DIST_PATH;
	      if (!result.isPresent() || result.get().size() < minPathSizeForTimeout
	      		|| (!aggressive && (
	      		    (!result.get().getLast().agent.onGround && !result.get().getLast().agent.touchingWater)
	      		    || result.get().getLast().agent.isClimbing(TungstenModDataContainer.world)))
	      		|| result.get().getLast().agent.getPos().distanceTo(result.get().getFirst().agent.getPos()) < TungstenModDataContainer.PATHFINDER.minDistPath * 2.0) {
	          return false;
	      }
//        if (player.getPos().distanceTo(result.get().getFirst().agent.getPos()) < 1 && next.agent.getPos().distanceTo(target) > 1) {
	    if (setCurrentPath(target, start, player)) {
	    	if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) Debug.logMessage("Time ran out!");
		    return true;
	    }
//        }
        return false;
    }
    
    private static boolean setCurrentPath(Vec3d target, Node start, PlayerEntity player) {
        Optional<List<Node>> result = PathFinder.bestSoFar(true, 0, start, TungstenModDataContainer.PATHFINDER.TARGET);

        if (!result.isPresent()) {
            return false;
        }
        Node newStart = null;
        if (result.get().getLast() != null) {
        	newStart = TungstenModDataContainer.PATHFINDER.initializeStartNode(result.get().getLast(), target);
        } else if (result.get().get(result.get().size()-2) != null) {
        	newStart = TungstenModDataContainer.PATHFINDER.initializeStartNode(result.get().get(result.get().size()-2), target);
        }
        if (newStart == null || !newStart.agent.onGround && !newStart.agent.touchingWater && !newStart.agent.isClimbing(TungstenModDataContainer.world)) return false;
//        if (TungstenModDataContainer.EXECUTOR.getPath() != null && TungstenModDataContainer.EXECUTOR.getPath().getLast().hashCode(1, true) == result.get().getLast().hashCode(1, true)) return false;
//        if (TungstenModDataContainer.EXECUTOR.getPath() != null && TungstenModDataContainer.EXECUTOR.getPath().getFirst().hashCode(1, true) == result.get().getFirst().hashCode(1, true)) return false;
        TungstenModDataContainer.EXECUTOR.addPath(result.get());
        TungstenModDataContainer.EXECUTOR.blockPath = blockPath.orElseGet(null);
//        RenderHelper.renderPathCurrentlyExecuted();
        for (int i = 0; i < COEFFICIENTS.length; i++) {
	        TungstenModDataContainer.PATHFINDER.bestSoFar.set(i, null);
		}
        TungstenModDataContainer.PATHFINDER.clearParentsForBestSoFar(newStart);
        TungstenModDataContainer.PATHFINDER.closed.clear();
        TungstenModDataContainer.PATHFINDER.bestHeuristicSoFar = TungstenModDataContainer.PATHFINDER.initializeBestHeuristics(newStart);
        TungstenModDataContainer.PATHFINDER.openSet = new BinaryHeapOpenSet();
        TungstenModDataContainer.PATHFINDER.openSet.insert(newStart);
        TungstenModDataContainer.PATHFINDER.start = newStart;
        numNodesConsidered.set(0);
//        try {
//			Thread.sleep(150);
//		} catch (InterruptedException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//        RenderHelper.clearRenderers();
//        Node finalNewStart = newStart;
//        (new Runnable() {
//			
//			@Override
//			public void run() {
//				// TODO Auto-generated method stub
//		        TungstenModDataContainer.PATHFINDER.search(TungstenModDataContainer.world, finalNewStart, target, player);
//				
//			}
//		}).run();
        return true;
    }
    
    private boolean filterChidren(Node child, BlockNode lastBlockNode, BlockNode nextBlockNode, boolean isSmallBlock, WorldView world) {
    	boolean isLadder = nextBlockNode.getBlockState(world).getBlock() instanceof LadderBlock;
    	boolean isLadderBelow = world.getBlockState(nextBlockNode.getBlockPos().down()).getBlock() instanceof LadderBlock;
    	if (isLadder || isLadderBelow) return child.agent.getPos().getY() < (nextBlockNode.getPos(true).getY() - 3.6);
//    	double distB = DistanceCalculator.getHorizontalEuclideanDistance(lastBlockNode.getPos(true), nextBlockNode.getPos(true));
    	
//    	if (distB > 6 || child.agent.isClimbing(TungstenModDataContainer.world)) return  child.agent.getPos().getY() < (nextBlockNode.getPos(true).getY() - 0.8);
    	
    	if (nextBlockNode.isDoingNeo())
    		return child.agent.getBlockPos().getY() != nextBlockNode.getBlockPos().getY();

    	if (nextBlockNode.isDoingLongJump(world)) return child.agent.getBlockPos().getY() < nextBlockNode.getBlockPos().getY()-1;

    	if (isSmallBlock) return child.agent.getPos().getY() < (nextBlockNode.getPos(true).getY()-1);

    	return child.agent.getPos().getY() < (nextBlockNode.getPos(true).getY() - 1.5);
//    	return false;
    }

    private boolean processNodeChildren(WorldView world, Node parent, Vec3d target, Optional<List<BlockNode>> blockPath,
            BinaryHeapOpenSet openSet, Set<Vec3d> closed) {
			boolean timing = TungstenConfig.get().debugTime;
			long t0 = timing ? System.nanoTime() : 0;

			AtomicBoolean failing = new AtomicBoolean(true);
			if (blockPath.isEmpty()) return false;
			List<Node> children = parent.getChildren(world, target, blockPath.get().get(NEXT_CLOSEST_BLOCKNODE_IDX.get()));
			if (children.isEmpty()) return false;

			long tChildren = timing ? System.nanoTime() : 0;
			
//			Debug.logMessage("All children");
//			for (Node node : children) {
//				if (stop.get()) return false;
//		    	if (Thread.currentThread().isInterrupted()) return false;
//		        RenderHelper.renderNode(node);
//			}
//			try {
//				Thread.sleep(500);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
			
			Queue<Node> validChildren = new ConcurrentLinkedQueue<>();

			BlockNode lastBlockNode = blockPath.get().get(NEXT_CLOSEST_BLOCKNODE_IDX.get()-1);
			BlockNode nextBlockNode = blockPath.get().get(NEXT_CLOSEST_BLOCKNODE_IDX.get());
	        double closestBlockVolume = BlockShapeChecker.getShapeVolume(nextBlockNode.getBlockPos().down(), world);
	        boolean isSmallBlock = closestBlockVolume > 0 && closestBlockVolume < 1;
			
			List<Callable<Void>> tasks = new ArrayList<>();
			
			if (children.size() > 5) {
				Node[][] chunks = ArrayChunkSplitter.splitArrayIntoChunksOfX(children.toArray(new Node[children.size()]), children.size()/5);
				
				for (int i = 0; i < chunks.length; i++) {
					Node[] nodes = chunks[i];
					tasks.add(() -> {
						for (int j = 0; j < nodes.length; j++) {
							Node child = nodes[j];
							if (stop.get()) return null;
					    	if (Thread.currentThread().isInterrupted()) return null;
							
							// Check if this child is too close to any already accepted child
						    for (Node other : validChildren) {
						    	if (Thread.currentThread().isInterrupted()) return null;
						        double distance = other.agent.getPos().distanceTo(child.agent.getPos());
				
						        boolean bothClimbing = other.agent.isClimbing(world) && child.agent.isClimbing(world);
						        boolean bothNotClimbing = !other.agent.isClimbing(world) && !child.agent.isClimbing(world);
				
						        if ((bothClimbing && distance < 0.03) || (bothNotClimbing && distance < 0.294) || (isSmallBlock && distance < 0.2)) {
						            return null; // too close to existing child
						        }
						    }
							
							boolean skip = filterChidren(child, lastBlockNode, nextBlockNode, isSmallBlock, world);
							
							if (skip || checkForFallDamage(child, world)) {
								return null;
							}
							
							validChildren.add(child);
						}
						return null;
					});
				}
				
			} else {
				tasks = children.stream().map(child -> (Callable<Void>) () -> {
					if (stop.get()) return null;
			    	if (Thread.currentThread().isInterrupted()) return null;
					
					// Check if this child is too close to any already accepted child
				    for (Node other : validChildren) {
				    	if (Thread.currentThread().isInterrupted()) return null;
				        double distance = other.agent.getPos().distanceTo(child.agent.getPos());
		
				        boolean bothClimbing = other.agent.isClimbing(world) && child.agent.isClimbing(world);
				        boolean bothNotClimbing = !other.agent.isClimbing(world) && !child.agent.isClimbing(world);
		
				        if ((bothClimbing && distance < 0.03) || (bothNotClimbing && distance < 0.294) || (isSmallBlock && distance < 0.2)) {
				            return null; // too close to existing child
				        }
				    }
					
					boolean skip = filterChidren(child, lastBlockNode, nextBlockNode, isSmallBlock, world);
					
					if (skip || checkForFallDamage(child, world)) {
						return null;
					}
					
					validChildren.add(child);
					return null;
				}).collect(Collectors.toList());
			}
			
//			for (Iterator iterator = tasks.iterator(); iterator.hasNext();) {
//				Callable<Void> callable = (Callable<Void>) iterator.next();
//				try {
//					callable.call();
//				} catch (Exception e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//			}
			
			try {
				List<Future<Void>> futures = executor.invokeAll(tasks);
				
				for (Future<Void> future : futures) {
					if (!future.isDone()) {
						Thread.sleep(50);
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			long tFiltered = timing ? System.nanoTime() : 0;

			Object openSetLock = new Object();  // if openSet is not thread-safe
			
			List<Callable<Void>> processingTasks = new ArrayList<>();
					
			if (validChildren.size() > 25) {
				Node[][] chunks = ArrayChunkSplitter.splitArrayIntoChunksOfX(validChildren.toArray(new Node[validChildren.size()]), children.size()/25);
				
				for (int i = 0; i < chunks.length; i++) {
					Node[] nodes = chunks[i];
					tasks.add(() -> {
						for (int j = 0; j < nodes.length; j++) {
							Node child = nodes[j];
							if (stop.get()) return null;
					    	if (Thread.currentThread().isInterrupted()) return null;
					        updateNode(world, parent, child, target, TARGET, blockPath.get(), closed);
		
					        synchronized (openSetLock) {
					            if (child.isOpen()) {
					                openSet.update(child);
					            } else {
					                openSet.insert(child);
					            }
					        }

					        // Update best heuristic safely
					        synchronized (bestHeuristicSoFar) {
					            if (!updateBestSoFar(child, target, bestHeuristicSoFar)) {
					                failing.set(false);
					            }
					        }
						}
						return null;
					});
				}
				
			} else {
		
				processingTasks = validChildren.stream()
				    .map(child -> (Callable<Void>) () -> {
						if (stop.get()) return null;
				    	if (Thread.currentThread().isInterrupted()) return null;
				        updateNode(world, parent, child, target, TARGET, blockPath.get(), closed);
	
				        synchronized (openSetLock) {
				            if (child.isOpen()) {
				                openSet.update(child);
				            } else {
				                openSet.insert(child);
				            }
				        }
	
				        // Update best heuristic safely
				        synchronized (bestHeuristicSoFar) {
				            if (!updateBestSoFar(child, target, bestHeuristicSoFar)) {
				                failing.set(false);
				            }
				        }
	
				        // Optional: render node for debugging
//				         RenderHelper.renderNode(child);
	
				        return null;
				    })
				    .collect(Collectors.toList());

			}

//			for (Iterator iterator = processingTasks.iterator(); iterator.hasNext();) {
//				Callable<Void> callable = (Callable<Void>) iterator.next();
//				try {
//					callable.call();
//				} catch (Exception e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//			}
			
		    try {
				List<Future<Void>> futures = executor.invokeAll(processingTasks);
				
				for (Future<Void> future : futures) {
					if (!future.isDone()) {
						Thread.sleep(50);
					}
				}
				
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
				
//			for (Node child : validChildren) {
//				updateNode(world, parent, child, target, blockPath.get(), closed);
//				
//				if (child.isOpen()) {
//					openSet.update(child);
//				} else {
//					openSet.insert(child);
//				}
//				
//				// Update best so far
//				if (updateBestSoFar(child, bestHeuristicSoFar, target)) {
//					failing.set(false);
//				}
//				
//				// Optionally render or handle visual updates here
//				// RenderHelper.renderNode(child);
//			}
		    
//		    RenderHelper.clearRenderers();
//
//			Debug.logMessage("Valid children");
//			for (Node node : validChildren) {
//				if (stop.get()) return false;
//		    	if (Thread.currentThread().isInterrupted()) return false;
//		        RenderHelper.renderNode(node);
//			}
//			try {
//				Thread.sleep(20);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
			if (timing) {
				long tDone = System.nanoTime();
				double msGetChildren = (tChildren - t0) / 1_000_000.0;
				double msFilter = (tFiltered - tChildren) / 1_000_000.0;
				double msOpenSet = (tDone - tFiltered) / 1_000_000.0;
				double msTotal = (tDone - t0) / 1_000_000.0;
				System.out.printf("Tungsten [node#%d] %.1fms total | getChildren=%.1fms (%d raw) | filter=%.1fms (%d valid) | openSet+update=%.1fms%n",
					numNodesConsidered.get(), msTotal, msGetChildren, children.size(), msFilter, validChildren.size(), msOpenSet);
			}

			return failing.get();
		}
    
    private boolean updateNextClosestBlockNodeIDX(List<BlockNode> blockPath, Node node, Set<Vec3d> closed, WorldView world) {
    	if (blockPath == null) return false;

    	if (NEXT_CLOSEST_BLOCKNODE_IDX.get()+1 >= blockPath.size()) return false;
    	BlockNode lastClosestPos = blockPath.get(NEXT_CLOSEST_BLOCKNODE_IDX.get()-1);
    	BlockNode closestPos = blockPath.get(NEXT_CLOSEST_BLOCKNODE_IDX.get());
    	BlockNode nextNodePos = blockPath.get(NEXT_CLOSEST_BLOCKNODE_IDX.get()+1);
    	
    	boolean isRunningLongDist = lastClosestPos.getPos(true).distanceTo(closestPos.getPos(true)) > 7;

    	Vec3d nodePos = node.agent.getPos();
    	if (!node.agent.onGround && !node.agent.touchingWater && !node.agent.isClimbing(world)) return false;
    	
    	boolean isNextNodeAbove = nextNodePos.getBlockPos().getY() > closestPos.getBlockPos().getY() && (nextNodePos.getBlockPos().getY() - closestPos.getBlockPos().getY()) > 1.5 && node.agent.onGround;
    	boolean isNextNodeBelow = nextNodePos.getBlockPos().getY() < closestPos.getBlockPos().getY();
    	
    	BlockPos nodeBlockPos = new BlockPos(node.agent.blockX, node.agent.blockY, node.agent.blockZ);
    	int closestPosIDX = findClosestPositionIDX(world, nodeBlockPos, blockPath);
    	BlockNode newClosestPos = blockPath.get(closestPosIDX);
        BlockState state = world.getBlockState(closestPos.getBlockPos());
        BlockState stateBelow = world.getBlockState(closestPos.getBlockPos().down());
        double closestBlockBelowHeight = BlockShapeChecker.getBlockHeight(closestPos.getBlockPos().down(), world);
        double closestBlockVolume = BlockShapeChecker.getShapeVolume(closestPos.getBlockPos(), world);
        double distanceToClosestPos = nodePos.distanceTo(closestPos.getPos(true));
        double heightDiff = closestPos.getJumpHeight(Math.ceil(nodePos.y), closestPos.y);

        boolean isWater = BlockStateChecker.isAnyWater(state);
        boolean isLadder = state.getBlock() instanceof LadderBlock;
        boolean isCarpet = state.getBlock() instanceof CarpetBlock;
        boolean isVine = state.getBlock() instanceof VineBlock;
        boolean isConnected = BlockStateChecker.isConnected(nodeBlockPos, world);
        boolean isBelowLadder = stateBelow.getBlock() instanceof LadderBlock;
        boolean isBottomSlab = BlockStateChecker.isBottomSlab(state);
        boolean isBelowClosedTrapDoor= BlockStateChecker.isClosedBottomTrapdoor(stateBelow);
        boolean isBelowGlassPane = (stateBelow.getBlock() instanceof PaneBlock) || (stateBelow.getBlock() instanceof StainedGlassPaneBlock);
        boolean isBlockBelowTall = closestBlockBelowHeight > 1.3;
        


    	if (!isLadder && !isCarpet) {
	    	if (closestPos.getPos(true).y - nodePos.y > 0.6 || !nodePos.isWithinRangeOf(closestPos.getPos(true), (isRunningLongDist ? 2.80 : 1.95), (isRunningLongDist ? 1.20 : 1.20)))  {
	    		return false;
	    	}
	    	
	    	Node p = node.parent;
	    	for (int i = 0; i < 4; i++) {
	    		if (p != null && closestPos.getPos(true).y <= p.agent.getPos().y &&  !p.agent.getPos().isWithinRangeOf(closestPos.getPos(true), (isRunningLongDist ? 2.80 : 1.95), (isRunningLongDist ? 1.20 : 1.80))) return false;
			}
    	}
        
        boolean validWaterProximity = isWater && nodePos.isWithinRangeOf(BlockPosShifter.getPosOnLadder(closestPos, world), 0.9, 1.2);
        // Agent state conditions
        boolean agentOnGroundOrClimbingOrOnTallBlock = node.agent.onGround || node.agent.isClimbing(world) || isBelowLadder || isLadder || isBlockBelowTall;

        // Ladder-specific conditions
        boolean validLadderProximity = (isLadder || isBelowLadder || isVine) && nodePos.isWithinRangeOf(BlockPosShifter.getPosOnLadder(closestPos, world), 1.95, 1.7);
        
        // Tall block position conditions. Things like fences and walls
        boolean validTallBlockProximity = isBlockBelowTall 
            && nodePos.isWithinRangeOf(closestPos.getPos(true), 0.8, 0.58);

        boolean validBottomSlabProximity = isBottomSlab && distanceToClosestPos < 0.99
                && heightDiff < 2;
        
        
        boolean validClosedTrapDoorProximity = isBelowClosedTrapDoor && nodePos.isWithinRangeOf(closestPos.getPos(true), 0.88, 2.2);
        
        boolean isBlockAboveSolid = BlockShapeChecker.getShapeVolume(nodeBlockPos.up(2), world) > 0;
        
        // General position conditions
        boolean validStandardProximity = !isLadder && !isBelowLadder && !isBelowGlassPane 
            && !isBlockBelowTall
            && (isBlockAboveSolid
        	&&	distanceToClosestPos < (isRunningLongDist ? 1.80 : 0.85)
            || !isBlockAboveSolid
            && (
            		distanceToClosestPos < (isRunningLongDist ? 1.80 : 1.25)
            && heightDiff < 1.8
            && heightDiff > 1
            || 
            node.agent.onGround
            && heightDiff < 0.8
            && heightDiff >= 0
            && distanceToClosestPos < (isRunningLongDist ? 1.80 : 1.25)
            || isCarpet && heightDiff < 1
            && heightDiff >= -1
            && distanceToClosestPos < 2
            ));

        // Glass pane conditions
        boolean validGlassPaneProximity = isBelowGlassPane && distanceToClosestPos < 0.5;
        
        // Block volume conditions
        boolean validSmallBlockProximity = !isBelowGlassPane && closestBlockVolume > 0 && closestBlockVolume < 1 && distanceToClosestPos < 0.7;
        
//        for (int j = 0; j < blockPath.size(); j++) {
//			if (j >= closestPosIDX) {
//	        	RenderHelper.renderBlockPath(blockPath, j);
//				try {
//					Thread.sleep(200);
//				} catch (InterruptedException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//			}
//		}
        
        if (validLadderProximity) {
        	if (setCurrentPath(TARGET, this.start, TungstenModDataContainer.player)) {
				NEXT_CLOSEST_BLOCKNODE_IDX.set(closestPosIDX+1);
	        	RenderHelper.renderBlockPath(blockPath, NEXT_CLOSEST_BLOCKNODE_IDX.get());
				return true;
			}
        } else if (closestPosIDX+1 > NEXT_CLOSEST_BLOCKNODE_IDX.get()+1 && heightDiff <= 1) {

				NEXT_CLOSEST_BLOCKNODE_IDX.set(closestPosIDX+1);
	        	RenderHelper.renderBlockPath(blockPath, NEXT_CLOSEST_BLOCKNODE_IDX.get());
				return true;
        }
    	if (closestPosIDX+1 > NEXT_CLOSEST_BLOCKNODE_IDX.get() && closestPosIDX +1 < blockPath.size()
    			&&  heightDiff <= 1
    			&& ( validWaterProximity || !isConnected
//    			&& BlockNode.wasCleared(world, nodeBlockPos, blockPath.get(closestPosIDX+1).getBlockPos())
				&& agentOnGroundOrClimbingOrOnTallBlock
    			&& (
	    			validTallBlockProximity
		    		|| validStandardProximity
		    		|| validGlassPaneProximity
		    		|| validSmallBlockProximity
		    		|| validBottomSlabProximity
		    		|| validClosedTrapDoorProximity
	    		)
//			    && (child.agent.getBlockPos().getY() == blockPath.get(closestPosIDX).getBlockPos().getY())
    			)
    			) {

                boolean isNeo = blockPath.get(NEXT_CLOSEST_BLOCKNODE_IDX.get()).isDoingNeo();

    			if (!isNeo || setCurrentPath(TARGET, this.start, TungstenModDataContainer.player)) {
    				NEXT_CLOSEST_BLOCKNODE_IDX.set(closestPosIDX+1);
    	        	RenderHelper.renderBlockPath(blockPath, NEXT_CLOSEST_BLOCKNODE_IDX.get());
    				return true;
    			}
//	    		try {
//					Thread.sleep(150);
//				} catch (InterruptedException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
    	}
    	return false;
    }
	
}
