package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.goals.AltoGoal;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;

/** Reach any exact feet cell through the ordinary Tungsten movement graph. */
public final class GetToAnyBlockTask extends CustomBaritoneGoalTask implements ITaskRequiresGrounded {
    private final AltoGoal.AnyBlock destinations;

    public GetToAnyBlockTask(Collection<BlockPos> positions) {
        destinations = new AltoGoal.AnyBlock(positions);
    }

    public boolean contains(BlockPos pos) { return destinations.reached(pos); }

    /** Whether this exact destination snapshot still owns a running search or route. */
    boolean ownsRoute() {
        return kaptainwutax.tungsten.task.FastNavigator.isNearestSearch(destinations);
    }

    @Override
    protected Task onTick() {
        driveTungstenPrimary(AltoClef.getInstance());
        return null;
    }

    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) { return destinations; }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetToAnyBlockTask task
                && destinations.cells().equals(task.destinations.cells());
    }

    @Override
    protected String toDebugString() { return "Getting to any of " + destinations.cells().size() + " cells"; }
}
