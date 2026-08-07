package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.goals.AltoGoal;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;

public class RunAwayFromPositionTask extends CustomBaritoneGoalTask {

    private final BlockPos[] _dangerBlocks;
    private final double _distance;
    private final Integer _maintainY;

    public RunAwayFromPositionTask(double distance, BlockPos... toRunAwayFrom) {
        this(distance, null, toRunAwayFrom);
    }

    public RunAwayFromPositionTask(double distance, Integer maintainY, BlockPos... toRunAwayFrom) {
        _distance = distance;
        _dangerBlocks = toRunAwayFrom;
        _maintainY = maintainY;
    }

    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        // G-0: fleeing has no destination of its own, so the DIRECTION is computed here -- where the
        // player is standing is known at this point -- and AltoGoal.Flee stays a pure record. See
        // the note on that type for why the goal must not read the player itself.
        return AltoGoal.flee(mod.getPlayer().getPos(), Arrays.asList(_dangerBlocks),
                _distance, _maintainY);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromPositionTask task) {
            return Arrays.equals(task._dangerBlocks, _dangerBlocks);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Running away from " + Arrays.toString(_dangerBlocks);
    }
}
