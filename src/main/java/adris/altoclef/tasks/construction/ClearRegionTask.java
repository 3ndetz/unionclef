package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;


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
        Baritone baritone = AltoClef.getInstance().getClientBaritone();

        if (!baritone.getBuilderProcess().isActive()) {
            baritone.getBuilderProcess().clearArea(_from, _to);
        }
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getClientBaritone().getBuilderProcess().onLostControl();
    }

    @Override
    public boolean isFinished() {
        int x = _from.getX() - _to.getX();
        int y = _from.getY() - _to.getY();
        int z = _from.getZ() - _to.getZ();
        for (int xx = 0; xx < Math.abs(x); ++xx) {
            for (int yy = 0; yy < Math.abs(y); ++yy) {
                for (int zz = 0; zz < Math.abs(z); ++zz) {
                    BlockPos toCheck = new BlockPos(_from).add(xx * -Integer.signum(x),yy * -Integer.signum(y),zz * -Integer.signum(z));
                    assert MinecraftClient.getInstance().world != null;
                    if (!MinecraftClient.getInstance().world.isAir(toCheck)) {
                        return false;
                    }
                }
            }
        }
        return true;
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
