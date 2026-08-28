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
import kaptainwutax.tungsten.helpers.MovementHelper;
import kaptainwutax.tungsten.helpers.blockPath.BlockPosShifter;
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


public class PathFinder {

	
	ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	public AtomicBoolean active = new AtomicBoolean(false);
	public AtomicBoolean stop = new AtomicBoolean(false);
	/** Volatile: written by the search worker as it exits, read by callers in find(). */
	public volatile Thread thread = null;
	private final Set<Integer> closed = Collections.synchronizedSet(new HashSet<>());
	private AtomicDoubleArray bestHeuristicSoFar;
	private BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
	protected static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};
	protected static final AtomicReferenceArray<Node> bestSoFar = new AtomicReferenceArray<Node>(COEFFICIENTS.length);
	/**
	 * Upstream's MIN_IMPROVEMENT (AbstractNodeCostSearch.java:75-82): the smallest heuristic gain
	 * worth recording, there to shrug off floating-point noise of the order 1e-16.
	 *
	 * <p>It was <b>-500</b>. A NEGATIVE threshold does not filter improvements, it accepts
	 * regressions: {@code bestHeuristicSoFar - heuristic > -500} is true for a child that is 400
	 * units WORSE, so every child overwrote {@code bestSoFar[i]} and the array ended up holding
	 * whichever child the parallelStream happened to finish last. Which partial path got handed
	 * to the executor on a timeout was, literally, a race.
	 *
	 * <p>⚠ Making it positive makes the record MONOTONE, and two places in this file were only
	 * ever correct because it was not: see {@link #setCurrentPath} (which threw away its re-seed)
	 * and the note on the moving waypoint at {@link #updateNextClosestBlockNodeIDX}.
	 */
	private static final double minimumImprovement = 0.01;
	/** Give up a search that has made no REAL progress (emit / block-path advance) for
	 *  this long. The re-root machinery resets the primary timeout every time it
	 *  re-plans, so a goal it can never reach (over the void, no blocks to place) would
	 *  otherwise re-root forever — the bot "computes" endlessly without moving. A
	 *  progressing search bumps lastProgressMs and never hits this; a stalled one gives
	 *  up cleanly (#user-bug: air / tall-grass goal spun forever). */
	private static final long HARD_SEARCH_CAP_MS = 20000L;
	private static Optional<List<BlockNode>> blockPath = Optional.empty();
	/** The robust elevation-aware block path from the last/current async search
	 *  (BlockSpacePathFinder). Available while a search runs; the drift-immune
	 *  BlockPathWalker follows this on natural terrain instead of the drift-prone
	 *  physics executor. */
	public static Optional<List<BlockNode>> getComputedBlockPath() { return blockPath; }
	protected static final double MIN_DIST_PATH = 1.8;
	protected static AtomicInteger NEXT_CLOSEST_BLOCKNODE_IDX = new AtomicInteger(1);
	protected static AtomicInteger numNodesConsidered = new AtomicInteger(0);
	
	// searchTimeoutMs is now in TungstenConfig (tungsten.json)
	/** Minimum path length (nodes) required before a timeout partial-path can be emitted.
	 *  Default: 46 (~2.3s). Set lower (e.g. 5) for follow-entity close-range. */
	public int minPathSizeForTimeout = 15;

	/** Minimum path progress distance before bestSoFar can be accepted.
	 *  Default: MIN_DIST_PATH (1.8). Set near 0 for snap/dash mode (accept any path immediately). */
	public double minDistPath = MIN_DIST_PATH;

	/** If set, physics A* starts from this position instead of player's current position.
	 *  Used by BlockPathWalker: BFS covers immediate blocks, A* starts from BFS endpoint.
	 *  Consumed (set to null) after use. */
	public Vec3d overrideStartPos = null;

	/** Searches thrown away because their root went stale while they ran. A COUNTER rather than a
	 *  log line: the message it replaces sits behind verboseDebugLogging, which is exactly why the
	 *  fix that re-seeds the next search at the player could only be called "suggestive" — the
	 *  before and after were greps of a channel neither run controlled. */
	public static volatile int staleRootRejections;
	/** Searches that exhausted with the fall guard on and were retried with it relaxed. */
	public static volatile int fallGuardRetries;
	/** Give-ups that retried with the guard relaxed instead of salvaging a scrap. */
	public static volatile int gaveUpFallRetry;

	/** Times the PHYSICS search exhausted its open set. Read as srch=physOut/blockOut.
	 *  Which of the two searches is even trying on a course is not obvious from the outside:
	 *  nav_water's failing runs print 828 bare "Ran out of nodes!" (this file) and NOT ONE of
	 *  BlockSpacePathFinder's long form, which is how the water work turned out to be aimed at
	 *  the wrong engine. Count them apart. */
	/** Searches that hit the hard give-up cap, and how many still handed back a partial route. Read as gaveUp/salvaged. */
	/** Empty paths refused rather than handed over as an arrival. */
	/** Searches killed by the shared guide being cleared under them, and those salvaged. */
	/** Physics searches aborted by the stop flag. The last untraced exit. */
	/** Path hand-overs: how many, how many nodes in total, and by which door. */
	/** Best-partial deliveries that bypass executePath, and their node counts. */
	/** Deliveries from the reset-search branch -- the third door. */
	/** Reset prefixes refused because they contained no movement. */
	/** Re-roots that did not extend the guide, and so were not repeated. */
	/** Children generated and offered to the frontier. Distinguishes 'no moves' from 'no progress'. */
	/** Raw moves generated, and how many survived the validity filter. */
	public static volatile int rawChildren, validAfterFilter;

	public static volatile int expandedChildren;

	public static volatile int resetNoGain;
	private boolean rerootExhausted = false;

	public static volatile int resetEmitRefused;

	public static volatile int resetEmit, resetEmitNodes;

	public static volatile int salvageEmit, salvageEmitNodes;

	/** Emit gate: how often it is asked, and how often the agent is stationary there. */
	/** Goal tests run, and the nearest the frontier ever came, in centimetres. */
	public static volatile int goalTests, nearestApproachCm;
	/** The coarse guide: node count, endpoint, and its distance to the real goal. */
	public static volatile String guideInfo = "-";
	/** Furthest guide node index the physics search ever reached. */
	public static volatile int guideIdxReached;
	/** Guides discarded because the relaxation that built them ended. */
	public static volatile int relaxedGuideDropped;
	/** The first agent/target pair a goal test saw, verbatim. */
	public static volatile String lastGoalPair = "-";

	/**
	 * DOES THE GUIDE LEAD WHERE THE PHYSICS CANNOT STEP?
	 *
	 * The coarse block search hands the physics leg a list of cells and the physics leg works
	 * along them one at a time (NEXT_CLOSEST_BLOCKNODE_IDX). When a search ends with no route,
	 * the pair that matters is how LONG the guide was and how far ALONG it the physics got --
	 * and, at that index, what the next hop looks like in the world.
	 *
	 * guideIdxReached above is a LIFETIME maximum over every search, so it cannot be paired with
	 * any one guide: a long guide from a healthy search leaves a high number standing next to a
	 * later stall's short one. This is the per-search version, and it is what the halt record and
	 * the hop histogram are built from.
	 */
	public static volatile int guideIdxThisSearch;
	/** Agent position at the moment a search was furthest along its own guide. */
	private static volatile Vec3d guideFurthestAt;
	/** Searches that ended with no route while still holding a guide. */
	public static volatile int gvpSamples;
	/** Of those: never left the opening hop / did walk the guide to its last node. */
	public static volatile int gvpStuckAtStart, gvpReachedGuideEnd;
	/** The shape (dx,dy,dz) of the hop the physics never crossed, tallied over samples. */
	public static final java.util.Map<String, Integer> gvpHopShapes =
			java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
	/** The last few halts in full, for reading rather than counting. */
	private static final java.util.ArrayDeque<String> gvpDumps = new java.util.ArrayDeque<>();

	/** The halt records, newest last, one per line. */
	public static String gvpDumpText() {
		synchronized (gvpDumps) {
			if (gvpDumps.isEmpty()) return "-";
			return String.join(System.lineSeparator(), gvpDumps);
		}
	}

	/** Cleared with the counters: a stale halt from the previous run reads exactly like a fresh one. */
	public static void gvpClearDumps() {
		synchronized (gvpDumps) { gvpDumps.clear(); }
		guideIdxThisSearch = 0;
		guideFurthestAt = null;
	}

	/** The hop histogram, most frequent first, as "dx,dy,dz xN". */
	public static String gvpHopDump() {
		synchronized (gvpHopShapes) {
			if (gvpHopShapes.isEmpty()) return "none";
			return gvpHopShapes.entrySet().stream()
					.sorted((a, b) -> b.getValue() - a.getValue())
					.limit(12)
					.map(e -> e.getKey() + "x" + e.getValue())
					.collect(Collectors.joining(" "));
		}
	}

	public static volatile int tryEmitCalls, tryEmitStationary;
	/** Emissions that happened ONLY because the moving-arrival rule is on. */
	public static volatile int tryEmitMoving;
	/** Goal tests a search must spend looking for a stopped arrival before a moving one counts. */
	private static final int MOVING_FALLBACK_AFTER_TESTS = 40;

	public static volatile int emitCount, emitTotalNodes, emitFresh, emitAppended;

	/** Resumes that left an in-flight search on the same goal alone instead of killing it. */
	public static volatile int resumeLetItFinish = 0;

	/**
	 * WHO KILLS THE SEARCH. searchAborted=40 with tryEmit=0 says the physics leg is
	 * destroyed before its first attempt to hand back a route, and three fixes aimed at
	 kaptainwutax.tungsten.path.PathFinder.noteStop("PathFinder@173");
	 * guessed call sites never fired. So stop guessing: every stop.set(true) in the module
	 * tags itself here, and the readout names the site instead of me nominating one.
	 */
	public static final java.util.Map<String, Integer> stopBy =
			java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
	public static void noteStop(String who) { synchronized (stopBy) { stopBy.merge(who, 1, Integer::sum); } }
	public static String stopByDump() {
		synchronized (stopBy) {
			if (stopBy.isEmpty()) return "none";
			StringBuilder sb = new StringBuilder();
			stopBy.forEach((k, v) -> sb.append(k).append(':').append(v).append(' '));
			return sb.toString().trim();
		}
	}

	/**
	 * Has the CURRENT search handed back a route yet? Cleared when a search starts, set the
	 * first time one is emitted. Read by the altoclef stall detector, which must not reset a
	 * search that has never produced anything -- killing it cannot help, and measurably
	 * prevents the very movement whose absence triggered the reset.
	 */
	/** Stall resets skipped because the search had not emitted anything yet. */
	public static volatile int stallSpared = 0;

	public static volatile boolean searchHasEmitted = false;

	public static volatile int searchAborted;

	public static volatile int guideVanished, guideVanishedSalvaged;

	public static volatile int emptyPathRefused;

	public static volatile int searchGaveUp, searchGaveUpSalvaged;

	public static volatile int physicsRanOut;
	/** Of those, how many were salvaged into a partial route instead of standing still. */
	public static volatile int physicsRanOutSalvaged;

	private long startTime;
	private Node start;

	public Vec3d TARGET = new Vec3d(0.5D, 10.0D, 0.5D);
	
	/**
	 * The goal this pathfinder was last asked for, whoever asked.
	 *
	 * <p>TungstenMod.TARGET is only written by hand-driven entry points (;goto, the keybinding,
	 * follow-entity, py4j), so an altoclef-driven path leaves it stale -- and the post-mining
	 * resume, which gates on it, gives up. This is the goal that is actually being worked.
	 */
	public static volatile Vec3d lastSearchTarget = null;

	synchronized public boolean find(WorldView world, Vec3d target, PlayerEntity player) {
		lastSearchTarget = target;
		return find(world, target, player, Optional.empty());
	}

    /**
     * Start a search. Returns FALSE when the request was refused because a search is
     * already running.
     *
     * <p>This used to be void and simply `return`ed: a caller had no way to know its request
     * had been thrown away. Worse, the worker clears `active` BEFORE `thread`, so there is a
     * window where `active==false && thread!=null` and a perfectly timed request is dropped
     * for no reason. That is what made nav_gaps flaky ~30% of the time — a run whose FIRST
     * request landed in the teardown window of the previous one never moved at all
     * (final_dist stayed at the start position while every other run passed in ~8 s).
     */
    synchronized public boolean find(WorldView world, Vec3d target, PlayerEntity player, Optional<List<BlockNode>> blockPath) {
		lastSearchTarget = target;

        if(active.get() || thread != null) return false;
        active.set(true);
        stop.set(false);
        // A FRESH SEARCH HAS EMITTED NOTHING YET. The altoclef stall detector uses this to
        // tell 'a search that is still working' from 'a route that went bad', so that it
        // never destroys the former. See stallResetSparesAVirginSearch.
        searchHasEmitted = false;
        TARGET = target;
        PathFinder.blockPath = blockPath;
        numNodesConsidered.set(0);
        this.start = null;

        thread = new Thread(() -> {
            try {
                // Skip startup delays when using override start (BFS walker active)
                // or in aggressive close-range mode
                if (overrideStartPos == null && TungstenConfig.get().searchTimeoutMs > 500) {
                    // Bounded: an unbounded wait pinned the search forever whenever the
                    // bot never landed (over a gap, in a hole). And the trailing
                    // unconditional sleep(500) that used to sit after this loop was pure
                    // latency on EVERY request — it made a ~58 ms jump search report as
                    // "558 ms", which is how the physics engine got its reputation for
                    // being slow at short hops.
                    long groundWaitUntil = System.currentTimeMillis() + 2000;
                    while (!player.isOnGround() && !player.isTouchingWater()) {
                        if (stop.get() || System.currentTimeMillis() > groundWaitUntil) break;
                        try {
                            Thread.sleep(50);
                        } catch(Exception e) {
                            e.printStackTrace();
                        }
                    }
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
            overrideStartPos = null;

        });
        thread.setName("PathFinder");
        // Below the client thread: a physics search that outbids the renderer
        // drops the game to ~1 fps and the bot stops moving even though it has a
        // plan (stand-observed on the real-terrain chase).
        thread.setPriority(Thread.MIN_PRIORITY);
        startTime = System.currentTimeMillis();
        thread.start();
        return true;
    }
	
	private boolean checkForFallDamage(Node n, WorldView world) {
		if (this.stop.get()) return false;
		if (TungstenModDataContainer.searchIgnoresFallDamage()) return false;
		if (BlockStateChecker.isAnyWater(world.getBlockState(n.agent.getBlockPos()))) return false;
		// Landing on slime bounces instead of hurting — any fall height is safe.
		if (MovementHelper.isSlimeColumnBelow(world, n.agent.getBlockPos(), 32)) return false;
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
			if (currFallDist < -2.75 || prev.agent.isDamaged || n.agent.isDamaged) {
				return true;
			}
		} while (!prev.agent.onGround && !prev.agent.touchingWater);

        return DistanceCalculator.getJumpHeight(prev.agent.getPos().y, n.agent.getPos().y) < -2.75 || prev.agent.isDamaged || n.agent.isDamaged;
	}

	private void search(WorldView world, Vec3d target, PlayerEntity player) {
		search(world, null, target, player);
	}

	private void search(WorldView world, Node start, Vec3d target, PlayerEntity player) {
		search(world, start, target, player, 0);
	}

	private void search(WorldView world, Node start, Vec3d targetIn, PlayerEntity player, int failedAttempts) {
	    boolean failing = true;
	    // Set when the hard cap ends the search; read after the loop, where it used to fall through
	    // both arms and hand the caller nothing.
	    boolean gaveUpHard = false;
	    TungstenModRenderContainer.RENDERERS.clear();

	    long startTime = System.currentTimeMillis();
	    long primaryTimeoutTime = startTime + TungstenConfig.get().searchTimeoutMs;
	    // Time of the last REAL progress (emitted a runnable partial, or advanced along
	    // the block path). NOT bumped by re-roots — a re-root re-plans the same
	    // unreachable partial and would otherwise mask a stall. If no real progress for
	    // HARD_SEARCH_CAP_MS the goal is unreachable and the search gives up (#user-bug).
	    long lastProgressMs = startTime;
		numNodesConsidered.set(0);
	    int timeCheckInterval = 1 << 3;
	    double minVelocity = BlockStateChecker.isAnyWater(world.getBlockState(new BlockPos((int) targetIn.getX(), (int) targetIn.getY(), (int) targetIn.getZ()))) ? 0.2 :  0.07;
	
	    if (player.getEntityPos().distanceTo(targetIn) < 1.0 && minDistPath >= MIN_DIST_PATH) {
	        Debug.logMessage("Already at target location!");
	        return;
	    }
	    if (start == null) {
		    	if (overrideStartPos != null) {
		    		start = initializeStartNodeFromPos(player, overrideStartPos, targetIn);
		    		overrideStartPos = null; // consumed
		    	} else {
		    		start = initializeStartNode(player, targetIn);
		    	}
		    	this.start = start;
	    }
	    if (blockPath.isEmpty()) {
		    long tBlock0 = TungstenConfig.get().debugTime ? System.nanoTime() : 0;
		    Optional<List<BlockNode>> blockPath = findBlockPath(world, targetIn, player);
		    if (blockPath.isPresent()) {
	        	RenderHelper.renderBlockPath(blockPath.get(), NEXT_CLOSEST_BLOCKNODE_IDX.get());
	        	PathFinder.blockPath = blockPath;
	    	    NEXT_CLOSEST_BLOCKNODE_IDX.set(1);

				if (TungstenConfig.get().verboseDebugLogging) Debug.logMessage("Serching for inputs!");
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

	    // Already standing at the wall: the truncated guidance is a stub the
	    // physics search starves on ("Ran out of nodes" with nothing emitted).
	    // Skip the physics leg — hand the executor an empty path with the break
	    // queue, mining starts immediately and the goto retry drives the rest.
	    // ⛔ AND ONLY IF THERE IS STILL A WALL. This shortcut trades the physics leg for a
	    // break, so with every planned block already air it skips the leg for no work at all --
	    // and nothing walks the body through the hole it made a moment ago. That is the stall:
	    // mine, clear the path, return, retry, find the same truncated path, mine again. The
	    // 636 ms "Mining done" is the tell.
	    boolean wallStillThere = false;
	    if (pendingBreaks != null) {
	        for (net.minecraft.util.math.BlockPos bp : pendingBreaks) {
	            if (!world.getBlockState(bp).isAir()) { wallStillThere = true; break; }
	        }
	    }
	    if (!wallStillThere && pendingBreaks != null && !pendingBreaks.isEmpty()
	            && TungstenConfig.get().wallShortcutNeedsAWall) {
	        wallSkipRefused++;
	    }
	    if (pendingBreaks != null && !pendingBreaks.isEmpty()
	            && (wallStillThere || !TungstenConfig.get().wallShortcutNeedsAWall)
	            && blockPath.get().size() <= 2
	            && player.getEyePos().distanceTo(net.minecraft.util.math.Vec3d.ofCenter(pendingBreaks.get(0))) < 4.0) {
	        Debug.logMessage("At the wall — mining without a physics leg");
	        TungstenModDataContainer.EXECUTOR.setPath(new ArrayList<>());
	        TungstenModDataContainer.EXECUTOR.blockPath = blockPath.get();
	        TungstenModDataContainer.EXECUTOR.startBreaking(pendingBreaks);
	        PathFinder.blockPath = Optional.empty();
	        return;
	    }

	    // DIAGNOSTIC (behind verboseDebugLogging): dump the guide the physics search is about
	    // to work with, together with the bot's real position. Three attempts to hook the
	    // pillar mechanism failed because a condition over these nodes never matched, and
	    // guessing at their coordinate convention has already produced two off-by-one bugs
	    // today. Print them instead of assuming.
	    if (TungstenConfig.get().verboseDebugLogging && blockPath.isPresent()) {
	        StringBuilder sb = new StringBuilder();
	        Vec3d me = player.getEntityPos();
	        sb.append(String.format("GUIDE bot=(%.1f,%.1f,%.1f) n=%d:",
	                me.x, me.y, me.z, blockPath.get().size()));
	        int shown = 0;
	        for (BlockNode n : blockPath.get()) {
	            Vec3d np = n.getPos(true, world);
	            sb.append(String.format(" (%.1f,%.1f,%.1f)", np.x, np.y, np.z));
	            if (++shown >= 4) { sb.append(" ..."); break; }
	        }
	        // ALSO the tail: whether the guide ends at the obstacle or on top of it is the
	        // whole question, and printing only the head hid it for four attempts.
	        int n = blockPath.get().size();
	        for (int i = Math.max(n - 3, shown); i < n; i++) {
	            Vec3d np = blockPath.get().get(i).getPos(true, world);
	            sb.append(String.format(" END(%.1f,%.1f,%.1f)", np.x, np.y, np.z));
	        }
	        Debug.logMessage(sb.toString());
	    }

	    // At the gap: the guidance is a stub the physics search starves on (can't sim onto
	    // a not-yet-placed floor). Skip the physics leg — hand the executor an empty path
	    // with the place queue; bridging starts immediately and the goto retry drives the
	    // rest of the now-bridged world. Mirror of the break handoff above.
	    // NOTE: the `size() <= 2` gate is LOAD-BEARING — it fires the handoff only when the
	    // bot is AT the edge, so between paves the physics leg walks the bot forward onto the
	    // just-placed floor. Dropping it (fire on reach alone) made the handoff monopolise
	    // with empty paths and the bot never advanced -> 0/8. The residual ~50% flakiness is
	    // NOT here: it's the block-space search occasionally returning a fall-partial (no
	    // bridge planned that find()) so the physics leg sims ACROSS the gap and falls — a
	    // #1.6.1 search-reliability problem, tracked separately.
	    if (pendingPlaces != null && !pendingPlaces.isEmpty()
	            && blockPath.get().size() <= 2
	            && player.getEyePos().distanceTo(net.minecraft.util.math.Vec3d.ofCenter(pendingPlaces.get(0))) < 5.0) {
	        Debug.logMessage("At the gap — bridging without a physics leg");
	        TungstenModDataContainer.EXECUTOR.setPath(new ArrayList<>());
	        TungstenModDataContainer.EXECUTOR.blockPath = blockPath.get();
	        TungstenModDataContainer.EXECUTOR.placeQueue = new ArrayList<>(pendingPlaces);
	        PathFinder.blockPath = Optional.empty();
	        return;
	    }

	    // A break/place plan means the guide was TRUNCATED at an obstacle, so the real goal
	    // sits behind something that does not exist yet — an unmined wall, an unbuilt floor,
	    // the far side of a pool or a bounce. Searching for it cannot succeed, and the
	    // search dutifully spends its whole 20 s budget before reporting "goal unreachable":
	    // measured at 180 such attempts on nav_break and 232 on nav_slime, i.e. the entire
	    // run wasted. The shortcuts above only rescue the case where the bot is ALREADY at
	    // the obstacle.
	    //
	    // From further out the physics leg's job is just to DELIVER the bot to the obstacle;
	    // the matching mechanism (mining / bridging / swimming) then changes the world and
	    // the route is recomputed. So aim at the end of the truncated guide, not through it.
	    //
	    // NOTE ON SHAPE: `target` is captured by a lambda further down and must stay
	    // effectively final, so the parameter is `targetIn` and the effective target is this
	    // final local. Every use below is unchanged.
	    Vec3d approach = targetIn;
	    boolean obstaclePlanned = (pendingBreaks != null && !pendingBreaks.isEmpty())
	            || (pendingPlaces != null && !pendingPlaces.isEmpty());
	    if (obstaclePlanned && !blockPath.get().isEmpty()) {
	        Vec3d stubEnd = blockPath.get().get(blockPath.get().size() - 1).getPos(true, world);
	        if (stubEnd.distanceTo(targetIn) > 1.5) {
	            approach = stubEnd;
	            if (TungstenConfig.get().verboseDebugLogging) {
	                Debug.logMessage(String.format(
	                        "Obstacle ahead — physics aims at the approach point (%.1f,%.1f,%.1f)",
	                        approach.x, approach.y, approach.z));
	            }
	        }
	    }
	    final Vec3d target = approach;
	    // WHERE DOES THE GUIDE ACTUALLY LEAD? The physics stops 11.3 blocks from a 14.3 block
	    // goal, and the one remaining candidate is that the coarse path points somewhere the
	    // physics cannot step to. Record its endpoint and length next to the goal it is meant to
	    // serve, so the two can be compared instead of assumed to agree.
	    try {
	        if (blockPath.isPresent() && !blockPath.get().isEmpty()) {
	            var lastGuide = blockPath.get().get(blockPath.get().size() - 1).getPos(true, world);
	            // AND NODE 1, because that is the hop the physics cannot take: idx never passes 1.
	            var n0 = blockPath.get().get(0).getPos(true, world);
	            var n1 = blockPath.get().size() > 1
	                    ? blockPath.get().get(1).getPos(true, world) : lastGuide;
	            guideInfo = String.format(java.util.Locale.ROOT,
	                    "n%d n0y%.1f n1[%.1f,%.1f,%.1f]d%.1f dy01%.1f end[%.1f,%.1f,%.1f]toTgt%.1f",
	                    blockPath.get().size(), n0.y, n1.x, n1.y, n1.z,
	                    n1.distanceTo(player.getEntityPos()), n1.y - n0.y,
	                    lastGuide.x, lastGuide.y, lastGuide.z,
	                    lastGuide.distanceTo(targetIn));
	        }
	    } catch (Throwable ignored) {
	    }

	    rerootExhausted = false;
	    // PER-SEARCH, not lifetime: the halt record is only readable when the index and the
	    // guide it indexes belong to the same search.
	    guideIdxThisSearch = 0;
	    guideFurthestAt = null;
	    bestHeuristicSoFar = initializeBestHeuristics(this.start);
	    openSet = new BinaryHeapOpenSet();
	    openSet.insert(this.start);
	    closed.clear();

	    int yieldCounter = 0;
	    while (!openSet.isEmpty()) {
	    	// GIVE THE CLIENT ITS CPU BACK. Thread priority is advisory and the
	    	// stand runs on few cores: a physics search grinding to its timeout
	    	// starved the render/tick thread to 1-4 fps, and a bot that gets two
	    	// ticks per second cannot press movement keys — it stood still with a
	    	// valid plan while this loop spun (measured: fps recovers to 15-17 the
	    	// instant the search gives up). Sleeping ~1ms per batch costs the
	    	// search almost nothing and keeps the game playable.
	    	if ((++yieldCounter & 0xFF) == 0) {
	    		try { Thread.sleep(1); } catch (InterruptedException ignored) {}
	    	}
		    if (blockPath.isEmpty() || blockPath.get().size() < 1) {
		    	// ⛔ THE GUIDE VANISHED UNDER A RUNNING SEARCH, AND THIS USED TO RETURN SILENTLY.
		    	//
		    	// blockPath is the SHARED STATIC field, and four places assign Optional.empty() to
		    	// it -- the wall shortcut, the gap shortcut, the restart and the resume. Any of them
		    	// on any thread kills a search already in flight, and it died without emitting a
		    	// route, without a counter and without a log line.
		    	//
		    	// Measured on flat ground with no obstacles: bs=143/143 (the coarse search finishes
		    	// every time), emptyPathRefused=0 (nothing is handed over at all), physicsRanOut=1
		    	// (not exhaustion either) -- and exArrived=51 with exSprint=0/0, the executor
		    	// re-reporting arrival on a path it finished long ago.
		    	//
		    	// The exhaustion branch below already hands back the best partial route rather than
		    	// nothing. Same mercy here.
		    	guideVanished++;
		    	if (TungstenConfig.get().vanishedGuideSalvagesRoute
		    	        && setCurrentPath(target, start, player)) {
		    		guideVanishedSalvaged++;
		    		Debug.logMessage("Guide vanished mid-search — advancing on the best partial route");
		    	}
		    	return;
		    }
	        if (stop.get()) {
	        	// COUNT THE ABORT. This is the last exit of the physics loop that left no trace,
	        	// and the elimination points here: on flat ground the coarse search finishes 139
	        	// times while emptyPathRefused=0, guideVanished=1 and physicsRanOut=1, so almost
	        	// every search ends somewhere else -- and this is what is left.
	        	//
	        	// The drive sets stop from its own stall detector (14 s without improvement, and
	        	// the stall reset). A bot that is not moving trips that detector, the detector
	        	// aborts the search, and the abort is why it cannot start moving. Self-sustaining.
	        	searchAborted++;
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
	


	        if (isPathComplete(next, target, failing, world)) {
	            if (tryExecutePath(next, target, minVelocity)) {
	            	TungstenModRenderContainer.RENDERERS.clear();
	            	TungstenModRenderContainer.TEST.clear();
	    			closed.clear();
	    			PathFinder.blockPath = Optional.empty();
	                return;
	            }
	        } else if ((numNodesConsidered.get() & (timeCheckInterval - 1)) == 0 && blockPath.isPresent() && NEXT_CLOSEST_BLOCKNODE_IDX.get() == (blockPath.get().size()-1) && blockPath.get().getLast().getPos(true, world).distanceTo(target) > 5 && System.currentTimeMillis() - lastProgressMs < HARD_SEARCH_CAP_MS) {
    			BlockNode lastBlockNode = blockPath.get().getLast();
	        	if (setCurrentPath(TARGET, next, TungstenModDataContainer.player)) {
	        		TungstenModRenderContainer.RENDERERS.clear();
	        		TungstenModRenderContainer.TEST.clear();
	    			closed.clear();
					try { Thread.sleep(500); } catch (InterruptedException e) { e.printStackTrace(); }
					while (TungstenModDataContainer.EXECUTOR.isRunning()) {
						if (stop.get()) return;
						if (TungstenModDataContainer.EXECUTOR.getPath().size() - TungstenModDataContainer.EXECUTOR.getCurrentTick() < 50) break;
						try { Thread.sleep(500); } catch (InterruptedException e) { e.printStackTrace(); }
					}
	    		    // 220ms starved the freshly re-rooted search — handleTimeout
	    		    // fired before any real expansion happened past this point
	    		    primaryTimeoutTime = System.currentTimeMillis() + 3000L;
	        		if (blockPath.get().getLast().getPos(true, world).distanceTo(player.getEntityPos()) < 20) {
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
	
	        // ⛔ A RE-ROOT THAT BUYS NOTHING RESTARTS THE SEARCH FOR NOTHING. The branch below
	        // throws away the frontier AND the closed set, so if the guide comes back no longer
	        // than it went in, the search begins again every eight nodes and never gets deep
	        // enough to emit. Measured: emit/salvage/resetEmit all zero against bs=143/144.
	        if (rerootExhausted && TungstenConfig.get().rerootMustExtendTheGuide) {
	            // fall through: search on with the guide we have
	        } else if (shouldResetSearch(numNodesConsidered.get(), blockPath, next, target)) {
	            final int guideBefore = blockPath.isPresent() ? blockPath.get().size() : 0;
	        	if (TungstenModDataContainer.EXECUTOR.isRunning()) {
	        		TungstenModDataContainer.EXECUTOR.cb = () -> {
	        			blockPath = resetSearch(next, world, blockPath, target, player);
	        		};
	        	} else {
	        		// Executor idle: nothing was emitted yet. The deferred callback
	        		// would never fire, and re-rooting without emitting hands the
	        		// executor a path that starts far from the player (instant
	        		// drift abort). Emit the prefix and extend the block path now.
	        		blockPath = resetSearch(next, world, blockPath, target, player);
	        	}
	            final int guideAfter = blockPath.isPresent() ? blockPath.get().size() : 0;
	            if (TungstenConfig.get().rerootMustExtendTheGuide && guideAfter <= guideBefore) {
	                resetNoGain++;
	                rerootExhausted = true;   // do not restart the search again for this one
	                continue;
	            }
	            openSet = new BinaryHeapOpenSet();
	            this.start = initializeStartNode(next, target);
	            openSet.insert(this.start);
	            // The re-rooted search MUST NOT emit chains from the old root:
	            // stale bestSoFar entries made handleTimeout hand the executor a
	            // path starting where the bot no longer is (drift abort at tick 1).
	            bestHeuristicSoFar = initializeBestHeuristics(this.start);
	            closed.clear();
	            while (TungstenModDataContainer.EXECUTOR.isRunning()) {
	                if (stop.get()) break;
	                try { Thread.sleep(500); } catch (InterruptedException e) { e.printStackTrace(); }
	            }
	            continue;
	        }

	        if ((numNodesConsidered.get() & (timeCheckInterval - 1)) == 0) {
	            if (handleTimeout(startTime, primaryTimeoutTime, next, target, start, player, closed)) {
	            	primaryTimeoutTime = System.currentTimeMillis() + 1020L;
	            	lastProgressMs = System.currentTimeMillis();   // emitted a runnable partial = progress
	                continue;
	            }
	            // Hard give-up: no real progress (no emit, no block-path advance) for the
	            // cap. On open ground the physics openSet never empties, so without this
	            // the search expands forever and the bot "computes" without moving. Stop.
	            if (System.currentTimeMillis() - lastProgressMs > HARD_SEARCH_CAP_MS) {
	                Debug.logWarning("Search gave up: goal unreachable after "
	                        + (HARD_SEARCH_CAP_MS / 1000) + "s without progress");
	                gaveUpHard = true;
	                break;
	            }
	        }
	        
	        if (numNodesConsidered.get() % 20 == 0) {
	        	RenderHelper.renderPathSoFar(next);
	        }
	
	        failing = processNodeChildren(world, next, target, start.agent.getPos(), blockPath, openSet, closed);

	        numNodesConsidered.set(numNodesConsidered.get()+1);
	        // HOW FAR ALONG THE GUIDE DOES THE PHYSICS ACTUALLY GET? The guide has 16 nodes and
	        // ends on the target; the physics stops 11.3 blocks short. The index it reaches names
	        // the exact node pair it cannot step between, which is the whole question now.
	        {
	            int idxNow = NEXT_CLOSEST_BLOCKNODE_IDX.get();
	            if (idxNow > guideIdxReached) guideIdxReached = idxNow;
	            if (idxNow > guideIdxThisSearch) {
	                guideIdxThisSearch = idxNow;
	                guideFurthestAt = next.agent.getPos();
	            }
	        }
	        if (updateNextClosestBlockNodeIDX(blockPath.get(), next, closed, world)) {
	        	primaryTimeoutTime = System.currentTimeMillis() + 1120L;
	        	lastProgressMs = System.currentTimeMillis();   // advanced along the block path = progress
				failedAttempts = 0;
	        	// A NEW OBJECTIVE NEEDS A NEW RECORD. The waypoint just moved, and updateNode
	        	// measures estimatedCostToGoal against THAT waypoint — so the yardstick behind
	        	// bestHeuristicSoFar has changed while the record it holds has not. With a
	        	// positive minimumImprovement the record is monotone, so it freezes until the
	        	// frontier physically reaches the new waypoint and bestSoFar[] lags a segment
	        	// behind. Upstream never meets this because a PathNode's estimatedCostToGoal is
	        	// fixed for the whole search; here the goal legitimately advances, and re-seeding
	        	// is what "the goal changed" means. `next` is the node that reached the new
	        	// waypoint, so it is the honest seed — the same thing setCurrentPath does on a
	        	// re-root.
	        	bestHeuristicSoFar = initializeBestHeuristics(next);
	        	// (previous note, kept for the record:)
	        	// KNOWN, DELIBERATELY NOT FIXED IN THIS PASS (C5.21 follow-up). The waypoint just
	        	// moved, and updateNode measures estimatedCostToGoal against THAT waypoint — so
	        	// the yardstick behind bestHeuristicSoFar has changed while the record it holds
	        	// has not. Upstream cannot hit this: a PathNode's estimatedCostToGoal is fixed for
	        	// the whole search. Here the record now (positive minimumImprovement) freezes
	        	// until the frontier physically reaches the new waypoint, so bestSoFar[] lags a
	        	// segment behind. It self-clears — each emission re-seeds via setCurrentPath, and
	        	// nodes that reach the new waypoint beat the old record — so it costs emission
	        	// FRESHNESS, not emission itself. Left alone on purpose: it is a behaviour change
	        	// nobody has measured, and this pass already has one variable under test.
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
	
	    if (gaveUpHard) {
	        // THE HARD GIVE-UP FELL BETWEEN THE TWO BRANCHES BELOW, AND DELIVERED NOTHING.
	        //
	        // stop is false here and the open set is NOT empty -- that is the whole reason the hard
	        // cap exists, because "on open ground the physics openSet never empties". So neither
	        // arm ran, the search returned no route at all, and the bot simply stood. The comment
	        // under `openSet.isEmpty()` already records what that costs, learned from the twin
	        // search: "a bot that stood in one place for ~500 seconds". Exhaustion was given the
	        // mercy of a partial route; this exit, added later, never was.
	        //
	        // Measured on chop_canopy, a course where a bait log sits three blocks away and seven
	        // up. On the failing runs:
	        //   Search gave up: goal unreachable after 20s without progress
	        //   t=37.9  [-6.4, -60.0, -18.4]
	        //   t=150.9 [-6.4, -60.0, -18.4]     <- unchanged for 110 seconds
	        // The bot walks off, the search quits, nothing comes back, and it stands there for the
	        // rest of the run.
	        //
	        // setCurrentPath is the same delivery the timeout and exhaustion paths use, and
	        // bestSoFar only yields a route with real forward progress, so a search that truly got
	        // nowhere still ends cleanly instead of oscillating.
	        // ⛔ BEFORE SALVAGING A SCRAP, TRY THE ROUTE THIS SEARCH WAS FORBIDDEN TO TAKE.
	        //
	        // The fall-guard relaxation below -- search safely, and if that fails take the damaging
	        // route -- is wired to the EXHAUSTED branch only, and the comment above this one says
	        // why that is the wrong exit: on open ground the physics openSet never empties. So the
	        // branch that actually fires on real terrain has been giving up without ever asking.
	        //
	        // This changes nothing about what the search explores -- the pruning stays, so no extra
	        // nodes and no shift in route preference, which is what sank permitting the drop and
	        // pricing it. It only relaxes the guard for a search that was about to return nothing.
	        if (TungstenConfig.get().gaveUpRetriesWithFallGuardRelaxed
	                && failedAttempts < 2
	                && !TungstenModDataContainer.searchIgnoresFallDamage()) {
	            gaveUpFallRetry++;
	            TungstenModDataContainer.fallGuardRelaxed = true;
	            try {
	                RenderHelper.clearRenderers();
	                closed.clear();
	                PathFinder.blockPath = Optional.empty();
	                search(world, start, target, player, failedAttempts + 1);
	            } finally {
	                TungstenModDataContainer.fallGuardRelaxed = false;
	                // ⛔ AND THE GUIDE MUST NOT OUTLIVE THE RELAXATION THAT BUILT IT.
	                //
	                // This retry builds a NEW block path with the fall guard relaxed and leaves it
	                // in the static field. The next search finds it non-empty, skips rebuilding,
	                // and runs the PHYSICS against it unrelaxed -- so the guide contains descents
	                // the physics is forbidden to take.
	                //
	                // Measured at the 1219 stall: guide n16 with node ONE three blocks below the
	                // bot (n1[1218.5,101.0,-842.5] against bot y=104), and the physics never
	                // advances past idx1. Sixteen planned nodes, none of them reachable, because
	                // the opening hop was planned under rules that no longer apply.
	                //
	                // Neither layer's rules change here. What changes is that a guide is only used
	                // under the rules it was built for.
	                if (TungstenConfig.get().guideDiesWithItsRelaxation) {
	                    PathFinder.blockPath = Optional.empty();
	                    relaxedGuideDropped++;
	                }
	            }
	            return;
	        }
	        searchGaveUp++;
	        noteGuideVsPhysics("gaveUp", world, target, player);
	        if (setCurrentPath(target, start, player)) {
	            searchGaveUpSalvaged++;
	            Debug.logMessage("Search gave up — advancing on the best partial route");
	        }
	    } else if (stop.get()) {
	        if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) Debug.logMessage("stopped!");
	        noteGuideVsPhysics("stopped", world, target, player);
	        stop.set(false);
	    } else if (openSet.isEmpty()) {
			if (failedAttempts < 2 && TungstenModDataContainer.EXECUTOR.getPath() != null) {
				RenderHelper.clearRenderers();
				closed.clear();
				PathFinder.blockPath = Optional.empty();
				Node lastNode = TungstenModDataContainer.EXECUTOR.getPath().getLast();

				search(world, lastNode, target, player, failedAttempts+1);
				return;
			}
			// ⛔ AN EXHAUSTED SEARCH WITH THE FALL GUARD ON MEANS "NO SAFE ROUTE", NOT "NO ROUTE".
			//
			// Measured: with pathAvoidsFallDamage on, two playthrough runs froze at exactly
			// (71.7, 120.0, -70.7), items=0, for their whole duration -- pdEnter+460 and mqSteps+0,
			// so the driver kept asking and the queue never advanced a single step. Zero damage
			// taken, because the bot never moved. That is why the guard shipped disabled: it is
			// correct, and on its own it is fatal on real terrain.
			//
			// The guard should express a PREFERENCE, not a veto. A player does not stand on a hill
			// for five minutes rather than take three hearts. So: search safely first, and if that
			// exhausts, retry once with the guard relaxed and take the damaging route. The relax is
			// cleared immediately after, so the NEXT search starts safe again.
			if (!TungstenModDataContainer.searchIgnoresFallDamage()) {
				fallGuardRetries++;
				TungstenModDataContainer.fallGuardRelaxed = true;
				try {
					RenderHelper.clearRenderers();
					closed.clear();
					PathFinder.blockPath = Optional.empty();
					search(world, start, target, player, failedAttempts);
				} finally {
					TungstenModDataContainer.fallGuardRelaxed = false;
				}
				return;
			}
			physicsRanOut++;
			noteGuideVsPhysics("ranOut", world, target, player);
			// EXHAUSTION DESERVES THE SAME MERCY AS A TIMEOUT, AND ONLY ONE OF THEM HAD IT.
			// handleTimeout (this file, ~1129) already hands the executor the best partial route
			// when the clock runs out; the open set emptying returned nothing at all, so the bot
			// simply stood. Its twin, BlockSpacePathFinder, learned this the expensive way and
			// carries TWO fallbacks with a comment recording a bot that stood in one place for
			// ~500 seconds.
			// Measured on nav_water with smartMoves pinned on: srch=283/0 -- this search exhausted
			// 283 times in one run while the block-space search never did, and the failing runs
			// show the bot never leaving the start pad.
			// setCurrentPath is the same delivery handleTimeout uses, and bestSoFar only yields a
			// route with real forward progress, so a search that got nowhere still gives up
			// cleanly rather than oscillating in place.
			if (setCurrentPath(target, start, player)) {
				physicsRanOutSalvaged++;
				Debug.logMessage("Ran out of nodes — advancing on the best partial route");
			} else {
				Debug.logMessage("Ran out of nodes!");
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

	private boolean shouldSkipChild(Node child, Vec3d target, WorldView world) {
	    return child.agent.touchingWater && shouldSkipNode(child, target, world);
	}

	private boolean shouldSkipNode(Node node, Vec3d target, WorldView world) {
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
	
	private static boolean shouldNodeBeSkipped(Node n, Vec3d target, Set<Integer> closed, boolean addToClosed, boolean isDoingLongJump, boolean shouldAddYaw) {

		int hashCode = n.hashCode(1, shouldAddYaw);
	    Vec3d agentPos = n.agent.getPos();
	    double distanceToTarget = agentPos.distanceTo(target);

	    // Determine scaling factors based on conditions
	    double xScale, yScale, zScale;
	    if (distanceToTarget < 1.0 /* || n.agent.isSubmergedInWater || n.agent.isClimbing(MinecraftClient.getInstance().world) */) {
	        xScale = 1e3;
	        yScale = 1e3;
	        zScale = 1e3;
	    } else if (isDoingLongJump) {
	        xScale = 10;
	        yScale = 1e2;
	        zScale = 10;
	    } else if (n.agent.isClimbing(TungstenModDataContainer.world)) {
	        xScale = 10;
	        yScale = 1e4;
	        zScale = 10;
	    } else if (n.agent.touchingWater) {
	        xScale = 1e3;
	        yScale = 1e2;
	        zScale = 1e3;
	    } else {
	        xScale = 100;
	        yScale = 100;
	        zScale = 100;
	    }

	    // Compute scaled position with hashCode offset
	    int nodeHash = computeScaledPosition(agentPos, hashCode, xScale, yScale, zScale);

	    // Check if the position is in the closed set
	    if (closed.contains(nodeHash)) {
	        return true;
	    }

	    // Optionally add the position to the closed set
	    if (addToClosed) {
	        closed.add(nodeHash);
	    }

	    return false;
	}

	private static int computeScaledPosition(Vec3d pos, int hashCode, double xScale, double yScale, double zScale) {
	    return new Vec3d(
	        Math.round(pos.x * xScale),
	        Math.round(pos.y * yScale),
	        Math.round(pos.z * zScale)
	    ).hashCode() + hashCode;
	}
	
	private static double computeHeuristic(Vec3d position, boolean onGround, Vec3d target, Vec3d realTarget) {
		double xzMultiplier = 1;
	    double dx = (target.x - position.x)*xzMultiplier;
	    double dy = 0;
	    if (target.y != Double.MIN_VALUE) {
		    dy = (target.y - position.y);//* 4.8;//*16;
//		    if (!onGround || dy > 0 && dy < 1.4) dy = 0;
//			dy *= 1.8;
	    }
	    double dz = (target.z - position.z)*xzMultiplier;

		double realTargetDist = DistanceCalculator.getEuclideanDistance(position, realTarget);

	    return
				(Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.8
	    		 + (((blockPath.map(blockNodes -> blockNodes.size() - NEXT_CLOSEST_BLOCKNODE_IDX.get()).orElse(0))) * 0.0)
	    		+ (realTargetDist)
	    		);
	}
	
	private static void updateNode(WorldView world, Node current, Node child, Vec3d target, Vec3d realTarget, List<BlockNode> blockPath, Set<Integer> closed) {
	    Vec3d childPos = child.agent.getPos();

	    double collisionScore = 0;
	    double tentativeCost = child.cost + 1; // Assuming uniform cost for each step
//	    if (child.agent.horizontalCollision && child.agent.getPos().distanceTo(target) > 3) {
//	        collisionScore += 25 + (Math.abs(0.3 - child.agent.velZ) + Math.abs(0.3 - child.agent.velX)) * (child.agent.blockY <= blockPath.get(NEXT_CLOSEST_BLOCKNODE_IDX.get()).getBlockPos().getY() ? 2 : 1);
//	    }
	    /*
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
	    } */
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

//	    child.parent = current;
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
	
	private static boolean updateBestSoFar(Node child, Vec3d start, AtomicDoubleArray bestHeuristicSoFar) {
		boolean failing = true;
	    for (int i = 0; i < COEFFICIENTS.length; i++) {
	        // h + g/C, not (h + g)/C (AStarPathFinder.java:154). The coefficient exists to
	        // DISCOUNT the distance already travelled against the distance still to go — that is
	        // what makes the seven of them a spread of "how much detour am I willing to accept".
	        // Dividing the combined cost discounts both halves equally, so all seven coefficients
	        // rank the frontier identically and only the scale changes: seven searches for the
	        // price of seven, all of them the same search.
	        double heuristic = child.estimatedCostToGoal + child.cost / COEFFICIENTS[i];
	        if (bestHeuristicSoFar.get(i) - heuristic > minimumImprovement) {
	            bestHeuristicSoFar.set(i, heuristic);
	            bestSoFar.set(i, child);
	            if (failing && getDistFromStartSq(child, start) > MIN_DIST_PATH * MIN_DIST_PATH) {
                    failing = false;
                }
	        }
	    }
	    return failing;
	}

	/**
	 * How far this node has travelled from the search's start, squared — upstream is
	 * AbstractNodeCostSearch.java:149-154, which reads startX / startY / startZ.
	 *
	 * <p>All three axes used to read {@code start.x}: a copied line with the letter left
	 * unchanged, returning a number that was not a distance at all. With a start at x=100, y=64
	 * the Y term alone contributed (100-64)^2 = 1296, so the result was enormous everywhere and
	 * the question it answers — "have we got clear of the start yet?" — was satisfied by the
	 * FIRST child expanded, wherever it was. Register C2.3.
	 *
	 * <p>Fixing this alone was measured on 2026-08-02 and reverted: nav went 12/12 -> 10/12.
	 * That was never evidence the arithmetic was right, it was evidence that this engine's
	 * defects mask each other — with the number honest, `failing` stays set, and the outcome
	 * passes to the five defects that were still in place. All six land together (C5.21), which
	 * is the only shape in which this one is meaningful.
	 *
	 * <p>Note for whoever reads a suite result: the value produced here reaches exactly one
	 * consumer — the local `failing` in {@link #updateBestSoFar}, which travels up through
	 * {@code processNodeChildren} to {@code isPathComplete(next, target, failing, world)}, and
	 * THAT method does not read its `failing` parameter. In this engine, as it stands, this
	 * function's result is discarded. It cannot on its own move a course either way.
	 */
	private static double getDistFromStartSq(Node n, Vec3d start) {
		double xDiff = start.x - n.agent.getPos().x;
		double yDiff = start.y - n.agent.getPos().y;
		double zDiff = start.z - n.agent.getPos().z;
		return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff;
	}
	
	private Node initializeStartNode(Node node, Vec3d target) {
        Node start = new Node(null,  Agent.of(node.agent, node.agent.input.toPathInput()), new Color(255, 255, 255), 0);
        start.agent.tick(TungstenModDataContainer.world);
        // h AND f, not just f (AStarPathFinder.java:55-56). estimatedCostToGoal was left at its
        // field default of 0, which was harmless only while initializeBestHeuristics read
        // combinedCost; it seeds the best-so-far metric now, and a seed of 0 would mean no
        // child ever counts as an improvement and nothing is ever emitted.
        start.estimatedCostToGoal = computeHeuristic(start.agent.getPos(), start.agent.onGround, target, TARGET);
        start.combinedCost = start.estimatedCostToGoal;
        return start;
    }

	
	private Node initializeStartNode(PlayerEntity player, Vec3d target) {
        Node start = new Node(null, Agent.of(player), new Color(255, 255, 255), 0);
        start.estimatedCostToGoal = computeHeuristic(start.agent.getPos(), start.agent.onGround, target, TARGET);
        start.combinedCost = start.estimatedCostToGoal;
        return start;
    }

	/** Create start node at a custom position (BFS endpoint).
	 *  Copies player state (effects, dimensions, hunger) but overrides position.
	 *  Velocity zeroed, onGround=true, yaw facing target. */
	private Node initializeStartNodeFromPos(PlayerEntity player, Vec3d pos, Vec3d target) {
        Agent agent = Agent.of(player);
        agent.posX = pos.x;
        agent.posY = pos.y;
        agent.posZ = pos.z;
        agent.blockX = (int) Math.floor(pos.x);
        agent.blockY = (int) Math.floor(pos.y);
        agent.blockZ = (int) Math.floor(pos.z);
        agent.velX = 0;
        agent.velY = 0;
        agent.velZ = 0;
        agent.onGround = true;
        // face toward target
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        agent.yaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
        agent.pitch = 0;
        Node start = new Node(null, agent, new Color(255, 255, 255), 0);
        start.estimatedCostToGoal = computeHeuristic(start.agent.getPos(), start.agent.onGround, target, TARGET);
        start.combinedCost = start.estimatedCostToGoal;
        return start;
    }

    /**
     * The block-space guide for the physics search.
     *
     * THIS is where the fast planner belongs. The physics A* already routes ALONG
     * a block path (see NEXT_CLOSEST_BLOCKNODE_IDX / computeHeuristic): the block
     * route says WHERE to go, tungsten computes HOW to move there with real
     * physics and jumps — which is the whole reason it beats a block-only
     * navigator. Feeding the fast route here means one guided engine instead of
     * two racing ones (the walker sprinting cells while the search re-derives its
     * own guide with the blind radius-8 scan).
     */
    /** How close an INCOMPLETE fast route may stop to the target and still be used as
     *  the physics guide — the last hop is the jump physics was asked for. */
    private static final double FAST_GUIDE_ARRIVE_DIST = 3.0;

    private Optional<List<BlockNode>> findBlockPath(WorldView world, Vec3d target, PlayerEntity player) {
        if (kaptainwutax.tungsten.TungstenConfig.get().fastBlockFirst) {
            try {
                kaptainwutax.tungsten.path.blockSpaceSearchAssist.Goal goal =
                        new kaptainwutax.tungsten.path.blockSpaceSearchAssist.Goal(
                                (int) target.x, (int) target.y, (int) target.z);
                var fast = kaptainwutax.tungsten.path.fast.FastPlanner.plan(
                        world, player.getBlockPos(),
                        net.minecraft.util.math.BlockPos.ofFloored(target),
                        kaptainwutax.tungsten.TungstenConfig.get().fastPlanBudgetMs);
                // ⛔ THE TOGGLE EVIDENCE QUOTED BELOW IS NOW INVERTED. RE-MEASURED 2026-08-10,
                // three runs an arm on a healthy stand, the pin verified in the log:
                //
                //     course       fastBlockFirst ON (default)   OFF (the "old behaviour")
                //     nav_slime            2/3 PASS                    0/3
                //     nav_ladder           3/3 PASS                    3/3
                //     nav_water            3/3 PASS                    0/3
                //
                // "OFF passes, ON fails" was true when it was written and is the opposite of what
                // the stand says today: OFF now fails nav_water outright and never passes slime.
                // The reason is in the same file the claim distrusts -- FastPlanner.special()
                // (:485-530) models ladders, swimming and slime bounce now, and its own debug line
                // prints slime= and climb=. The move-set gap the rule was built on has closed.
                //
                // AND THE RULE ITSELF WAS THEN MEASURED, via fastGuidePartial. Third arm, same
                // courses, three runs each, all healthy:
                //
                //     arm                                   nav_slime  nav_ladder  nav_water
                //     fastBlockFirst=false (old behaviour)     0/3         3/3        0/3
                //     default (fast-first + this rule)         2/3         3/3        3/3
                //     fastGuidePartial=true (rule relaxed)     3/3         3/3        0/3
                //
                // The rule EARNS ITS KEEP ON nav_water and nowhere else that was measured:
                // relaxing it costs that course 3/3, decisively. Its supposed cost on nav_slime is
                // NOT established -- the 2/3 above is one series, a later three at the default read
                // 3/3, so with the rule in force slime is 5/6 against 6/6 relaxed, which no series
                // this size can separate. The first version of this note asserted the cost anyway.
                //
                // Of the four moves the justification below names -- slime bounce, ladder, vine,
                // swim -- only the WATER half is still load-bearing, which is what
                // FastPlanner.special() modelling slime and ladders predicts.
                //
                // Only guide with a COMPLETE fast route. The fast move set is
                // walking, climbing, dropping and gap jumps — it has no slime
                // bounce, ladder, vine or swim moves, which the legacy search
                // does have. Guiding the physics engine with a partial fast route
                // through such terrain hides those options and broke the slime
                // drop-bounce course (proven with the toggle: OFF passes, ON
                // fails). An incomplete plan means "this terrain needs moves I do
                // not model" — hand it back to the search that models them.
                //
                // ...BUT a route that stops right NEXT to the target is not that case: it
                // is the normal outcome when the last step is a jump the fast planner
                // deliberately delegates to physics — which is exactly what this guide is
                // being built for. Rejecting it sent those requests to the legacy search,
                // which answered "Ran out of nodes" and killed every parkour hand-off.
                // So: accept a complete route, or an incomplete one that already arrives.
                boolean arrivesAnyway = !fast.path.isEmpty()
                        && Math.sqrt(fast.path.get(fast.path.size() - 1).pos
                                .getSquaredDistance(net.minecraft.util.math.BlockPos.ofFloored(target)))
                            <= FAST_GUIDE_ARRIVE_DIST;
                // STEP 2 OF THE RE-MEASUREMENT, BEHIND A FLAG SO THE DEFAULT IS UNTOUCHED.
                // The toggle evidence above is inverted, but that only settles fast-first itself;
                // the complete-only acceptance has never been tested on its own because it lives
                // inside the arm that passes. fastGuidePartial=true accepts ANY fast route as a
                // guide, which is the rule's opposite, so the two can be compared on the same
                // courses that produced the original claim. Default false: shipping behaviour does
                // not move until the measurement says it should.
                // ⛔ THE CONDITIONAL VERSION WAS BUILT, MEASURED AND REVERTED. The table says the
                // rule is blunt -- it saves nav_water and costs nav_slime -- so the obvious fix is
                // to reject a partial route only when the REMAINDER needs the one move the fast set
                // still cannot make. Implemented as a straight XZ walk from the route's end to the
                // goal, sampling a vertical band for fluid, conservative past 64 cells.
                //
                // It scored 6/9: nav_slime 3/3, nav_ladder 3/3, nav_water 0/3 -- IDENTICAL to
                // relaxing the rule entirely. So the discriminator never fires on the course it was
                // written for: whatever makes a partial route fatal there is not fluid on the
                // straight line between the route's end and the target.
                //
                // The next attempt should not guess at geometry again. FastPlanner KNOWS why it
                // stopped -- which move it wanted and did not have -- and asking it is a fact where
                // this was an inference. Until someone does that, the blanket rule stays: 8/9
                // against 6/9 for both relaxations.
                boolean acceptable = fast.complete || arrivesAnyway
                        || kaptainwutax.tungsten.TungstenConfig.get().fastGuidePartial;
                if (acceptable && fast.path.size() >= 2) {
                    return truncateAtBreaks(Optional.of(fast.toBlockNodes(goal, player)));
                }
            } catch (Exception e) {
                Debug.logWarning("Fast block guide failed, falling back: " + e.getMessage());
            }
        }
        return truncateAtBreaks(kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockSpacePathFinder.search(world, target, player));
    }

    private Optional<List<BlockNode>> findBlockPath(WorldView world, BlockNode start, Vec3d target, PlayerEntity player) {
        return truncateAtBreaks(kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockSpacePathFinder.search(world, start, target, player));
    }

    /** Planned mining positions for the wall right past the current block path
     *  segment (null when the path has no breaks). Copied to the executor on
     *  every emission; the executor mines them once the replay finishes, then
     *  the goto retry / path-extension machinery re-searches the opened world. */
    public static List<BlockPos> pendingBreaks = null;
    /** Bridge floor blocks to PLACE at the segment end — the mirror of pendingBreaks. */
    public static List<BlockPos> pendingPlaces = null;

    /** Physics guidance must stop at the cell before a wall (break) OR a gap (place) —
     *  the live world can't be simulated through the missing/extra blocks. Truncates at
     *  whichever comes first and records the break/place plan for that segment. */
    private static Optional<List<BlockNode>> truncateAtBreaks(Optional<List<BlockNode>> path) {
        if (path.isEmpty()) {
            return path;
        }
        List<BlockNode> list = path.get();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).hasBreaks()) {
                pendingBreaks = new ArrayList<>(list.get(i).toBreak);
                pendingPlaces = null;
                Debug.logMessage("Path needs mining: " + pendingBreaks.size() + " block(s) at segment end");
                return Optional.of(new ArrayList<>(list.subList(0, Math.max(i, 1))));
            }
            if (list.get(i).hasPlaces()) {
                pendingPlaces = new ArrayList<>(list.get(i).toPlace);
                pendingBreaks = null;
                Debug.logMessage("Path needs bridging: " + pendingPlaces.size() + " block(s) at segment end");
                return Optional.of(new ArrayList<>(list.subList(0, Math.max(i, 1))));
            }
        }
        pendingBreaks = null;
        pendingPlaces = null;
        if (TungstenConfig.get().verboseDebugLogging) {
            Debug.logMessage("block path: no breaks/places (size " + list.size() + ")");
        }
        return path;
    }

    private AtomicDoubleArray initializeBestHeuristics(Node start) {
    	AtomicDoubleArray bestHeuristicSoFar = new AtomicDoubleArray(COEFFICIENTS.length);
        for (int i = 0; i < bestHeuristicSoFar.length(); i++) {
            // Same metric updateBestSoFar uses, seeded with the start (AStarPathFinder.java:61).
            // The start's g is 0, so this is just its heuristic — but written in full, because
            // the two ends of this comparison drifting apart is how the whole thing went wrong.
            bestHeuristicSoFar.set(i, start.estimatedCostToGoal + start.cost / COEFFICIENTS[i]);
            bestSoFar.set(i, start);
        }
        return bestHeuristicSoFar;
    }
    
    /**
     * WHERE THE GUIDE LEADS, AND WHERE THE PHYSICS ACTUALLY STOPS.
     *
     * <p>Called at the three exits where a search ends with no route to hand over. It writes down,
     * for THIS search: how long the guide was, how far along it the physics got, where the body and
     * the frontier were, and -- the point of the whole thing -- the hop the physics never crossed,
     * with the blocks under, at and above both of its ends.
     *
     * <p>The reason it is a per-search record and a histogram rather than a log line: a single halt
     * is a sample, and this repo has paid twice for reading one. The histogram says whether the
     * un-crossed hop has a SHAPE (a three-block descent, a six-block coarse waypoint, a step into
     * leaves) or whether the physics stops in a different place every time -- which are different
     * defects with different fixes.
     */
    private void noteGuideVsPhysics(String why, WorldView world, Vec3d target, PlayerEntity player) {
        try {
            if (blockPath.isEmpty() || blockPath.get().isEmpty()) return;
            List<BlockNode> g = blockPath.get();
            int n = g.size();
            int idx = Math.max(0, Math.min(guideIdxThisSearch, n - 1));
            gvpSamples++;
            if (idx <= 1) gvpStuckAtStart++;
            if (idx >= n - 1) gvpReachedGuideEnd++;
            StringBuilder sb = new StringBuilder();
            Vec3d me = player.getEntityPos();
            Vec3d guideEnd = g.get(n - 1).getPos(true, world);
            sb.append(String.format(java.util.Locale.ROOT,
                    "%s n%d idx%d bot(%.1f,%.1f,%.1f) tgt(%.1f,%.1f,%.1f) endToTgt%.1f",
                    why, n, idx, me.x, me.y, me.z, target.x, target.y, target.z,
                    guideEnd.distanceTo(target)));
            BlockNode at = g.get(idx);
            Vec3d fur = guideFurthestAt;
            if (fur != null) {
                sb.append(String.format(java.util.Locale.ROOT, " phys(%.1f,%.1f,%.1f)dNode%.1f",
                        fur.x, fur.y, fur.z, fur.distanceTo(at.getPos(true, world))));
            }
            String shape;
            if (idx + 1 < n) {
                BlockNode to = g.get(idx + 1);
                shape = (to.x - at.x) + "," + (to.y - at.y) + "," + (to.z - at.z);
                sb.append(" hop[").append(shape).append("] from").append(cellDump(world, at))
                  .append(" to").append(cellDump(world, to));
            } else {
                // The physics walked the whole guide and still could not finish: the guide is
                // short of the goal, which is a different defect from an uncrossable hop.
                shape = "END";
                sb.append(" hop[END] at").append(cellDump(world, at));
            }
            synchronized (gvpHopShapes) { gvpHopShapes.merge(shape, 1, Integer::sum); }
            synchronized (gvpDumps) {
                gvpDumps.addLast(sb.toString());
                while (gvpDumps.size() > 8) gvpDumps.removeFirst();
            }
        } catch (Throwable ignored) {
            // A diagnostic must never be able to break the search it is watching.
        }
    }

    /** A guide cell as the world sees it: below|feet|head, so "no floor" and "occupied" are visible. */
    private static String cellDump(WorldView world, BlockNode n) {
        BlockPos p = new BlockPos(n.x, n.y, n.z);
        return String.format(java.util.Locale.ROOT, "(%d,%d,%d)[%s|%s|%s]", n.x, n.y, n.z,
                blkName(world, p.down()), blkName(world, p), blkName(world, p.up()));
    }

    private static String blkName(WorldView world, BlockPos p) {
        try {
            String t = world.getBlockState(p).getBlock().toString();   // Block{minecraft:stone}
            int a = t.indexOf('{'), b = t.lastIndexOf('}');
            if (a >= 0 && b > a) t = t.substring(a + 1, b);
            return t.replace("minecraft:", "");
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private boolean isPathComplete(Node node, Vec3d target, boolean failing, WorldView world) {
    	// NOTE: an edge-completion attempt (complete at the truncated block-path endpoint when a
    	// break/place is pending) was tried here and REVERTED — break-safe (break_test 4/4) but it
    	// did NOT fix the core_bridge ~50% flakiness (3/8, within noise of the 2/6 baseline). The
    	// flakiness root is deeper in the block-space search (#1.6.1) than the physics-leg fall.
    	// Deferred to a dedicated #1.6.1 rework; see TODOS.md.
    	if (BlockStateChecker.isAnyWater(world.getBlockState(new BlockPos((int) target.getX(), (int) target.getY(), (int) target.getZ()))))
    		return node.agent.getPos().squaredDistanceTo(target) <= 0.9D;
    	if (world.getBlockState(new BlockPos((int) target.getX(), (int) target.getY(), (int) target.getZ())).getBlock() instanceof LadderBlock)
    		return node.agent.getPos().squaredDistanceTo(target) <= 0.9D;
        // HOW CLOSE DOES THE FRONTIER ACTUALLY GET? The window is 0.2 SQUARED -- 0.45 blocks --
        // and a simulation step is 0.2-0.3 walking and about 0.4 sprinting, so arrival is a target
        // smaller than one step. This records the nearest approach so the fix is aimed at a
        // measured miss distance rather than a guessed tolerance.
        double d2 = node.agent.getPos().squaredDistanceTo(target);
        // CLAMPED, because the last reading saturated int and that could have been my arithmetic
        // rather than the distance. Also record the raw pair once, so 'the target is wrong' is
        // confirmed by coordinates instead of inferred from an overflow.
        double dist = Math.sqrt(d2);
        int cm = (int) Math.min(dist * 100.0, 2_000_000_000.0);
        if (nearestApproachCm == 0 || cm < nearestApproachCm) nearestApproachCm = cm;
        if (goalTests == 0) {
            lastGoalPair = String.format(java.util.Locale.ROOT, "agent[%.1f,%.1f,%.1f]->tgt[%.1f,%.1f,%.1f]d%.1f",
                    node.agent.getPos().x, node.agent.getPos().y, node.agent.getPos().z,
                    target.x, target.y, target.z, dist);
        }
        goalTests++;
        return d2 <= 0.2D;
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
        // COUNT THE GATE: a route is emitted only when the simulated agent is STATIONARY at
        // the final node. A search that reaches the goal while still moving -- which is what
        // sprinting across open ground does -- is refused, and emit=0 against 1.4M expanded
        // nodes is exactly how that looks from outside.
        tryEmitCalls++;
        boolean stationary = AgentChecker.isAgentStationary(node.agent, minVelocity);
        if (stationary) tryEmitStationary++;
        // ARRIVING IS NOT THE SAME AS STOPPING, AND ONLY ONE OF THEM WAS ACCEPTED.
        // This method is reached only from isPathComplete, so the node has ALREADY satisfied
        // the goal test. Refusing it because the simulated agent still carries velocity throws
        // away a finished route for the most ordinary case there is: walking across open
        // ground at speed. Measured on a twenty-minute run: tryEmit=209/45 -- one hundred and
        // sixty-four completed searches discarded for moving -- with srch=173/0/0 downstream,
        // the executor handed an empty path, tick==path.size() true at once, "arrived", replan,
        // and a bot that stands. The comment above this gate has said so since it was counted;
        // what was missing was the change, not the diagnosis.
        // A FALLBACK, NOT A PREFERENCE -- WHICH IS WHAT THE FIRST VERSION GOT WRONG.
        // Emitting on the FIRST goal-reaching node makes the bot arrive with momentum even
        // when a stationary arrival was a few nodes further on, and the paired runs show the
        // cost: dead time improved (median 42 -> 36) while items COLLECTED fell (22 -> 12).
        // The runs it rescues are the ones where the search finds no stationary arrival at
        // all -- tryEmit=89/0 without it, against 466/119 in a healthy run. So take the
        // moving arrival only once this search has tried and failed to find a stopped one.
        boolean movingFallback =
                kaptainwutax.tungsten.TungstenConfig.get().emitAtGoalEvenWhenMoving
                && !stationary
                && goalTests >= MOVING_FALLBACK_AFTER_TESTS;
        if (movingFallback) tryEmitMoving++;
        if (stationary
        		|| movingFallback || 
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
        if (TungstenConfig.get().verboseDebugLogging && !path.isEmpty()) {
            Debug.logMessage(String.format("emit[executePath] root=%s size=%d",
                path.get(0).agent.getPos().toString(), path.size()));
        }
        // ⛔ AN EMPTY PATH IS A FAILED SEARCH, NOT AN ARRIVAL.
        //
        // The executor finishes a path when tick == path.size(), which an empty list satisfies on
        // the first tick: it reports ARRIVED having replayed nothing, and the drive plans the next
        // goal believing this one was reached. Measured on flat ground with no obstacles at all --
        // exArrived=49 against exSprint=0/0, mqStarted=0, the body covering nothing.
        //
        // The callers all know how to handle a FAILED search: retry, relax the fall guard, wander.
        // None of them can handle being told the bot got there.
        if (TungstenConfig.get().emptyPathIsNotArrival && (path == null || path.isEmpty())) {
            emptyPathRefused++;
            Debug.logWarning("Search produced an empty path — refusing to call that an arrival");
            return;
        }
        // COUNT WHAT IS ACTUALLY HANDED OVER, AND BY WHICH DOOR. Static reading has gone in a
        // circle here: the executor completes 99 paths in "0 minutes, 0 seconds" while replaying
        // ZERO ticks, my empty-path guard never fires, and neither deliberate empty-path branch
        // appears in the log. One counter of size-and-door settles which of those readings is wrong.
        emitCount++;
        searchHasEmitted = true;
        emitTotalNodes += path.size();
        if (TungstenModDataContainer.EXECUTOR.isRunning()) emitAppended++; else emitFresh++;
        if (TungstenModDataContainer.EXECUTOR.isRunning()) {
            TungstenModDataContainer.EXECUTOR.addPath(path);
            TungstenModDataContainer.EXECUTOR.blockPath = blockPath.orElseGet(null);
        } else {
        	TungstenModDataContainer.EXECUTOR.setPath(path);
            TungstenModDataContainer.EXECUTOR.blockPath = blockPath.orElseGet(null);
        }
        TungstenModDataContainer.EXECUTOR.startBreaking(pendingBreaks);
        TungstenModDataContainer.EXECUTOR.placeQueue = pendingPlaces == null ? null : new ArrayList<>(pendingPlaces);
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
            if (TungstenConfig.get().verboseDebugLogging && !path.isEmpty()) {
                Debug.logMessage(String.format("emit[resetSearch] root=%s size=%d",
                    path.get(0).agent.getPos().toString(), path.size()));
            }
            // ⛔ THE THIRD DELIVERY DOOR, AND THE ONLY ONE LEFT UNCOUNTED. executePath reads
            // emit=0/0/0/0 and setCurrentPath reads salvage=0/0, yet the executor reports 44
            // arrivals -- so the paths it finishes instantly arrive HERE, from the reset-search
            // branch, which builds a route from the node in hand and hands it over directly.
            // ⛔ A PREFIX WITH NO MOVEMENT IN IT IS NOT A ROUTE.
            //
            // shouldResetSearch opens on (numNodesConsidered & 7) == 0, which is true at ZERO, so
            // this can fire on the first node considered -- and constructPath then returns the
            // start node alone. The executor finds tick == path.size() on arrival, replays nothing
            // (a root carries no input) and reports ARRIVED, and the drive plans the next goal
            // believing this one was reached.
            //
            // Measured: resetEmit=54/54 -- fifty-four hand-overs of fifty-four nodes in total.
            // Re-rooting the guide is right and stays; handing over an empty walk is not.
            if (TungstenConfig.get().resetPrefixNeedsMovement && path.size() < 2) {
                resetEmitRefused++;
            } else {
            resetEmit++;
            resetEmitNodes += path.size();
            TungstenModDataContainer.EXECUTOR.setPath(path);
            TungstenModDataContainer.EXECUTOR.blockPath = blockPath.orElseGet(null);
            TungstenModDataContainer.EXECUTOR.startBreaking(pendingBreaks);
            }
            TungstenModDataContainer.EXECUTOR.placeQueue = pendingPlaces == null ? null : new ArrayList<>(pendingPlaces);
        TungstenModDataContainer.EXECUTOR.placeQueue = pendingPlaces == null ? null : new ArrayList<>(pendingPlaces);
            NEXT_CLOSEST_BLOCKNODE_IDX.set(1);
        	RenderHelper.renderBlockPath(blockPath.get(), NEXT_CLOSEST_BLOCKNODE_IDX.get());
        	return blockPath;
        }
        Debug.logWarning("Failed!");
        kaptainwutax.tungsten.path.PathFinder.noteStop("PathFinder@1533");
        stop.set(true);
        return Optional.empty();
    }

    private boolean handleTimeout(long startTime, long primaryTimeoutTime, Node next, Vec3d target, Node start, PlayerEntity player, Set<Integer> closed) {
        long now = System.currentTimeMillis();
        if (now < primaryTimeoutTime) return false;
        Optional<List<Node>> result = PathFinder.bestSoFar(true, 0, start, TungstenModDataContainer.PATHFINDER.TARGET);

		  if (result.isEmpty() // || result.get().size() < 46
//				  || !(result.get().getLast().agent.onGround && result.get().getLast().agent.touchingWater)
				  || result.get().getLast().agent.isClimbing(TungstenModDataContainer.world)
				  || result.get().getLast().agent.getPos().distanceTo(result.get().getFirst().agent.getPos()) < 1.5
		  ) {
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

        // The executor replays inputs from the path's first node — a chain that
        // does not start where the player actually stands is garbage (stale
        // root from before a re-root) and drift-aborts on tick 1. Refuse it.
        if (result.get().getFirst().agent.getPos().distanceTo(player.getEntityPos()) > 2.0) {
            // THE EXECUTOR ALREADY KNOWS HOW TO HANDLE A DISTANT ROOT — ASK IT.
            // PathExecutor.setPath ARMS a path whose root is out of reach: it does not replay,
            // it waits while the WALKER carries the bot to that root, and only then starts. Its
            // own comment says arming exists for exactly this, and that arming without a walker
            // would be a deadlock rather than a wait.
            //
            // This check fires FIRST and throws the emission away, so that mechanism never gets
            // the chance — measured as staleRoot=609 in eight minutes, every one a whole search
            // discarded. Three ways of avoiding the staleness have now been tried and measured
            // worse or useless (slicing the tail, re-seeding the next search at the player,
            // rooting ahead of the player). The reason they all failed is the same: they treated
            // a distant root as an error to be prevented, when the executor treats it as a
            // situation to be waited out.
            //
            // So the refusal narrows to the case the executor genuinely cannot survive: nobody
            // is walking the bot to that root.
            if (kaptainwutax.tungsten.task.BlockPathWalker.isRunning()) {
                if (TungstenConfig.get().verboseDebugLogging) {
                    Debug.logMessage("Distant root, but the walker is driving — letting the "
                            + "executor arm it");
                }
            } else {
            staleRootRejections++;
            if (TungstenConfig.get().verboseDebugLogging) {
                Debug.logMessage("Rejecting stale-rooted path emission (root far from player)");
            }
            // ...AND MAKE THE NEXT SEARCH START WHERE THE BOT ACTUALLY IS.
            // The root goes stale because the bot kept walking while the search ran, and
            // refusing the emission alone lets that repeat forever: 374 rejections in one live
            // @gamer run, every one of them a whole search thrown away.
            //
            // Slicing the path and emitting the tail was tried and measured WORSE (nav 10/12,
            // nav_flat and nav_staircase red with freezes on flat ground): this executor replays
            // recorded INPUTS from the first node, so starting mid-chain replays inputs for a
            // body that was already moving. A waypoint list can be re-rooted; a recording cannot.
            //
            // The honest fix is on the search side, and the machinery already exists —
            // overrideStartPos is consumed by the next search to root it at a given position.
            TungstenModDataContainer.PATHFINDER.overrideStartPos = player.getEntityPos();
            return false;
            }
        }

        Node newStart = null;
        if (result.get().getLast() != null) {
        	newStart = TungstenModDataContainer.PATHFINDER.initializeStartNode(result.get().getLast(), target);
        } else if (result.get().get(result.get().size()-2) != null) {
        	newStart = TungstenModDataContainer.PATHFINDER.initializeStartNode(result.get().get(result.get().size()-2), target);
        }
        if (newStart == null || !newStart.agent.onGround && !newStart.agent.touchingWater && !newStart.agent.isClimbing(TungstenModDataContainer.world)) return false;
        if (TungstenConfig.get().verboseDebugLogging) {
            Debug.logMessage(String.format("emit[setCurrentPath] root=%s size=%d",
                result.get().get(0).agent.getPos().toString(), result.get().size()));
        }
        // THE SECOND DOOR, AND THE ONE THE emit COUNTER COULD NOT SEE. setCurrentPath delivers a
        // best-partial route straight to the executor, bypassing executePath entirely -- which is
        // why emit read 0/0/0/0 while the executor was arriving 56 times. Count it here.
        salvageEmit++;
        salvageEmitNodes += result.get().size();
        // ⛔ THE SECOND DOOR, AND THE emit COUNTER NEVER SAW IT. executePath is instrumented and
        // reads 0/0/0/0, yet the executor reports 56 arrivals -- because setCurrentPath delivers
        // here, bypassing it entirely. Its callers are the timeout, the exhaustion branch, the
        // give-up and the vanished-guide salvage, and it hands over the BEST PARTIAL route, which
        // on a bot that has not moved is a stub the executor finishes instantly.
        salvageEmit++;
        TungstenModDataContainer.EXECUTOR.addPath(result.get());
        TungstenModDataContainer.EXECUTOR.blockPath = blockPath.orElseGet(null);
        TungstenModDataContainer.EXECUTOR.startBreaking(pendingBreaks);
        TungstenModDataContainer.EXECUTOR.placeQueue = pendingPlaces == null ? null : new ArrayList<>(pendingPlaces);
        // Continue A* from the last node of the emitted path — don't reset the
        // entire search. This allows pathfinder to keep computing while executor
        // runs the partial path, appending new nodes via addPath().
        for (int i = 0; i < COEFFICIENTS.length; i++) {
	        TungstenModDataContainer.PATHFINDER.bestSoFar.set(i, null);
		}
        TungstenModDataContainer.PATHFINDER.clearParentsForBestSoFar(newStart);
        TungstenModDataContainer.PATHFINDER.closed.clear();
        // THE RE-SEED HAS TO BE KEPT. initializeBestHeuristics BUILDS AND RETURNS a new
        // threshold array — the return value was dropped here, so the re-rooted search carried
        // on comparing against the thresholds the PREVIOUS root had already driven down.
        //
        // That was survivable only while minimumImprovement was negative, i.e. while the
        // thresholds were ignored. With a positive threshold the record is monotone, so after
        // this emission no child of the new root can beat the old root's record, bestSoFar[]
        // stays where initializeBestHeuristics just put it — at newStart, whose parent is null —
        // and bestSoFar() skips every entry and returns empty. The bot emits ONCE and then
        // stands still until the 20 s hard cap, on every course at the same time.
        TungstenModDataContainer.PATHFINDER.bestHeuristicSoFar =
                TungstenModDataContainer.PATHFINDER.initializeBestHeuristics(newStart);
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


        return shouldSkipNode(child, TARGET, world);
    }

    /**
     * Evaluate ONE candidate child and keep it if it survives every filter.
     *
     * <p>Extracted so the chunked and per-child parallel branches share one implementation:
     * they were duplicated, and the duplicate had drifted into dropping the rest of a chunk
     * on the first rejection. Rejecting a child must never affect any other child.
     */
    private void acceptChildIfValid(Node child, BlockNode lastBlockNode, BlockNode nextBlockNode,
            boolean isSmallBlock, WorldView world, Queue<Node> validChildren) {
        // Reject if too close to an already accepted child (near-duplicate state).
        for (Node other : validChildren) {
            if (Thread.currentThread().isInterrupted()) return;
            double distance = other.agent.getPos().distanceTo(child.agent.getPos());

            boolean bothClimbing = other.agent.isClimbing(world) && child.agent.isClimbing(world);
            boolean bothNotClimbing = !other.agent.isClimbing(world) && !child.agent.isClimbing(world);

            if ((bothClimbing && distance < 0.03) || (bothNotClimbing && distance < 0.294)
                    || (isSmallBlock && distance < 0.2)) {
                return;
            }
        }

        if (filterChidren(child, lastBlockNode, nextBlockNode, isSmallBlock, world)) return;
        if (checkForFallDamage(child, world)) return;

        validChildren.add(child);
    }

    private boolean processNodeChildren(WorldView world, Node parent, Vec3d target, Vec3d start, Optional<List<BlockNode>> blockPath,
            BinaryHeapOpenSet openSet, Set<Integer> closed) {
			boolean timing = TungstenConfig.get().debugTime;
			long t0 = timing ? System.nanoTime() : 0;

			AtomicBoolean failing = new AtomicBoolean(true);
			if (blockPath.isEmpty()) return false;
			int blockIdx = Math.min(NEXT_CLOSEST_BLOCKNODE_IDX.get(), blockPath.get().size() - 1);
			List<Node> children = parent.getChildren(world, target, blockPath.get().get(blockIdx));
			// SPLIT THE ZERO. children=0 at the insertion site says the frontier is never fed, but
			// not whether the move generator produced nothing or the filter rejected everything.
			// Two counters, one question each.
			rawChildren += children.size();
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

			BlockNode lastBlockNode = blockPath.get().get(Math.max(blockIdx - 1, 0));
			BlockNode nextBlockNode = blockPath.get().get(blockIdx);
	        double closestBlockVolume = BlockShapeChecker.getShapeVolume(nextBlockNode.getBlockPos().down(), world);
	        boolean isSmallBlock = closestBlockVolume > 0 && closestBlockVolume < 1;
			
			List<Callable<Void>> tasks = new ArrayList<>();
			
			// Both branches evaluate a child IDENTICALLY; they used to be two copies of the
			// same block, and the copies had drifted into a serious bug. In the chunked
			// branch the body ran inside `for (j : nodes)`, but every rejection path was
			// `return null` — which exits the whole Callable, so the FIRST rejected child
			// silently discarded every remaining child in its chunk. With ~192 candidates
			// per expansion and a near-duplicate test that rejects early and often, the
			// physics search was exploring a small, arbitrary subset of its own move space,
			// and WHICH subset depended on ForkJoin scheduling order — so the search was
			// non-deterministic on top of being crippled. One shared method now, so the two
			// paths cannot drift again. (Audit 2026-07-27, C2.4.)
			if (children.size() > 5) {
				Node[][] chunks = ArrayChunkSplitter.splitArrayIntoChunksOfX(children.toArray(new Node[children.size()]), children.size()/5);

				for (int i = 0; i < chunks.length; i++) {
					Node[] nodes = chunks[i];
					tasks.add(() -> {
						for (Node child : nodes) {
							if (stop.get()) return null;
							if (Thread.currentThread().isInterrupted()) return null;
							acceptChildIfValid(child, lastBlockNode, nextBlockNode, isSmallBlock,
									world, validChildren);
						}
						return null;
					});
				}

			} else {
				tasks = children.stream().map(child -> (Callable<Void>) () -> {
					if (stop.get()) return null;
					if (Thread.currentThread().isInterrupted()) return null;
					acceptChildIfValid(child, lastBlockNode, nextBlockNode, isSmallBlock,
							world, validChildren);
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

			validAfterFilter += validChildren.size();
			long tFiltered = timing ? System.nanoTime() : 0;

			Object openSetLock = new Object();  // if openSet is not thread-safe
			
			List<Callable<Void>> processingTasks = new ArrayList<>();
					
			if (validChildren.size() > 25) {
				Node[][] chunks = ArrayChunkSplitter.splitArrayIntoChunksOfX(validChildren.toArray(new Node[validChildren.size()]), children.size()/25);

				for (int i = 0; i < chunks.length; i++) {
					Node[] nodes = chunks[i];
					processingTasks.add(() -> {
						for (int j = 0; j < nodes.length; j++) {
							Node child = nodes[j];
							if (stop.get()) return null;
					    	if (Thread.currentThread().isInterrupted()) return null;
					        updateNode(world, parent, child, target, TARGET, blockPath.get(), closed);
		
					        synchronized (openSetLock) {
					            expandedChildren++;
					            if (child.isOpen()) {
					                openSet.update(child);
					            } else {
					                openSet.insert(child);
					            }
					        }

					        // Update best heuristic safely
					        synchronized (bestHeuristicSoFar) {
					            if (!updateBestSoFar(child, start, bestHeuristicSoFar)) {
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
				            expandedChildren++;   // the branch that ACTUALLY runs: <=25 valid children
				            if (child.isOpen()) {
				                openSet.update(child);
				            } else {
				                openSet.insert(child);
				            }
				        }
	
				        // Update best heuristic safely
				        synchronized (bestHeuristicSoFar) {
				            if (!updateBestSoFar(child, start, bestHeuristicSoFar)) {
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
    
    private boolean updateNextClosestBlockNodeIDX(List<BlockNode> blockPath, Node node, Set<Integer> closed, WorldView world) {
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

//			if (setCurrentPath(TARGET, this.start, TungstenModDataContainer.player)) {
				NEXT_CLOSEST_BLOCKNODE_IDX.set(closestPosIDX+1);
	        	RenderHelper.renderBlockPath(blockPath, NEXT_CLOSEST_BLOCKNODE_IDX.get());
				closed.clear();
				return true;
//			}
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
    				closed.clear();
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
	
    /** Wall shortcuts refused because every planned break was already air. */
    public static volatile int wallSkipRefused;
}