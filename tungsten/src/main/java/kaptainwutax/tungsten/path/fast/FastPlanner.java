package kaptainwutax.tungsten.path.fast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.helpers.PlayerFit;
import kaptainwutax.tungsten.path.calculators.ActionCosts;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

/**
 * A fast, honest block-space planner: baritone-class A* over cells, built so the
 * bot can START WALKING almost immediately while the physics engine keeps
 * working on the hard parts.
 *
 * WHY A NEW PLANNER. The existing block search generated neighbours by scanning
 * a blind radius-8 sphere (hundreds of candidates per expansion), never
 * accumulated a path cost (g(n) was a constant, so it was greedy best-first and
 * every move cost was dead), and judged passability by XZ area. That is slow AND
 * wrong; measured on the stand it dropped the client to 1-6 fps while searching.
 * This planner:
 *   - expands a FIXED move set (traverse, diagonal, ascend, descend, parkour),
 *     so an expansion is ~14 candidates instead of ~2000;
 *   - accumulates real g-cost with an admissible octile heuristic (the baritone
 *     coefficient 3.563, just under sprint speed, so A* stays optimal);
 *   - uses an array binary heap + an open-addressing long map: no per-edge
 *     allocation, O(1) membership, O(log n) decrease-key;
 *   - asks {@link PlayerFit} whether the body actually fits, so a plan is
 *     physically executable (no slab-capped 1.5-block passages);
 *   - is time-sliced and always returns the best chain it found, so movement can
 *     start on a partial plan.
 *
 * Moves that vanilla walking cannot execute (gap jumps) are flagged
 * {@code needsPhysics} on the resulting waypoint, so the caller can hand exactly
 * those segments to the physics engine instead of walking into them — that is
 * how parkour keeps working while the rest of the route runs at walker speed.
 */
public final class FastPlanner {

    /** Heuristic weight: baritone's 3.563, a hair under SPRINT_ONE_BLOCK_COST
     *  (3.564) so the estimate stays an underestimate and A* stays optimal. */
    private static final double HEURISTIC = 3.563;
    private static final double SQRT2 = Math.sqrt(2);

    /** Max drop we plan without physics help (fall damage stays survivable). */
    private static final int MAX_FALL = 3;
    /**
     * Max horizontal gap a parkour jump may cross, in blocks of AIR.
     *
     * <p>Was 3, which silently made every 4-wide gap unplannable: `parkour()` emitted
     * nothing, the route tailed at the take-off block, and the navigator took its
     * dead-end branch. A vanilla sprint-jump clears 4 air blocks (the standard
     * "4-block jump"), so 3 was leaving a real, commonly-built move on the table.
     */
    private static final int MAX_JUMP_GAP = 4;
    /**
     * How far below a lip to look for slime to land on. Ordinary drops are capped at
     * {@link #MAX_FALL} because they hurt; a slime landing does not, so the useful depth is
     * set by how high the bounce can throw you back, not by damage. The bounce course drops
     * EIGHT blocks, so the old fixed 6 put the slime out of sight and the move could not
     * fire once no matter what else was right.
     */
    private static final int MAX_SLIME_DROP = 12;
    /** Horizontal blocks a sprinting player covers per tick — carried through a bounce. */
    private static final double SPRINT_BLOCKS_PER_TICK = 0.28;
    /** Ticks of airtime a lossless bounce buys per sqrt(block) of drop (up plus down). */
    private static final double AIRTIME_TICKS_PER_SQRT_BLOCK = 10.0;
    /** Hard ceiling on bounce travel, so a deep pit cannot explode the branching factor. */
    private static final int MAX_SLIME_REACH = 8;
    /**
     * Share of the drop a slime bounce actually returns as height. The collision is lossless
     * in the code (Agent.java:832 flips velY) but vertical drag eats most of the climb back.
     * MEASURED on the stand with a tick-rate probe, dropping onto a pad from four heights:
     *
     * <pre>
     *   drop  4.0 -> rise 1.53  (0.38)
     *   drop  7.0 -> rise 3.07  (0.44)
     *   drop 10.0 -> rise 4.25  (0.43)
     *   drop 15.0 -> rise 8.78  (0.59)
     * </pre>
     *
     * Holding JUMP through the landing changes nothing — 3.07 either way — so there is no
     * "boosted bounce" to plan for; that idea was tested and is dead.
     *
     * <p>The value used is the IN-MOTION one, not the table above. Those drops start from a
     * standstill; entering the pad at a run the tick trace puts the apex at -55.4 from the
     * same 7-block drop, i.e. 4.6 blocks, about 0.66. Routes are planned for a bot that is
     * moving, so that is the number that belongs here — the standing figures are kept
     * because they are what killed the "boosted bounce" idea.
     */
    private static final double BOUNCE_HEIGHT_RETURN = 0.66;
    /**
     * Highest ledge we still plan a route over. Anything above a plain jump
     * (PlayerFit.JUMP_HEIGHT) is emitted as a physics-required step: the walker
     * stops there and the physics engine climbs it. Refusing these outright is
     * what made the chase stop at the foot of a mountain.
     */
    private static final int CLIMB_MAX = 3;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public static final class Waypoint {
        public final BlockPos pos;
        /** True when reaching this cell needs a real jump the walker cannot do
         *  reliably (gap crossing) — the caller should let physics run it. */
        public final boolean needsPhysics;
        /** Cells that must be mined (top-down) before this waypoint is walkable, or null.
         *  The receiving side already exists: PathFinder.truncateAtBreaks reads
         *  BlockNode.toBreak and PathExecutor.tickBreaking performs the mining — this
         *  planner simply had no way to ASK for it. */
        public final List<BlockPos> toBreak;
        /** Cells that must be PLACED before this waypoint is walkable, or null. The mirror of
         *  toBreak, and the receiving side already exists too: BlockNode.hasPlaces ->
         *  PathFinder.truncateAtBreaks -> PathExecutor's place queue. The planner simply had
         *  no way to ASK for a bridge, which is why a gap it could not JUMP was a dead end
         *  even with a stack of blocks in the bot's hand. */
        public final List<BlockPos> toPlace;

        Waypoint(BlockPos pos, boolean needsPhysics) {
            this(pos, needsPhysics, null);
        }

        Waypoint(BlockPos pos, boolean needsPhysics, List<BlockPos> toBreak) {
            this(pos, needsPhysics, toBreak, null);
        }

        Waypoint(BlockPos pos, boolean needsPhysics, List<BlockPos> toBreak,
                 List<BlockPos> toPlace) {
            this.pos = pos;
            this.needsPhysics = needsPhysics;
            this.toBreak = toBreak;
            this.toPlace = toPlace;
        }
    }

    public static final class Result {
        public final List<Waypoint> path;
        /** true when the plan actually reaches the goal cell. */
        public final boolean complete;
        public final int expanded;
        public final long millis;

        Result(List<Waypoint> path, boolean complete, int expanded, long millis) {
            this.path = path;
            this.complete = complete;
            this.expanded = expanded;
            this.millis = millis;
        }

        public boolean isEmpty() { return path.isEmpty(); }

        /**
         * The route as block-space nodes, i.e. in the form the PHYSICS search
         * consumes as guidance (PathFinder.find(..., blockPath)). This is the
         * point of the planner: tungsten computes the physical route ALONG a
         * good block route, instead of the walker sprinting the cells itself
         * (which is exactly the straight/diagonal movement baritone already
         * does) while the physics search re-derives its own guide with the slow
         * blind scan.
         */
        public java.util.List<kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode>
                toBlockNodes(kaptainwutax.tungsten.path.blockSpaceSearchAssist.Goal goal,
                             net.minecraft.entity.player.PlayerEntity player) {
            java.util.List<kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode> out =
                    new ArrayList<>(path.size());
            for (Waypoint w : path) {
                kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode bn =
                        new kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode(
                                w.pos.getX(), w.pos.getY(), w.pos.getZ(), goal, player);
                bn.toBreak = w.toBreak;   // consumed by PathFinder.truncateAtBreaks
                // ...AND THE BRIDGE PLAN WITH IT. This line was missing, so every bridge the
                // planner worked out was thrown away right here, at the seam: the executor
                // was never told to place anything and the route simply stopped at the gap.
                // truncateAtBreaks already handles hasPlaces() identically to hasBreaks().
                bn.toPlace = w.toPlace;
                out.add(bn);
            }
            return out;
        }

