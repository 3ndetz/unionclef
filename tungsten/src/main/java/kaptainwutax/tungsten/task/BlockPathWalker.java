package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.combat.AttackTiming;
import kaptainwutax.tungsten.combat.CombatPathfinder;
import kaptainwutax.tungsten.combat.SafetySystem;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.List;

/**
 * Immediate movement while physics A* computes.
 *
 * Priority chain:
 *   1. DIRECT — LOS + distance shrinking + safe → sprint straight at target
 *   2. BFS    — no LOS or danger detected → follow BFS waypoints
 *   3. (stop) — executor ready or path exhausted → hand off to A*
 *
 * Auto-stops when PathExecutor takes over.
 */
public class BlockPathWalker {

    private enum Mode { DIRECT, BFS }

    private static List<BlockPos> path = null;
    private static int waypointIdx = 0;
    private static boolean active = false;
    private static Mode mode = Mode.DIRECT;

    // progress tracking for direct-sprint
    private static double lastDistToTarget = Double.MAX_VALUE;
    private static int noProgressTicks = 0;
    private static final int NO_PROGRESS_LIMIT = 15; // ~0.75s without getting closer → switch to BFS
    private static final double MIN_APPROACH_SPEED = 0.03; // ~walk speed per tick

    private static Vec3d directTarget = null;

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Start with direct-sprint toward target. BFS path is fallback.
     * @param target      the actual target position
     * @param blockPath   BFS path (fallback if direct fails), may be null
     */
    public static void start(Vec3d target, List<BlockPos> blockPath) {
        stop();
        directTarget = target;
        path = blockPath;
        waypointIdx = (blockPath != null && blockPath.size() > 1) ? 1 : 0;
        lastDistToTarget = Double.MAX_VALUE;
        noProgressTicks = 0;
        mode = Mode.DIRECT;
        active = true;
        Debug.logMessage("Walker: direct→target" +
                (blockPath != null ? " (BFS fallback: " + blockPath.size() + " wp)" : ""));
    }

    /** Start BFS-only (no direct sprint). */
    public static void startBFS(List<BlockPos> blockPath) {
        if (blockPath == null || blockPath.size() < 2) return;
        stop();
        path = blockPath;
        waypointIdx = 1;
        mode = Mode.BFS;
        active = true;
        Debug.logMessage("Walker: BFS " + blockPath.size() + " wp");
    }

    public static void stop() {
        if (active) {
            releaseKeys();
        }
        active = false;
        path = null;
        directTarget = null;
        waypointIdx = 0;
        noProgressTicks = 0;
        lastDistToTarget = Double.MAX_VALUE;
    }

    public static boolean isRunning() {
        return active;
    }

    /** BFS endpoint for A* start position. */
    public static Vec3d getEndpoint() {
        if (path == null || path.isEmpty()) return null;
        return Vec3d.ofBottomCenter(path.get(path.size() - 1));
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    public static void tick(ClientPlayerEntity player) {
        if (!active) return;

        // auto-stop when executor takes over
        if (TungstenModDataContainer.isExecutorRunning()) {
            stop();
            return;
        }

        if (mode == Mode.DIRECT) {
            tickDirect(player);
        } else {
            tickBFS(player);
        }
    }

    // ── DIRECT: sprint straight at target ────────────────────────────────────

    private static void tickDirect(ClientPlayerEntity player) {
        if (directTarget == null) { switchToBFS(); return; }

        Vec3d playerPos = player.getEntityPos();
        WorldView world = player.getEntityWorld();
        double dist = horizontalDist(playerPos, directTarget);

        // check LOS
        boolean hasLOS = FollowEntityTask.hasLineOfSight(player, directTarget);

        // check progress — distance should be shrinking
        double progress = lastDistToTarget - dist;
        lastDistToTarget = dist;
        if (progress < MIN_APPROACH_SPEED) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
        }

        // check safety: landing safe + no holes on path to target
        BlockPos targetBlock = BlockPos.ofFloored(directTarget);
        boolean landingSafe = SafetySystem.isJumpLandingSafe(playerPos, player.getVelocity(), world);
        boolean pathSafe = !SafetySystem.hasHolesOnPath(playerPos, targetBlock, world);
        boolean groundSafe = CombatPathfinder.isWalkable(player.getBlockPos(), world);

        // bail to BFS if: no LOS, no progress, or danger
        if (!hasLOS || noProgressTicks >= NO_PROGRESS_LIMIT || !pathSafe || !groundSafe) {
            if (!hasLOS) Debug.logMessage("Walker: no LOS → BFS");
            else if (noProgressTicks >= NO_PROGRESS_LIMIT) Debug.logMessage("Walker: no progress → BFS");
            else Debug.logMessage("Walker: danger → BFS");
            switchToBFS();
            return;
        }

        // close enough — done
        if (dist < 1.5) {
            stop();
            return;
        }

        // movement
        float yaw = AttackTiming.yawTo(playerPos, directTarget);
        WindMouseRotation.INSTANCE.setTarget(yaw, 0);

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        boolean canJump = TungstenConfig.get().followJumpingEnabled
                && player.isOnGround() && landingSafe;
        mc.options.jumpKey.setPressed(canJump);
    }

    private static void switchToBFS() {
        if (path != null && path.size() >= 2) {
            mode = Mode.BFS;
            noProgressTicks = 0;
        } else {
            stop();
        }
    }

    // ── BFS: follow waypoints ────────────────────────────────────────────────

    private static void tickBFS(ClientPlayerEntity player) {
        if (path == null || waypointIdx >= path.size()) {
            stop();
            return;
        }

        BlockPos wp = path.get(waypointIdx);
        Vec3d wpPos = Vec3d.ofBottomCenter(wp);
        Vec3d playerPos = player.getEntityPos();
        double dist = horizontalDist(playerPos, wpPos);

        // advance waypoint
        if (dist < 1.5) {
            waypointIdx++;
            if (waypointIdx >= path.size()) {
                stop();
                return;
            }
            wp = path.get(waypointIdx);
            wpPos = Vec3d.ofBottomCenter(wp);
        }

        float yaw = AttackTiming.yawTo(playerPos, wpPos);
        WindMouseRotation.INSTANCE.setTarget(yaw, 0);

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        boolean needJumpUp = wp.getY() > player.getBlockPos().getY();
        boolean canJump = TungstenConfig.get().followJumpingEnabled
                && player.isOnGround()
                && (needJumpUp || SafetySystem.isJumpLandingSafe(
                        playerPos, player.getVelocity(), player.getEntityWorld()));
        mc.options.jumpKey.setPressed(canJump);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static double horizontalDist(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        WindMouseRotation.INSTANCE.clearTarget();
    }
}
