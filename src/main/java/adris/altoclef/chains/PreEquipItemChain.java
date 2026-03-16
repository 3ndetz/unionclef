package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.pathing.movement.Movement;
import baritone.utils.BlockStateInterface;

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

        // we will need to place or break some blocks, do not pre-equip anything...
        Optional<IPath> pathOptional = mod.getClientBaritone().getPathingBehavior().getPath();
        if (pathOptional.isEmpty()) return;

        IPath path = pathOptional.get();

        // should this really be created each tick?
        BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext());
        for (IMovement iMovement : path.movements()) {
            Movement movement = (Movement) iMovement;
            if (movement.toBreak(bsi).stream().anyMatch(pos -> mod.getWorld().getBlockState(pos).getBlock().getHardness() > 0)
                    || !movement.toPlace(bsi).isEmpty()) return;
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
