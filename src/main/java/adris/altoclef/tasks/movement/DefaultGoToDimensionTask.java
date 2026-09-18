package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * Some generic tasks require us to go to the nether/overworld/end.
 * <p>
 * The user should be able to specify how this should be done in settings
 * (ex, craft a new portal from scratch or check particular portal areas first or highway or whatever)
 */
public class DefaultGoToDimensionTask extends Task {

    private final Dimension _target;
    // Cached to keep build properties alive if this task pauses/resumes.
    private final Task _cachedNetherBucketConstructionTask = new ConstructNetherPortalBucketTask();
    private final Task _cachedNetherObsidianConstructionTask = new ConstructNetherPortalObsidianTask();

    public DefaultGoToDimensionTask(Dimension target) {
        _target = target;
    }

    @Override
    protected void onStart() {

    }

    @Override
    protected Task onTick() {
        if (WorldHelper.getCurrentDimension() == _target) return null;

        switch (_target) {
            case OVERWORLD:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        return goToOverworldFromNetherTask();
                    case END:
                        return goToOverworldFromEndTask();
                }
                break;
            case NETHER:
                switch (WorldHelper.getCurrentDimension()) {
                    case OVERWORLD:
                        return goToNetherFromOverworldTask();
                    case END:
                        // First go to the overworld
                        return goToOverworldFromEndTask();
                }
                break;
            case END:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        // First go to the overworld
                        return goToOverworldFromNetherTask();
                    case OVERWORLD:
                        return goToEndTask();
                }
                break;
        }

        setDebugState(WorldHelper.getCurrentDimension() + " -> " + _target + " is NOT IMPLEMENTED YET!");
        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DefaultGoToDimensionTask task) {
            return task._target == _target;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Going to dimension: " + _target + " (default version)";
    }

    @Override
    public boolean isFinished() {
        return WorldHelper.getCurrentDimension() == _target;
    }

    private Task goToOverworldFromNetherTask() {
        AltoClef mod = AltoClef.getInstance();

        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }

        Optional<BlockPos> closest = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER);
        if (closest.isPresent()) {
            setDebugState("Going to last nether portal pos");
            return new GetToBlockTask(closest.get());
        }

        setDebugState("Constructing nether portal with obsidian");
        return new ConstructNetherPortalObsidianTask();
    }

    private Task goToOverworldFromEndTask() {
        setDebugState("TODO: Go to center portal (at 0,0). If it doesn't exist, kill ender dragon lol");
        return null;
    }

    private Task goToNetherFromOverworldTask() {
        AltoClef mod = AltoClef.getInstance();

        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }
        return switch (mod.getModSettings().getOverworldToNetherBehaviour()) {
            // ⛔ PLACE OBSIDIAN, DON'T CAST IT IN PLACE (G108, benched 2026-09-18). The bucket
            // cast (ConstructNetherPortalBucketTask) forms each frame block where it stands by
            // building a mould + lava + water; that works at ground level but STALLS on the upper
            // (mid-air) frame -- the mould cannot be built in the air, PlaceObsidianBucketTask's
            // progress check trips, and it drops into TimeoutWanderTask(5) forever ("Wander for 5.0
            // blocks", portal never completes). The obsidian method casts obsidian at GROUND level
            // (the easy case), mines it with the diamond pickaxe the bot has by the nether stage,
            // then PLACES the frame blocks -- a normal mid-air block place (PlaceStructureBlockTask
            // builds its own scaffold), which is reliable. Bench A/B on a 5x5 lava lake: bucket
            // FAILs (no portal in 180 s), obsidian PASSes ("Done constructing nether portal" in
            // ~139 s). So prefer obsidian whenever the bot can mine it; keep the bucket cast as the
            // fallback for the no-diamond-pickaxe case (early, before this stage).
            case BUILD_PORTAL_VANILLA -> mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE)
                    ? _cachedNetherObsidianConstructionTask
                    : _cachedNetherBucketConstructionTask;
            case GO_TO_HOME_BASE -> new GetToBlockTask(mod.getModSettings().getHomeBasePosition());
        };
    }

    private Task goToEndTask() {
        // Keep in mind that getting to the end requires going to the nether first.
        setDebugState("TODO: Get to End, Same as BeatMinecraft");
        return null;
    }

    private boolean netherPortalIsClose(AltoClef mod) {
        if (mod.getBlockScanner().anyFound(Blocks.NETHER_PORTAL)) {
            Optional<BlockPos> closest = mod.getBlockScanner().getNearestBlock( Blocks.NETHER_PORTAL);
            return closest.isPresent() && closest.get().isWithinDistance(mod.getPlayer().getPos(), 2000);
        }
        return false;
    }

    public enum OVERWORLD_TO_NETHER_BEHAVIOUR {
        BUILD_PORTAL_VANILLA,
        GO_TO_HOME_BASE
    }
}
