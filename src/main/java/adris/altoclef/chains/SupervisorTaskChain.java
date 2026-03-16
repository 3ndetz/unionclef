package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.MLGBucketTask;
import adris.altoclef.tasks.movement.ThrowEnderPearlSimpleProjectileTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.time.TimerReal;

/**
 * Prioritized supervisor task chain — runs forced tasks at priority 51.
 */
@SuppressWarnings("ALL")
public class SupervisorTaskChain extends SingleTaskChain {

    public TimerReal _taskTimer = new TimerReal(30);
    private boolean _active = true;

    public SupervisorTaskChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        if (!mod.getUserTaskChain().isActive() && !this.isActive()) {
            mod.getTaskRunner().disable();
            mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
        }
    }

    @Override
    public float getPriority() {
        if (!AltoClef.inGame()) {
            return Float.NEGATIVE_INFINITY;
        }

        if (mainTask != null) {
            if (mainTask.stopped()
                    || mainTask.isFinished()
                    || _taskTimer.elapsed()) {
                setTask(null);
            } else {
                return 51f;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    public void runTask(AltoClef mod, Task task, double time) {
        if (task != null) {
            Debug.logMessage("[SUDO] Task set to: " + task.toString());
        }
        setTask(task);
        _taskTimer.setInterval(time);
        _taskTimer.reset();

        if (mod.getModSettings().failedToLoad()) {
            Debug.logWarning("Settings file failed to load. Check logs or delete to re-load.");
        }
    }

    public void runTask(AltoClef mod, Task task) {
        runTask(mod, task, 30);
    }

    @Override
    public void onInterrupt(TaskChain other) {
    }

    @Override
    public String getName() {
        return "Forced Task Chain";
    }

    @Override
    public boolean isActive() {
        return _active;
    }
}
