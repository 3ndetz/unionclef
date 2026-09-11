package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.goals.AltoGoal;
import adris.altoclef.util.helpers.LookHelper;
import net.minecraft.util.math.BlockPos;

/**
 * Walk -- or DIG -- until a block is within arm's reach. The approach a miner actually needs.
 *
 * <p>Three approaches to a block that must be broken were measured before this one and each has
 * a hole (see {@link GetWithinReachOfBlockTask}'s notes): occupying the cell is impossible while
 * the block stands; a distance radius is satisfied from behind an obstruction; reach-completion on
 * top of a distance goal freezes at the radius. The 2026-09-11 recorded playthrough named the
 * common cause (docs/BARITONE-GAPS.md G25): every one of them hands the navigator a POSITION the
 * walker cannot reach when the block is underground, so no engine that can dig is ever asked.
 *
 * <p>This task hands the navigator the BLOCK, with baritone's GoalGetToBlock semantics
 * ({@link AltoGoal.Adjacent}): the drive routes it straight to FastNavigator, whose planner
 * completes on any neighbouring cell and may break its way there (breakDown / breakThrough /
 * breakStair). Arrival is the adjacency test, or the miner's own: the block can be struck from
 * here.
 */
public class GetAdjacentToBlockTask extends CustomBaritoneGoalTask implements ITaskRequiresGrounded {

    private final BlockPos block;

    public GetAdjacentToBlockTask(BlockPos block) {
        this.block = block;
    }

    @Override
    protected Task onTick() {
        if (driveTungstenPrimary(AltoClef.getInstance())) return null;
        return super.onTick();
    }

    @Override
    protected AltoGoal newAltoGoal(AltoClef mod) {
        return AltoGoal.adjacent(block);
    }

    @Override
    public boolean isFinished() {
        return super.isFinished() || LookHelper.getReach(block).isPresent();
    }

    @Override
    protected void onWander(AltoClef mod) {
        super.onWander(mod);
        mod.getBlockScanner().requestBlockUnreachable(block);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetAdjacentToBlockTask task && task.block.equals(block);
    }

    @Override
    protected String toDebugString() {
        return "Getting within reach of " + block.toShortString() + " (dig allowed)";
    }
}
