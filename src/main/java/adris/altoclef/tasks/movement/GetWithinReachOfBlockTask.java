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
/*
 * MEASURED AND NOT SHIPPED (2026-08-27). Kept because the exploration is worth more than
 * the code: four ways to approach a block that must be broken, and every one has a hole.
 *
 *   occupy the cell (flag off)     ladder steady 5 5 5 3 5, but 98.7% of the window on
 *                                  ONE unsatisfiable goal
 *   distance 3 (flag on)           SHUTTLING: 10 of 21 windows, ladder scattered 6 4 2 0 5
 *   distance 3 + reach completion  FREEZE: 85% dead, 2 rungs -- the body reaches the radius,
 *                                  the goal is satisfied, and reach never happens
 *   distance 1 + reach completion  the CLIENT DIES: craft 19/22 with three
 *                                  'tester1 stayed dead for 60s'
 *
 * None beats the baseline, so breakGoalIsReach stays off and this class is unused. What the
 * numbers actually say: the defect is not WHICH goal is used but that the miner asks the
 * navigator for a position at all, when what it needs is a line of sight. A real fix belongs
 * in move generation -- a goal that means 'somewhere I can see this block from' -- not in
 * swapping one radius for another.
 */
public class GetWithinReachOfBlockTask extends GetWithinRangeOfBlockTask {

    public GetWithinReachOfBlockTask(BlockPos blockPos, int range) {
        super(blockPos, range);
    }

    /**
     * KEEP CLOSING UNTIL IT CAN BE HIT.
     *
     * <p>Inheriting the range-3 goal was wrong and measured so at once: the bot walked to
     * three blocks, the navigation goal was satisfied, and there it stood -- 86% dead time
     * with 2 rungs, because arrival here is decided by reach and reach was never achieved.
     * That traded the operator's shuttle (0 shuttles, ratio 0.99 -- the shuttle really did
     * go) for a freeze, which is no bargain.
     *
     * <p>So the navigation goal pulls right up to the block while completion still waits
     * for reach. The body stops when it can strike, not when a radius says so.
     */
    @Override
    protected adris.altoclef.util.goals.AltoGoal newAltoGoal(AltoClef mod) {
        return adris.altoclef.util.goals.AltoGoal.near(blockPos, 1);
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
