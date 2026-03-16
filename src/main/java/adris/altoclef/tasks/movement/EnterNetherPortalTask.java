package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.function.Predicate;

public class EnterNetherPortalTask extends Task {
    private final Task getPortalTask;
    private final Dimension targetDimension;

    private final TimerGame portalTimeout = new TimerGame(10);
    private final TimeoutWanderTask wanderTask = new TimeoutWanderTask(5);

    private final Predicate<BlockPos> goodPortal;

    private boolean leftPortal;

    public EnterNetherPortalTask(Task getPortalTask, Dimension targetDimension, Predicate<BlockPos> goodPortal) {
        if (targetDimension == Dimension.END)
            throw new IllegalArgumentException("Can't build a nether portal to the end.");
        this.getPortalTask = getPortalTask;
        this.targetDimension = targetDimension;
        this.goodPortal = goodPortal;
    }

    public EnterNetherPortalTask(Dimension targetDimension, Predicate<BlockPos> goodPortal) {
        this(null, targetDimension, goodPortal);
    }

    public EnterNetherPortalTask(Task getPortalTask, Dimension targetDimension) {
        this(getPortalTask, targetDimension, blockPos -> true);
    }

    public EnterNetherPortalTask(Dimension targetDimension) {
        this(null, targetDimension);
    }

    @Override
    protected void onStart() {
        leftPortal = false;
        portalTimeout.reset();
        wanderTask.resetWander();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (wanderTask.isActive() && !wanderTask.isFinished()) {
            setDebugState("Exiting portal for a bit.");
            portalTimeout.reset();
            leftPortal = true;
            return wanderTask;
        }

        if (mod.getWorld().getBlockState(mod.getPlayer().getBlockPos()).getBlock() == Blocks.NETHER_PORTAL) {

            if (portalTimeout.elapsed() && !leftPortal) {
                return wanderTask;
            }
            setDebugState("Waiting inside portal");
            mod.getClientBaritone().getExploreProcess().onLostControl();
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getMineProcess().onLostControl();
            mod.getClientBaritone().getFarmProcess().onLostControl();
            mod.getClientBaritone().getGetToBlockProcess();
            mod.getClientBaritone().getBuilderProcess();
            mod.getClientBaritone().getFollowProcess();
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
            return null;
        } else {
            portalTimeout.reset();
        }

        Predicate<BlockPos> standablePortal = blockPos -> {
            if (mod.getWorld().getBlockState(blockPos).getBlock() == Blocks.NETHER_PORTAL) {
                return goodPortal.test(blockPos);
            }
            // REQUIRE that there be solid ground beneath us, not more portal.
            if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                // Eh just assume it's good for now
                return goodPortal.test(blockPos);
            }
            BlockPos below = blockPos.down();
            boolean canStand = WorldHelper.isSolidBlock(below) && !mod.getBlockScanner().isBlockAtPosition(below, Blocks.NETHER_PORTAL);
            return canStand && goodPortal.test(blockPos);
        };

        if (mod.getBlockScanner().anyFound(standablePortal, Blocks.NETHER_PORTAL)) {
            setDebugState("Going to found portal");
            return new DoToClosestBlockTask(blockPos -> new GetToBlockTask(blockPos, false), standablePortal, Blocks.NETHER_PORTAL);
        }

        //this probably isn't needed here, the check should fail everytime
        
        if (!mod.getBlockScanner().anyFound(standablePortal, Blocks.NETHER_PORTAL)) {
            setDebugState("Making new nether portal.");
            if (WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
                return new ConstructNetherPortalBucketTask();
            } else {
                return new ConstructNetherPortalObsidianTask();
            }
        }
        setDebugState("Getting our portal");
        return getPortalTask;
    }

    @Override
    protected void onStop(Task interruptTask) {

    }

    @Override
    public boolean isFinished() {
        return WorldHelper.getCurrentDimension() == targetDimension;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof EnterNetherPortalTask task) {
            return (Objects.equals(task.getPortalTask, getPortalTask) && Objects.equals(task.targetDimension, targetDimension));
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Entering nether portal";
    }
}
