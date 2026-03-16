package adris.altoclef.tasks.multiplayer.minigames;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.CombatTask;
import adris.altoclef.tasksystem.Task;

/**
 * Battle royale mode: tracks all players as potential targets and executes combat.
 */
public class BattleRoyaleTask extends Task {

    public BattleRoyaleTask() {
    }

    @Override
    protected void onStart() {
    }

    @Override
    protected Task onTick() {
        return new CombatTask();
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BattleRoyaleTask;
    }

    @Override
    protected String toDebugString() {
        return "Battle Royale Mode";
    }
}
