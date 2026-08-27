package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import net.minecraft.util.math.BlockPos;

/**
 * Walk toward a block until it can actually be HIT, not merely until it is near.
 *
 * <p>Two approaches were measured before this one and both have a hole.
 *
 * <p>{@code GetToBlockTask} asks to OCCUPY the block's cell, which is impossible while the block
 * stands. Polling the live task chain through the window after stone tools found the bot spending
 * 98.7% of it on a single such goal -- {@code Getting to block {x=1449, y=63, z=89}}, a spruce_log
 * -- seven minutes of a ten-minute run on a goal that cannot be satisfied. That is the ladder's
 * five-rung ceiling.
 *
 * <p>{@code GetWithinRangeOfBlockTask} completes on DISTANCE alone and never asks whether the block
 * is visible. Swapping it in (breakGoalIsReach) moved dead time from a 19% median to 14% but took
 * the ladder from a steady 5 5 5 3 5 to a wild 6 4 2 0 5: the bot can stand three blocks away
 * behind an obstruction, count the approach as done, and still be unable to break anything.
 *
 * <p>So the honest completion test is the one the miner itself uses: {@link LookHelper#getReach}
 * returns a rotation only when the block can be struck from here. Distance still drives the
 * navigation -- something has to be walked toward -- but arrival is decided by reach.
 */
public class GetWithinReachOfBlockTask extends GetWithinRangeOfBlockTask {

    public GetWithinReachOfBlockTask(BlockPos blockPos, int range) {
        super(blockPos, range);
    }

    @Override
    public boolean isFinished() {
        return LookHelper.getReach(blockPos).isPresent();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetWithinReachOfBlockTask task
                && task.blockPos.equals(blockPos) && task.range == range;
    }

    @Override
    protected String toDebugString() {
        return "Getting within reach of " + blockPos.toShortString();
    }
}
