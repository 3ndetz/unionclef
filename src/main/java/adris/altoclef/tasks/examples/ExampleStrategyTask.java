package adris.altoclef.tasks.examples;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.misc.PositionWrapper;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasks.misc.ChooseStrategyTask;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ExampleStrategyTask extends Task {

    public ExampleStrategyTask() {
    }

    // Define your strategy enum
    enum Strategy {
        LOOT_CHEST,
        KILL_PLAYER,
        DIAMONDS,
        SOME_STRATEGY
    }


    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        // BlockScanner doesn't need trackBlock - scanning is automatic
        mod.getBehaviour().addProtectedItems(Items.COBBLESTONE);
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        // Create strategy map
        Map<Strategy, PositionWrapper> strategyMap = new HashMap<>();
        Vec3d pos = mod.getPlayer().getPos();
        Optional<Entity> ply = mod.getEntityTracker().getClosestEntity(
                mod.getPlayer().getPos(),
                toPunk -> toPunk.distanceTo(mod.getPlayer()) < 50,
                PlayerEntity.class, ZombieEntity.class, PigEntity.class

        );

        Optional<BlockPos> cont = mod.getBlockScanner().getNearestBlock(
                blockPos -> WorldHelper.isUnopenedChest(blockPos) &&
                        mod.getPlayer().getBlockPos().isWithinDistance(blockPos, 50), Blocks.CHEST);
        Optional<BlockPos> ore = mod.getBlockScanner().getNearestBlock(
                blockPos -> mod.getPlayer().getBlockPos().isWithinDistance(blockPos, 50) &&
                        WorldHelper.canReach(blockPos), Blocks.DIAMOND_ORE);
        // Debug.logMessage("cont "+ cont.orElse(null));
        strategyMap.put(Strategy.LOOT_CHEST, PositionWrapper.ofBlockPos(cont.orElse(null),
            (mod_next, p) -> WorldHelper.isUnopenedChest(p)));
        strategyMap.put(Strategy.DIAMONDS, PositionWrapper.ofBlockPos(ore.orElse(null),
            (mod_next, p) -> !WorldHelper.isAir(p)));
        strategyMap.put(Strategy.KILL_PLAYER, PositionWrapper.ofEntity(ply.orElse(null),
            (mod_next, e) -> e != null && e.isAlive() && e.isInRange(mod_next.getPlayer(), 50)));
// Can also put empty positions:
        //strategyMap.put(Strategy.SOME_STRATEGY, PositionWrapper.empty());

        // Create the task
        return new ChooseStrategyTask<>(
                strategy -> {
                    // Your strategy handler
                    switch (strategy) {
                        case LOOT_CHEST:
                            setDebugState("LOOT_CHEST");
                            return new InteractWithBlockTask(cont.orElse(null));
                        case KILL_PLAYER:
                            setDebugState("KILL_PLAYER");
                            return new KillEntityTask(ply.orElse(null));
                        case DIAMONDS:
                            setDebugState("Diamonds");
                            return new DestroyBlockTask(ore.orElse(null));
                        default: {
                            setDebugState("No strategy");
                            return null;
                        }
                    }
                },
                strategyMap
        );

        //return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ExampleStrategyTask;
    }

    @Override
    protected String toDebugString() {
        return "ExampleStrategyTask";
    }

}
