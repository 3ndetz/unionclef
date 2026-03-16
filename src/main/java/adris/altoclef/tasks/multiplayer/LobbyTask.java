package adris.altoclef.tasks.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.agent.Pipeline;

public class LobbyTask extends Task {
    public boolean _clicked = false;
    public boolean _joined = false;
    public Pipeline _pipeline;

    public LobbyTask() {
        this(AltoClef.getPipeline());
    }

    public LobbyTask(Pipeline pipeline) {
        _pipeline = pipeline;
    }

    @Override
    protected void onStart() {
    }

    @Override
    protected Task onTick() {
        this._joined = true;
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof LobbyTask;
    }

    @Override
    protected String toDebugString() {
        return "Server chest menu click handler";
    }

    @Override
    public boolean isFinished() {
        return this._joined;
    }
}
