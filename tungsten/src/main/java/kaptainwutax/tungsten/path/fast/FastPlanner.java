package kaptainwutax.tungsten.path.fast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kaptainwutax.tungsten.Debug;
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

        Waypoint(BlockPos pos, boolean needsPhysics) {
            this.pos = pos;
            this.needsPhysics = needsPhysics;
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
                out.add(new kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode(
                        w.pos.getX(), w.pos.getY(), w.pos.getZ(), goal, player));
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
            if (Double.isNaN(support)) continue;   // cell became unstandable

            expand(world, current, support, goal, map, open, scratch);
        }

        Node tail = complete && goalNode != null ? goalNode : best;
        List<Waypoint> path = new ArrayList<>();
        for (Node n = tail; n != null; n = n.parent) {
            path.add(new Waypoint(new BlockPos(n.x, n.y, n.z), n.viaJump));
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
    }

    /** One horizontal move, trying the same level, one up, and drops. */
    private static void step(WorldView world, Node from, double support, int dx, int dz,
                             BlockPos goal, NodeMap map, Heap open, BlockPos.Mutable scratch,
                             double baseCost) {
        int nx = from.x + dx, nz = from.z + dz;

        // same level / climb / drop down to MAX_FALL. CLIMB_MAX covers ledges a
        // plain jump cannot reach: the route is still emitted, flagged so the
        // physics engine executes that step (pillar/parkour). Without it the
        // planner simply refused every cliff and the chase stopped dead at the
        // foot of a mountain while the prey climbed away (stand-measured).
        for (int dy = CLIMB_MAX; dy >= -MAX_FALL; dy--) {
            scratch.set(nx, from.y + dy, nz);
            double top = PlayerFit.supportTop(world, scratch);
            if (Double.isNaN(top)) continue;
            double rise = top - support;
            if (rise > CLIMB_MAX) continue;                              // out of reach entirely
            if (!PlayerFit.bodyFits(world, nx + 0.5, top, nz + 0.5)) continue;
            if (rise > PlayerFit.STEP_HEIGHT) {
                // needs a jump: head clearance above the origin cell
                scratch.set(from.x, from.y, from.z);
                if (!PlayerFit.passableAt(world, scratch, support + 0.6)) continue;
            }
            // above a plain jump the physics engine has to do it (pillar up, a
            // parkour-ascend, a momentum jump) — emit it, flagged, and charge for it
            boolean climb = rise > PlayerFit.JUMP_HEIGHT;
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
        Node next = map.get(x, y, z, goal);
        double tentative = from.cost + edgeCost;
        if (tentative >= next.cost) return;
        next.cost = tentative;
        next.combined = tentative + next.heuristic * HEURISTIC;
        next.parent = from;
        next.viaJump = viaJump;
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
