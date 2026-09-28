package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import kaptainwutax.tungsten.path.RouteHazards;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Wait out the night in a capped two-deep hole: dig the two cells under the feet, drop in, put a
 * block where the feet were, and stay until morning.
 *
 * <p>WHY. Without a bed the main task used to "work through the night" on the surface. Measured on
 * the rung-bucket checkpoint (snowy mountains, full47 and two replays): every health loss of the
 * night came from surface work -- a long haul to a skeleton under fire, a food search wandering the
 * slopes, running from a creeper -- 20 -> 1.7 health in four minutes, and full47 died to a zombie
 * there and spent the next 25 minutes re-gathering. In a 1x1 shaft with a roof nothing can reach
 * the body: no line of sight for arrows or creepers, no room beside it for a zombie, and too little
 * light-free space around it for anything to spawn next to it.
 *
 * <p>Only the surface is dangerous this way; the caller does not use this underground, where the
 * night changes nothing and mining goes on.
 *
 * <p>{@link #holding()} tells MobDefense and the stuck detector that standing still is the point.
 */
public class NightShelterTask extends Task {

    private static final int SITE_SEARCH_RADIUS = 6;
    private static volatile boolean holding;

    private BlockPos top;   // the cap: the cell the feet were in when the digging started
    /** Set on the first dig: from then on the site is kept. Re-picking it after every dig is how
     *  the first version took a 186-block shaft down from y 155 to -31 (each dug site stopped
     *  passing siteHolds, the next one was picked under the feet). */
    private boolean committed;

    public static boolean holding() {
        return holding;
    }

    /** Can this inventory cap a shelter? */
    public static boolean hasBlock(AltoClef mod) {
        return mod.getItemStorage().hasItem(mod.getThrowawayItems().toArray(new Item[0]));
    }

    @Override
    protected void onStart() {
        top = null;
        committed = false;
        holding = false;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        World world = mod.getWorld();
        holding = false;
        BlockPos feet = mod.getPlayer().getBlockPos();

        if (committed && top != null && floods(world, top)) {
            committed = false;   // water or lava reached the shaft: pick somewhere else
            top = null;
        }
        if (top == null || !committed && !siteHolds(world, top)) {
            top = pickSite(world, feet);
            if (top == null) {
                setDebugState("No place to dig in for the night here");
                return null;
            }
            Debug.logMessage("Night shelter at " + top.toShortString());
        }
        BlockPos mid = top.down(), bottom = top.down(2);
        if (!feet.equals(top) && !feet.equals(mid) && !feet.equals(bottom)) {
            setDebugState("Going to dig in for the night at " + top.toShortString());
            return new GetToBlockTask(top);
        }
        if (solid(world, mid)) {
            committed = true;
            setDebugState("Digging in for the night");
            return new DestroyBlockTask(mid);
        }
        if (solid(world, bottom)) {
            committed = true;
            setDebugState("Digging in for the night");
            return new DestroyBlockTask(bottom);
        }
        if (!feet.equals(bottom)) {
            setDebugState("Dropping into the shelter");
            return new GetToBlockTask(bottom);
        }
        holding = true;
        if (world.getBlockState(top).isReplaceable()) {
            setDebugState("Closing the shelter");
            return new PlaceBlockTask(top, new Block[0], true, false);
        }
        setDebugState("Waiting for the morning in a shelter");
        return null;
    }

    private static boolean floods(World world, BlockPos top) {
        for (int d = 0; d <= 2; d++) {
            if (!world.getFluidState(top.down(d)).isEmpty()) return true;
        }
        return false;
    }

    /** The nearest acceptable site, the feet cell first. */
    private static BlockPos pickSite(World world, BlockPos from) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -SITE_SEARCH_RADIUS; dx <= SITE_SEARCH_RADIUS; dx++) {
            for (int dz = -SITE_SEARCH_RADIUS; dz <= SITE_SEARCH_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) candidates.add(from.add(dx, dy, dz));
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(from)));
        for (BlockPos p : candidates) {
            if (siteHolds(world, p)) return p;
        }
        return null;
    }

    /**
     * The body can stand at {@code top}; the two cells below are solid, breakable and dry, with no
     * fluid or hazard beside them (digging must not open a flood or lava onto the body); the floor
     * under them is solid and not a hazard.
     */
    static boolean siteHolds(World world, BlockPos top) {
        if (!kaptainwutax.tungsten.helpers.PlayerFit.standable(world, top)) return false;
        BlockPos.Mutable s = new BlockPos.Mutable();
        for (int d = 1; d <= 2; d++) {
            BlockPos c = top.down(d);
            if (!solid(world, c) || !WorldHelper.canBreak(c)) return false;
            if (!world.getFluidState(c).isEmpty()) return false;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos n = c.offset(dir);
                if (!world.getFluidState(n).isEmpty() || RouteHazards.hazardAt(world, n.getX(), n.getY(), n.getZ(), s)) {
                    return false;
                }
                // A side open to the air is a way in; the walls come from the ground itself.
                if (!solid(world, n)) return false;
            }
        }
        BlockPos floor = top.down(3);
        return solid(world, floor) && !RouteHazards.hazardAt(world, floor.getX(), floor.getY(), floor.getZ(), s);
    }

    private static boolean solid(World world, BlockPos p) {
        return !world.getBlockState(p).getCollisionShape(world, p).isEmpty();
    }

    @Override
    public boolean isFinished() {
        // Not at the first minute of dawn: the night's zombies and skeletons are still standing
        // there and burn only once the sun is up. Measured on the rung-bucket replay: a shelter
        // left at dawn met them at once, 20 -> 10 health chasing a zombie. Stay until time 1000.
        int t = WorldHelper.getTimeOfDay();
        return !WorldHelper.canSleep() && t >= 1000;
    }

    @Override
    protected void onStop(Task interruptTask) {
        holding = false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof NightShelterTask;
    }

    @Override
    protected String toDebugString() {
        return "Waiting out the night underground";
    }
}
