package adris.altoclef.tasks.movement;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.Optional;

public class GetToOuterEndIslandsTask extends Task {
    public final int END_ISLAND_START_RADIUS = 800;
    public final Vec3i[] OFFSETS = {
            new Vec3i(1, -1, 1),
            new Vec3i(1, -1, -1),
            new Vec3i(-1, -1, 1),
            new Vec3i(-1, -1, -1),
            new Vec3i(2, -1, 0),
            new Vec3i(0, -1, 2),
            new Vec3i(-2, -1, 0),
            new Vec3i(0, -1, -2)
    };
    private Task _beatTheGame;

    public GetToOuterEndIslandsTask() {

    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        // BlockScanner doesn't need trackBlock - scanning is automatic
        _beatTheGame = new BeatMinecraft2Task();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        if (mod.getBlockScanner().anyFound(Blocks.END_GATEWAY)) {
            if (!mod.getItemStorage().hasItemInventoryOnly(Items.ENDER_PEARL)) {
                setDebugState("Getting an ender pearl");
                return new CataloguedResourceTask(new ItemTarget(Items.ENDER_PEARL, 1));
            }
            Optional<BlockPos> gatewayOpt = mod.getBlockScanner().getNearestBlock(Blocks.END_GATEWAY);
            if (!gatewayOpt.isPresent()) {
                setDebugState("Waiting for END_GATEWAY position...");
                return null;
            }
            BlockPos gateway = gatewayOpt.get();
            int blocksNeeded = Math.abs(mod.getPlayer().getBlockY() - gateway.getY()) +
                    Math.abs(mod.getPlayer().getBlockX() - gateway.getX()) +
                    Math.abs(mod.getPlayer().getBlockZ() - gateway.getZ()) - 3;
            if (StorageHelper.getBuildingMaterialCount() < blocksNeeded) {
                setDebugState("Getting building materials");
                return new GetBuildingMaterialsTask(blocksNeeded);
            }
            // THE APPROACH USED TO GO TO THE LEGACY ENGINE, AND IT ARRIVED ONE RUN IN THREE.
            // setGoal(goal) + path() on getCustomGoalProcess is the same hand-off that left
            // InteractWithBlockTask standing still. Here it is not dead, it is UNRELIABLE, which is
            // worse to diagnose: measured on end_gateway with a closest-approach counter, the bot
            // reached the gateway once and stalled eight to twelve blocks short twice
            // (closest = 2.2 / 7.7 / 12.3 over three runs).
            //
            // The goal it built was GoalAnd(GoalComposite(eight cells beside the gateway),
            // GoalYLevel(74)) -- "stand on one of these eight AND be at y=74". Beside, not on: you
            // stand next to a gateway and throw the pearl in, because stepping into one teleports
            // you. Expressed directly that is just "walk to the nearest of eight cells", which the
            // live drive can steer at without a pathfinder type in the middle.
            //
            // The y=74 term is dropped deliberately. It is a hardcoded REAL-End gateway height, and
            // the eight cells already sit at gateway.y-1: if a gateway is at any other height the
            // AND could never be satisfied at all. Using the cells alone is strictly more correct.
            BlockPos approach = null;
            double approachDist = Double.POSITIVE_INFINITY;
            boolean standingOnACell = false;
            for (Vec3i off : OFFSETS) {
                BlockPos cell = gateway.add(off);
                if (cell.equals(mod.getPlayer().getBlockPos())) standingOnACell = true;
                double d = cell.getSquaredDistance(mod.getPlayer().getPos());
                if (d < approachDist) {
                    approachDist = d;
                    approach = cell;
                }
            }
            if ((!standingOnACell || !mod.getPlayer().isOnGround()) && approach != null) {
                setDebugState("Getting close to gateway...");
                return new GetToBlockTask(approach);
            }
            setDebugState("Throwing the pearl inside");
            return new InteractWithBlockTask(Items.ENDER_PEARL, gateway);
        }
        setDebugState("Beating the Game to get to an end gateway");
        return _beatTheGame;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();
        // BlockScanner doesn't need stopTracking
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GetToOuterEndIslandsTask;
    }

    @Override
    public boolean isFinished() {
        return WorldHelper.getCurrentDimension() == Dimension.END &&
                !WorldHelper.inRangeXZ(new Vec3d(0, 64, 0), AltoClef.getInstance().getPlayer().getPos(), END_ISLAND_START_RADIUS);
    }

    @Override
    protected String toDebugString() {
        return "Going to outer end islands";
    }

}
