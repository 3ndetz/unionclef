package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;

import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.path.PathExecutor;

public class PreEquipItemChain extends SingleTaskChain {


    public PreEquipItemChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {

    }

    @Override
    public float getPriority() {
        update(AltoClef.getInstance());

        // we don't care about overtaking... just pre-equip items in the background
        return -1;
    }

    private void update(AltoClef mod) {
        if (mod.getFoodChain().isTryingToEat()) return;

        TaskChain currentChain = mod.getTaskRunner().getCurrentTaskChain();
        if (currentChain == null) return;

        // The movement queue and executor own separate block-work plans. FastNavigator
        // hands digs directly to the executor, so an empty movement queue does not mean
        // the hand is free. Switching to a sword here resets vanilla mining progress.
        PathExecutor executor = TungstenModDataContainer.EXECUTOR;
        if (TungstenModDataContainer.builderOwnsInputs()
                || (executor != null && executor.isBreakingNow())
                || mod.getControllerExtras().isBreakingBlock()
                || kaptainwutax.tungsten.path.movements.MovementQueue.remainingNeedsBlockWork(mod.getWorld())) {
            return;
        }

        // we are *probably* trying to kill sth, might as well equip sword
        if (currentChain.getTasks().stream().anyMatch(task -> task instanceof AbstractKillEntityTask)) {
            AbstractKillEntityTask.equipWeapon(mod);
        }

    }

    @Override
    public String getName() {
        return "pre-equip item chain";
    }

    @Override
    public boolean isActive() {
        return true;
    }
}
