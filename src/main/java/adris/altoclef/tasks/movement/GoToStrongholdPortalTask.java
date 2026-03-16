package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;


public class GoToStrongholdPortalTask extends Task {

    private LocateStrongholdCoordinatesTask _locateCoordsTask;
    private final int _targetEyes;
    private final int MINIMUM_EYES = 12;
    private BlockPos _strongholdCoordinates;

    public GoToStrongholdPortalTask(int targetEyes) {
        _targetEyes = targetEyes;
        _strongholdCoordinates = null;
        _locateCoordsTask = new LocateStrongholdCoordinatesTask(targetEyes);
    }

    @Override
    protected void onStart() {

    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        /*
            If we don't know where stronghold is, find out where stronghold is.
            If we do know where stronghold is, fast travel there
            If there search it
         */
        if (_strongholdCoordinates == null) {
            // in case any screen is open, prevents from getting stuck
            StorageHelper.closeScreen();

            _strongholdCoordinates = _locateCoordsTask.getStrongholdCoordinates().orElse(null);
            if (_strongholdCoordinates == null) {
                if (mod.getItemStorage().getItemCount(Items.ENDER_EYE) < MINIMUM_EYES && mod.getEntityTracker().itemDropped(Items.ENDER_EYE)) {
                    setDebugState("Picking up dropped eye");
                    return new PickupDroppedItemTask(Items.ENDER_EYE, MINIMUM_EYES);
                }
                setDebugState("Triangulating stronghold...");
                return _locateCoordsTask;
            }
        }

        if (mod.getPlayer().getPos().distanceTo(WorldHelper.toVec3d(_strongholdCoordinates)) < 10 && !mod.getBlockScanner().anyFound(Blocks.END_PORTAL_FRAME)) {
            mod.log("Something went wrong whilst triangulating the stronghold... either the action got disrupted or the second eye went to a different stronghold");
            mod.log("We will try to triangulate again now...");
            _strongholdCoordinates = null;
            _locateCoordsTask = new LocateStrongholdCoordinatesTask(_targetEyes);
            return null;
        }
        // Search stone brick chunks, but while we're wandering, go to the nether
        setDebugState("Searching for Stronghold...");
        /*return new SearchChunkForBlockTask(Blocks.STONE_BRICKS) {
            @Override
            protected Task onTick(AltoClef mod) {
                if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
                    return getWanderTask(mod);
                }
                return super.onTick(mod);
            }

            @Override
            protected Task getWanderTask(AltoClef mod) {
                return new FastTravelTask(_strongholdCoordinates, 300, true);
            }
        };*/
        return new FastTravelTask(_strongholdCoordinates, 300, true);
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GoToStrongholdPortalTask;
    }

    @Override
    protected String toDebugString() {
        return "Locating Stronghold";
    }
}