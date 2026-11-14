package kaptainwutax.tungsten.elytra;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The way through the air to a point, on a coarse grid: cells of {@link #CELL} blocks, a cell open when
 * no block in it has collision, A* over the 26 neighbours. Climbing costs more than descending (height
 * is bought with rockets, a glide gives it back as distance), a cell next to a closed one costs extra
 * (a glider needs room). The cell path is then pulled straight: only the corners a straight flight
 * cannot cut remain, and the solver flies them like rings.
 *
 * <p>The search reads the world off the client thread; a chunk not loaded counts as open but dear.
 * Meant to run on a worker while the glider circles.
 */
public final class AirRoute {

    public static final int CELL = 4;
    // Enough to find the way back to a hall's door after flying over its roof (40 000 was not: the open
    // sky above was searched first, 01.11). On a worker, a second at most.
    private static final int MAX_NODES = 150_000;
    // Weighted A*: a way a little longer than the best, found in a fraction of the cells. Searched
    // plainly, the way back to a hall's door from over its roof spent the 150 000 on the open sky.
    private static final double H_WEIGHT = 1.5;

    private record Node(int x, int y, int z, double g, double f, Node parent) {
    }

    private AirRoute() {
    }

    /** Corners from start to goal (goal included, start not), or null when no way was found. */
    public static List<Vec3d> find(BlockView world, Vec3d start, Vec3d goal, int minY, int maxY) {
        List<int[]> chain = cells(world, start, goal, minY, maxY);
        if (chain == null) return null;
        List<Vec3d> pts = new ArrayList<>();
        for (int[] c : chain) pts.add(centre(c[0], c[1], c[2]));
        pts.set(pts.size() - 1, goal);
        return pull(world, start, pts);
    }

    /** The chain of cells from the start's to the goal's, both included, or null. */
    public static List<int[]> cells(BlockView world, Vec3d start, Vec3d goal, int minY, int maxY) {
        return cells(world, start, goal, minY, maxY, false);
    }

    /**
     * As above; with {@code seenOnly} the chain is cut where it first enters a chunk the client has
     * not got: the way is searched as if the unseen were open (dearer), and flown only as far as it
     * has been seen -- a frontier, moved on as the chunks come in. A wall beyond the view distance
     * reads as air, and a plan through it once went over a hall's roof and down through it (01.11).
     */
    public static List<int[]> cells(BlockView world, Vec3d start, Vec3d goal, int minY, int maxY, boolean seenOnly) {
        int sx = cell(start.x), sy = cell(start.y), sz = cell(start.z);
        int gx = cell(goal.x), gy = cell(goal.y), gz = cell(goal.z);
        int cyMin = cell(minY), cyMax = cell(maxY);
        Map<Long, Double> best = new HashMap<>();
        Map<Long, Boolean> openCache = new HashMap<>();
        PriorityQueue<Node> q = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        q.add(new Node(sx, sy, sz, 0, H_WEIGHT * h(sx, sy, sz, gx, gy, gz), null));
        best.put(key(sx, sy, sz), 0.0);
        Node found = null, closest = null;
        double closestH = Double.MAX_VALUE;
        int expanded = 0;
        while (!q.isEmpty() && expanded < MAX_NODES) {
            Node n = q.poll();
            if (n.g() > best.getOrDefault(key(n.x(), n.y(), n.z()), Double.MAX_VALUE)) continue;
            expanded++;
            double hn = h(n.x(), n.y(), n.z(), gx, gy, gz);
            if (hn < closestH) {
                closestH = hn;
                closest = n;
            }
            if (n.x() == gx && n.y() == gy && n.z() == gz) {
                found = n;
                break;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int x = n.x() + dx, y = n.y() + dy, z = n.z() + dz;
                        if (y < cyMin || y > cyMax) continue;
                        boolean goalCell = x == gx && y == gy && z == gz;
                        if (!goalCell && !open(world, x, y, z, openCache)) continue;
                        // diagonal moves must not cut a closed cell's corner
                        if ((dx != 0 && dy != 0 || dx != 0 && dz != 0 || dy != 0 && dz != 0)
                                && (!open(world, n.x() + dx, n.y(), n.z(), openCache) && dx != 0
                                || !open(world, n.x(), n.y() + dy, n.z(), openCache) && dy != 0
                                || !open(world, n.x(), n.y(), n.z() + dz, openCache) && dz != 0)) continue;
                        double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        double cost = step + (dy > 0 ? 2.0 : dy < 0 ? -0.3 : 0) + crowd(world, x, y, z, openCache)
                                + (known(world, x * CELL, z * CELL) ? 0 : UNKNOWN_COST);
                        double g = n.g() + cost;
                        long k = key(x, y, z);
                        if (g < best.getOrDefault(k, Double.MAX_VALUE)) {
                            best.put(k, g);
                            q.add(new Node(x, y, z, g, g + H_WEIGHT * h(x, y, z, gx, gy, gz), n));
                        }
                    }
                }
            }
        }
        if (found == null) return null;
        List<int[]> chain = new ArrayList<>();
        for (Node n = found; n != null; n = n.parent()) chain.add(0, new int[]{n.x(), n.y(), n.z()});
        if (seenOnly) {
            for (int i = 1; i < chain.size(); i++) {
                int[] c = chain.get(i);
                if (!known(world, c[0] * CELL + CELL / 2, c[2] * CELL + CELL / 2)) return new ArrayList<>(chain.subList(0, i));
            }
        }
        return chain;
    }

    /**
     * The open cells within one cell of a chain, and for each the way left to the goal through them,
     * in blocks: where the physics search ({@link AirPlan}) may fly, and how far it still has to.
     * The blocks of these cells and of one cell around them are read once, 64 bits a cell, so the
     * search checks a box against memory, not the world: a plan tries ~80 moves of 8 ticks a state.
     * Sized by the corridor, not by its bounding box (a diagonal kilometre would be a billion blocks).
     */
    public static final class Corridor {
        private long[] keys = new long[1 << 12];
        private int[] slots = new int[1 << 12];
        private int used;
        private long[] mask = new long[256];     // per slot: the cell's 64 blocks, bit set = solid
        private int unknown;                     // cells read from chunks the client had not got
        private double[] toGo = new double[256]; // per slot: blocks to the goal, -1 not in the corridor
        private static final long EMPTY = Long.MIN_VALUE;
        private static final int WIDTH = 2;

        /** @param around open cells within this many cells of the chain's start belong to it too: the
         *  glider circles while the search runs, and plans from wherever that took it. */
        private Vec3d goalCell;

        /** Where the chain ends: the goal's cell, or the seen cell nearest it (its centre). */
        public Vec3d goalCell() {
            return goalCell;
        }

        public Corridor(BlockView world, List<int[]> chain, int around) {
            java.util.Arrays.fill(keys, EMPTY);
            int[] last = chain.get(chain.size() - 1);
            goalCell = centre(last[0], last[1], last[2]);
            // the corridor cells (open ones within one of the chain, or near its start), then their skin
            List<int[]> in = new ArrayList<>();
            int[] s0 = chain.get(0);
            for (int dx = -around; dx <= around; dx++) {
                for (int dy = -around; dy <= around; dy++) {
                    for (int dz = -around; dz <= around; dz++) {
                        int x = s0[0] + dx, y = s0[1] + dy, z = s0[2] + dz;
                        if (slot(x, y, z) < 0) {
                            int i = add(x, y, z, world);
                            if (mask[i] == 0) in.add(new int[]{x, y, z, i});
                        }
                    }
                }
            }
            // two cells either side of the chain: room for a glider's turn (one was too narrow to
            // turn back to a hall's door, 01.11)
            for (int[] c : chain) {
                for (int dx = -WIDTH; dx <= WIDTH; dx++) {
                    for (int dy = -WIDTH; dy <= WIDTH; dy++) {
                        for (int dz = -WIDTH; dz <= WIDTH; dz++) {
                            int x = c[0] + dx, y = c[1] + dy, z = c[2] + dz;
                            if (slot(x, y, z) < 0) {
                                int i = add(x, y, z, world);
                                if (mask[i] == 0) in.add(new int[]{x, y, z, i});
                            }
                        }
                    }
                }
            }
            int[] g = chain.get(chain.size() - 1);
            int gi = slot(g[0], g[1], g[2]);
            for (int[] c : in) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (slot(c[0] + dx, c[1] + dy, c[2] + dz) < 0) add(c[0] + dx, c[1] + dy, c[2] + dz, world);
                        }
                    }
                }
            }
            boolean[] inside = new boolean[used];
            for (int[] c : in) inside[c[3]] = true;
            // Cells with a block or two in them, next to the corridor's open ones: a glider beside a
            // wall is in one (a 4-block cell holding the wall it skims), and a plan from there was
            // off the corridor and missed. Their way to go is through their best open neighbour.
            List<int[]> edge = new ArrayList<>();
            for (int[] c : in) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int i = slot(c[0] + dx, c[1] + dy, c[2] + dz);
                            if (i >= 0 && i < inside.length && !inside[i] && mask[i] != -1L) edge.add(new int[]{c[0] + dx, c[1] + dy, c[2] + dz, i});
                        }
                    }
                }
            }
            for (int[] c : edge) { // and the blocks around them, for the box checks
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (slot(c[0] + dx, c[1] + dy, c[2] + dz) < 0) add(c[0] + dx, c[1] + dy, c[2] + dz, world);
                        }
                    }
                }
            }
            inside[gi] = true; // the goal's cell counts even when a block is in it (it is above ground)
            // Dijkstra from the goal's cell over the corridor
            PriorityQueue<double[]> q = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
            toGo[gi] = 0;
            q.add(new double[]{0, g[0], g[1], g[2]});
            while (!q.isEmpty()) {
                double[] e = q.poll();
                int x = (int) e[1], y = (int) e[2], z = (int) e[3];
                if (e[0] > toGo[slot(x, y, z)]) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            int i = slot(x + dx, y + dy, z + dz);
                            if (i < 0 || !inside[i]) continue;
                            double d = e[0] + CELL * Math.sqrt(dx * dx + dy * dy + dz * dz);
                            if (toGo[i] < 0 || d < toGo[i]) {
                                toGo[i] = d;
                                q.add(new double[]{d, x + dx, y + dy, z + dz});
                            }
                        }
                    }
                }
            }
            for (int[] c : edge) {
                double best = -1;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int i = slot(c[0] + dx, c[1] + dy, c[2] + dz);
                            if (i < 0 || i >= inside.length || !inside[i] || toGo[i] < 0) continue;
                            double d = toGo[i] + CELL * Math.sqrt(dx * dx + dy * dy + dz * dz);
                            if (best < 0 || d < best) best = d;
                        }
                    }
                }
                if (best >= 0 && (toGo[c[3]] < 0 || best < toGo[c[3]])) toGo[c[3]] = best;
            }
        }

        private int add(int cx, int cy, int cz, BlockView world) {
            if (used * 2 >= keys.length) grow();
            if (used == mask.length) {
                mask = java.util.Arrays.copyOf(mask, used * 2);
                toGo = java.util.Arrays.copyOf(toGo, used * 2);
            }
            long m = 0;
            if (!known(world, cx * CELL + CELL / 2, cz * CELL + CELL / 2)) {
                unknown++;
                m = -1L; // not seen: nothing to fly through
            } else for (int x = 0; x < CELL; x++) {
                for (int y = 0; y < CELL; y++) {
                    for (int z = 0; z < CELL; z++) {
                        if (AirRoute.solid(world, cx * CELL + x, cy * CELL + y, cz * CELL + z)) m |= 1L << ((x * CELL + y) * CELL + z);
                    }
                }
            }
            int i = used++;
            mask[i] = m;
            toGo[i] = -1;
            put(key(cx, cy, cz), i);
            return i;
        }

        private void put(long k, int v) {
            int h = (int) (mix(k) & (keys.length - 1));
            while (keys[h] != EMPTY) h = (h + 1) & (keys.length - 1);
            keys[h] = k;
            slots[h] = v;
        }

        private void grow() {
            long[] ok = keys;
            int[] os = slots;
            keys = new long[ok.length * 2];
            slots = new int[ok.length * 2];
            java.util.Arrays.fill(keys, EMPTY);
            for (int j = 0; j < ok.length; j++) if (ok[j] != EMPTY) put(ok[j], os[j]);
        }

        private int slot(int cx, int cy, int cz) {
            long k = key(cx, cy, cz);
            int h = (int) (mix(k) & (keys.length - 1));
            while (keys[h] != EMPTY) {
                if (keys[h] == k) return slots[h];
                h = (h + 1) & (keys.length - 1);
            }
            return -1;
        }

        private static long mix(long k) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            return k ^ (k >>> 33);
        }

        /** A block with collision at x, y, z; a block in a cell that was not read counts as solid. */
        public boolean solidAt(int x, int y, int z) {
            int i = slot(Math.floorDiv(x, CELL), Math.floorDiv(y, CELL), Math.floorDiv(z, CELL));
            if (i < 0) return true;
            return (mask[i] >>> ((Math.floorMod(x, CELL) * CELL + Math.floorMod(y, CELL)) * CELL + Math.floorMod(z, CELL)) & 1) != 0;
        }

        /** Any block in the gliding box at these feet, grown by m each way. */
        public boolean boxHits(Vec3d feet, double m) {
            return boxHits(feet, feet, m);
        }

        /** Any block in the gliding box on its way from one tick's feet to the next (the box around
         *  both), grown by m each way. */
        public boolean boxHits(Vec3d from, Vec3d to, double m) {
            int xa = MathHelper.floor(Math.min(from.x, to.x) - 0.3 - m), xb = MathHelper.floor(Math.max(from.x, to.x) + 0.3 + m);
            int ya = MathHelper.floor(Math.min(from.y, to.y) - m), yb = MathHelper.floor(Math.max(from.y, to.y) + 0.6 + m);
            int za = MathHelper.floor(Math.min(from.z, to.z) - 0.3 - m), zb = MathHelper.floor(Math.max(from.z, to.z) + 0.3 + m);
            for (int x = xa; x <= xb; x++) {
                for (int y = ya; y <= yb; y++) {
                    for (int z = za; z <= zb; z++) {
                        if (solidAt(x, y, z)) return true;
                    }
                }
            }
            return false;
        }

        /** Blocks left to the goal from the cell of this point, or -1 outside the corridor. */
        public double toGo(Vec3d p) {
            int i = slot(cell(p.x), cell(p.y), cell(p.z));
            return i < 0 ? -1 : toGo[i];
        }

        /** Cells read (the corridor and its skin). */
        public int size() {
            return used;
        }

        /** Cells of it read as air for want of their chunk: it should be read again as they come in. */
        public int unknown() {
            return unknown;
        }
    }

    /** Longest straight leg kept: the solver sees two seconds ahead, so between corners further apart
     *  it threads the gaps on its own -- once it did, once it climbed into the corner over a gap
     *  (01.11, the maze). A point every 28 blocks keeps it on the searched way. */
    private static final double MAX_LEG = 28;

    /** String pulling: from each kept point, the furthest point still in clear sight with room, at
     *  most {@link #MAX_LEG} away. */
    private static List<Vec3d> pull(BlockView world, Vec3d start, List<Vec3d> pts) {
        List<Vec3d> out = new ArrayList<>();
        Vec3d from = start;
        int i = 0;
        while (i < pts.size()) {
            int far = i;
            for (int j = pts.size() - 1; j > i; j--) {
                if (pts.get(j).distanceTo(from) <= MAX_LEG && clear(world, from, pts.get(j))) {
                    far = j;
                    break;
                }
            }
            out.add(pts.get(far));
            from = pts.get(far);
            i = far + 1;
        }
        return out;
    }

    /** A straight flight from a to b keeps a block and a half from any block (sampled every block). */
    public static boolean clear(BlockView world, Vec3d a, Vec3d b) {
        Vec3d d = b.subtract(a);
        int n = Math.max(1, (int) Math.ceil(d.length()));
        for (int i = 0; i <= n; i++) {
            Vec3d p = a.add(d.multiply(i / (double) n));
            for (double ox = -1.5; ox <= 1.5; ox += 1.5) {
                for (double oy = -1.0; oy <= 1.5; oy += 1.25) {
                    for (double oz = -1.5; oz <= 1.5; oz += 1.5) {
                        if (solid(world, MathHelper.floor(p.x + ox), MathHelper.floor(p.y + oy), MathHelper.floor(p.z + oz))) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static boolean open(BlockView world, int cx, int cy, int cz, Map<Long, Boolean> cache) {
        return cache.computeIfAbsent(key(cx, cy, cz), k -> {
            int x0 = cx * CELL, y0 = cy * CELL, z0 = cz * CELL;
            for (int x = x0; x < x0 + CELL; x++) {
                for (int y = y0; y < y0 + CELL; y++) {
                    for (int z = z0; z < z0 + CELL; z++) {
                        if (solid(world, x, y, z)) return false;
                    }
                }
            }
            return true;
        });
    }

    /** Extra cost for a cell with closed cells around it: a glider wants room. */
    private static double crowd(BlockView world, int x, int y, int z, Map<Long, Boolean> cache) {
        int closed = 0;
        if (!open(world, x + 1, y, z, cache)) closed++;
        if (!open(world, x - 1, y, z, cache)) closed++;
        if (!open(world, x, y + 1, z, cache)) closed++;
        if (!open(world, x, y - 1, z, cache)) closed++;
        if (!open(world, x, y, z + 1, cache)) closed++;
        if (!open(world, x, y, z - 1, cache)) closed++;
        return closed * 0.8;
    }

    /** A cell in a chunk the client has not got costs this much more: it reads as air, and may not be. */
    private static final double UNKNOWN_COST = 1.0;

    /** The client has the chunk of this block: what it reads there is the world, not air for want of it.
     *  Beyond the view distance (128 blocks at 8 chunks) it has not. */
    public static boolean known(BlockView world, int x, int z) {
        if (!(world instanceof net.minecraft.world.World w)) return true;
        try {
            return w.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean solid(BlockView world, int x, int y, int z) {
        try {
            BlockPos p = new BlockPos(x, y, z);
            var st = world.getBlockState(p);
            return st != null && !st.getCollisionShape(world, p).isEmpty();
        } catch (Exception e) { // a chunk unloading under the worker: count it open
            return false;
        }
    }

    private static int cell(double v) {
        return MathHelper.floor(v / CELL);
    }

    private static Vec3d centre(int x, int y, int z) {
        return new Vec3d(x * CELL + CELL / 2.0, y * CELL + CELL / 2.0, z * CELL + CELL / 2.0);
    }

    private static double h(int x, int y, int z, int gx, int gy, int gz) {
        return Math.sqrt((double) (x - gx) * (x - gx) + (double) (y - gy) * (y - gy) + (double) (z - gz) * (z - gz));
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }
}
