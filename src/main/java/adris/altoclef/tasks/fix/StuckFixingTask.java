package adris.altoclef.tasks.fix;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasksystem.Task;

import java.util.concurrent.ThreadLocalRandom;

public class StuckFixingTask extends Task {
    private int x = 0;
    private int z = 0;

    public StuckFixingTask() {
        x = ThreadLocalRandom.current().nextInt(-30, 31);
        z = ThreadLocalRandom.current().nextInt(-30, 31);
        Debug.logMessage("[STUCK FIX] STUCK FIXING; COORDS: x=" + x + ";z=" + z);
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
    }

    @Override
    protected Task onTick() {
        return new GetToXZTask(x, z);
    }

    @Override
    public boolean isFinished() {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getPlayer() == null) return false;
        var cur = mod.getPlayer().getBlockPos();
        return cur.getX() == x && cur.getZ() == z;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        if (mod != null) mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return false;
    }

    @Override
    protected String toDebugString() {
        return "StuckFix: moving to " + x + "," + z;
    }
}
