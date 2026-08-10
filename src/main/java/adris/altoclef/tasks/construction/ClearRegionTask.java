package adris.altoclef.tasks.construction;

import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;


/**
 * Break every block in an inclusive box.
 *
 * <p>G-0a: this used to hand the box to baritone's {@code BuilderProcess.clearArea} and poll it.
 * It now walks the box itself and mines one cell at a time with {@link DestroyBlockTask}, which is
 * the machinery altoclef already uses everywhere else it breaks a specific block — approach,
 * tool selection, progress checking and all. Slower than a batch builder on a large region, and
 * that is the honest trade: nothing in this repo calls this task with a large region, or with any
 * region at all (see the note on onTick).
 */
public class ClearRegionTask extends Task implements ITaskRequiresGrounded {

    private final BlockPos _from;
    private final BlockPos _to;

    // TODO: Progress checkers in the event of a failure.
    // Progress checker 1 for movement
    // Progress checker 2 for if block breaking isn't happening
    // Make it an "and", as in both MUST fail for a failure to count.

    public ClearRegionTask(BlockPos from, BlockPos to) {
        _from = from;
        _to = to;
    }

    @Override
    protected void onStart() {

    }

    @Override
    protected Task onTick() {
        // NOTHING IN THE REPO CALLS THIS TASK — checked across every module, including the
        // commands, the py4j surface and the MCP tool list. It is kept rather than deleted
        // because "clear this box" is a lever an agent will want and the capability is three
        // lines once the box walk exists; it is NOT kept as evidence that the port works. The
        // first caller should expect to be its first test.
        BlockPos next = firstNonAir();
        if (next == null) return null;
        setDebugState("Clearing " + next.toShortString());
        return new DestroyBlockTask(next);
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    public boolean isFinished() {
        return firstNonAir() == null;
    }

    /**
     * First non-air cell of the box, or null when the box is clear.
     *
     * <p>ONE walk shared by the tick and the finish test, so they cannot disagree about which
     * cells are in the box. The version this replaces had two different opinions and both were
     * wrong the same way: {@code for (xx = 0; xx < Math.abs(from.x - to.x); ++xx)} excludes the
     * far plane, and collapses to zero iterations on any axis where from == to — so a flat or
     * single-column region reported "finished" without checking a single block.
     */
    private BlockPos firstNonAir() {
        World world = MinecraftClient.getInstance().world;
        if (world == null) return null;
        for (BlockPos p : BlockPos.iterate(_from, _to)) {
            if (!world.isAir(p)) return p.toImmutable();
        }
        return null;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ClearRegionTask) {
            ClearRegionTask task = (ClearRegionTask) other;
            return (task._from.equals(_from) && task._to.equals(_to));
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Clear region from " + _from.toShortString() + " to " + _to.toShortString();
    }
}
