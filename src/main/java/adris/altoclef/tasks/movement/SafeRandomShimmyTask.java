package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.Baritone;
import kaptainwutax.tungsten.path.movements.Input;

/**
 * Will move around randomly while holding shift
 * Used to escape weird situations where baritone doesn't work.
 */
public class SafeRandomShimmyTask extends Task {

    private final TimerGame _lookTimer;

    public SafeRandomShimmyTask(float randomLookInterval) {
        _lookTimer = new TimerGame(randomLookInterval);
    }

    public SafeRandomShimmyTask() {
        this(5);
    }

    @Override
    protected void onStart() {
        _lookTimer.reset();
    }

    @Override
    protected Task onTick() {

        if (_lookTimer.elapsed()) {
            Debug.logMessage("Random Orientation");
            _lookTimer.reset();
            LookHelper.randomOrientation();
        }

        Baritone baritone = AltoClef.getInstance().getClientBaritone();

        AltoClef.getInstance().getInputControls().hold(Input.SNEAK);
        AltoClef.getInstance().getInputControls().hold(Input.MOVE_FORWARD);
        AltoClef.getInstance().getInputControls().hold(Input.CLICK_LEFT);
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        Baritone baritone = AltoClef.getInstance().getClientBaritone();

        AltoClef.getInstance().getInputControls().release(Input.MOVE_FORWARD);
        AltoClef.getInstance().getInputControls().release(Input.SNEAK);
        AltoClef.getInstance().getInputControls().release(Input.CLICK_LEFT);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SafeRandomShimmyTask;
    }

    @Override
    protected String toDebugString() {
        return "Shimmying";
    }
}
