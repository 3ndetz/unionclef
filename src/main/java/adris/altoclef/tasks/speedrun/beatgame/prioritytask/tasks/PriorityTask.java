package adris.altoclef.tasks.speedrun.beatgame.prioritytask.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;

import java.util.function.Function;

public abstract class PriorityTask {

    private final Function<AltoClef, Boolean> canCall;
    private final boolean shouldForce;
    private final boolean canCache;

    //this can be used if we are sure we would want to end this task earlier than 3 seconds after being invoked
    public final boolean bypassForceCooldown;

    public PriorityTask(Function<AltoClef, Boolean> canCall, boolean shouldForce, boolean canCache, boolean bypassForceCooldown) {
        this.canCall = canCall;
        this.shouldForce = shouldForce;
        this.canCache = canCache;
        this.bypassForceCooldown = bypassForceCooldown;
    }

    public final double calculatePriority(AltoClef mod) {
        if (!canCall.apply(mod)) return Double.NEGATIVE_INFINITY;

        return getPriority(mod);
    }

    @Override
    public String toString() {
        return getDebugString();
    }

    public abstract Task getTask(AltoClef mod);

    public abstract String getDebugString();

    // maybe pass distance as well?
    protected abstract double getPriority(AltoClef mod);

    public boolean needCraftingOnStart(AltoClef mod) {
        return false;
    }

    public boolean shouldForce() {
        return shouldForce;
    }

    public boolean canCache() {
        return canCache;
    }
}
