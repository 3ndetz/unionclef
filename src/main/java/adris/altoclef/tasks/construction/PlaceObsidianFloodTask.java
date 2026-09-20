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

    // ⛔ APPROACH PROGRESS IS DISTANCE TO THE RIM, NOT RAW BODY DISPLACEMENT (G108, 2026-09-19).
    //
    // Measured on the gamer server from the deep (y=37) nether-reach checkpoint: the bot descended to
    // a lava lake at y=21, ended in a cramped pocket at y=19 UNDER it, committed to a rim on the lake
    // it could not climb up to, and FROZE at (788,19,819) for the whole 27-minute run -- obsidian 0,
    // portal never built. The rim's stand was optimistically "reachable" (canReach = !isUnreachable,
    // and nothing had marked it yet), so the flood committed; then the UnstuckChain shimmied the
    // wedged body in place, and BOTH movement-based progress checkers here and in InteractWithBlockTask
    // read the jiggle as movement and reset every time, so neither ever concluded the rim was
    // unreachable and blacklisted it. A permanent deadlock built entirely out of "the body moved".
    //
    // Distance to the water cell only falls when the body is genuinely getting to the rim; a jiggle in
    // a pocket cannot fake it. So the rim commit is guarded by approach, not displacement: reset while
    // the best distance keeps improving (or once we are in range for the interact task to place), and
    // blacklist the rim after RIM_NO_APPROACH_LIMIT flood ticks with no improvement -- then the rim is
    // skipped and the flood re-selects, and with all reachable-looking rims exhausted it explores for
    // lava it can actually stand beside. Reset on every new rim.
    private double _bestApproachSq = Double.MAX_VALUE;
    private int _noApproachTicks = 0;
    // Flood ticks of no approach to the rim before it is judged unreachable. The flood owns most ticks
    // (the shimmy a minority), so this is ~10 s of the body never getting closer -- long enough not to
    // punish a legitimate long walk (which keeps beating its own best distance), short enough that a
    // genuinely unreachable rim is abandoned quickly instead of hanging the whole run.
    private static final int RIM_NO_APPROACH_LIMIT = 200;
    // Within this squared distance of the water cell the body is close enough for InteractWithBlockTask
    // to do the placing; the approach is done, so the guard rests (5 blocks).
    private static final double RIM_IN_RANGE_SQ = 25.0;

    // ⛔ THE IN-RANGE RIM NEEDS ITS OWN SHIMMY-PROOF GUARD (G108, 2026-09-21). The approach guard above
    // closed the FAR rim; it deliberately rests once the body is within range -- and that left the
    // NEAR rim on the old displacement guard alone. Measured live, three runs at the same lake: the
    // flood committed to rim (789,21,816) with the body at (789.5,20,818.6) -- 2.7 blocks away, lava
    // 2.4 blocks away, so "in range" -- InteractWithBlockTask sat in "Getting within reach" (iw 16k
    // ticks) because the rim's top face cannot be reached from the pocket under the lake, and the
    // UnstuckChain owned the bot EVERY tick (own 11938 -> 12147 in 15 s, rescues 119 -> 121): its
    // shimmy jiggled the body in place and reset the displacement guard forever. The exact deadlock
    // 0.95.24 fixed for the far rim, one radius closer. So the in-range case is guarded by the goal
    // itself: if the water has not LANDED after RIM_NO_PLACE_LIMIT flood ticks in range, the rim is
    // unplaceable from any stand the body can take and is blacklisted; the flood re-selects, and with
    // every reachable-looking rim exhausted it explores for a lake it can flood from solid ground.
    // Intermittent by which rim the flood happens to pick -- which is why the same lake floods on one
    // run and freezes the next. Not a timeout on the body: a bound on the goal never being reached.
    private int _inRangeNoPlaceTicks = 0;
    private static final int RIM_NO_PLACE_LIMIT = 200;

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
            _bestApproachSq = Double.MAX_VALUE;
            _noApproachTicks = 0;
            _inRangeNoPlaceTicks = 0;
        }

        // Approach guard (shimmy-proof): blacklist a rim the body never gets closer to. Distance to
        // the water cell, not raw displacement, so the unstuck shimmy jiggling a wedged body in place
        // cannot fake progress. See the field comment above (the y=19-under-a-lake deadlock).
        double approachSq = mod.getPlayer().getPos().squaredDistanceTo(
                _waterCell.getX() + 0.5, _waterCell.getY() + 0.5, _waterCell.getZ() + 0.5);
        if (approachSq <= RIM_IN_RANGE_SQ) {
            _noApproachTicks = 0;                 // close enough; let the interact task place
            // ...but only for so long: in range with no water landing means the rim's top face is not
            // reachable from here, and no amount of shimmy will change that. See the field comment.
            if (++_inRangeNoPlaceTicks > RIM_NO_PLACE_LIMIT) {
                Nav.cancel();
                _rimBlacklist.add(_rim);
                _rim = null;
                _waterCell = null;
                _progress.reset();
                _bestApproachSq = Double.MAX_VALUE;
                _noApproachTicks = 0;
                _inRangeNoPlaceTicks = 0;
                return null;
            }
        } else if (approachSq < _bestApproachSq - 0.25) {
            _bestApproachSq = approachSq;         // genuine progress toward the rim
            _noApproachTicks = 0;
        } else if (++_noApproachTicks > RIM_NO_APPROACH_LIMIT) {
            Nav.cancel();
            _rimBlacklist.add(_rim);
            _rim = null;
            _waterCell = null;
            _progress.reset();
            _bestApproachSq = Double.MAX_VALUE;
            _noApproachTicks = 0;
            _inRangeNoPlaceTicks = 0;
            return null;
        }

        // Progress guard: if we can't get to / place at this rim, blacklist it and pick another.
        if (!_progress.check(mod)) {
            Nav.cancel();
            _rimBlacklist.add(_rim);
            _rim = null;
            _waterCell = null;
            _progress.reset();
            _bestApproachSq = Double.MAX_VALUE;
            _noApproachTicks = 0;
            _inRangeNoPlaceTicks = 0;
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
            // ⛔ THE CLICKABLE RIM ITSELF MUST BE REACHABLE (G108, 2026-09-19). This used to accept the
            // rim if EITHER the rim OR the water cell above it was reachable. But water is placed by
            // clicking the rim's TOP face (InteractWithBlockTask on `rim`), and that task marks `rim`
            // -- not rim.up() -- unreachable when it cannot get there. The old OR then kept re-selecting
            // the same rim through rim.up()'s optimism (canReach = !isUnreachable, never set on it), so
            // a rim on a lava lake the bot was stuck UNDER was chosen again and again. Require the rim
            // the bot must actually reach and click.
            if (!WorldHelper.canReach(rim)) continue;
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
