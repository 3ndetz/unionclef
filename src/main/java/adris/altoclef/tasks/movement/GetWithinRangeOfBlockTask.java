package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.goals.AltoGoal;
import net.minecraft.util.math.BlockPos;

public class GetWithinRangeOfBlockTask extends CustomBaritoneGoalTask {

    public final BlockPos blockPos;
    public final int range;

    public GetWithinRangeOfBlockTask(BlockPos blockPos, int range) {
        this.blockPos = blockPos;
        this.range = range;
    }

    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        return AltoGoal.near(blockPos, range);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetWithinRangeOfBlockTask task) {
            return task.blockPos.equals(blockPos) && task.range == range;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Getting within " + range + " blocks of " + blockPos.toShortString();
    }
}
