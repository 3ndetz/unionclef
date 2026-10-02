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
 * Wait out the night in a capped hole: dig the three cells under the feet, drop to the bottom, put
 * a block in the top dug cell (one below ground level), and stay until morning.
 *
 * <p>WHY THREE AND NOT TWO (2026-09-29). The first version dug two and capped the cell the feet
 * had been in, at ground level. A block there needs a solid neighbour to be placed against; below
 * it is the dug hole and on flat ground its sides are open air, so on open ground the cap had
 * nothing to go on (found reading placementStand, TODOS 2026-09-28; the two live successes were on
 * slopes). One cell lower the cap is surrounded by the ground itself -- the same walls siteHolds
 * already requires.
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

    /** Loaded nearby terrain, including banks above a pool; not a one-level surface slice. */
    private static final int SITE_SEARCH_RADIUS = 16;
    /** Cells dug under the feet: the body takes the lower two, the cap goes in the top one. */
    private static final int DEPTH = 3;
    private static volatile boolean holding;

    private BlockPos top;   // the cell the feet were in when the digging started; the cap goes under it
    /** Set on the first dig: from then on the site is kept. Re-picking it after every dig is how
     *  the first version took a 186-block shaft down from y 155 to -31 (each dug site stopped
     *  passing siteHolds, the next one was picked under the feet). */
    private boolean committed;
    private GetToAnyBlockTask approach;
    private long nextSiteScanTick;
    /** Last discovery cost and destination count, to verify the client scan on the bench. */
    public static volatile long siteScanNanos;
    public static volatile int siteScanCandidates;

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
        approach = null;
        nextSiteScanTick = 0;
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
            approach = null;
        }
        if (top != null && !committed && !siteHolds(world, top)) {
            top = null;
            approach = null;
        }
        if (top == null) {
            // Revalidate the real destination before the first dig: an approach can
            // remove one of its walls. A snapshot is for routing, never a dig permit.
            if (mod.getPlayer().isOnGround() && siteHolds(world, feet)) {
                top = feet.toImmutable();
                approach = null;
                Debug.logMessage("Night shelter at " + top.toShortString());
            } else {
                if (approach != null && approach.contains(feet) && mod.getPlayer().isOnGround()) {
                    approach = null; // reached a site that no longer passes the live predicate
                }
                if ((approach == null || !approach.ownsRoute()) && world.getTime() >= nextSiteScanTick) {
                    List<BlockPos> sites = pickSites(world, feet);
                    // baritone/src/main/java/baritone/process/GetToBlockProcess.java:103-108 refreshes destinations
                    // during a goal. Refresh when our search finishes, preserving an ongoing
                    // route. The enclosed-site control otherwise retried one stale cell even
                    // after a reachable site appeared; Unstuck merely hid it by restarting us.
                    nextSiteScanTick = world.getTime() + 20;
                    if (sites.isEmpty()) {
                        approach = null;
                    } else {
                        GetToAnyBlockTask refreshed = new GetToAnyBlockTask(sites);
                        // Keep the active Task/owner if discovery found the same set.
                        if (!refreshed.equals(approach)) approach = refreshed;
                    }
                }
                if (approach == null) {
                    setDebugState("No place to dig in for the night in nearby loaded terrain");
                    return null;
                }
                setDebugState("Looking for a reachable place to dig in for the night");
                return approach;
            }
        }
        BlockPos cap = top.down(), bottom = top.down(DEPTH);
        if (feet.getX() != top.getX() || feet.getZ() != top.getZ()
                || feet.getY() > top.getY() || feet.getY() < bottom.getY()) {
            setDebugState("Going to dig in for the night at " + top.toShortString());
            return new GetToBlockTask(top);
        }
        // Dig only on the way down. At the bottom the top dug cell IS the cap, and a solid cap is
        // the goal: full54 dug its own cap out, put it back, dug it out again, for eight minutes.
        if (!feet.equals(bottom)) {
            for (int d = 1; d <= DEPTH; d++) {
                if (solid(world, top.down(d))) {
                    committed = true;
                    setDebugState("Digging in for the night");
                    return new DestroyBlockTask(top.down(d));
                }
            }
        }
        if (!feet.equals(bottom)) {
            setDebugState("Dropping into the shelter");
            return new GetToBlockTask(bottom);
        }
        holding = true;
        if (world.getBlockState(cap).isReplaceable()) {
            setDebugState("Closing the shelter");
            return new PlaceBlockTask(cap, new Block[0], true, false);
        }
        setDebugState("Waiting for the morning in a shelter");
        return null;
    }

    private static boolean floods(World world, BlockPos top) {
        for (int d = 0; d <= DEPTH; d++) {
            if (!world.getFluidState(top.down(d)).isEmpty()) return true;
        }
        return false;
    }

    /** Diagnostic nearest valid site; execution searches all candidates for reachability. */
    private static BlockPos pickSite(World world, BlockPos from) {
        return pickSites(world, from).stream()
                .min(Comparator.comparingDouble(p -> p.getSquaredDistance(from))).orElse(null);
    }

    /** Client-thread policy checks produce immutable destinations for the planner worker. */
    private static List<BlockPos> pickSites(World world, BlockPos from) {
        long began = System.nanoTime();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -SITE_SEARCH_RADIUS; dx <= SITE_SEARCH_RADIUS; dx++) {
            for (int dz = -SITE_SEARCH_RADIUS; dz <= SITE_SEARCH_RADIUS; dz++) {
                if (!world.isChunkLoaded((from.getX() + dx) >> 4, (from.getZ() + dz) >> 4)) continue;
                for (int dy = -SITE_SEARCH_RADIUS; dy <= SITE_SEARCH_RADIUS; dy++) {
                    BlockPos cell = from.add(dx, dy, dz);
                    if (cell.getY() - DEPTH - 1 < world.getBottomY()) continue;
                    if (siteHolds(world, cell)) candidates.add(cell);
                }
            }
        }
        siteScanNanos = System.nanoTime() - began;
        siteScanCandidates = candidates.size();
        return candidates;
    }

    /**
     * The body can stand at {@code top}; the DEPTH cells below are solid, breakable and dry, with
     * solid walls and no fluid or hazard beside them (digging must not open a flood or lava onto the
     * body, and the top dug cell's walls are what the cap is placed against); the floor under them
     * is solid and not a hazard.
     */
    static boolean siteHolds(World world, BlockPos top) {
        if (!kaptainwutax.tungsten.helpers.PlayerFit.standable(world, top)) return false;
        BlockPos.Mutable s = new BlockPos.Mutable();
        for (int d = 1; d <= DEPTH; d++) {
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
        BlockPos floor = top.down(DEPTH + 1);
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
