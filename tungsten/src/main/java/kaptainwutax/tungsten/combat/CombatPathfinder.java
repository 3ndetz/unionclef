package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import net.minecraft.block.*;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.*;

/**
 * Lightweight BFS pathfinder for combat — runs on block grid.
 *
 * Two paths maintained:
 *   attackPath  — shortest walkable safe route to target
 *   retreatPath — best escape route (far from target + high ground + safe)
 *
 * Hazard blocks (lava, fire, magma, campfire, cactus) are impassable.
 * Slowdown blocks (water, cobweb, soul sand, honey) get extra cost in BFS.
 *
 * Jump trajectories are visualized as arcs between waypoints.
 */
public class CombatPathfinder {

    private static final int MAX_RADIUS = 25;
    private static final int MAX_NODES = 2000;
    private static final Color COL_ATTACK    = new Color(255, 100, 50);  // orange
    private static final Color COL_RETREAT   = new Color(50, 150, 255);  // blue
    private static final Color COL_JUMP_ARC  = new Color(255, 220, 50);  // yellow arcs

    // sprint-jump covers ~4 blocks horizontal, ~1.25 up
    private static final double JUMP_HORIZ = 3.5;
    private static final int JUMP_ARC_SEGMENTS = 8;

    private List<BlockPos> attackPath = Collections.emptyList();
    private List<BlockPos> retreatPath = Collections.emptyList();
    private int tickCounter = 0;
    private static final int UPDATE_INTERVAL = 10;

    // ── tick ─────────────────────────────────────────────────────────────────

    public void tick(BlockPos playerPos, BlockPos targetPos, WorldView world) {
        tickCounter++;
        if (tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        attackPath = bfsPath(playerPos, targetPos, world);
        retreatPath = findRetreatPath(playerPos, targetPos, world);
    }

    // ── render ───────────────────────────────────────────────────────────────

    public void renderUpdate(float tickDelta) {
        renderPathWithJumps(attackPath, COL_ATTACK);
        renderPathWithJumps(retreatPath, COL_RETREAT);
    }

    private void renderPathWithJumps(List<BlockPos> path, Color col) {
        if (path.size() < 2) return;

        // find jump waypoints: points where a sprint-jump would land
        // skip intermediate blocks that fall within one jump distance
        List<BlockPos> jumpPoints = computeJumpWaypoints(path);

        // render waypoint cubes
        for (BlockPos bp : jumpPoints) {
            Vec3d center = Vec3d.ofBottomCenter(bp);
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    center.subtract(0.2, 0, 0.2), new Vec3d(0.4, 0.15, 0.4), col));
        }

