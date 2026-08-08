package adris.altoclef.tasks.movement;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.goals.AltoGoal;
import adris.altoclef.util.helpers.BaritoneHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.SkeletonEntity;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RunAwayFromHostilesTask extends CustomBaritoneGoalTask {

    private final double distanceToRun;
    private final boolean includeSkeletons;

    public RunAwayFromHostilesTask(double distance, boolean includeSkeletons) {
        distanceToRun = distance;
        this.includeSkeletons = includeSkeletons;
    }

    public RunAwayFromHostilesTask(double distance) {
        this(distance, false);
    }


    /**
     * OFF BARITONE'S GOAL TYPE, AND IT IS THE LIVE READ THAT MADE THIS AWKWARD.
     *
     * <p>{@code CustomBaritoneGoalTask} caches the goal object for the life of the task, so a
     * snapshot flee would send the bot to wherever the mobs stood when it started running and
     * leave it there. {@link AltoGoal.FleeLive} recomputes from live positions instead, once per
     * tick, which is the same freshness the old path got by re-interrogating the baritone goal
     * every time the drive asked it for a point.
     */
    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        // We want to run away NOW
        Nav.cancel();
        return new AltoGoal.FleeLive(
                () -> hostiles(mod).stream()
                        .map(e -> new net.minecraft.util.math.Vec3d(e.getX(), e.getY(), e.getZ()))
                        .collect(Collectors.toList()),
                () -> mod.getPlayer() == null ? null : mod.getPlayer().getPos(),
                distanceToRun);
    }

    /** The hostiles this task runs from — skeletons only when asked, as before. */
    private List<Entity> hostiles(AltoClef mod) {
        Stream<LivingEntity> stream = mod.getEntityTracker().getHostiles().stream();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            if (!includeSkeletons) {
                stream = stream.filter(hostile -> !(hostile instanceof SkeletonEntity));
            }
            return stream.collect(Collectors.toList());
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromHostilesTask task) {
            return Math.abs(task.distanceToRun - distanceToRun) < 1;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "NIGERUNDAYOO, SUMOOKEYY! distance="+ distanceToRun +", skeletons="+ includeSkeletons;
    }

}
