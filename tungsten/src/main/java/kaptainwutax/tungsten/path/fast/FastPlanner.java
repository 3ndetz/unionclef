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

        Waypoint(BlockPos pos, boolean needsPhysics) {
            this(pos, needsPhysics, null);
        }

        Waypoint(BlockPos pos, boolean needsPhysics, List<BlockPos> toBreak) {
            this.pos = pos;
            this.needsPhysics = needsPhysics;
            this.toBreak = toBreak;
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
        int heapPosition = -1;

        Node(int x, int y, int z, double heuristic) {
            this.x = x; this.y = y; this.z = z; this.heuristic = heuristic;
        }
        boolean isOpen() { return heapPosition != -1; }
    }

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
            if (Double.isNaN(support)) {
                // NO FLOOR IS NOT THE SAME AS NO MOVE. Water and ladder cells are
                // supportless BY DEFINITION, and special() is precisely the generator that
                // does not need a floor. Dropping them here is what made every swim and
                // every ladder route unplannable: special() emitted the node, and this line
                // deleted it the moment it was popped, so it never expanded even once.
                // Only the floor-based generators (step, diagonal) actually need `support`.
                if (isWater(world, current.x, current.y, current.z, scratch)
                        || isLadder(world, current.x, current.y, current.z, scratch)) {
                    special(world, current, goal, map, open, scratch);
                }
                continue;   // genuinely unstandable
            }

            expand(world, current, support, goal, map, open, scratch);
        }

        Node tail = complete && goalNode != null ? goalNode : best;
        List<Waypoint> path = new ArrayList<>();
        for (Node n = tail; n != null; n = n.parent) {
            path.add(new Waypoint(new BlockPos(n.x, n.y, n.z), n.viaJump, n.toBreak));
        }
        Collections.reverse(path);
        long ms = System.currentTimeMillis() - t0;
        if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) {
            Debug.logMessage(String.format("FastPlanner: %d nodes, %d wp, %s, %d ms",
                    expanded, path.size(), complete ? "complete" : "partial", ms));
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
                Debug.logMessage(String.format(
                        "SPECIAL at (%d,%d,%d): water@feet=%b water@head=%b ladder@feet=%b ladder@head=%b",
                        from.x, from.y, from.z, inW, inW2, lad, lad2));
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
                boolean water = isWater(world, nx, ny, nz, scratch);
                boolean exit = ny >= from.y && PlayerFit.bodyFits(world, nx + 0.5, ny, nz + 0.5);
                if (water || exit) {
                    relax(map, open, from, nx, ny, nz,
                            ActionCosts.SWIM_ONE_BLOCK_COST, goal, true);
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
                    Debug.logMessage(String.format(
                            "WATER-ENTRY from (%d,%d,%d) -> (%d,%d,%d) EMITTED",
                            from.x, from.y, from.z, nx, entry, nz));
                }
                if (entry != Integer.MIN_VALUE) {
                    relax(map, open, from, nx, entry, nz,
                            ActionCosts.SWIM_ONE_BLOCK_COST, goal, true);
                }
            }
        }

        // ── slime: dropping onto it throws us back up, so ledges a plain jump
        //    cannot reach become reachable ──
        for (int[] d : CARDINALS) {
            int nx = from.x + d[0], nz = from.z + d[1];
            for (int drop = 1; drop <= 6; drop++) {
                int by = from.y - drop;
                scratch.set(nx, by, nz);
                if (!isSlime(world, nx, by, nz, scratch)) {
                    // ONE CELL'S OCCUPANCY IS A COLLISION-SHAPE QUESTION. passableAt's third
                    // argument is an ABSOLUTE world feet height, so 0.1 asked "does the body
                    // fit at y=0.1" — open sky, always true. This scan therefore never
                    // stopped at a floor and went looking for slime straight through solid
                    // ground. Exactly the trap that made break-through unreachable; this was
                    // the last instance of it.
                    scratch.set(nx, by, nz);   // isSlime borrows scratch — re-point it
                    if (!world.getBlockState(scratch).getCollisionShape(world, scratch).isEmpty()) {
                        break;                                          // hit a non-slime floor
                    }
                    continue;
                }
                // bounce back up: offer landings above the slime, on either side
                for (int rise = 1; rise <= 6; rise++) {
                    int ly = by + rise;
                    for (int[] e : CARDINALS) {
                        int lx = nx + e[0], lz = nz + e[1];
                        if (!PlayerFit.bodyFits(world, lx + 0.5, ly, lz + 0.5)) continue;
                        scratch.set(lx, ly - 1, lz);
                        if (Double.isNaN(PlayerFit.supportTop(world, scratch))) continue;
                        relax(map, open, from, lx, ly, lz,
                                ActionCosts.JUMP_ONE_BLOCK_COST
                                        + (drop + rise) * ActionCosts.FALL_ONE_BLOCK_COST,
                                goal, true);
                    }
                }
                break;
            }
        }
    }

    private static boolean isLadder(WorldView w, int x, int y, int z, BlockPos.Mutable s) {
        s.set(x, y, z);
        return w.getBlockState(s).getBlock() instanceof net.minecraft.block.LadderBlock;
    }

    private static boolean isWater(WorldView w, int x, int y, int z, BlockPos.Mutable s) {
        s.set(x, y, z);
        return kaptainwutax.tungsten.helpers.BlockStateChecker.isAnyWater(w.getBlockState(s));
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
                if (diag) Debug.logMessage(String.format(
                        "CLIMB rejected at (%d,%d): rise %.2f > CLIMB_MAX %d", nx, nz, rise, CLIMB_MAX));
                continue;                                                // out of reach entirely
            }
            if (!PlayerFit.bodyFits(world, nx + 0.5, top, nz + 0.5)) {
                if (diag) Debug.logMessage(String.format(
                        "CLIMB rejected at (%d,%d): body does not fit at top %.2f (rise %.2f)",
                        nx, nz, top, rise));
                continue;
            }
            if (diag) Debug.logMessage(String.format(
                    "CLIMB candidate at (%d,%d) rise %.2f top %.2f — passed fit (planPlace=%b)",
                    nx, nz, rise, top, TungstenConfig.get().planPlaceMoves));
            if (rise > PlayerFit.STEP_HEIGHT) {
                // needs a jump: head clearance above the origin cell
                scratch.set(from.x, from.y, from.z);
                if (!PlayerFit.passableAt(world, scratch, support + 0.6)) {
                    if (diag) Debug.logMessage(String.format(
                            "CLIMB rejected at (%d,%d): no head clearance over origin", nx, nz));
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
            boolean climb = rise > PlayerFit.JUMP_HEIGHT;
            if (climb && !TungstenConfig.get().planPlaceMoves) {
                if (diag) Debug.logMessage(String.format(
                        "CLIMB rejected at (%d,%d): planPlaceMoves is OFF", nx, nz));
                continue;
            }
            if (diag) Debug.logMessage(String.format(
                    "CLIMB EMITTED at (%d,%d) rise %.2f climb=%b", nx, nz, rise, climb));
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
            float delta = st.calcBlockBreakingDelta(player, world, cell);
            if (delta <= 0) return;                                     // unbreakable here
            ticks += delta >= 1 ? 1 : Math.ceil(1f / delta);
            plan.add(cell);
        }
        if (plan.isEmpty()) return;                                     // nothing in the way

        double cost = ActionCosts.WALK_ONE_BLOCK_COST
                + ticks * TungstenConfig.get().breakCostMultiplier;
        // Flagged needsPhysics: the WALKER cannot mine — it would just walk into the wall.
        // Flagging cuts the walked leg here and hands the cell to the physics side, whose
        // guide carries toBreak into PathFinder.truncateAtBreaks -> PathExecutor.tickBreaking.
        if (TungstenConfig.get().verboseDebugLogging) {
            Debug.logMessage("FastPlanner: break-through planned at " + nx + "," + from.y + "," + nz
                    + " (" + plan.size() + " block(s), " + (int) ticks + " ticks)");
        }
        relax(map, open, from, nx, from.y, nz, cost, goal, true, plan);
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
        Node next = map.get(x, y, z, goal);
        double tentative = from.cost + edgeCost;
        if (tentative >= next.cost) return;
        next.cost = tentative;
        next.combined = tentative + next.heuristic * HEURISTIC;
        next.parent = from;
        next.viaJump = viaJump;
        next.toBreak = toBreak;
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
