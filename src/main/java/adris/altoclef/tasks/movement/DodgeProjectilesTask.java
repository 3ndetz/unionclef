package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.util.goals.AltoGoal;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.ProjectileHelper;
import net.minecraft.util.math.Vec3d;
import adris.altoclef.tasksystem.Task;

public class DodgeProjectilesTask extends CustomBaritoneGoalTask {

    private final double _distanceHorizontal;
    private final double _distanceVertical;
    /** Max time (ms) before dodge is considered done — prevents blocking other tasks. */
    private static final long DODGE_TIMEOUT_MS = 5000;
    private long startTime;

    public DodgeProjectilesTask(double distanceHorizontal, double distanceVertical) {
        _distanceHorizontal = distanceHorizontal;
        _distanceVertical = distanceVertical;
    }

    @Override
    protected void onStart() {
        super.onStart();
        startTime = System.currentTimeMillis();
    }

    @Override
    protected Task onTick() {
        return super.onTick();
    }

    @Override
    public boolean isFinished() {
        // Timeout: don't block other tasks forever
        if (System.currentTimeMillis() - startTime > DODGE_TIMEOUT_MS) return true;
        return super.isFinished();
    }

    @SuppressWarnings("RedundantIfStatement")
    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DodgeProjectilesTask task) {
            if (Math.abs(task._distanceHorizontal - _distanceHorizontal) > 1) return false;
            if (Math.abs(task._distanceVertical - _distanceVertical) > 1) return false;
            return true;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Dodge arrows at " + _distanceHorizontal + " blocks away";
    }

    /**
     * OFF BARITONE'S GOAL TYPE — and this one was not merely legacy, it was DEAD.
     *
     * <p>{@code GoalDodgeProjectiles} is a pure predicate: {@code isInGoal} plus a {@code
     * heuristic}, and no position of its own. {@code goalToVec} has no branch for it, so the drive
     * asked this task where to go and got NULL every tick, exactly as the flee goal did before it
     * grew a point. The heuristic that ranked "further from the arrow line" is read only by
     * shredder's A*. So on tungsten the bot has not been dodging: it stood on the arrow's line and
     * the task timed out.
     *
     * <p>{@link AltoGoal.NearestSatisfying} is the shape this needs, and its own javadoc names
     * "dodge — not on the arrow's line" as one of the four cases it was written for while noting
     * that nothing called it yet. This is the first caller.
     *
     * <p>LIMITATION, STATED RATHER THAN HIDDEN: NearestSatisfying takes a FIXED origin, and
     * CustomBaritoneGoalTask caches the goal, so the search always expands from where the bot
     * stood when the dodge began. The predicate is live — it re-reads the projectiles every tick —
     * but the origin is not. That is tolerable here only because the task carries a timeout
     * ({@code DODGE_TIMEOUT_MS}) and is recreated; it would not be tolerable for a long-lived goal.
     */
    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        net.minecraft.util.math.BlockPos origin = mod.getPlayer() == null
                ? net.minecraft.util.math.BlockPos.ORIGIN
                : mod.getPlayer().getBlockPos();
        return new AltoGoal.NearestSatisfying(pos -> isClear(mod, pos), origin, DODGE_SEARCH_RADIUS);
    }

    /** How far to look for a cell off the arrow's line. Beyond this the dodge is a retreat. */
    private static final int DODGE_SEARCH_RADIUS = 8;

    /** Is this cell off every tracked arrow's closest approach? The old goal's isInGoal, intact. */
    private boolean isClear(AltoClef mod, net.minecraft.util.math.BlockPos cell) {
        Vec3d p = new Vec3d(cell.getX(), cell.getY(), cell.getZ());
        for (CachedProjectile projectile : mod.getEntityTracker().getProjectiles()) {
            if (projectile == null) continue;
            try {
                if (projectile.needsToRecache()) {
                    projectile.setCacheHit(ProjectileHelper.calculateArrowClosestApproach(projectile, p));
                }
                Vec3d delta = p.subtract(projectile.getCachedHit());
                double horizontalSq = delta.x * delta.x + delta.z * delta.z;
                if (horizontalSq < _distanceHorizontal * _distanceHorizontal
                        && Math.abs(delta.y) < _distanceVertical) {
                    return false;
                }
            } catch (Exception e) {
                // A projectile we cannot evaluate must not make a safe cell look unsafe.
            }
        }
        return true;
    }
}