        /** Plain cell list for the block walker. */
        public List<BlockPos> positions() {
            List<BlockPos> out = new ArrayList<>(path.size());
            for (Waypoint w : path) out.add(w.pos);
            return out;
        }

        /** Index of the first waypoint that needs physics, or -1. */
        /**
         * MEASURED DEAD END — DO NOT RETRY WITHOUT A DIFFERENT PLAN. Skipping waypoints that
         * carry toPlace here (on the theory that "a placement is ours to build, not physics'
         * job") REGRESSED the courses that already worked: nav_wall2 went from PASS to FAIL
         * twice over, stuck 6.4 blocks out at the foot of its wall, and nav_bridge dropped
         * from 3 passes of 3 to 1 of 2. The reason is that the ledge courses pass BECAUSE the
         * cut happens: the hand-off is what routes the climb to PillarTask. Giving the block
         * planner its own route to the executor is still the right fix — it is just a bigger
         * job than a condition in this method.
         */
        public int firstPhysicsIndex() {
            for (int i = 0; i < path.size(); i++) if (path.get(i).needsPhysics) return i;
            return -1;
        }

        /**
         * Index of the LAST waypoint in the physics run that starts at {@code first} —
         * i.e. the far side of the whole physics-only segment.
         *
         * <p>Handing physics only the FIRST flagged waypoint is wrong whenever the segment
         * is more than one step, which is exactly the ladder case: the first flagged cell is
         * the ladder's base, level with the bot, so physics is asked to travel to where it
         * already stands and does nothing. The bot then sits at the foot of the ladder
         * forever. What physics must be given is the TOP of the climb.
         */
        public int physicsRunEnd(int first) {
            int i = first;
            while (i + 1 < path.size() && path.get(i + 1).needsPhysics) i++;
            return i;
        }
    }

    /**
     * Plan off the client thread and hand the cells to {@code onReady} when the
     * plan is worth walking. The chase calls this: a real terrain route costs
     * more than a tick's budget, and freezing the client to compute it would
     * cost exactly the fps this planner exists to save.
     */
    public static void planAsync(WorldView world, BlockPos start, BlockPos goal,
                                 long budgetMs, java.util.function.Consumer<Result> onReady) {
        Thread t = new Thread(() -> {
            try {
                Result r = plan(world, start, goal, budgetMs);
                if (!r.isEmpty() && r.path.size() >= 2) onReady.accept(r);
            } catch (Exception e) {
                Debug.logWarning("FastPlanner async failed: " + e.getMessage());
            }
        });
        t.setName("FastPlanner-async");
        t.setDaemon(true);
        t.start();
    }

    // ── search node ──────────────────────────────────────────────────────────
    private static final class Node {
        final int x, y, z;
        final double heuristic;
        double cost = Double.POSITIVE_INFINITY;   // g
        double combined = Double.POSITIVE_INFINITY; // f = g + h
        Node parent;
        boolean viaJump;
        List<BlockPos> toBreak;
        List<BlockPos> toPlace;
        /**
         * How many blocks this branch has placed on its way here, counting every ancestor.
         * Zero for the overwhelming majority of nodes, which is what makes
         * {@link #branchPlaced} free on routes that build nothing: the walk up the parent
         * chain stops the moment it meets a node that has placed nothing.
         */
        int placedDepth;
        int heapPosition = -1;

        Node(int x, int y, int z, double heuristic) {
            this.x = x; this.y = y; this.z = z; this.heuristic = heuristic;
        }
        boolean isOpen() { return heapPosition != -1; }
    }

    /**
     * How many blocks the bot may promise to place on ONE route. A bridge used to be capped
     * at a single block by a bug (see {@link #branchPlaced}); with that gone, nothing stopped
     * the search from planning a hundred-block causeway across a void with an empty pocket,
     * which is a plan that cannot be walked. Set from the client thread before each search —
     * reading the inventory off the planning thread is not safe.
     */
    public static volatile int placeBudget = Integer.MAX_VALUE;

    /**
     * The world this thread's search is reading, so {@link #relax} can refuse a hazardous
     * destination without every generator having to pass it down. Set for the duration of one
     * {@link #plan} call, exactly like the state memo beside it; null outside a search, and the
     * hazard gate is simply inert then.
     */
    private static final ThreadLocal<WorldView> SEARCH_WORLD = new ThreadLocal<>();