        // render jump arcs between waypoints
        for (int i = 0; i < jumpPoints.size() - 1; i++) {
            Vec3d from = Vec3d.ofBottomCenter(jumpPoints.get(i)).add(0, 0.1, 0);
            Vec3d to = Vec3d.ofBottomCenter(jumpPoints.get(i + 1)).add(0, 0.1, 0);
            renderJumpArc(from, to, COL_JUMP_ARC);
        }
    }

    /**
     * From a dense block path, pick waypoints a sprint-jump apart.
     * Walk along path, accumulate distance. When distance >= JUMP_HORIZ, mark waypoint.
     */
    private List<BlockPos> computeJumpWaypoints(List<BlockPos> path) {
        List<BlockPos> waypoints = new ArrayList<>();
        waypoints.add(path.get(0));

        double accumulated = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos prev = path.get(i - 1);
            BlockPos curr = path.get(i);
            double step = Math.sqrt(prev.getSquaredDistance(curr));
            accumulated += step;

            if (accumulated >= JUMP_HORIZ || i == path.size() - 1) {
                waypoints.add(curr);
                accumulated = 0;
            }
        }
        return waypoints;
    }

    /** Render a parabolic arc from → to (simple ballistic curve). */
    private void renderJumpArc(Vec3d from, Vec3d to, Color col) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double dy = to.y - from.y;

        // peak height: ~1.25 blocks above start for sprint-jump
        double peakH = 1.25 + Math.max(0, dy);

        Vec3d prev = from;
        for (int s = 1; s <= JUMP_ARC_SEGMENTS; s++) {
            double t = (double) s / JUMP_ARC_SEGMENTS;
            double x = from.x + dx * t;
            double z = from.z + dz * t;
            // parabola: y = start + peak * 4t(1-t) + dy*t
            double y = from.y + peakH * 4 * t * (1 - t) + dy * t;

            Vec3d curr = new Vec3d(x, y, z);
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(prev, curr, col));
            prev = curr;
        }
    }

    // ── BFS ──────────────────────────────────────────────────────────────────

    private List<BlockPos> bfsPath(BlockPos start, BlockPos goal, WorldView world) {
        if (start.equals(goal)) return Collections.emptyList();

        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        queue.add(start);
        cameFrom.put(start, null);
        int explored = 0;

        while (!queue.isEmpty() && explored < MAX_NODES) {
            BlockPos current = queue.poll();
            explored++;

            if (current.equals(goal) || current.isWithinDistance(goal, 1.5)) {
                return reconstructPath(cameFrom, current);
            }

            for (BlockPos neighbor : getWalkableNeighbors(current, world)) {
                if (cameFrom.containsKey(neighbor)) continue;
                if (!start.isWithinDistance(neighbor, MAX_RADIUS)) continue;
                cameFrom.put(neighbor, current);
                queue.add(neighbor);
            }
        }

        // partial path to closest reached node
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        for (BlockPos p : cameFrom.keySet()) {
            double d = p.getSquaredDistance(goal);
            if (d < closestDist) { closestDist = d; closest = p; }
        }
        return closest != null ? reconstructPath(cameFrom, closest) : Collections.emptyList();
    }

    private List<BlockPos> findRetreatPath(BlockPos playerPos, BlockPos targetPos, WorldView world) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        queue.add(playerPos);
        cameFrom.put(playerPos, null);

        BlockPos bestRetreat = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int explored = 0;

        while (!queue.isEmpty() && explored < MAX_NODES) {
            BlockPos current = queue.poll();
            explored++;

            double distFromTarget = Math.sqrt(current.getSquaredDistance(targetPos));
            double heightBonus = (current.getY() - targetPos.getY()) * 2.0;
            double edgePenalty = VoidDetector.edgeScore(Vec3d.ofBottomCenter(current), world) * -10.0;
            double score = distFromTarget + heightBonus + edgePenalty;

            if (score > bestScore && !current.equals(playerPos)) {
                bestScore = score;
                bestRetreat = current;
            }

            for (BlockPos neighbor : getWalkableNeighbors(current, world)) {
                if (cameFrom.containsKey(neighbor)) continue;
                if (!playerPos.isWithinDistance(neighbor, MAX_RADIUS)) continue;
                cameFrom.put(neighbor, current);
                queue.add(neighbor);
            }
        }

        return bestRetreat != null ? reconstructPath(cameFrom, bestRetreat) : Collections.emptyList();
    }

    // ── neighbors + block checks ─────────────────────────────────────────────

    private static final int[][] HORIZONTAL = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

    private List<BlockPos> getWalkableNeighbors(BlockPos pos, WorldView world) {
        List<BlockPos> result = new ArrayList<>();

        for (int[] off : HORIZONTAL) {
            BlockPos candidate = pos.add(off[0], 0, off[1]);
            if (isWalkable(candidate, world)) { result.add(candidate); continue; }

            BlockPos up = candidate.up();
            if (isWalkable(up, world) && canPassThrough(pos.up().up(), world)) { result.add(up); continue; }

            BlockPos down = candidate.down();
            if (isWalkable(down, world) && canPassThrough(candidate, world)) { result.add(down); }
        }
        return result;
    }

    private boolean isWalkable(BlockPos feetPos, WorldView world) {
        BlockPos below = feetPos.down();
        if (!isSolid(below, world)) return false;
        if (isHazard(below, world)) return false;           // standing ON hazard
        if (!canPassThrough(feetPos, world)) return false;
        if (isHazardOrSlow(feetPos, world)) return false;   // feet IN hazard/slow
        if (!canPassThrough(feetPos.up(), world)) return false;
        if (isHazardOrSlow(feetPos.up(), world)) return false;
        return true;
    }

    private boolean isSolid(BlockPos pos, WorldView world) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private boolean canPassThrough(BlockPos pos, WorldView world) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    /** Blocks that deal damage — never walk here. */
    private boolean isHazard(BlockPos pos, WorldView world) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        return block == Blocks.LAVA
                || block instanceof FireBlock
                || block == Blocks.MAGMA_BLOCK
                || block instanceof CampfireBlock
                || block == Blocks.CACTUS
                || block == Blocks.WITHER_ROSE
                || block instanceof SweetBerryBushBlock;
    }

    /** Hazard OR slowdown — avoid in pathfinding. */
    private boolean isHazardOrSlow(BlockPos pos, WorldView world) {
        if (isHazard(pos, world)) return true;
        Block block = world.getBlockState(pos).getBlock();
        return block == Blocks.WATER
                || block instanceof CobwebBlock
                || block == Blocks.SOUL_SAND
                || block == Blocks.HONEY_BLOCK
                || block == Blocks.POWDER_SNOW;
    }

    // ── path reconstruction ──────────────────────────────────────────────────

    private List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos current = end;
        while (current != null) {
            path.add(current);
            current = cameFrom.get(current);
        }
        Collections.reverse(path);
        return path;
    }

    // ── getters ──────────────────────────────────────────────────────────────

    public List<BlockPos> getAttackPath()  { return attackPath; }
    public List<BlockPos> getRetreatPath() { return retreatPath; }

    public void reset() {
        attackPath = Collections.emptyList();
        retreatPath = Collections.emptyList();
        tickCounter = 0;
    }
}
