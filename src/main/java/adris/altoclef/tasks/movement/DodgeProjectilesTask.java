package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalDodgeProjectiles;
import baritone.api.pathing.goals.Goal;

public class DodgeProjectilesTask extends CustomBaritoneGoalTask {

    private final double _distanceHorizontal;
    private final double _distanceVertical;
    /** Max time (ms) before dodge is considered done — prevents blocking other tasks. */
    private static final long DODGE_TIMEOUT_MS = 5000;
    private long startTime;

    public DodgeProjectilesTask(double distanceHorizontal, double distanceVertical) {
        _distanceHorizontal = distanceHorizontal;
        _distanceVertical = distanceVertical;
    }

    @Override
    protected void onStart() {
        super.onStart();
        startTime = System.currentTimeMillis();
    }

    @Override
    protected Task onTick() {
        if (cachedGoal != null) {
            GoalDodgeProjectiles goal = (GoalDodgeProjectiles) cachedGoal;
        }
        return super.onTick();
    }

    @Override
    public boolean isFinished() {
        // Timeout: don't block other tasks forever
        if (System.currentTimeMillis() - startTime > DODGE_TIMEOUT_MS) return true;
        return super.isFinished();
    }

    @SuppressWarnings("RedundantIfStatement")
    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DodgeProjectilesTask task) {
            if (Math.abs(task._distanceHorizontal - _distanceHorizontal) > 1) return false;
            if (Math.abs(task._distanceVertical - _distanceVertical) > 1) return false;
            return true;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Dodge arrows at " + _distanceHorizontal + " blocks away";
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalDodgeProjectiles(mod, _distanceHorizontal, _distanceVertical);
    }
}