    /** Blocks in the pocket, i.e. the honest value for {@link #placeBudget}. */
    public static int countPlaceable(net.minecraft.entity.player.PlayerEntity player) {
        if (player == null) return 0;
        int n = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var st = inv.getStack(i);
            if (st.getItem() instanceof net.minecraft.item.BlockItem) n += st.getCount();
        }
        return n;
    }

    // DIAGNOSTICS DO NOT BELONG IN THE INNER LOOP. Each of these used to be a
    // Debug.logMessage at the point of emission, i.e. a chat message per candidate move:
    // measured 16568 pillar lines and 7024 bridge lines in ONE run. That is the search's
    // own budget being spent on talking about itself — the very "164 nodes in 204 ms" that
    // made every hard course return a truncated plan. Counted here, printed once per search.
    private static int cntBridge, cntPillar, cntSlimeDrop, cntClimb, cntSpecial, cntBreak, cntHazard;

    private FastPlanner() {}

    // ── public entry ─────────────────────────────────────────────────────────

    /**
     * Plan from {@code start} to {@code goal} within a wall-clock budget.
     * Always returns a Result; when the goal is not reached the path is the
     * chain to the node that got closest (so movement can still begin).
     */
    public static Result plan(WorldView world, BlockPos start, BlockPos goal, long budgetMs) {
        long t0 = System.currentTimeMillis();
        NodeMap map = new NodeMap();
        Heap open = new Heap();

        Node startNode = map.get(start.getX(), start.getY(), start.getZ(), goal);
        startNode.cost = 0;
        startNode.combined = startNode.heuristic;
        open.insert(startNode);

        Node best = startNode;
        double bestScore = startNode.heuristic;
        int expanded = 0;
        boolean complete = false;
        Node goalNode = null;

        cntBridge = cntPillar = cntSlimeDrop = cntClimb = cntSpecial = cntBreak = cntHazard = 0;
        SEARCH_WORLD.set(world);
        STATE_CACHE.get().clear();   // the world changes between plans — never reuse
        // Memoise the geometry reads for the duration of this search (see PlayerFit).
        kaptainwutax.tungsten.helpers.PlayerFit.beginCachedRead();
        try {
        BlockPos.Mutable scratch = new BlockPos.Mutable();

        while (!open.isEmpty()) {
            if ((expanded & 0x3F) == 0 && System.currentTimeMillis() - t0 > budgetMs) break;

            Node current = open.removeLowest();
            expanded++;

            if (current.x == goal.getX() && current.z == goal.getZ()
                    && Math.abs(current.y - goal.getY()) <= 1) {
                goalNode = current;
                complete = true;
                break;
            }
            if (current.heuristic < bestScore) {
                bestScore = current.heuristic;
                best = current;
            }

            scratch.set(current.x, current.y, current.z);
            double support = PlayerFit.supportTop(world, scratch);
            // THE OTHER HALF OF branchPlaced, and without it the bridge was still capped at
            // one plank — just somewhere else. placeAcross would happily lay a plank and step
            // onto it, and then THIS line threw the node away the moment it was popped,
            // because the world has no floor there: the plank exists only in the plan. The
            // search closed its whole open list in 202 nodes and returned a stump. A placed
            // block is a full cube, so the surface we are standing on is the top of the cell
            // below, i.e. exactly our own feet height.
            if (Double.isNaN(support)
                    && branchPlaced(current, current.x, current.y - 1, current.z)) {
                support = current.y;
            }
            if (Double.isNaN(support)) {
                // NO FLOOR IS NOT THE SAME AS NO MOVE. Water and ladder cells are
                // supportless BY DEFINITION, and special() is precisely the generator that
                // does not need a floor. Dropping them here is what made every swim and
                // every ladder route unplannable: special() emitted the node, and this line
                // deleted it the moment it was popped, so it never expanded even once.
                // Only the floor-based generators (step, diagonal) actually need `support`.
                // TREATING THE SURFACE FLOAT AS SWIMMABLE MADE IT WORSE — BOTH HALVES,
                // MEASURED, DO NOT RETRY. A player at the top of a pool has its FEET CELL IN
                // AIR with the water one below, so this test says no, the node is dropped, and
                // the search returns a one-node plan from the middle of a pool ("1 nodes, 1 wp,
                // partial, 0 ms" with the water counter at zero, one run in three, 8.2 short).
                // That diagnosis is correct. The fix is not: accepting water-one-below here
                // took nav_water to 8.5 / 8.5 FAIL, and additionally pricing a cell above water
                // as a swim took it to 4 FAILS of 4 (13.5 / 8.5 / 9.5 / 8.5), against 2 passes
                // of 3 with neither. Expanding those nodes replaces a clean climb-onto-the-bank
                // route with surface floating, which is what the walker is worst at. The real
                // fix is NOT "write a swimming executor" — one already exists and is live:
                // path/specialMoves/SwimmingMove (plus Diving/EnterWaterAndSwim/ExitWater),
                // called from Node.java:163 in PHYSICS move generation, which simulates the
                // real body. What is missing is a route that swims AND builds, since physics
                // has no place/break move at all. See the capability table in
                // docs/NAVIGATION.md. The course passes today by walking round the rim, and
                // that is the honest state of it.
                if (isWater(world, current.x, current.y, current.z, scratch)
                        || isLadder(world, current.x, current.y, current.z, scratch)) {
                    special(world, current, goal, map, open, scratch);
                }
                continue;   // genuinely unstandable
            }

            expand(world, current, support, goal, map, open, scratch);
        }
        } finally {
            kaptainwutax.tungsten.helpers.PlayerFit.endCachedRead();
            SEARCH_WORLD.remove();
        }

        Node tail = complete && goalNode != null ? goalNode : best;
        List<Waypoint> path = new ArrayList<>();
        for (Node n = tail; n != null; n = n.parent) {
            path.add(new Waypoint(new BlockPos(n.x, n.y, n.z), n.viaJump, n.toBreak, n.toPlace));
        }
        Collections.reverse(path);
        long ms = System.currentTimeMillis() - t0;
        if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) {
            Debug.logMessage(String.format(
                    "FastPlanner: %d nodes, %d wp, %s, %d ms (bridge=%d pillar=%d slime=%d climb=%d spec=%d brk=%d haz=%d)",
                    expanded, path.size(), complete ? "complete" : "partial", ms,
                    cntBridge, cntPillar, cntSlimeDrop, cntClimb, cntSpecial, cntBreak, cntHazard));
        }
        return new Result(path, complete, expanded, ms);
    }

    // ── move generation ──────────────────────────────────────────────────────

    private static void expand(WorldView world, Node from, double support, BlockPos goal,
                               NodeMap map, Heap open, BlockPos.Mutable scratch) {
        // straight + diagonal steps and one-block climbs
        for (int[] d : CARDINALS) {
            step(world, from, support, d[0], d[1], goal, map, open, scratch, ActionCosts.WALK_ONE_BLOCK_COST);
        }
        for (int[] d : DIAGONALS) {
            // no corner cutting: both orthogonal cells must be passable too
            if (!sideClear(world, from, d[0], 0, support, scratch)) continue;
            if (!sideClear(world, from, 0, d[1], support, scratch)) continue;
            step(world, from, support, d[0], d[1], goal, map, open, scratch,
                    ActionCosts.WALK_ONE_BLOCK_COST * SQRT2);
        }
        if (TungstenConfig.get().planPlaceMoves) {
            for (int[] d : CARDINALS) placeAcross(world, from, d[0], d[1], support, goal, map, open, scratch);
            pillarUp(world, from, goal, map, open, scratch);
        }
        special(world, from, goal, map, open, scratch);
    }

    /**
     * Moves that have no solid floor to step onto, so {@link #step} never sees them:
     * ladders, swimming and slime bounces.
     *
     * <p>These simply did not exist in this planner. Its move set was walk / diagonal /
     * climb / drop / parkour, all of which require {@code PlayerFit.supportTop} to return a
     * real surface — which is NaN inside water and on a ladder. So a route through any of
     * them was unplannable, and since this is the planner that actually drives the bot,
     * the ladder, water and slime courses could never pass no matter what else was fixed.
     *
     * <p>All three are emitted flagged (viaJump), i.e. handed to the physics engine: it
     * simulates the real player, so it is the part of the system that can actually hold
     * itself against a ladder, swim, or ride a bounce.
     */
    private static void special(WorldView world, Node from, BlockPos goal,
                                NodeMap map, Heap open, BlockPos.Mutable scratch) {
        // DIAGNOSTIC: measured that water/slime routes come out incomplete with ZERO
        // flagged waypoints, i.e. these moves never reach the plan. Print what this
        // generator actually sees rather than assuming which branch is at fault.
        final boolean diagS = TungstenConfig.get().verboseDebugLogging;
        if (diagS) {
            int headY = from.y + 1;   // node.y is the FEET cell (planned from getBlockPos())
            boolean inW = isWater(world, from.x, from.y, from.z, scratch);
            boolean lad = isLadder(world, from.x, from.y, from.z, scratch);
            boolean inW2 = isWater(world, from.x, headY, from.z, scratch);
            boolean lad2 = isLadder(world, from.x, headY, from.z, scratch);
            if (inW || lad || inW2 || lad2) {
                cntSpecial++;
            }
        }
        // ── ladders: climb the column we are in, or step onto an adjacent one ──
        if (isLadder(world, from.x, from.y, from.z, scratch)) {
            for (int dy : new int[]{1, -1}) {
                int ny = from.y + dy;
                if (isLadder(world, from.x, ny, from.z, scratch)
                        || PlayerFit.bodyFits(world, from.x + 0.5, ny, from.z + 0.5)) {
                    // NOT flagged for physics. Ladder moves used to be delegated to the
                    // physics engine on the grounds that only a real simulation can hold
                    // itself against a rung — but its climb move is gated behind ALREADY
                    // standing in the ladder column (Node.java:133, horizontal Manhattan
                    // <= 0.5), so from the ground beside a ladder it is never generated and
                    // the climb never happened. The walker owns what the walker can do.
                    relax(map, open, from, from.x, ny, from.z,
                            ActionCosts.LADDER_ONE_BLOCK_COST, goal, false);
                }
            }
            // GETTING OFF THE LADDER. The water branch below has an exit clause; this one
            // never did, so a ladder was a one-way trip — the bot could climb the column
            // and then had nowhere to go. Step onto a cardinal neighbour that is genuinely
            // standable, at our level or one up: the shelf beside a ladder top normally
            // sits one above the last rung, so level-only would still find nothing.
            for (int[] d : CARDINALS) {
                for (int dy : new int[]{0, 1}) {
                    int nx = from.x + d[0], ny = from.y + dy, nz = from.z + d[1];
                    if (isLadder(world, nx, ny, nz, scratch)) continue;   // climb handles it
                    scratch.set(nx, ny, nz);
                    if (!Double.isNaN(PlayerFit.supportTop(world, scratch))
                            && PlayerFit.bodyFits(world, nx + 0.5, ny, nz + 0.5)) {
                        relax(map, open, from, nx, ny, nz,
                                ActionCosts.LADDER_ONE_BLOCK_COST, goal, false);
                    }
                }
            }
        } else {
            for (int[] d : CARDINALS) {
                int nx = from.x + d[0], nz = from.z + d[1];
                if (isLadder(world, nx, from.y, nz, scratch)) {
                    relax(map, open, from, nx, from.y, nz,
                            ActionCosts.LADDER_ONE_BLOCK_COST, goal, false);
                }
            }
        }

        // ── water: swim through it, and surface / climb out onto a bank ──
        if (isWater(world, from.x, from.y, from.z, scratch)) {
            int[][] dirs = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0}};
            for (int[] d : dirs) {
                int nx = from.x + d[0], ny = from.y + d[1], nz = from.z + d[2];
                // PRICING A CELL ABOVE WATER AS A SWIM MADE IT WORSE — MEASURED, DO NOT RETRY.
                // The idea was that moving along the surface is a swim, not a climb-out. With
                // it, nav_water went 4 FAILS of 4 (13.5 / 8.5 / 9.5 / 8.5) against 2 passes of
                // 3 without it: cells above water stopped being exits and became swims, so the
                // search preferred floating along the surface to climbing onto the bank, and
                // floating is the thing the walker is worst at. Only the dead-end half of that
                // change is kept — see the surface-float note in plan() and above.
                boolean water = isWater(world, nx, ny, nz, scratch);
                // SHORT-CIRCUIT THE EXPENSIVE TEST. bodyFits walks the real 0.6x1.8 box
                // against every block it touches, and it was being run for all six directions
                // even when the cell is already known to be water — where the answer cannot
                // change the outcome. Measured before this: 192 nodes in 414 ms, i.e. 2.2 ms
                // PER NODE, so the search could not cross the pool inside its 250 ms budget
                // and the course stalled roughly one run in three.
                boolean exit = !water && ny >= from.y
                        && PlayerFit.bodyFits(world, nx + 0.5, ny, nz + 0.5);
                if (water || exit) {
                    // DIVING AND SURFACING COST MORE THAN CROSSING. Vertical movement in water
                    // is slower in vanilla, and pricing all six directions the same made the
                    // search tour the pool's whole volume instead of crossing it: a stalled
                    // run shows every water cell being expanded while the bot never left the
                    // bank. A pool is something you swim ACROSS.
                    double swim = ActionCosts.SWIM_ONE_BLOCK_COST * (d[1] != 0 ? 1.6 : 1.0);
                    // WALKER-OWNED, NOT FLAGGED FOR PHYSICS — the same call that made the
                    // ladder work. Vanilla swims for you: hold forward in water and you move.
                    // Handing a swim to the physics engine instead produced the starved
                    // hand-off all over again: measured on a stalled run, a 19-waypoint plan
                    // whose only flagged cell is the last one, the walker completes its 18,
                    // and then NAVSTATE sits at "awaiting=true" forever while physics fails to
                    // solve a stroke of swimming.
                    relax(map, open, from, nx, ny, nz, swim, goal, false);
                }
            }
        } else {
            // Entering water from land. YOU STEP DOWN INTO A POOL — a pool's surface
            // normally sits one block BELOW the bank you are standing on, exactly like the
            // ordinary walk-down move. Looking only at our own foot level meant a normal
            // pool was never entered at all: the cell beside us at foot level is the AIR
            // above the water, not the water.
            for (int[] d : CARDINALS) {
                int nx = from.x + d[0], nz = from.z + d[1];
                int entry = Integer.MIN_VALUE;
                for (int ny : new int[]{from.y, from.y - 1}) {
                    if (isWater(world, nx, ny, nz, scratch)) { entry = ny; break; }
                }
                if (diagS && entry != Integer.MIN_VALUE) {
                    cntSpecial++;
                }
                if (entry != Integer.MIN_VALUE) {
                    relax(map, open, from, nx, entry, nz,
                            ActionCosts.SWIM_ONE_BLOCK_COST, goal, false);
                }
            }
        }

        // ── slime, as TWO ordinary moves rather than one compound leap ─────────────
        // It used to be a single edge straight from the lip to the far landing, which left
        // NO waypoint on the slime itself. The physics engine is guided by those waypoints,
        // so it was handed "get from x=6.5 to x=18" in one piece and answered
        // "Partial path (goal unreachable)" 208 times in a single run. Split in two — fall
        // ONTO the slime, then bounce OFF it — the route carries the touch point and each
        // half is a short, ordinary problem.

        // (A) FALL ONTO SLIME. Landing on slime does no damage, so this is not capped by
        // MAX_FALL the way an ordinary drop is.
        //
        // OFFERED ONLY WHEN SOMETHING CAN RIDE THE LANDING. A bouncing pad is not a surface
        // the waypoint walker can steer on — it is airborne almost every tick — and this move
        // is walker-owned (viaJump=false, see the note at its relax below). Offering a move to
        // an executor that cannot perform it is how nav_slime spent a whole session red: the
        // drop was planned 553 times a run, it is genuinely the cheapest route because falling
        // costs almost nothing, and then the bot either parked at the lip or ended in the void.
        //
        // The engines have DISJOINT capabilities (capability table in docs/NAVIGATION.md):
        // physics has SlimeBounceMove and can ride a pad but cannot place a block, the block
        // engine can bridge but cannot ride a bounce. `slimeCrossing` is the switch for the
        // walker-side crossing task that CAN ride it, and it ships OFF. So while it is off,
        // do not offer the drop — the search then solves the course the way baritone would,
        // by BUILDING across, which is a route the block engine can execute end to end
        // (proved on nav_bridge). Turn the crossing on and the drop comes back with it.
        //
        // Handing this move to physics instead is a RECORDED DEAD END: as one compound leap it
        // produced "Partial path (goal unreachable)" 208 times in a single run.
        if (!TungstenConfig.get().slimeCrossing) return;
        for (int[] d : CARDINALS) {
            // Only look where stepping would actually DROP: at a solid neighbour the walk
            // generator already has the answer, and scanning every node to full depth in
            // every direction would cost hundreds of world reads per expansion.
            scratch.set(from.x + d[0], from.y, from.z + d[1]);
            if (!Double.isNaN(PlayerFit.supportTop(world, scratch))) continue;

            // A run-up is part of the move: slime is rarely directly under the lip you leave
            // from (here the pad ends at x=6 and the slime starts at x=9, across two cells of
            // void), and a player who runs off an edge keeps travelling as it falls.
            // FURTHEST FIRST, then stop. Descending order is the whole point: the first hit
            // is the deepest landing on the pad, and once it is emitted the nearer ones are
            // not offered at all.
            boolean landed = false;
            for (int reach = MAX_JUMP_GAP; reach >= 1 && !landed; reach--) {
                int nx = from.x + d[0] * reach, nz = from.z + d[1] * reach;
                for (int drop = 1; drop <= MAX_SLIME_DROP; drop++) {
                    int by = from.y - drop;
                    // A FALL IS HALF A BOUNCE, SO IT BUYS HALF THE TRAVEL — the same airtime
                    // model, one way only. A flat MAX_JUMP_GAP aimed the route four cells out
                    // from the lip on a seven-block drop, which needs a perfect sprint the
                    // whole way down: the plan came out complete and the bot still fell past
                    // the slime at x=8.5 chasing a target at x=10.5. Deeper drops buy more
                    // travel, so this only skips the column at THIS depth — the scan keeps
                    // going down, where the same offset becomes reachable.
                    int fallReach = Math.max(1, (int) (SPRINT_BLOCKS_PER_TICK
                            * (AIRTIME_TICKS_PER_SQRT_BLOCK / 2.0) * Math.sqrt(drop)));
                    if (reach > fallReach) continue;
                    if (isSlime(world, nx, by, nz, scratch)) {
                        // The route stops at the pad's lip and never takes the drop, so say
                        // whether this move is even offered rather than inferring it from the
                        // shape of the plan.
                        cntSlimeDrop++;
                        // LAND ON THE FAR EDGE OF THE PAD, NOT THE NEAR ONE. Worked out from
                        // the measured trace rather than taste: a bounce leaves the pad at
                        // about +1.05 blocks/tick, which with vanilla gravity keeps the bot
                        // above the ledge's level for ~17 ticks, and at the measured 0.26
                        // blocks/tick that is 4.4 blocks of travel. The ledge starts at x=17,
                        // so the bounce has to begin at x>=12.6 — the pad's last cell. Landing
                        // near the pad's start, which is what the search picked when every
                        // cell was offered, spends the height on hops that each shed speed.
                        // Only the FURTHEST reachable slime cell in this direction is emitted.
                        // stand ON the slime: feet in the cell above the block
                        if (PlayerFit.bodyFits(world, nx + 0.5, by + 1, nz + 0.5)) {
                            // WALKER-OWNED, NOT FLAGGED FOR PHYSICS. Running off a lip and
                            // falling is not a manoeuvre that needs a simulator — it is a step
                            // forward, and gravity does the rest. Handing it to physics instead
                            // produced a LIVELOCK measured on this course: the search cannot
                            // solve an 11-block guided leap, gives up, the navigator clears
                            // 'awaiting', replans, hands the SAME jump over again — 208 refusals
                            // in one run with the walker stopped each round, so the bot simply
                            // stood at the lip. Same call as the ladder: the executor that can
                            // do it, owns it.
                            // (Charging for horizontal air travel was tried here to bias the
                            // search towards the near, forgiving edge of the pad. It measured
                            // WORSE — 1 landing in 4 against 1 in 3 — so it is not kept. The
                            // initial drop was never the problem; the bot dies later.)
                            relax(map, open, from, nx, by + 1, nz,
                                    ActionCosts.JUMP_ONE_BLOCK_COST
                                            + drop * ActionCosts.FALL_ONE_BLOCK_COST,
                                    goal, false);
                            landed = true;
                        }
                        break;
                    }
                    // One cell's occupancy is a COLLISION-SHAPE question. passableAt's third
                    // argument is an absolute world feet height, so the 0.1 that used to be
                    // passed here asked "does the body fit at y=0.1" — open sky, always true —
                    // and the scan never stopped at a floor, hunting slime through solid rock.
                    scratch.set(nx, by, nz);   // isSlime borrows scratch — re-point it
                    if (!world.getBlockState(scratch).getCollisionShape(world, scratch).isEmpty()) {
                        break;                                      // hit a non-slime floor
                    }
                }
            }
        }

        // (B) BOUNCE OFF THE SLIME WE ARE STANDING ON.
        // How high and how far, read from the simulator instead of guessed:
        // Agent.java:832-836 flips velY outright, so the bounce is LOSSLESS and the apex is
        // the height you fell from; Agent.java:849-856 damps horizontal speed only once you
        // have SETTLED (|velY| < 0.1), so speed carries through the bounce untouched.
        // The drop that charged this bounce is simply how far we came down to get here,
        // which the parent node records — block-space A* has no velocity, but it does have
        // the route that led in.
        if (from.parent != null && isSlime(world, from.x, from.y - 1, from.z, scratch)) {
            int fell = from.parent.y - from.y;
            if (fell > 0) {
                double apex = fell * BOUNCE_HEIGHT_RETURN;
                // THE BOUNCE IS LOSSLESS IN THE COLLISION, NOT IN THE FLIGHT. Agent.java:832
                // flips velY exactly, but vertical drag then eats about a third of the climb,
                // so the apex is roughly two thirds of the drop — MEASURED on the stand with
                // the airborne trace: a 7-block drop bounced to 4.7 above the pad
                // (-60 -> -55.4), not the 7 the collision alone would suggest. Planning for
                // the lossless number offers landings the bot cannot physically reach, and it
                // then chases an impossible waypoint and falls past everything, which is
                // exactly what this course did.
                for (int rise = 1; rise <= (int) Math.floor(apex); rise++) {
                    // THE HIGHER YOU LAND, THE LESS TIME YOU HAVE UP THERE. A single reach for
                    // the whole bounce is wrong at both ends: it under-sells a low landing and
                    // badly over-sells one near the apex, where the window is almost nothing.
                    // Distance is speed times the time spent at or above the landing height —
                    // up to the apex, then back down to it. With this, the ledge stops being
                    // offered from the NEAR edge of the pad, which the trace showed the bot
                    // chasing and missing (apex -55.4 at x=12.3, ledge at x=17 needing -56),
                    // and the route is forced to bounce from the FAR edge instead.
                    double ticksAbove = (AIRTIME_TICKS_PER_SQRT_BLOCK / 2.0)
                            * (Math.sqrt(apex) + Math.sqrt(Math.max(0.0, apex - rise)));
                    int reach = Math.min(MAX_SLIME_REACH,
                            (int) Math.floor(SPRINT_BLOCKS_PER_TICK * ticksAbove));
                    if (reach < 1) continue;
                    int ly = from.y + rise;
                    for (int[] e : CARDINALS) {
                        for (int lr = 1; lr <= reach; lr++) {
                            int lx = from.x + e[0] * lr, lz = from.z + e[1] * lr;
                            if (!PlayerFit.bodyFits(world, lx + 0.5, ly, lz + 0.5)) continue;
                            // Ask about the LANDING cell: supportTop() already looks at
                            // cell.down(), so testing one lower accepts a cell whose real
                            // floor is a block further down and aims physics into mid-air.
                            scratch.set(lx, ly, lz);
                            if (Double.isNaN(PlayerFit.supportTop(world, scratch))) continue;
                            // Also walker-owned: bouncing is jumping on the slime and holding
                            // the direction, which is exactly what the walker already does.
                            relax(map, open, from, lx, ly, lz,
                                    ActionCosts.JUMP_ONE_BLOCK_COST
                                            + (rise + lr) * ActionCosts.FALL_ONE_BLOCK_COST,
                                    goal, false);
                        }
                    }
                }
            }
        }
    }

    // ── per-search block cache ───────────────────────────────────────────────────
    // The same cell is asked about many times in one expansion — is it water, is it a ladder,
    // is it solid, does a body fit — and every ask was a fresh live-world lookup from a
    // BACKGROUND thread. Measured cost: 2.2-2.4 ms PER NODE, which is why a pool cannot be
    // crossed inside a 250 ms budget. A plain memo for the duration of one search is the
    // cheap half of the off-thread snapshot this planner really wants (C4.1 / TODO #11).
    private static final ThreadLocal<java.util.HashMap<Long, net.minecraft.block.BlockState>>
            STATE_CACHE = ThreadLocal.withInitial(java.util.HashMap::new);

    private static net.minecraft.block.BlockState cachedState(WorldView w, int x, int y, int z,
                                                              BlockPos.Mutable s) {
        long key = (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) (y + 2048) & 0xFFFL);
        var cache = STATE_CACHE.get();
        var hit = cache.get(key);
        if (hit != null) return hit;
        s.set(x, y, z);
        var st = w.getBlockState(s);
        cache.put(key, st);
        return st;
    }

    private static boolean isLadder(WorldView w, int x, int y, int z, BlockPos.Mutable s) {
        return cachedState(w, x, y, z, s).getBlock() instanceof net.minecraft.block.LadderBlock;
    }

    private static boolean isWater(WorldView w, int x, int y, int z, BlockPos.Mutable s) {
        return kaptainwutax.tungsten.helpers.BlockStateChecker.isAnyWater(cachedState(w, x, y, z, s));
    }

    private static boolean isSlime(WorldView w, int x, int y, int z, BlockPos.Mutable s) {
        s.set(x, y, z);
        return w.getBlockState(s).getBlock() instanceof net.minecraft.block.SlimeBlock;
    }

    /** One horizontal move, trying the same level, one up, and drops. */
    private static void step(WorldView world, Node from, double support, int dx, int dz,
                             BlockPos goal, NodeMap map, Heap open, BlockPos.Mutable scratch,
                             double baseCost) {
        int nx = from.x + dx, nz = from.z + dz;

        // Mining is offered ALONGSIDE stepping, not only as a last resort. The loop below
        // `return`s as soon as ANY level is steppable, and a 2-high wall always offers one:
        // its own TOP. So the planner "solved" a wall by climbing over it — a climb it then
        // could not execute — and breakThrough was never even reached (proved by
        // instrumentation: the "break-through planned" line never appeared once).
        // Emitting both lets A* choose on cost, and mining two dirt blocks is far cheaper
        // than a 2-block climb.
        breakThrough(world, from, dx, dz, goal, map, open, scratch);

        // same level / climb / drop down to MAX_FALL. CLIMB_MAX covers ledges a
        // plain jump cannot reach: the route is still emitted, flagged so the
        // physics engine executes that step (pillar/parkour). Without it the
        // planner simply refused every cliff and the chase stopped dead at the
        // foot of a mountain while the prey climbed away (stand-measured).
        for (int dy = CLIMB_MAX; dy >= -MAX_FALL; dy--) {
            scratch.set(nx, from.y + dy, nz);
            double top = PlayerFit.supportTop(world, scratch);
            // DIAGNOSTIC: four attempts to make a 2-block ledge reachable failed because the
            // candidate was rejected somewhere in here and nobody knew where. Print the exact
            // check that kills a would-be climb instead of guessing at it again.
            boolean diag = TungstenConfig.get().verboseDebugLogging
                    && !Double.isNaN(top) && (top - support) > 1.25;
            if (Double.isNaN(top)) continue;
            double rise = top - support;
            if (rise > CLIMB_MAX) {
                cntClimb++;
                continue;                                                // out of reach entirely
            }
            if (!PlayerFit.bodyFits(world, nx + 0.5, top, nz + 0.5)) {
                cntClimb++;
                continue;
            }
            cntClimb++;
            if (rise > PlayerFit.STEP_HEIGHT) {
                // needs a jump: head clearance above the origin cell
                scratch.set(from.x, from.y, from.z);
                if (!PlayerFit.passableAt(world, scratch, support + 0.6)) {
                    cntClimb++;
                    continue;
                }
            }
            // Above a plain jump the ONLY real way up is to pillar — place a block under
            // yourself — and this planner emits no place moves, so without them such a
            // "climb" is a move nobody can perform. It was emitted anyway, priced at about
            // 30, which made it CHEAPER than mining the same wall (~34.6): the planner
            // preferred a fantasy climb over a real tunnel, handed it to physics, and
            // physics burned its whole budget reporting "goal unreachable". Only offer it
            // when pillaring is actually available.
            // A CLIMB ABOVE JUMP HEIGHT IS A PLACEMENT, SO IT NEEDS BLOCKS IN THE POCKET.
            // The flag alone was the whole condition, and an empty inventory was never checked —
            // so with placement enabled the search happily emitted a climb the bot could not
            // possibly perform. Measured on nav_water, whose kit is EMPTY (`bot_kit = []`):
            // "PLAN complete=true firstPhysics=1 flagged=1" x102, 24 hand-offs, physics takes it
            // ZERO times, bridge and pillar counts both zero — the flag unlocked no placement at
            // all, only this climb — and the bot never left the start (final_dist 25.5, 3 of 3).
            // placeAcross and pillarUp already respect placeBudget; this generator did not.
            boolean climb = rise > PlayerFit.JUMP_HEIGHT;
            if (climb && (!TungstenConfig.get().planPlaceMoves || placeBudget <= 0)) {
                cntClimb++;
                continue;
            }
            cntClimb++;
            double cost = baseCost
                    + (rise > PlayerFit.STEP_HEIGHT ? ActionCosts.JUMP_PENALTY : 0)
                    + (climb ? ActionCosts.JUMP_PENALTY * 2 * rise : 0)
                    + (rise < -0.5 ? ActionCosts.FALL_ONE_BLOCK_COST * -rise : 0);
            relax(map, open, from, nx, from.y + dy, nz, cost, goal, climb);
            return;   // nearest reachable level in this direction wins
        }

        // nothing to step onto: try a parkour jump across the gap
        parkour(world, from, support, dx, dz, goal, map, open, scratch);
    }

    /**
     * Mine through into the adjacent cell when it is blocked only by breakable blocks.
     *
     * <p>This planner had NO notion of breaking at all — grepping it for allowBreak /
     * BreakRules / toBreak returned nothing. Since it is the planner that actually drives
     * the bot, a wall across the only corridor was simply an unreachable goal: the route
     * ended at the wall and the search gave up after 20 s. The receiving half of the
     * feature was already complete (BlockNode.toBreak -> PathFinder.truncateAtBreaks ->
     * PathExecutor.tickBreaking); only the producer was missing.
     */
    private static void breakThrough(WorldView world, Node from, int dx, int dz,
                                     BlockPos goal, NodeMap map, Heap open,
                                     BlockPos.Mutable scratch) {
        if (dx != 0 && dz != 0) return;                       // cardinal only
        if (!TungstenConfig.get().allowBreak) return;
        net.minecraft.entity.player.PlayerEntity player = TungstenMod.mc.player;
        if (player == null) return;

        int nx = from.x + dx, nz = from.z + dz;
        // There must be a floor on the far side to land on. supportTop(cell) already looks
        // at cell.down(), so the cell to ask about is the DESTINATION, not the one below it
        // — asking one level too low made this return on every single call (the course floor
        // has nothing under it), which is why the move never fired even once.
        scratch.set(nx, from.y, nz);
        if (Double.isNaN(PlayerFit.supportTop(world, scratch))) return;

        List<BlockPos> plan = new ArrayList<>();
        double ticks = 0;
        // head first: the upper block would otherwise fall into the opening
        for (int dy = 1; dy >= 0; dy--) {
            BlockPos cell = new BlockPos(nx, from.y + dy, nz);
            // "Is this ONE cell occupied?" is a collision-shape question. It must NOT be
            // asked with passableAt(cell, 0.1): that signature takes an ABSOLUTE world feet
            // height, so 0.1 asked "does the body fit at y=0.1" — open sky, always true.
            // Every wall block was therefore treated as already open, the plan came out
            // empty, and the move returned before doing anything. It could never fire once.
            // A LADDER IS A ROUTE, NOT A WALL. Ladders carry a real (thin) collision box, so
            // the occupancy test below counts one as an obstruction and the search plans to
            // MINE the very thing it meant to climb. Measured on nav_ladder: 'break-through
            // planned at 9,-60,0 (2 block(s))' aimed straight down the ladder column, and the
            // bot ended up falling out of the world at x=9.5. Destroying your own way up is
            // never the cheaper route — leave climbables to special().
            if (isLadder(world, cell.getX(), cell.getY(), cell.getZ(), scratch)) return;
            if (world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) continue;
            net.minecraft.block.BlockState st = world.getBlockState(cell);
            if (!kaptainwutax.tungsten.path.BreakRules.canBreak(world, cell, st)) return;
            // PRICE THE DIG WITH THE TOOL WE WILL ACTUALLY USE, NOT THE ONE IN HAND. This read
            // `st.calcBlockBreakingDelta(player, ...)`, which asks "how fast with whatever is
            // held right now" — while the executor swaps to the best tool before digging. So the
            // search priced stone at fist speed and then mined it with a pickaxe: register entry
            // C5.2. The ported MovementHelper.getMiningDurationTicks (MovementHelperB:751, from
            // baritone MovementHelper.java:649-685) prices with the best tool via strVsBlock,
            // applies the break-rule multiplier, and returns COST_INF for the unbreakable — so
            // an impossible dig becomes unplannable instead of a surprise at run time.
            double cellTicks = kaptainwutax.tungsten.path.movements.MovementHelperB
                    .getMiningDurationTicks(world, player, cell.getX(), cell.getY(), cell.getZ(),
                                            st, dy == 1);
            if (cellTicks >= 1_000_000) return;                         // unbreakable here
            ticks += cellTicks;
            plan.add(cell);
        }
        if (plan.isEmpty()) return;                                     // nothing in the way

        double cost = ActionCosts.WALK_ONE_BLOCK_COST
                + ticks * TungstenConfig.get().breakCostMultiplier;
        // Flagged needsPhysics: the WALKER cannot mine — it would just walk into the wall.
        // Flagging cuts the walked leg here and hands the cell to the physics side, whose
        // guide carries toBreak into PathFinder.truncateAtBreaks -> PathExecutor.tickBreaking.
        cntBreak++;
        relax(map, open, from, nx, from.y, nz, cost, goal, true, plan);
    }

    /**
     * BRIDGE ACROSS A GAP: place a block into the hole and step onto it. The mirror of
     * {@link #breakThrough}, and the reason it has to exist at all — baritone reaches
     * anywhere by BREAKING AND PLACING, and without this a gap the bot cannot JUMP is a dead
     * end even with a stack of cobblestone in its hand. The receiving side was already
     * complete (BlockNode.hasPlaces -> PathFinder.truncateAtBreaks -> the executor's place
     * queue); only the planner had no way to ask.
     */
    private static void placeAcross(WorldView world, Node from, int dx, int dz, double support,
                                    BlockPos goal, NodeMap map, Heap open,
                                    BlockPos.Mutable scratch) {
        if (from.placedDepth >= placeBudget) return;   // no more blocks in the pocket
        if (dx != 0 && dz != 0) return;                          // cardinal only
        int nx = from.x + dx, nz = from.z + dz;

        // The destination must be a HOLE: nothing to stand on, and room for a body once the
        // floor exists. If it already has a floor the ordinary walk move covers it.
        scratch.set(nx, from.y, nz);
        if (!Double.isNaN(PlayerFit.supportTop(world, scratch))) return;
        if (!PlayerFit.bodyFits(world, nx + 0.5, from.y, nz + 0.5)) return;

        // The block goes in the cell BELOW the destination, and that cell has to be empty.
        BlockPos floor = new BlockPos(nx, from.y - 1, nz);
        if (!world.getBlockState(floor).getCollisionShape(world, floor).isEmpty()) return;
        // Already planked by this very route (a bridge doubling back on itself): walking onto
        // it is free and placing there twice is not a move at all.
        if (branchPlaced(from, nx, from.y - 1, nz)) {
            relax(map, open, from, nx, from.y, nz, ActionCosts.WALK_ONE_BLOCK_COST, goal, false);
            return;
        }

        // You cannot place against nothing: vanilla needs a face to click. The cell we are
        // standing on is that face when we bridge straight out from our own feet — and it
        // counts whether the world put it there or this route did, which is what lets a
        // bridge be longer than one block (see branchPlaced).
        BlockPos against = new BlockPos(from.x, from.y - 1, from.z);
        if (world.getBlockState(against).getCollisionShape(world, against).isEmpty()
                && !branchPlaced(from, from.x, from.y - 1, from.z)) return;

        // A BACKPLACE IS A SNEAK, AND UPSTREAM PRICES IT AS ONE: MovementTraverse.cost
        // multiplies the walk by SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST for exactly this
        // branch (baritone/.../MovementTraverse.java:164). Pricing it as a plain walk made the
        // search treat bridging as cheaper than it is.
        double cost = ActionCosts.SNEAK_ONE_BLOCK_COST
                + ActionCosts.PLACE_ONE_BLOCK_COST * TungstenConfig.get().placeCostMultiplier;
        cntBridge++;
        relax(map, open, from, nx, from.y, nz, cost, goal, true, null,
                new java.util.ArrayList<>(java.util.List.of(floor)));
    }

    /**
     * PILLAR UP ONE BLOCK: jump, place a block under yourself, land on it. Repeatable, which
     * is the whole point — a single climb move is capped at CLIMB_MAX (3), so a ledge four
     * blocks up was unreachable by construction no matter how many blocks the bot carried.
     * Baritone gets anywhere partly BECAUSE it will just build a tower; this is that move.
     * The receiving side is the same one bridging uses (toPlace -> the executor's place
     * queue), and PillarTask already performs the manoeuvre when navigation asks for it.
     */
    private static void pillarUp(WorldView world, Node from, BlockPos goal,
                                 NodeMap map, Heap open, BlockPos.Mutable scratch) {
        if (from.placedDepth >= placeBudget) return;   // no more blocks in the pocket
        int upY = from.y + 1;
        // Room for the body one block higher, and nothing already occupying our own cell.
        if (!PlayerFit.bodyFits(world, from.x + 0.5, upY, from.z + 0.5)) return;
        BlockPos feet = new BlockPos(from.x, from.y, from.z);
        if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) return;
        // We must be standing on something to jump off in the first place — including the
        // block the previous pillar step of this same route placed, without which a tower is
        // capped at a single block for exactly the reason a bridge was.
        scratch.set(from.x, from.y, from.z);
        if (Double.isNaN(PlayerFit.supportTop(world, scratch))
                && !branchPlaced(from, from.x, from.y - 1, from.z)) return;

        double cost = ActionCosts.JUMP_ONE_BLOCK_COST
                + ActionCosts.PLACE_ONE_BLOCK_COST * TungstenConfig.get().placeCostMultiplier;
        cntPillar++;
        relax(map, open, from, from.x, upY, from.z, cost, goal, true, null,
                new java.util.ArrayList<>(java.util.List.of(feet)));
    }

    /**
     * Is this cell solid ON THIS BRANCH — either in the world, or because the route itself
     * puts a block there before it arrives?
     *
     * <p>THE BRIDGE COULD ONLY EVER BE ONE BLOCK LONG WITHOUT THIS. Placing needs a face to
     * click, and the face for the second plank of a bridge is the FIRST plank — a block that
     * does not exist in the world at search time, only in the plan. So {@link #placeAcross}
     * asked the world, got "empty", and returned; the same for the second step of a tower.
     * Every route that needed more than a single placed block was therefore unplannable, and
     * that is a whole class of route, not a corner case: reaching anywhere at all by breaking
     * and placing is the thing baritone does that this planner could not.
     */
    private static boolean branchPlaced(Node from, int x, int y, int z) {
        for (Node n = from; n != null && n.placedDepth > 0; n = n.parent) {
            List<BlockPos> placed = n.toPlace;
            if (placed == null) continue;
            for (int i = 0; i < placed.size(); i++) {
                BlockPos b = placed.get(i);
                if (b.getX() == x && b.getY() == y && b.getZ() == z) return true;
            }
        }
        return false;
    }

    /** Jump across up to MAX_JUMP_GAP empty cells onto a standable landing. */
    private static void parkour(WorldView world, Node from, double support, int dx, int dz,
                                BlockPos goal, NodeMap map, Heap open, BlockPos.Mutable scratch) {
        if (dx != 0 && dz != 0) return;               // straight jumps only
        // the body must clear the takeoff cell at jump height
        scratch.set(from.x, from.y, from.z);
        if (!PlayerFit.passableAt(world, scratch, support + 1.0)) return;

        for (int gap = 1; gap <= MAX_JUMP_GAP; gap++) {
            int gx = from.x + dx * gap, gz = from.z + dz * gap;
            scratch.set(gx, from.y, gz);
            // the gap cells must be free at flight height
            if (!PlayerFit.passableAt(world, scratch, support + 0.5)) return;
            // landing one cell further, same level or one down
            int lx = from.x + dx * (gap + 1), lz = from.z + dz * (gap + 1);
            for (int dy = 0; dy >= -1; dy--) {
                scratch.set(lx, from.y + dy, lz);
                double top = PlayerFit.supportTop(world, scratch);
                if (Double.isNaN(top)) continue;
                if (top - support > PlayerFit.STEP_HEIGHT) continue;   // can't land higher
                if (!PlayerFit.bodyFits(world, lx + 0.5, top, lz + 0.5)) continue;
                double cost = ActionCosts.PARKOUR_ONE_BLOCK_COST * (gap + 1)
                        + ActionCosts.JUMP_PENALTY;
                relax(map, open, from, lx, from.y + dy, lz, cost, goal, true);
                return;
            }
        }
    }

    /** Is the body able to pass through the neighbouring cell (diagonal guard)? */
    private static boolean sideClear(WorldView world, Node from, int dx, int dz,
                                     double support, BlockPos.Mutable scratch) {
        scratch.set(from.x + dx, from.y, from.z + dz);
        return PlayerFit.passableAt(world, scratch, support);
    }

    /** Standard A* relaxation (this is the g-cost accumulation the old search lacked). */
    private static void relax(NodeMap map, Heap open, Node from, int x, int y, int z,
                              double edgeCost, BlockPos goal, boolean viaJump) {
        relax(map, open, from, x, y, z, edgeCost, goal, viaJump, null);
    }

    private static void relax(NodeMap map, Heap open, Node from, int x, int y, int z,
                              double edgeCost, BlockPos goal, boolean viaJump,
                              List<BlockPos> toBreak) {
        relax(map, open, from, x, y, z, edgeCost, goal, viaJump, toBreak, null);
    }

    /**
     * Ported VERBATIM from baritone's {@code MovementHelper.avoidWalkingInto}
     * (baritone/src/main/java/baritone/pathing/movement/MovementHelper.java:420-431), minus the
     * blanket fluid clause: baritone refuses ALL fluids there, while tungsten deliberately
     * swims, so water stays traversable and only lava is refused.
     *
     * <p>WHY THIS DID NOT EXIST AND HAD TO. {@link PlayerFit} classifies a cell by its
     * COLLISION SHAPE, and lava, fire, cobweb, sweet berry, bubble column and powder snow all
     * have empty or near-empty shapes — so to every generator in this planner they were
     * indistinguishable from AIR, and magma was an ordinary floor. The planner that drives the
     * bot had no notion of a dangerous cell at all. tungsten even had the pieces already
     * (BlockStateChecker.isAnyLava, and a working predicate in CombatPathfinder.isHazard) and
     * this class called neither.
     */
    private static boolean hazardAt(WorldView w, int x, int y, int z, BlockPos.Mutable s) {
        var state = cachedState(w, x, y, z, s);
        if (kaptainwutax.tungsten.helpers.BlockStateChecker.isAnyLava(state)) return true;
        var b = state.getBlock();
        return b == net.minecraft.block.Blocks.MAGMA_BLOCK
                || b == net.minecraft.block.Blocks.CACTUS
                || b == net.minecraft.block.Blocks.SWEET_BERRY_BUSH
                || b instanceof net.minecraft.block.AbstractFireBlock
                || b instanceof net.minecraft.block.EndPortalFrameBlock
                || b == net.minecraft.block.Blocks.END_PORTAL
                || b == net.minecraft.block.Blocks.COBWEB
                || b == net.minecraft.block.Blocks.POWDER_SNOW
                || b instanceof net.minecraft.block.BubbleColumnBlock;
    }

    /**
     * A MARGIN, NOT JUST A BAN. Refusing hazardous cells is not enough: the body is 0.6 wide
     * and the walker steers towards a waypoint's centre, so walking the lane directly beside
     * magma drifts across the boundary and puts the FEET BLOCK in it for a tick. Measured on
     * nav_hazard: the planned route avoids magma (the gate fires 176 times a run) and the bot
     * still took 1.0-2.0 damage. Advancing waypoints on cell occupancy instead of a radius was
     * ported from baritone and measured NEUTRAL, which is what narrowed it to sub-block drift.
     *
     * <p>So walking NEXT TO danger is priced instead of forbidden: the search takes the lane
     * one block further out when there is one, and still crosses a narrow ledge when there is
     * not. Baritone reaches the same place from the other direction — its executor moves
     * discretely cell to cell, so it has no drift to price.
     */
    private static double hazardProximityPenalty(int x, int y, int z) {
        WorldView w = SEARCH_WORLD.get();
        if (w == null) return 0.0;
        BlockPos.Mutable s = new BlockPos.Mutable();
        for (int[] d : CARDINALS) {
            int ax = x + d[0], az = z + d[1];
            if (hazardAt(w, ax, y, az, s) || hazardAt(w, ax, y - 1, az, s)) {
                return ActionCosts.WALK_ONE_BLOCK_COST * 2.0;
            }
        }
        return 0.0;
    }

    /** Body cell, head cell, or the surface we would stand on. */
    private static boolean hazardousDestination(int x, int y, int z) {
        WorldView w = SEARCH_WORLD.get();
        if (w == null) return false;              // not inside a search — gate is inert
        BlockPos.Mutable s = new BlockPos.Mutable();
        return hazardAt(w, x, y, z, s) || hazardAt(w, x, y + 1, z, s)
                || hazardAt(w, x, y - 1, z, s);
    }

    private static void relax(NodeMap map, Heap open, Node from, int x, int y, int z,
                              double edgeCost, BlockPos goal, boolean viaJump,
                              List<BlockPos> toBreak, List<BlockPos> toPlace) {
        // ONE GATE FOR EVERY MOVE. Every generator — step, diagonal, climb, drop, parkour,
        // bridge, pillar, swim, ladder, slime — arrives here, so refusing a hazardous
        // destination once covers all of them and cannot be forgotten in a new generator.
        if (hazardousDestination(x, y, z)) { cntHazard++; return; }
        Node next = map.get(x, y, z, goal);
        double tentative = from.cost + edgeCost + hazardProximityPenalty(x, y, z);
        if (tentative >= next.cost) return;
        next.cost = tentative;
        next.combined = tentative + next.heuristic * HEURISTIC;
        next.parent = from;
        next.viaJump = viaJump;
        next.toBreak = toBreak;
        next.toPlace = toPlace;
        next.placedDepth = from.placedDepth + (toPlace == null ? 0 : toPlace.size());
        if (next.isOpen()) open.update(next); else open.insert(next);
    }

    // ── containers (no external deps: tungsten has no fastutil) ──────────────

    /** Open-addressing long->Node map; one node object per cell per search. */
    private static final class NodeMap {
        private long[] keys = new long[1 << 14];
        private Node[] vals = new Node[1 << 14];
        private int size;

        private static long key(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
        }

        Node get(int x, int y, int z, BlockPos goal) {
            long k = key(x, y, z) + 1;      // 0 marks an empty slot
            int mask = keys.length - 1;
            int i = (int) ((k * 0x9E3779B97F4A7C15L) >>> 40) & mask;
            while (keys[i] != 0) {
                if (keys[i] == k) return vals[i];
                i = (i + 1) & mask;
            }
            Node n = new Node(x, y, z, octile(x, y, z, goal));
            keys[i] = k;
            vals[i] = n;
            if (++size * 2 > keys.length) grow();
            return n;
        }

        private void grow() {
            long[] ok = keys; Node[] ov = vals;
            keys = new long[ok.length << 1];
            vals = new Node[ov.length << 1];
            int mask = keys.length - 1;
            for (int j = 0; j < ok.length; j++) {
                if (ok[j] == 0) continue;
                int i = (int) ((ok[j] * 0x9E3779B97F4A7C15L) >>> 40) & mask;
                while (keys[i] != 0) i = (i + 1) & mask;
                keys[i] = ok[j];
                vals[i] = ov[j];
            }
        }
    }

    /** Octile distance: the admissible estimate for 8-way movement. */
    private static double octile(int x, int y, int z, BlockPos goal) {
        int dx = Math.abs(x - goal.getX());
        int dz = Math.abs(z - goal.getZ());
        int dy = Math.abs(y - goal.getY());
        int straight, diagonal;
        if (dx < dz) { straight = dz - dx; diagonal = dx; }
        else { straight = dx - dz; diagonal = dz; }
        return diagonal * SQRT2 + straight + dy;
    }

    /** Array binary heap with decrease-key via the node's stored position. */
    private static final class Heap {
        private Node[] array = new Node[1024];
        private int size;

        boolean isEmpty() { return size == 0; }

        void insert(Node value) {
            if (size + 1 >= array.length) {
                Node[] bigger = new Node[array.length << 1];
                System.arraycopy(array, 0, bigger, 0, array.length);
                array = bigger;
            }
            int index = ++size;
            array[index] = value;
            value.heapPosition = index;
            siftUp(index);
        }

        void update(Node value) { siftUp(value.heapPosition); }

        Node removeLowest() {
            Node result = array[1];
            result.heapPosition = -1;
            Node last = array[size];
            array[1] = last;
            array[size--] = null;
            if (size > 0) {
                last.heapPosition = 1;
                siftDown(1);
            }
            return result;
        }

        private void siftUp(int index) {
            Node value = array[index];
            while (index > 1) {
                int parent = index >>> 1;
                if (array[parent].combined <= value.combined) break;
                array[index] = array[parent];
                array[index].heapPosition = index;
                index = parent;
            }
            array[index] = value;
            value.heapPosition = index;
        }

        private void siftDown(int index) {
            Node value = array[index];
            while (true) {
                int child = index << 1;
                if (child > size) break;
                if (child + 1 <= size && array[child + 1].combined < array[child].combined) child++;
                if (array[child].combined >= value.combined) break;
                array[index] = array[child];
                array[index].heapPosition = index;
                index = child;
            }
            array[index] = value;
            value.heapPosition = index;
        }
    }
}
