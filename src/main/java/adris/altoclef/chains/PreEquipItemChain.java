package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;

import java.util.Optional;

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

        // ASK THE ENGINE THAT IS ACTUALLY DRIVING.
        // This read BARITONE's current path and returned on its first line when there wasn't one.
        // Tungsten drives, so there never is one: the whole chain has been silently dead since the
        // swap, and "equip the sword while walking to a fight" simply stopped happening.
        // The question it asks is engine-independent -- does the route ahead need blocks broken or
        // placed, because then the hand belongs to a tool rather than a weapon -- so the queue
        // answers it now.
        if (kaptainwutax.tungsten.path.movements.MovementQueue.remainingNeedsBlockWork(mod.getWorld())) {
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
