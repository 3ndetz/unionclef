package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.resources.CollectBucketLiquidTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import kaptainwutax.tungsten.path.movements.Input;
import kaptainwutax.tungsten.path.movements.Rotation;
import kaptainwutax.tungsten.path.movements.RotationHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * Retain one filled water bucket and empty any extras without discarding buckets.
 */
public class GetRidOfExtraWaterBucketTask extends Task {
    private BlockPos emptyAgainst;
    private final TimeoutWanderTask findFace = new TimeoutWanderTask(5, true);

    @Override
    protected void onStart() {
        emptyAgainst = null;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Keep the one water bucket the caller needs. The saved native endpoint
        // already has one filled and one empty bucket, yet the old dump-all /
        // collect-one phase keeps clicking its source-water floor indefinitely.
        // Inventory is the completion witness; do not empty the retained bucket
        // merely to fill it again.
        int waterBuckets = mod.getItemStorage().getItemCount(Items.WATER_BUCKET);
        if (waterBuckets > 1) {
            if (emptyAgainst == null || !canEmptyAgainst(mod, emptyAgainst)) {
                emptyAgainst = null;
                int range = (int) Math.ceil(RotationHelper.blockReachDistance(mod.getPlayer()));
                BlockPos eyeCell = BlockPos.ofFloored(mod.getPlayer().getCameraPosVec(1.0F));
                for (BlockPos support : BlockPos.iterateOutwards(eyeCell, range, range, range)) {
                    if (canEmptyAgainst(mod, support)) {
                        emptyAgainst = support.toImmutable();
                        break;
                    }
                }
            }
            if (emptyAgainst == null) {
                setDebugState("Searching for a reachable solid face");
                return findFace;
            }
            setDebugState("Emptying extra water buckets");
            // BucketItem.placeFluid rejects non-air replacement while sneaking
            // with a hit result, including the native pocket's existing water.
            // Any admitted face is suitable; the interaction still waits for an
            // actual live hit and equips the filled bucket before pressing use.
            return new InteractWithBlockTask(new ItemTarget(Items.WATER_BUCKET, 1),
                    null, emptyAgainst, Input.CLICK_RIGHT, false, false);
        }

        if (waterBuckets == 0) {
            return new CollectBucketLiquidTask.CollectWaterBucketTask(1);
        }

        return null;
    }

    private boolean canEmptyAgainst(AltoClef mod, BlockPos support) {
        if (!mod.getChunkTracker().isChunkLoaded(support)) return false;
        BlockState supportState = mod.getWorld().getBlockState(support);
        // Normal use must reach BucketItem.use. Vanilla NoteBlock.onUse consumes
        // it by tuning the note; RespawnAnchorBlock.onUse can consume it or
        // explode outside the Nether. Neither has a block entity, and the
        // shared interaction list omits both. Keep this admission task-local:
        // Baritone MovementHelper.java:841-855 can sneak for ordinary placement,
        // but a water bucket cannot replace existing water while sneaking.
        // The isolated support trace witnesses repeated note changes with both
        // water buckets retained; do not mistake successful use for emptying.
        if (supportState.getBlock() instanceof NoteBlock
                || supportState.getBlock() instanceof RespawnAnchorBlock
                || !WorldHelper.canPlaceAgainst(support)
                || WorldHelper.isInteractableBlock(support)
                || mod.getWorld().getBlockEntity(support) != null) {
            return false;
        }
        Optional<Rotation> reach = LookHelper.getReach(support);
        if (reach.isEmpty()) return false;
        // Baritone MovementHelper.java:806-859 admits supported placement only
        // after a real proposed ray hits that support and names the destination.
        // A filled bucket's vanilla ray ignores water: the liquid below our feet
        // cannot be the click target, even if adjacency has already completed.
        HitResult ray = RotationHelper.rayTraceTowards(mod.getPlayer(), reach.get(),
                RotationHelper.blockReachDistance(mod.getPlayer()));
        if (!(ray instanceof BlockHitResult hit) || ray.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(support)) {
            return false;
        }
        BlockPos destination = support.offset(hit.getSide());
        if (!mod.getChunkTracker().isChunkLoaded(destination)
                || !mod.getWorld().getWorldBorder().contains(destination)
                || !WorldHelper.canPlace(destination)) {
            return false;
        }
        BlockState state = mod.getWorld().getBlockState(destination);
        // Empty into air/replaceable terrain or water, never consume a lava source.
        return (state.getFluidState().isEmpty() || state.getFluidState().isOf(Fluids.WATER))
                && (state.isAir() || state.canBucketPlace(Fluids.WATER));
    }

    @Override
    public boolean isFinished() {
        return AltoClef.getInstance().getItemStorage().getItemCount(Items.WATER_BUCKET) == 1;
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetRidOfExtraWaterBucketTask;
    }

    @Override
    protected String toDebugString() {
        return "Keep one water bucket";
    }
}
