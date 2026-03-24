package adris.altoclef.tasks.entity;

import adris.altoclef.tasksystem.Task;
import kaptainwutax.tungsten.task.PunkPlayerTask;

/**
 * Thin wrapper around tungsten's PunkPlayerTask so altoclef's task system
 * can track it and stop it via #stop.
 *
 * PunkPlayerTask ticks itself from MixinClientPlayerEntity —
 * this task just manages start/stop lifecycle.
 */
public class TungstenPunkTask extends Task {

    private final String playerName;

    public TungstenPunkTask(String playerName) {
        this.playerName = playerName;
    }

    @Override
    protected void onStart() {
        PunkPlayerTask.start(playerName);
    }

    @Override
    protected Task onTick() {
        if (!PunkPlayerTask.isActive()) {
            // target died, disconnected, or task ended externally
            return null;
        }
        setDebugState("Tungsten punk: " + playerName
                + " [" + (PunkPlayerTask.getTargetName() != null ? "tracking" : "searching") + "]");
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        PunkPlayerTask.stop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof TungstenPunkTask t && t.playerName.equals(playerName);
    }

    @Override
    protected String toDebugString() {
        return "Tungsten punk: " + playerName;
    }
}
