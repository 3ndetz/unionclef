package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.control.Nav;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Makes obsidian by FLOODING AN EXISTING LAVA LAKE with a single water source -- the
 * speedrun-standard method -- instead of the per-block lava-bucket cast ({@link PlaceObsidianBucketTask}).
 *
 * <p>⛔ WHY THIS REPLACES THE CAST (G108, 2026-09-18). The cast builds a 10-block mould, places its
 * OWN lava into it with a lava bucket, then water on top, per obsidian block. That mould is fragile
 * in exactly the situations a real portal build hits -- mid-air, cramped or dirtied terrain -- and
 * its only failure response was a reactive {@code TimeoutWanderTask}, so the portal never completed.
 * Flooding removes the mould entirely: it uses the lava already in the world.
 *
 * <p>THE MECHANIC, validated on the stand 2026-09-18: a water source placed in the air cell one above
 * a lava SOURCE turns that lava to obsidian, and the flowing water spreads across the whole pool
 * surface, converting every lava source it reaches (a single edge placement floods a 5x5 pool =
 * 25/25 obsidian). We place the water on the TOP FACE of a solid "rim" block at the pool edge, so the
 * water lands at a known, static cell that stays a source -- which makes the reclaim reliable (the
 * prior flood attempt reclaimed an unknown, moving source and lost the bucket). Placing the water
 * empties the water bucket into an empty bucket, which is exactly what {@link ClearLiquidTask} needs
 * to scoop the source back, so the bucket cycles and no iron is consumed.
 *
 * <p>One task instance runs ONE flood cycle (find rim -> place water -> wait for conversion ->
 * reclaim) and then finishes; the caller ({@link adris.altoclef.tasks.resources.CollectObsidianTask})
 * mines the obsidian and starts a fresh cycle if it needs more.
 */
public class PlaceObsidianFloodTask extends Task {

    // How far the water is allowed to spread searching for lava; a lake within this is floodable.
    private static final int LAVA_SEARCH_RANGE = 64;

    private final TimerGame _convertWait = new TimerGame(3);
    // Best-effort cap on the whole place+convert+reclaim cycle: never dead-end on a stubborn reclaim.
    private final TimerGame _cycleDeadline = new TimerGame(25);
    private final MovementProgressChecker _progress = new MovementProgressChecker();
    private final Set<BlockPos> _rimBlacklist = new HashSet<>();

    private BlockPos _rim;        // solid edge block whose TOP face we click
    private BlockPos _waterCell;  // rim.up(): where the water source lands and is reclaimed
    private boolean _placed;      // water has been placed at _waterCell
    private boolean _done;        // one flood cycle complete (converted + reclaimed / settled)

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();
        mod.getBehaviour().push();
        // Only source blocks fill a bucket; ray-trace to sources so the water click and the reclaim
        // scoop target the source, not a flowing cell.
        mod.getBehaviour().setRayTracingFluidHandling(RaycastContext.FluidHandling.SOURCE_ONLY);
        // Don't let pathing dig out the rim we stand the water on.
        mod.getBehaviour().avoidBlockBreaking(pos -> _rim != null && pos.equals(_rim));
        _progress.reset();
        _cycleDeadline.reset();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Phase B: water is down -> wait for the conversion, then reclaim the source.
        if (_placed && _waterCell != null) {
            if (!_convertWait.elapsed()) {
                setDebugState("Waiting for lava to turn to obsidian");
                return null;
            }
            boolean waterThere = mod.getWorld().getBlockState(_waterCell).getBlock() == Blocks.WATER;
            if (!waterThere) {
                // Reclaimed or drained -> cycle complete.
                _done = true;
                return null;
            }
            // No empty bucket to scoop with, or we have spent long enough: leave the water source
            // (a lost bucket is cheap and the obsidian is already made) and NEVER dead-end here.
            if (!mod.getItemStorage().hasItem(Items.BUCKET) || _cycleDeadline.elapsed()) {
                Debug.logMessage("(flood) leaving water source (obsidian already made, reclaim skipped)");
                _done = true;
                return null;
            }
            setDebugState("Reclaiming the water source");
            return new ClearLiquidTask(_waterCell);
        }

