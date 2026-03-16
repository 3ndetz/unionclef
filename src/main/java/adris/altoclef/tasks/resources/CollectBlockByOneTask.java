package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.multiversion.versionedfields.Items;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.MiningRequirement;
import net.minecraft.block.Block;
import net.minecraft.item.Item;

import java.util.Arrays;

public class CollectBlockByOneTask extends ResourceTask {

    private final Item item;
    private final Block[] blocks;
    private final MiningRequirement requirement;
    private final int count;

    public CollectBlockByOneTask(Item item, Block[] blocks, MiningRequirement requirement, int targetCount) {
        super(item, targetCount);
        this.item = item;
        this.blocks = blocks;
        this.requirement = requirement;
        count = targetCount;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        return new MineAndCollectTask(item, 1, blocks, requirement);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectBlockByOneTask task) {
            return task.count == count && task.item.equals(item) && Arrays.stream(task.blocks).allMatch(block -> Arrays.stream(blocks).toList().contains(block));
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect "+item;
    }


    public static class CollectCobblestoneTask extends CollectBlockByOneTask {

        public CollectCobblestoneTask(int targetCount) {
            super(Items.COBBLESTONE, new Block[]{Blocks.STONE, Blocks.COBBLESTONE}, MiningRequirement.WOOD, targetCount);
        }
    }

    public static class CollectCobbledDeepslateTask extends CollectBlockByOneTask {

        public CollectCobbledDeepslateTask(int targetCount) {
            super(Items.COBBLED_DEEPSLATE, new Block[]{Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE}, MiningRequirement.WOOD, targetCount);
        }
    }

    public static class CollectEndStoneTask extends CollectBlockByOneTask {

        public CollectEndStoneTask(int targetCount) {
            super(Items.END_STONE, new Block[]{Blocks.END_STONE}, MiningRequirement.WOOD, targetCount);
        }
    }

}
