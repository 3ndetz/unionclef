package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.goals.AltoGoal;
import net.minecraft.entity.Entity;

/**
 * The LONG HAUL to an entity: walk -- or dig, or build -- until it is within {@code range}, through
 * the same drive every block goal travels (G48, 2026-09-11).
 *
 * <p>{@link GetToEntityTask} has exactly one engine, the physics search behind
 * {@code TungstenHelper.tryPathToEntity}, and it is a short-range engine: on the 15:38 recorded
 * playthrough a chicken 45 blocks away produced {@code lock=chicken:45.1>45.1, m0.0} -- a
 * thirty-second lock that moved the body ZERO -- then "Failed to get to target, wandering for a
 * bit", then a wander that was refused 4014 times, and the bot stood in one spot for the last five
 * minutes of the run with a full food task queued behind it. Same disease as the drops (G39) and
 * the ores (G25): the approach never reached the engine that can route terrain.
 *
 * <p>So the distance is split the way baritone splits it (GoalFollowEntity for the walk, the
 * interaction for the last blocks): beyond {@code GetToEntityTask.CLOSE_WALK_RANGE} this task drives
 * a {@link AltoGoal.NearLive} goal on the entity's current cell -- grid BFS, MovementQueue, and the
 * escalation to FastNavigator when walking cannot reach -- and hands back to the entity task once
 * the target is close, where the physics chase and the straight walk do their job.
 */
public class GetNearEntityTask extends CustomBaritoneGoalTask implements ITaskRequiresGrounded {

    private final Entity entity;
    private final int range;

    public GetNearEntityTask(Entity entity, int range) {
        this.entity = entity;
        this.range = range;
    }

    public boolean isFor(Entity e) {
        return entity.equals(e);
    }

    public int range() {
        return range;
    }

    @Override
    protected Task onTick() {
        if (driveTungstenPrimary(AltoClef.getInstance())) return null;
        return super.onTick();
    }

    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        return AltoGoal.nearLive(() -> entity.isRemoved() ? null : entity.getBlockPos(), range);
    }

    @Override
    public boolean isFinished() {
        AltoClef mod = AltoClef.getInstance();
        if (entity.isRemoved()) return true;
        // G61: half a block of slack -- a body beside a pig's cell is 1.2 from its centre, and a
        // range of 1 would never be met while the goal cell itself is reached.
        if (mod != null && mod.getPlayer() != null && mod.getPlayer().isInRange(entity, range + 0.5)) return true;
        return super.isFinished();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetNearEntityTask task && task.entity.equals(entity) && task.range == range;
    }

    @Override
    protected String toDebugString() {
        return "Long haul to " + entity.getType().getTranslationKey() + " (drive: walk / dig / build)";
    }
}
