package adris.altoclef.tasks.slot;

import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.slots.Slot;

public class ThrowCursorTask extends Task {

    private final Task throwTask = new ClickSlotTask(Slot.UNDEFINED);

    @Override
    protected void onStart() {
    }

    @Override
    protected Task onTick() {
        return throwTask;
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof ThrowCursorTask;
    }

    @Override
    protected String toDebugString() {
        return "Throwing Cursor";
    }

    @Override
    public boolean isFinished() {
        return throwTask.isFinished();
    }
}
