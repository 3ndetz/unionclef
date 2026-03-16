package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.movement.GetCloseToBlockTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public class CollectHoneycombTask extends ResourceTask {
    private final boolean campfire;
    private final int count;
    private BlockPos nest;

    public CollectHoneycombTask(int targetCount) {
        super(Items.HONEYCOMB, targetCount);
        campfire = true;
        count = targetCount;
    }

    public CollectHoneycombTask(int targetCount, boolean useCampfire) {
        super(Items.HONEYCOMB, targetCount);
        campfire = useCampfire;
        count = targetCount;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (nest == null) {
            Optional<BlockPos> getNearestNest = mod.getBlockScanner().getNearestBlock(Blocks.BEE_NEST);
            if (getNearestNest.isPresent()) nest = getNearestNest.get();
        }
        // If we are STILL null
        if (nest == null) {
            if (campfire && !mod.getItemStorage().hasItemInventoryOnly(Items.CAMPFIRE)) {
                // May as well get a campfire
                setDebugState("Can't find nest, getting campfire first...");
                return new CataloguedResourceTask(new ItemTarget(Items.CAMPFIRE, 1));
            }
            setDebugState("Alright, we're searching");
            return new SearchChunkForBlockTask(Blocks.BEE_NEST);
        }
        if (campfire && !isCampfireUnderNest(mod, nest)) {
            if (!mod.getItemStorage().hasItemInventoryOnly(Items.CAMPFIRE)) {
                setDebugState("Getting a campfire");
                return new CataloguedResourceTask(new ItemTarget(Items.CAMPFIRE, 1));
            }
            setDebugState("Placing campfire");
            return new PlaceBlockTask(nest.down(2), Blocks.CAMPFIRE);
        }
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.SHEARS)) {
            setDebugState("Getting shears");
            return new CataloguedResourceTask(new ItemTarget(Items.SHEARS, 1));
        }
        if (mod.getWorld().getBlockState(nest).get(Properties.HONEY_LEVEL) != 5) {
            if (!nest.isWithinDistance(mod.getPlayer().getPos(), 20)) {
                setDebugState("Getting close to nest");
                return new GetCloseToBlockTask(nest);
            }
            setDebugState("Waiting for nest to get honey...");
            return null;
        }
        return new InteractWithBlockTask(Items.SHEARS, nest);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectHoneycombTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + count + " Honeycombs " + (campfire ? "Peacefully" : "Recklessly");
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    private boolean isCampfireUnderNest(AltoClef mod, BlockPos pos) {
        for (BlockPos underPos : WorldHelper.scanRegion(pos.down(6), pos.down())) {
            if (mod.getWorld().getBlockState(underPos).getBlock() == Blocks.CAMPFIRE)
                return true;
        }
        return false;
    }
}
