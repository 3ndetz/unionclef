package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.PlaceObsidianFloodTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;

import java.util.function.Predicate;

/**
 * Collects obsidian. In the overworld it MAKES obsidian by flooding an existing lava lake with one
 * water source ({@link PlaceObsidianFloodTask}) and then mines the result; in the nether it trades
 * with piglins (no water there). Any obsidian already in the world (from a flood, or naturally) is
 * mined directly.
 *
 * <p>⛔ 2026-09-18 (G108): this used to gather obsidian by casting each block with a lava bucket in a
 * 10-block mould ({@link adris.altoclef.tasks.construction.PlaceObsidianBucketTask}), which was the
 * shared fragility of the whole nether-portal build -- fragile mid-air / in dirtied terrain, with a
 * reactive wander as its only failure response. Flooding a lake removes the mould entirely and makes
 * many obsidian per water placement. See {@link PlaceObsidianFloodTask}.
 */
public class CollectObsidianTask extends ResourceTask {

    private final int _count;

    private PlaceObsidianFloodTask _floodTask;

    public CollectObsidianTask(int count) {
        super(Items.OBSIDIAN, count);
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        // Only source blocks fill a bucket; ray-trace to sources for any bucket interaction.
        mod.getBehaviour().setRayTracingFluidHandling(RaycastContext.FluidHandling.SOURCE_ONLY);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // Obsidian needs a diamond pickaxe to mine, so get one first.
        if (!StorageHelper.miningRequirementMet(MiningRequirement.DIAMOND)) {
            setDebugState("Getting diamond pickaxe first");
            return new SatisfyMiningRequirementTask(MiningRequirement.DIAMOND);
        }

        // Hold an in-flight flood cycle (place water -> convert -> reclaim) to completion, so we never
        // strand a placed water source by jumping to mining the moment the first obsidian appears.
        boolean floodBusy = _floodTask != null && _floodTask.isActive() && !_floodTask.isFinished();

        // Mine obsidian that already exists (from a flood, or found naturally) or was dropped.
        Predicate<BlockPos> goodObsidian = (blockPos ->
                blockPos.isWithinDistance(mod.getPlayer().getPos(), 800)
                        && WorldHelper.canBreak(blockPos));
        if (!floodBusy && (mod.getBlockScanner().anyFound(goodObsidian, Blocks.OBSIDIAN)
                || mod.getEntityTracker().itemDropped(Items.OBSIDIAN))) {
            setDebugState("Mining/Collecting obsidian");
            _floodTask = null;
            return new MineAndCollectTask(new ItemTarget(Items.OBSIDIAN, _count),
                    new Block[]{Blocks.OBSIDIAN}, MiningRequirement.DIAMOND);
        }

        // No water in the nether -> trade with piglins for obsidian instead.
        if (WorldHelper.getCurrentDimension() == Dimension.NETHER) {
            final double AVERAGE_GOLD_PER_OBSIDIAN = 11.475;
            int gold_buffer = (int) (AVERAGE_GOLD_PER_OBSIDIAN * _count);
            setDebugState("We can't place water, so we're trading for obsidian");
            return new TradeWithPiglinsTask(gold_buffer, Items.OBSIDIAN, _count);
        }

        // Overworld: make obsidian by flooding a lava lake.
        if (_floodTask == null || _floodTask.isFinished()) {
            _floodTask = new PlaceObsidianFloodTask();
        }
        setDebugState("Making obsidian by flooding lava");
        return _floodTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectObsidianTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " blocks of obsidian";
    }
}
