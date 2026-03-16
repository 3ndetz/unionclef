package adris.altoclef.tasks.examples;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

public class ExampleTask extends Task {

    private final int numberOfStonePickaxesToGrab;
    private final BlockPos whereToPlaceCobblestone;

    public ExampleTask(int numberOfStonePickaxesToGrab, BlockPos whereToPlaceCobblestone) {
        this.numberOfStonePickaxesToGrab = numberOfStonePickaxesToGrab;
        this.whereToPlaceCobblestone = whereToPlaceCobblestone;
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();

        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(Items.COBBLESTONE);
    }

    @Override
    protected Task onTick() {

        /*
         * Grab X stone pickaxes
         * Make sure we have a block
         * Then, place the block.
         */
        AltoClef mod = AltoClef.getInstance();

        if (mod.getItemStorage().getItemCount(Items.STONE_PICKAXE) < numberOfStonePickaxesToGrab) {
            return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, numberOfStonePickaxesToGrab);
        }

        if (!mod.getItemStorage().hasItem(Items.COBBLESTONE)) {
            return TaskCatalogue.getItemTask(Items.COBBLESTONE, 1);
        }

        if (mod.getChunkTracker().isChunkLoaded(whereToPlaceCobblestone)) {
            if (mod.getWorld().getBlockState(whereToPlaceCobblestone).getBlock() != Blocks.COBBLESTONE) {
                return new PlaceBlockTask(whereToPlaceCobblestone, Blocks.COBBLESTONE); ///new PlaceStructureBlockTask(_whereToPlaceCobblestone);
            }
            return null;
        } else {
            return new GetToBlockTask(whereToPlaceCobblestone);
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getBehaviour().pop();
    }

    @Override
    public boolean isFinished() {
        AltoClef mod = AltoClef.getInstance();

        return mod.getItemStorage().getItemCount(Items.STONE_PICKAXE) >= numberOfStonePickaxesToGrab &&
                mod.getWorld().getBlockState(whereToPlaceCobblestone).getBlock() == Blocks.COBBLESTONE;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ExampleTask task) {
            return task.numberOfStonePickaxesToGrab == numberOfStonePickaxesToGrab
                    && task.whereToPlaceCobblestone.equals(whereToPlaceCobblestone);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Boofin";
    }
}
