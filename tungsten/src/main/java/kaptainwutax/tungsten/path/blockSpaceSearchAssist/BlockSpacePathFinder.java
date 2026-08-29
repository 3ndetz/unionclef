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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

public class BlockSpacePathFinder {

	/** Times the BLOCK-SPACE search exhausted its open set; the twin of PathFinder.physicsRanOut.
	 *  See the note there: telling the two searches apart is what settles which engine a course
	 *  actually runs on. */
	public static volatile int blockRanOut;

	
	public static boolean active = false;
	public static Thread thread = null;
	protected static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};
	protected static final BlockNode[] bestSoFar = new BlockNode[COEFFICIENTS.length];

	/**
	 * HOW THE COARSE SEARCH ENDED. The guide was measured ending 6.7 blocks short of the
	 * target while the physics goal test never passed once (tryEmit=0), and the four exits
	 * of the loop are indistinguishable from outside. They answer different questions:
	 * exhausted = the target is not reachable under the move set, timeout = it might be but
	 * the budget was too small, stopped = something killed the search again.
	 */
	public static volatile int bsComplete, bsTimeout, bsStopped, bsExhausted;
	/** Closest the coarse search actually got to the target, in centimetres. */
	public static volatile int bsClosestCm;
	/** Entries into find() and into the search loop -- separates 'never called' from
	 *  'called but returns before the loop'. All four outcome counters read zero while a
	 *  16-node guide existed, so the guide is not being recomputed at all. */
	public static volatile int bsFindCalls, bsSearchCalls, bsLoopIters;

	/**
	 * WHAT A SEARCH THAT DID NOT COMPLETE HANDED BACK. bsEnd says HOW the loop ended; these say
	 * whether the caller got anything to walk, which is a different question and the one the
	 * physics leg actually feels.
	 *
	 * <p>A STUB is a guide whose LAST CELL is less than MIN_DIST_PATH from the search start: walking
	 * it takes the bot nowhere. Deliberately measured in blocks and not in nodes -- stringPull()
	 * collapses a straight run to [start, end], and run3 of the same sweep reads guide=n2 with
	 * bsEnd[c63 t0 s0 x0], a two-node guide from a search that completed sixty-three times of
	 * sixty-four. Measured on a live @gamer stall (freezes/stall_run2.txt, 2026-08-28):
	 *
	 * <pre>
	 *   bsEnd[c0 t105 s11 x0]  bsIn[f0 s117 i1481451]
	 *   guide=n2 ... end[1513.5,63.0,-284.5]toTgt29.8
	 * </pre>
	 *
	 * <p>117 coarse searches, NONE completed, 1.48 M nodes expanded between them, and the guide
	 * handed over is two nodes whose end sits 29.8 blocks from the target. At the shallow failure
	 * budget of 1920 ms that is over three minutes of a ten-minute run spent computing a route
	 * that moves the bot nowhere.
	 *
	 * <p>bsStubHadCloser counts the stubs where the search HAD expanded a node both closer to the
	 * goal and further from the start, and threw it away; bsStubCloserCm is the furthest such
	 * node's distance from the start. The
	 * pair says how much material a salvage fallback has to work with -- if the closest node the
	 * search reached is itself one step from the start, the search is walled in and no fallback
	 * helps, which is a different defect and wants a different fix.
	 */
	public static volatile int bsStub, bsStubHadCloser, bsStubCloserCm;
	/**
	 * WHICH EXIT THIS SEARCH LEFT BY, for whoever inspects the guide afterwards.
	 *
	 * <p>bsStub reads 0 on every playthrough run while nowhere-guides pour out of this class, and
	 * the reason is structural: bsStub++ lives inside salvage(), and the EXHAUSTED exit hands back
	 * bestSoFar's partial directly, going around it. A counter reading zero says "my exit is
	 * quiet", not "the thing does not happen" -- that misreading has now cost two passes. Tagging
	 * the exit lets the caller attribute a bad guide without another counter per exit.
	 */
	public static volatile String lastExit = "-";
	/** Times the closest-cell fallback actually supplied the returned guide -- the mechanism
	 *  counter for {@link kaptainwutax.tungsten.TungstenConfig#coarseFallsBackToClosestCell}.
	 *  It must read 0 in a control arm and non-zero in a fix arm, or the pair measured nothing
	 *  (CHECKLIST rule 4a1). */
	public static volatile int bsClosestUsed;

	/** Tungsten's own value for upstream's MIN_IMPROVEMENT (AbstractNodeCostSearch.java:82,
	 *  where it is 0.01). Left as it was found — it is a positive threshold doing the job the
	 *  name says, unlike PathFinder's, which was -500. */
	private static final double minimumImprovement = 0.21;
	protected static final double MIN_DIST_PATH = 5;
	
	
	public static void find(WorldView world, Vec3d target, PlayerEntity player) {
		bsFindCalls++;
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
        // THE OTHER HALF OF UPSTREAM'S TIMEOUT (AStarPathFinder.java:85). The primary one only
        // fires once the search is out of trouble (`!failing`); the failure one is what stops a
        // search that never gets anywhere. It did not exist here at all, so a start that is
        // walled in — where no node ever gets MIN_DIST_PATH from the start, so `failing` stays
        // set forever — ran until the open set emptied, which on the blind scan is never. That
        // search is called SYNCHRONOUSLY by PathFinder.findBlockPath, so it took the pathfinder
        // thread down with it.
        // ADAPTER: upstream takes both budgets from settings (Settings.java:577,582 — 500 ms
        // primary, 2000 ms failure). Tungsten has no such settings, so the failure budget keeps
        // upstream's 4:1 ratio against the primary budget this search already had.
        long failureTimeoutTime = startTime + (generateDeep ? 19200L : 1920L);
        // Read ONCE per search, not per node and not into a static: the stand pins it at runtime
        // (run_suite.py --pin searchHeuristicScale=<x>), and a search must not change its own
        // yardstick halfway through — every f it has already ordered was measured with this one.
        final double heuristicScale = kaptainwutax.tungsten.TungstenConfig.get().searchHeuristicScale;

        TungstenModRenderContainer.RENDERERS.clear();
		if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) Debug.logMessage("Searchin...");
		start = new BlockNode(start.getBlockPos(), goal, player, world);

		// Near-goal re-plan (smartMoves): `failing` means "this search has not got clear of its
		// own start yet", and it decides WHICH timeout applies — the short primary one, or the
		// long failure one. A re-plan that starts within MIN_DIST_PATH of the goal never gets
		// 5 blocks away and so would sit on the failure budget for a search that is one hop
		// long. It is not failing, it is nearly done: say so. (This clause also used to be what
		// let a close goal complete at all, because isPathComplete carried a `&& !failing` that
		// upstream does not have; that conjunct is gone and completion no longer depends on it.)
		if (kaptainwutax.tungsten.TungstenConfig.get().smartMoves
				&& start.getPos().squaredDistanceTo(target) <= MIN_DIST_PATH * MIN_DIST_PATH) {
			failing = false;
		}

		BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
		Set<BlockNode> closed = new HashSet<>();
		// Block centre. Moved above the start's heuristic on purpose: the seed and the children
		// must be measured against the SAME target, and now that the heuristic is in cost units
		// half a block of disagreement is worth several ticks of it.
		target = target.subtract(0.5, 0, 0.5);

		// THE START IS THE ONE NODE WITH A KNOWN g (AStarPathFinder.java:55-56). Every node is
		// born at COST_INF so that a relaxation can only ever lower it; without this line the
		// start's g is COST_INF too and every path's cost begins at infinity.
		start.cost = 0;
		start.estimatedCostToGoal = computeHeuristic(start.getPos(), target, world, heuristicScale);
		start.combinedCost = start.estimatedCostToGoal;

		double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];//keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
		for (int i = 0; i < COEFFICIENTS.length; i++) {
            bestHeuristicSoFar[i] = start.estimatedCostToGoal;   // AStarPathFinder.java:61
            bestSoFar[i] = start;
        }

		// The closest cell this search actually reaches, tracked unconditionally — the
		// last-resort partial when the monotone bestSoFar record declines to move.
		BlockNode closestToGoal = null;
		double closestDistSq = Double.MAX_VALUE;
		// "How many children did we make, and how many got in" — the question that settles an
		// empty open set without guessing.
		int generatedChildren = 0;
		int insertedChildren = 0;

		openSet.insert(start);
		bsSearchCalls++;
		boolean openSetDrained = true;
		while(!openSet.isEmpty()) {
			if (TungstenModDataContainer.PATHFINDER.stop.get()) {
				bsStopped++; openSetDrained = false;
				RenderHelper.clearRenderers();
				break;
			}
			TungstenModRenderContainer.RENDERERS.clear();
			if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
                long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
                if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                    bsTimeout++; openSetDrained = false;
                    break;
                }
            }
			numNodes++;
			// yield to the client thread (same reason as in PathFinder: this
			// search starved the renderer to a few fps and the bot froze)
			if ((numNodes & 0xFF) == 0) {
				try { Thread.sleep(1); } catch (InterruptedException ignored) {}
			}
			bsLoopIters++;
			BlockNode next = openSet.removeLowest();
			{
				double dsq = next.getPos().squaredDistanceTo(target);
				int cm = (int) Math.min(Math.sqrt(dsq) * 100.0, 2_000_000_000.0);
				if (bsClosestCm == 0 || cm < bsClosestCm) bsClosestCm = cm;
			}

			if (closed.contains(next)) continue;
			
			closed.add(next);
			// THE CLOSEST CELL WE ACTUALLY EXPANDED, recorded where it is free. The declaration
			// below the loop header has existed since #67 and nothing ever assigned it, so the
			// "last resort" that reads it further down could not fire once -- a disconnected
			// ACTUATOR, which reports exactly like a refuted hypothesis (CHECKLIST rule one,
			// mirror image). Note this is per SEARCH: bsClosestCm above is a lifetime minimum
			// over the whole run and cannot answer a question about one halt.
			{
				double dsq = next.getPos().squaredDistanceTo(target);
				if (dsq < closestDistSq) { closestDistSq = dsq; closestToGoal = next; }
			}
			if(isPathComplete(next, target)) {
				TungstenModRenderContainer.RENDERERS.clear();
				List<BlockNode> path = generatePath(next, world);

				bsComplete++;
				lastExit = "complete";
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

				// g OF THE PARENT PLUS THE PRICE OF THIS MOVE (AStarPathFinder.java:143). It read
				// `child.cost + 1`: the wrong receiver, and the move's real price replaced by a
				// literal. g therefore never accumulated — f was h + 1, which is greedy
				// best-first wearing A*'s clothes, and every number in ActionCosts and
				// SmartMoves was dead weight the moment it was computed.
				double tentativeCost = next.cost + edgeCost(child, world);

				// ADAPTER: there is no getNodeAtPosition here (AbstractNodeCostSearch.java:169-176).
				// A BlockNode is a per-EDGE object in tungsten — it carries the move's own plan
				// (toBreak / toPlace, isDoingNeo + neoSide, isDoingCornerJump), so a single
				// canonical node per position would mix one parent's move into another parent's
				// route. Duplicate states are resolved lazily instead, at the pop above (the
				// `closed` test): the first time a position leaves the heap it does so with the
				// lowest f among its duplicates, which is the same answer for a consistent
				// heuristic. The guard below is kept in upstream's shape regardless — it is free,
				// it is where a node map would plug in, and half-conditions quietly dropped are
				// this engine's signature failure.
				// A NODE THAT HAS NEVER BEEN RELAXED MUST ACCEPT ITS FIRST RELAXATION.
				// Every node is born with cost = COST_INF, so the improvement test reads
				// `COST_INF - tentativeCost`. That is fine while tentativeCost is finite — but
				// the moment it is not (an edge priced COST_INF, or a parent still at infinity)
				// the expression is `COST_INF - COST_INF`, which is ZERO, which is not greater
				// than minimumImprovement — and the child is REJECTED. Reject every child of a
				// node and the open set empties right after the start: measured on a live @gamer
				// run as "Ran out of nodes!" 638 times, with the closest-reached fallback finding
				// no parent because nothing beyond the start was ever expanded.
				// Upstream never meets this: its nodes come from a node map with finite costs.
				boolean firstRelaxation = child.cost >= ActionCosts.COST_INF;
				generatedChildren++;
				if (firstRelaxation || child.cost - tentativeCost > minimumImprovement) {
					insertedChildren++;
					updateNode(next, child, tentativeCost, target, world, heuristicScale);

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
		}

		if (openSet.isEmpty()) {
			if (openSetDrained) bsExhausted++;
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
				lastExit = "exhaustedPartial";
				Debug.logMessage("Partial path (goal unreachable via move-gen) — advancing "
						+ partial.get().size() + " nodes toward goal");
				return partial;
			}
			// LAST RESORT: THE CLOSEST CELL WE ACTUALLY REACHED — see salvage() below, which now
			// serves this exit and the timeout one from the same code. Written for #67 against a
			// measured 500 s stall, and dead from the day it was written: the local it reads was
			// never assigned.
			Optional<List<BlockNode>> salvaged =
					salvage(Optional.empty(), closestToGoal, start, world, "exhausted");
			if (salvaged.isPresent()) { lastExit = "exhaustedSalvage"; return salvaged; }
			lastExit = "ranOut";
			blockRanOut++;
			Debug.logWarning("Ran out of nodes (children generated=" + generatedChildren
					+ " inserted=" + insertedChildren + ")");
			return Optional.empty();
		}
        // TIMED OUT OR STOPPED. The exhausted branch above has had a closest-cell last resort
        // since #67; this exit -- the one the playthrough actually takes -- had nothing but
        // bestSoFar, and bestSoFar is a MONOTONE record of heuristic improvement that can
        // decline to move. That is how 105 searches in a row returned a two-node stub.
        Optional<List<BlockNode>> result = bestSoFar(true, numNodes, start, world);
		lastExit = "stalled";
		return salvage(result, closestToGoal, start, world, "stalled");
	}

	/**
	 * Hand back something walkable when the heuristic record declined to move.
	 *
	 * <p>Called at every exit that did NOT reach the goal. If the guide about to be returned is a
	 * stub -- its last cell less than MIN_DIST_PATH from the search start, so walking it takes the
	 * bot nowhere -- and the search expanded a node that is BOTH closer to the goal and further from
	 * the start, that node's path is the honest answer: it is measured against the GOAL rather than
	 * against a record that can decline to move, and the bot advances and re-searches from there
	 * instead of standing still.
	 *
	 * <p>bestSoFar stays the primary answer, deliberately. Preferring the closest-to-goal node in
	 * general is how a search walks into the near lip of a chasm; upstream's seven COEFFICIENTS
	 * exist to avoid exactly that, and this only runs where they returned nothing to walk.
	 *
	 * <p>The counters are recorded whether or not the fallback is enabled, so a control arm reports
	 * how much material the fix WOULD have had. The return itself is gated on
	 * {@link kaptainwutax.tungsten.TungstenConfig#coarseFallsBackToClosestCell} so a paired A/B
	 * varies exactly one thing.
	 */
	private static Optional<List<BlockNode>> salvage(Optional<List<BlockNode>> handedBack,
			BlockNode closestToGoal, BlockNode start, WorldView world, String why) {
		// ⛔ A STUB IS MEASURED IN BLOCKS, NOT IN NODES. The first version of this asked whether the
		// guide had two nodes or fewer, which is wrong twice over: stringPull() deletes every
		// intermediate cell whose corner can be cut, so a healthy thirty-block straight walk collapses
		// to [start, end] -- and a stall dump proves it, run3 reading guide=n2 with bsEnd[c63 t0 s0 x0],
		// i.e. a two-node guide from a search that completed sixty-three times out of sixty-four.
		// Judging that a stub would have salvaged over perfectly good routes and inflated every
		// counter here. What the failing case actually shows is a guide whose END IS WHERE THE BOT
		// ALREADY IS, so ask that, against MIN_DIST_PATH -- this file's own word for "went somewhere",
		// and the gate bestSoFar's non-smartMoves branch already uses.
		List<BlockNode> given = handedBack.orElse(null);
		double movedSq = given == null || given.isEmpty() ? 0.0
				: getDistFromStartSq(given.get(given.size() - 1), start.getPos());
		if (movedSq > MIN_DIST_PATH * MIN_DIST_PATH) return handedBack;
		bsStub++;
		if (closestToGoal == null || closestToGoal.previous == null) return handedBack;
		double closerSq = getDistFromStartSq(closestToGoal, start.getPos());
		if (closerSq <= movedSq) return handedBack;
		List<BlockNode> path = generatePath(closestToGoal, world);
		if (path.size() <= 1) return handedBack;
		bsStubHadCloser++;
		int cm = (int) Math.min(Math.sqrt(closerSq) * 100.0, 2_000_000_000.0);
		if (cm > bsStubCloserCm) bsStubCloserCm = cm;
		if (!kaptainwutax.tungsten.TungstenConfig.get().coarseFallsBackToClosestCell) return handedBack;
		bsClosestUsed++;
		// logInternal, NOT logMessage: this fires once per non-completing search -- 117 times in
		// the measured run -- and logMessage sends to Minecraft CHAT from whatever thread calls it.
		// Search threads writing chat directly is TODOS C4.4, which already cost a session.
		Debug.logInternal("Coarse search " + why + " — advancing " + path.size()
				+ " nodes to the closest cell reached");
		return Optional.of(path);
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
            // NO `continue` HERE — upstream records the running maximum and then falls straight
            // into the return check (AbstractNodeCostSearch.java:198-201). The `continue` this
            // had meant a coefficient that became the new furthest never got asked whether it
            // was far enough to return, and i=0 — the least-detour coefficient, the one you
            // actually want — becomes the furthest every single time.
            if (dist > bestDist) {
                bestDist = dist;
            }
            if (dist > MIN_DIST_PATH * MIN_DIST_PATH) { // square the comparison since distFromStartSq is squared
                BlockNode n = bestSoFar[i];
				List<BlockNode> path = generatePath(n, world);
				if (path.size() > 1) return Optional.of(path);
            }
        }
        return Optional.empty();
    }
	
	/**
	 * Distance to the goal, in the same TICK unit the edges are priced in.
	 *
	 * <p>{@code heuristicScale} converts blocks to ticks. It has to: every edge cost in this
	 * search is an ActionCosts tick figure and this function measures blocks, so without the
	 * conversion f = g + h adds two different units and means nothing. The value is a live
	 * setting rather than a constant because it decides the search's whole character (broad and
	 * optimal below the walk price, straight-at-the-goal above it) and because the first attempt
	 * at this repair guessed it — see TungstenConfig#searchHeuristicScale for that measurement.
	 * It is passed down rather than read here so one search uses one yardstick throughout.
	 */
	private static double computeHeuristic(Vec3d position, Vec3d target, WorldView world, double heuristicScale) {
		double xzMultiplier = 1/*.2*/;
	    double dx = (target.x - position.x)*xzMultiplier;
	    double dy = 0;
	    double dz = (target.z - position.z)*xzMultiplier;
	    if (BlockStateChecker.isAnyWater(world.getBlockState(new BlockPos((int) position.x, (int) position.y, (int) position.z)))) {
	    	dy = (target.y - position.y)*1.8;
	    } else if (DistanceCalculator.getHorizontalManhattanDistance(position, target) < 32) {
	    	dy = (target.y - position.y)*1.5;
	    }
	    return (Math.sqrt(dx * dx + dy * dy + dz * dz)) * heuristicScale;
	}

	/**
	 * The price of ONE move into {@code child} — upstream's {@code res.cost}
	 * (AStarPathFinder.java:120), which the move generator has already worked out and parked on
	 * the node ({@link BlockNode#actionCost}: WALK / JUMP / PARKOUR from SmartMoves or the blind
	 * scan, plus whatever mining or bridging that particular move committed to).
	 *
	 * <p>Water is tungsten's own surcharge and is kept exactly as tuned — as a MULTIPLE of a walk
	 * step, which is what it was: it was written against the old implicit "1 per step", so 1.8
	 * meant "almost three times a step" and 5.8 meant "nearly seven". Converting rather than
	 * re-typing the numbers keeps the water landscape where it was measured.
	 */
	private static double edgeCost(BlockNode child, WorldView world) {
	    double cost = child.actionCost;

		if (BlockStateChecker.isAnyWater(child.getBlockState(world))) {
			cost += 1.8 * ActionCosts.WALK_ONE_BLOCK_COST;
		}
		if (BlockStateChecker.isAnyWater(world.getBlockState(child.getBlockPos().up()))) {
			cost += 5.8 * ActionCosts.WALK_ONE_BLOCK_COST;
		}

//	    cost += BlockStateChecker.isAnyWater(TungstenMod.mc.world.getBlockState(child.getBlockPos())) ? 50 : 0;

	    return cost;
	}

	/** Upstream's relaxation body, run only once the guard has passed (AStarPathFinder.java:145-147). */
	private static void updateNode(BlockNode current, BlockNode child, double tentativeCost, Vec3d target,
			WorldView world, double heuristicScale) {
	    double estimatedCostToGoal = computeHeuristic(child.getPos(), target, world, heuristicScale);

	    child.previous = current;
	    child.cost = tentativeCost;
	    child.estimatedCostToGoal = estimatedCostToGoal;
	    child.combinedCost = tentativeCost + estimatedCostToGoal;
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

	/**
	 * Standing on the goal IS the answer — upstream asks nothing else
	 * (AStarPathFinder.java:97, {@code goal.isInGoal(...)}), and its `failing` flag gates the
	 * TIMEOUT and nothing more. This carried an extra {@code && !failing}, so a search that had
	 * not yet got MIN_DIST_PATH clear of its own start would pop the goal, decline to recognise
	 * it, and keep going until it ran out of nodes next to the thing it was sent to.
	 */
	private static boolean isPathComplete(BlockNode node, Vec3d target) {
        return node.getPos().squaredDistanceTo(target) < 1.0D;
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

	        // NEVER SMOOTH AWAY A NODE THAT CARRIES WORK. String-pulling deletes intermediate
	        // nodes whose corner can be cut — but a node also carries the break/place plan for
	        // its own step (BlockNode.toBreak / toPlace), and dropping it drops the plan with
	        // it, silently: the route still looks walkable and the wall that had to be mined is
	        // simply never mined. Register entry C5.6, open since the audit. The node's own
	        // predicates already exist for this question (hasBreaks / hasPlaces, BlockNode:110
	        // and :120) — nothing consulted them.
	        boolean carriesWork = p.hasBreaks() || p.hasPlaces();
	        if (canGetFromLastNToCurrent && !carriesWork
	                && !p.isDoingJump && !p.previous.isDoingJump && heightDiff == 0) {
	        	path.remove(j-1);
	        } else {
	        	i = j-1;
				j++;
	        }
		}
	}
	
	
}
