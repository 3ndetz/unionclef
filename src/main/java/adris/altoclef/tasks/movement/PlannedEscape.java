package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * A recovery is a PLAN, never a random walk (docs/BARITONE-GAPS.md G26/G29, 2026-09-11).
 *
 * <p>Two places used to answer "the bot is not moving" with a random jiggle: UnstuckChain's
 * shimmy (which also DUG, at random) and TimeoutWanderTask's spiral of unreachable points. Both
 * throw the body at the world and hope. This helper answers it with the one engine that can
 * break and place its way anywhere: FastNavigator (FastPlanner with breakThrough / breakDown /
 * breakStair / pillarUp / placeAcross).
 *
 * <p>Where to? In this order:
 * <ol>
 *   <li>the LIVE altoclef goal, if the drive published one in the last two seconds and it is not
 *       where we already stand -- the thing we were trying to reach is the thing to reach;</li>
 *   <li>the SURFACE above the feet, when we are below it -- a pit, a shaft, a cave pocket;</li>
 *   <li>the nearest standable cell at least three blocks away, otherwise.</li>
 * </ol>
 * Arming is rate-limited so a navigator that gives a route up is not re-armed twenty times a
 * second, and callers keep control of the body while it drives (they return null / hold their
 * priority), so there is exactly one owner of the movement keys.
 */
public final class PlannedEscape {

    private PlannedEscape() {}

    /** Escapes armed, and how the goal was chosen. Read as escape=armed/goal/surface/nearby/none. */
    public static volatile int escapeArmed, escapeToGoal, escapeToSurface, escapeToNearby, escapeNoWhere;

    private static long lastArmMs = 0L;
    private static Vec3d armedFrom = null;

    /** Where the body was when the current escape started (null when none is running). */
    public static Vec3d armedFrom() {
        return kaptainwutax.tungsten.task.FastNavigator.isActive() ? armedFrom : null;
    }

    /**
     * Start a planned escape unless one is already running. Returns true while FastNavigator is
     * driving an escape (freshly armed or still going), false when nothing could be armed.
     */
    public static boolean tryStart(AltoClef mod, String why) {
        if (kaptainwutax.tungsten.task.FastNavigator.isActive()) return true;
        long now = System.currentTimeMillis();
        if (now - lastArmMs < 3000) return false;
        if (mod == null || mod.getPlayer() == null || mod.getWorld() == null) return false;
        Vec3d goal = pickGoal(mod);
        if (goal == null) {
            escapeNoWhere++;
            return false;
        }
        kaptainwutax.tungsten.task.BlockPathWalker.stop();
        kaptainwutax.tungsten.path.movements.MovementQueue.stop();
        var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
        if (ex != null) ex.stop = false;
        kaptainwutax.tungsten.task.FastNavigator.start(goal);
        armedFrom = mod.getPlayer().getPos();
        lastArmMs = now;
        escapeArmed++;
        Debug.logMessage(String.format("Planned escape (%s) -> %.1f,%.1f,%.1f via FastPlanner (dig/build allowed)",
                why, goal.x, goal.y, goal.z));
        return true;
    }

    private static Vec3d pickGoal(AltoClef mod) {
        Vec3d me = mod.getPlayer().getPos();
        net.minecraft.world.World w = mod.getWorld();
        // 1. the live goal
        Vec3d live = CustomBaritoneGoalTask.lastGoalVec;
        if (live != null && System.currentTimeMillis() - CustomBaritoneGoalTask.lastGoalAtMs < 2000
                && live.squaredDistanceTo(me) > 4.0) {
            escapeToGoal++;
            return live;
        }
        // 2. the surface above
        BlockPos feet = kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(mod.getPlayer());
        int top = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, feet.getX(), feet.getZ());
        if (top > feet.getY() + 1) {
            escapeToSurface++;
            return new Vec3d(feet.getX() + 0.5, top, feet.getZ() + 0.5);
        }
        // 3. the nearest standable cell at least three blocks away
        for (int r = 3; r <= 10; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 0; dy <= 2; dy++) {
                        for (int sy : dy == 0 ? new int[]{0} : new int[]{dy, -dy}) {
                            int x = feet.getX() + dx, y = feet.getY() + sy, z = feet.getZ() + dz;
                            if (CustomBaritoneGoalTask.standable(w, x, y, z)) {
                                escapeToNearby++;
                                return new Vec3d(x + 0.5, y, z + 0.5);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** All four cardinal neighbours of the feet cell are solid: the body cannot step anywhere. */
    public static boolean enclosed(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null || mod.getWorld() == null) return false;
        BlockPos feet = kaptainwutax.tungsten.path.movements.RotationHelper.playerFeet(mod.getPlayer());
        net.minecraft.world.World w = mod.getWorld();
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos n = feet.offset(d);
            if (w.getBlockState(n).getCollisionShape(w, n).isEmpty()) return false;
        }
        return true;
    }
}