        // Phase A: need a filled water bucket to flood with.
        if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
            setDebugState("Getting a water bucket to flood lava");
            _progress.reset();
            return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
        }

        // Find a rim to flood from.
        if (_rim == null || _waterCell == null) {
            Optional<BlockPos> rim = findFloodRim(mod);
            if (rim.isEmpty()) {
                // No reachable surface lava lake nearby. Approach the nearest known lava if we can,
                // otherwise explore for one -- locating lava is a genuine subgoal, not a give-up.
                Optional<BlockPos> lava = mod.getBlockScanner().getNearestBlock(Blocks.LAVA);
                if (lava.isPresent() && WorldHelper.canReach(lava.get().up())) {
                    setDebugState("Approaching lava to flood");
                    return new GetToBlockTask(lava.get().up(), false);
                }
                setDebugState("Searching for a lava lake to flood");
                return new TimeoutWanderTask();
            }
            _rim = rim.get();
            _waterCell = _rim.up();
            _progress.reset();
            _cycleDeadline.reset();
        }

        // Progress guard: if we can't get to / place at this rim, blacklist it and pick another.
        if (!_progress.check(mod)) {
            Nav.cancel();
            _rimBlacklist.add(_rim);
            _rim = null;
            _waterCell = null;
            _progress.reset();
            return null;
        }

        // If the water has landed, transition to the convert/reclaim phase.
        if (mod.getWorld().getBlockState(_waterCell).getBlock() == Blocks.WATER) {
            _placed = true;
            _convertWait.reset();
            _cycleDeadline.reset();
            return null;
        }

        // Place water on the TOP face of the rim -> it lands at _waterCell and floods the pool.
        setDebugState("Flooding the lava lake");
        return new InteractWithBlockTask(Items.WATER_BUCKET, Direction.UP, _rim, true);
    }

    /**
     * A rim to flood from: a SOLID block, at pool level with air above it, horizontally adjacent to a
     * surface LAVA SOURCE (a source with air above), reachable by the bot. Water on the rim's top face
     * lands one above the pool edge and flows across it, turning every lava source it reaches to
     * obsidian.
     */
    private Optional<BlockPos> findFloodRim(AltoClef mod) {
        Optional<BlockPos> lava = mod.getBlockScanner().getNearestBlock(
                mod.getPlayer().getPos(), p -> isFloodableLava(mod, p), Blocks.LAVA);
        return lava.flatMap(pos -> rimFor(mod, pos));
    }

    private boolean isFloodableLava(AltoClef mod, BlockPos lava) {
        if (!lava.isWithinDistance(mod.getPlayer().getPos(), LAVA_SEARCH_RANGE)) return false;
        if (!WorldHelper.isSourceBlock(lava, false)) return false;   // must be a source (flowing lava -> cobblestone)
        if (!WorldHelper.isAir(lava.up())) return false;             // must be surface lava (open above)
        return rimFor(mod, lava).isPresent();
    }

    private Optional<BlockPos> rimFor(AltoClef mod, BlockPos lava) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos rim = lava.offset(d);
            if (_rimBlacklist.contains(rim)) continue;
            if (!WorldHelper.isSolidBlock(rim)) continue;            // solid to click
            if (!WorldHelper.isAir(rim.up())) continue;              // air above to hold the water
            if (!WorldHelper.canReach(rim) && !WorldHelper.canReach(rim.up())) continue;
            return Optional.of(rim);
        }
        return Optional.empty();
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getBehaviour().pop();
    }

    @Override
    public boolean isFinished() {
        return _done;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlaceObsidianFloodTask;
    }

    @Override
    protected String toDebugString() {
        return "Flooding lava to make obsidian";
    }
}
