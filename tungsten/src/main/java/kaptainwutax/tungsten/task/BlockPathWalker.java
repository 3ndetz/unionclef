package kaptainwutax.tungsten.task;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.combat.AttackTiming;
import kaptainwutax.tungsten.combat.VoidDetector;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Simple sprint walker for BFS block paths.
 * Provides immediate movement while physics A* computes.
 *
 * Controls: look at waypoint (WindMouse), W + sprint + jump when needed.
 * Auto-stops when PathExecutor takes over or path exhausted.
 */
public class BlockPathWalker {

    private static List<BlockPos> path = null;
    private static int waypointIdx = 0;
    private static boolean active = false;

    public static void start(List<BlockPos> blockPath) {
        if (blockPath == null || blockPath.size() < 2) return;
        stop(); // clean previous
        path = blockPath;
        waypointIdx = 1; // skip [0] = player position
        active = true;
        Debug.logMessage("BFS walker: " + blockPath.size() + " waypoints");
    }

    public static void stop() {
        if (active) {
            releaseKeys();
        }
        active = false;
        path = null;
        waypointIdx = 0;
    }

    public static boolean isRunning() {
        return active && path != null;
    }

    /** Get the last waypoint position (BFS endpoint) for A* start. */
    public static Vec3d getEndpoint() {
        if (path == null || path.isEmpty()) return null;
        BlockPos end = path.get(path.size() - 1);
        return Vec3d.ofBottomCenter(end);
    }

    public static void tick(ClientPlayerEntity player) {
        if (!active || path == null) return;

        // auto-stop when executor takes over
        if (TungstenModDataContainer.EXECUTOR.isRunning()) {
            stop();
            return;
        }

        if (waypointIdx >= path.size()) {
            stop();
            return;
        }

        BlockPos wp = path.get(waypointIdx);
        Vec3d wpPos = Vec3d.ofBottomCenter(wp);
        Vec3d playerPos = player.getPos();
        double dx = playerPos.x - wpPos.x;
        double dz = playerPos.z - wpPos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        // advance waypoint when close enough
        if (dist < 1.5) {
            waypointIdx++;
            if (waypointIdx >= path.size()) {
                stop();
                return;
            }
            wp = path.get(waypointIdx);
            wpPos = Vec3d.ofBottomCenter(wp);
        }

        // look toward waypoint via WindMouse
        float yaw = AttackTiming.yawTo(playerPos, wpPos);
        WindMouseRotation.INSTANCE.setTarget(yaw, 0);

        // sprint + forward
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        // jump: if waypoint is higher, or sprint-jump for speed on flat
        boolean needJumpUp = wp.getY() > player.getBlockPos().getY();
        boolean safeToJump = isLandingSafe(playerPos, player);
        mc.options.jumpKey.setPressed(player.isOnGround() && (needJumpUp || safeToJump));
    }

    private static boolean isLandingSafe(Vec3d pos, ClientPlayerEntity player) {
        Vec3d vel = player.getVelocity();
        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (horizSpeed < 0.01) return true;

        double nx = vel.x / horizSpeed;
        double nz = vel.z / horizSpeed;
        int y = (int) Math.floor(pos.y);

        for (int d = 1; d <= 4; d++) {
            int x = (int) Math.floor(pos.x + nx * d);
            int z = (int) Math.floor(pos.z + nz * d);
            int fall = VoidDetector.fallHeight(new Vec3d(x + 0.5, y, z + 0.5), player.getWorld());
            if (fall >= 4) return false;
        }
        return true;
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
