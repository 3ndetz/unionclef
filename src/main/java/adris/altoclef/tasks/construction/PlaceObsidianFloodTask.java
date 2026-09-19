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
    // After the last source is scooped, give the flowing water a moment to drain before we hand the
    // fresh obsidian back to be mined -- so the miner never sees it still submerged (which would look
    // like "no obsidian" and kick off a pointless re-flood).
    private final TimerGame _drainSettle = new TimerGame(2.5);
    // How far the flood's water can spread from _waterCell; the reclaim scans this for its sources.
    private static final int RECLAIM_RADIUS = 12;
    private final MovementProgressChecker _progress = new MovementProgressChecker();
    private final MovementProgressChecker _reclaimProgress = new MovementProgressChecker(2);
    private final Set<BlockPos> _rimBlacklist = new HashSet<>();
    private final Set<BlockPos> _reclaimBlacklist = new HashSet<>();

    private BlockPos _rim;           // solid edge block whose TOP face we click
    private BlockPos _waterCell;     // rim.up(): where the water source lands
    private BlockPos _reclaimTarget; // the water source we're currently scooping back
    private boolean _placed;         // water has been placed at _waterCell
    private boolean _done;           // one flood cycle complete (converted + drained)

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
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Phase B: water is down -> wait for the conversion, then reclaim every source we made.
        if (_placed && _waterCell != null) {
            if (!_convertWait.elapsed()) {
                setDebugState("Waiting for lava to turn to obsidian");
                return null;
            }
            // No empty bucket to scoop with: a lost bucket is cheap and the obsidian is already made.
            if (!mod.getItemStorage().hasItem(Items.BUCKET)) {
                Debug.logMessage("(flood) no bucket to reclaim with -- leaving water, obsidian is made");
                _done = true;
                return null;
            }
            // Scoop back the water sources this flood placed. Removing a source drains ALL the flowing
            // water it feeds, so once no reclaimable sources remain in the pool region the sheet is gone
            // and the fresh obsidian is exposed for mining. Targeting a real SOURCE block (not one fixed
            // cell that may have become flowing) is what lets ClearLiquidTask actually finish -- the
            // old code scooped _waterCell forever if it was flowing, timed out, and left the sheet,
            // which submerged the obsidian and drove an endless re-flood loop.
            Optional<BlockPos> src = findNearestReclaimableSource(mod);
            if (src.isEmpty()) {
                // No sources left. Let any flowing water finish draining, then this cycle is done.
                if (!_drainSettle.elapsed()) {
                    setDebugState("Waiting for the flooded water to drain");
                    return null;
                }
                _done = true;
                return null;
            }
            _drainSettle.reset();
            if (!src.get().equals(_reclaimTarget)) {
                _reclaimTarget = src.get();
                _reclaimProgress.reset();
            }
            if (!_reclaimProgress.check(mod)) {
                // Can't get to / scoop this source -> give up on it and try the next one.
                Nav.cancel();
                _reclaimBlacklist.add(_reclaimTarget);
                _reclaimTarget = null;
                _reclaimProgress.reset();
                return null;
            }
            setDebugState("Reclaiming water source " + _reclaimTarget.toShortString());
            return new ClearLiquidTask(_reclaimTarget);
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
                // No reachable surface lava lake nearby. Approach the nearest FLOODABLE lava --
                // a SURFACE source (a source with air above it) -- so getting closer actually lets
                // findFloodRim succeed on arrival; otherwise explore for one.
                //
                // ⛔ DO NOT APPROACH ANY LAVA (G108, 2026-09-19). This used to approach the nearest
                // Blocks.LAVA of any kind. On natural terrain the nearest lava is routinely a DEEP
                // BURIED pocket (measured: the bot at the surface y=61 committed to lava at y=27
                // directly below it), which (a) cannot be flooded at all -- no air above the source to
                // land water on -- and (b) canReach() reports reachable for the cell above it while
                // the nav cannot actually shaft ~34 blocks down to it, so GetToBlockTask stalled
                // "Approaching lava to flood" for the whole window and the portal never built.
                // Filtering to a surface source targets lava the flood can USE and the body can stand
                // beside; a source buried in rock is skipped, and with none known we explore for a
                // real lake instead of committing to an unreachable, unfloodable pocket.
                Optional<BlockPos> lava = mod.getBlockScanner().getNearestBlock(
                        mod.getPlayer().getPos(),
                        p -> WorldHelper.isSourceBlock(p, false) && WorldHelper.isAir(p.up()),
                        Blocks.LAVA);
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
            _drainSettle.reset();
            return null;
        }

        // Place water on the TOP face of the rim -> it lands at _waterCell and floods the pool.
        setDebugState("Flooding the lava lake");
        return new InteractWithBlockTask(Items.WATER_BUCKET, Direction.UP, _rim, true);
    }

    /**
     * Nearest still-water SOURCE block within {@link #RECLAIM_RADIUS} of where we placed the flood,
     * skipping any we've already given up on. Scooping a source drains all the flowing water it feeds,
     * so reclaiming the handful of sources drains the whole sheet -- and targeting a real SOURCE (not a
     * fixed cell that may be flowing) is what lets the scoop actually complete.
     */
    private Optional<BlockPos> findNearestReclaimableSource(AltoClef mod) {
        if (_waterCell == null) return Optional.empty();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -RECLAIM_RADIUS; dx <= RECLAIM_RADIUS; dx++) {
            for (int dz = -RECLAIM_RADIUS; dz <= RECLAIM_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = _waterCell.add(dx, dy, dz);
                    if (_reclaimBlacklist.contains(p)) continue;
                    if (mod.getWorld().getBlockState(p).getBlock() != Blocks.WATER) continue;
                    if (!WorldHelper.isSourceBlock(p, true)) continue;
                    double d = p.getSquaredDistance(mod.getPlayer().getPos());
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
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
