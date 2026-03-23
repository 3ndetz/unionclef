package kaptainwutax.tungsten.combat;

import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.*;

/**
 * Lightweight BFS pathfinder for combat — runs on block grid.
 *
 * Two paths maintained:
 *   attackPath  — shortest walkable route to target
 *   retreatPath — shortest walkable route AWAY from target to safe high ground
 *
 * Runs every N ticks (not every frame). Results cached for render-time viz.
 * Simple BFS on walkable blocks within radius, typically <1ms.
 */
public class CombatPathfinder {

    private static final int MAX_RADIUS = 25;
    private static final int MAX_NODES = 2000;
    private static final Color COL_ATTACK  = new Color(255, 100, 50);   // orange
    private static final Color COL_RETREAT = new Color(50, 150, 255);   // blue

    private List<BlockPos> attackPath = Collections.emptyList();
    private List<BlockPos> retreatPath = Collections.emptyList();
    private int tickCounter = 0;
    private static final int UPDATE_INTERVAL = 10; // ticks between recalculations

    // ── tick: recalculate paths periodically ─────────────────────────────────

    public void tick(BlockPos playerPos, BlockPos targetPos, WorldView world) {
        tickCounter++;
        if (tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        attackPath = bfsPath(playerPos, targetPos, world);
        retreatPath = findRetreatPath(playerPos, targetPos, world);
    }

    // ── render: visualize cached paths ───────────────────────────────────────

    public void renderUpdate(float tickDelta) {
        renderPath(attackPath, COL_ATTACK, 0.35);
        renderPath(retreatPath, COL_RETREAT, 0.35);
    }

    private void renderPath(List<BlockPos> path, Color col, double cubeSize) {
        if (path.isEmpty()) return;

        double half = cubeSize / 2;
        for (int i = 0; i < path.size(); i++) {
            BlockPos bp = path.get(i);
            Vec3d center = Vec3d.ofBottomCenter(bp);

            // cube at each waypoint
            TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Cuboid(
                    center.subtract(half, 0, half),
                    new Vec3d(cubeSize, cubeSize * 0.3, cubeSize), col));

            // line connecting waypoints
            if (i < path.size() - 1) {
                Vec3d next = Vec3d.ofBottomCenter(path.get(i + 1));
                TungstenModRenderContainer.COMBAT_TRAJECTORY.add(new Line(
                        center.add(0, 0.1, 0), next.add(0, 0.1, 0), col));
            }
        }
    }

    // ── BFS: shortest walkable path between two points ───────────────────────

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

        // no path found — return best partial (closest to goal)
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        for (BlockPos p : cameFrom.keySet()) {
            double d = p.getSquaredDistance(goal);
            if (d < closestDist) {
                closestDist = d;
                closest = p;
            }
        }
        return closest != null ? reconstructPath(cameFrom, closest) : Collections.emptyList();
    }

    // ── retreat: find safest point away from target ───────────────────────────

    private List<BlockPos> findRetreatPath(BlockPos playerPos, BlockPos targetPos, WorldView world) {
        // BFS from player, score each reached node by:
        //   distance from target (farther = better) + height advantage (higher = better)
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

            // score: distance from target + height bonus
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

    // ── neighbors ────────────────────────────────────────────────────────────

    private static final int[][] HORIZONTAL = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

    private List<BlockPos> getWalkableNeighbors(BlockPos pos, WorldView world) {
        List<BlockPos> result = new ArrayList<>();

        for (int[] off : HORIZONTAL) {
            // same level
            BlockPos candidate = pos.add(off[0], 0, off[1]);
            if (isWalkable(candidate, world)) {
                result.add(candidate);
                continue;
            }
            // step up
            BlockPos up = candidate.up();
            if (isWalkable(up, world) && canPassThrough(pos.up().up(), world)) {
                result.add(up);
                continue;
            }
            // step down
            BlockPos down = candidate.down();
            if (isWalkable(down, world) && canPassThrough(candidate, world)) {
                result.add(down);
            }
        }

        return result;
    }

    /** Can stand at this block: solid below, air at feet and head level. */
    private boolean isWalkable(BlockPos feetPos, WorldView world) {
        return isSolid(feetPos.down(), world)
                && canPassThrough(feetPos, world)
                && canPassThrough(feetPos.up(), world);
    }

    private boolean isSolid(BlockPos pos, WorldView world) {
        BlockState state = world.getBlockState(pos);
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private boolean canPassThrough(BlockPos pos, WorldView world) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty();
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
