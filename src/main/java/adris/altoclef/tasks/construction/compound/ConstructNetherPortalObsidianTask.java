package adris.altoclef.tasks.construction.compound;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;

import java.util.LinkedList;

/**
 * Build a nether portal with obsidian blocks.
 */
public class ConstructNetherPortalObsidianTask extends Task {

    // There's some code duplication between here and ConstructNetherPortalBucketTask...
    // but it's so heavily intertwined/changed that it would take forever to untangle and
    // retangle the two together.

    // Order here matters
    private static final Vec3i[] PORTAL_FRAME = new Vec3i[]{
            // Left side
            new Vec3i(0, 0, -1),
            new Vec3i(0, 1, -1),
            new Vec3i(0, 2, -1),
            // Right side
            new Vec3i(0, 0, 2),
            new Vec3i(0, 1, 2),
            new Vec3i(0, 2, 2),
            // Top
            new Vec3i(0, 3, 0),
            new Vec3i(0, 3, 1),
            // Bottom
            new Vec3i(0, -1, 0),
            new Vec3i(0, -1, 1)
    };

    private static final Vec3i[] PORTAL_INTERIOR = new Vec3i[]{
            //Inside
            new Vec3i(0, 0, 0),
            new Vec3i(0, 1, 0),
            new Vec3i(0, 2, 0),
            new Vec3i(0, 0, 1),
            new Vec3i(0, 1, 1),
            new Vec3i(0, 2, 1),
            //Outside 1
            new Vec3i(1, 0, 0),
            new Vec3i(1, 1, 0),
            new Vec3i(1, 2, 0),
            new Vec3i(1, 0, 1),
            new Vec3i(1, 1, 1),
            new Vec3i(1, 2, 1),
            //Outside 2
            new Vec3i(-1, 0, 0),
            new Vec3i(-1, 1, 0),
            new Vec3i(-1, 2, 0),
            new Vec3i(-1, 0, 1),
            new Vec3i(-1, 1, 1),
            new Vec3i(-1, 2, 1)
    };

    private final TimerGame _areaSearchTimer = new TimerGame(5);

    private BlockPos origin;

    private BlockPos _destroyTarget;

    /**
     * ⛔ THE FRAME MUST NOT BE SITED IN THE CAST PIT (G108, 2026-09-18). Gathering obsidian by
     * casting (lava bucket + water in a mould, then mine) digs a chaotic, lava-adjacent hole and
     * leaves the body enclosed in it. The old check accepted ANY spot whose 3x6x6 was merely
     * placeable-or-breakable -- which a dug pit's air cells satisfy -- so {@code origin} landed IN
     * the pit and every frame placement failed "Enclosed -- escaping via FastPlanner", wedging the
     * build for ever (reproduced on the stand: 10 obsidian in the pack, frame positions all air,
     * gather<->place cycling 300 s+). Scan OUTWARD for a genuinely CLEAN, FLAT, OPEN pad instead
     * and build the frame there, off the pit.
     */
    private static BlockPos getBuildableAreaNearby(AltoClef mod) {
        BlockPos feet = mod.getPlayer().getBlockPos();
        // PASS 1: prefer a genuinely clean, flat, open pad (no scaffolding, no obstruction).
        BlockPos clean = scanForSite(mod, feet, true);
        if (clean != null) return clean;
        // PASS 2: fall back to a DECENT site -- solid floor under the footprint and an open column
        // above the origin, tolerating side obstructions the frame build clears. This is strictly
        // better than the old check (which accepted an enclosed pit) yet never wanders for ever when
        // no perfectly-clean pad exists nearby (the over-strictness risk of pass 1 alone).
        return scanForSite(mod, feet, false);
    }

    private static BlockPos scanForSite(AltoClef mod, BlockPos feet, boolean strict) {
        for (int r = 2; r <= 12; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // just the ring at radius r
                    for (int dy = 1; dy >= -2; dy--) {                         // prefer at / just above foot level
                        BlockPos origin = feet.add(dx, dy, dz);
                        if (!mod.getChunkTracker().isChunkLoaded(origin)) continue;
                        if (strict ? isCleanFlatBuildSite(mod, origin) : isDecentBuildSite(mod, origin)) {
                            return origin;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * A DECENT (not perfect) build site: a solid floor under the whole footprint and an OPEN column
     * above the origin (so the body is not sealed in a pit), with no lava/water anywhere in the frame
     * region. Side obstructions are tolerated -- the frame build's DestroyBlockTask clears them. This
     * is the fallback so the search never wanders for ever when no pristine pad is nearby, while
     * still rejecting the enclosed cast-pit that was the original wedge.
     */
    private static boolean isDecentBuildSite(AltoClef mod, BlockPos origin) {
        World world = mod.getWorld();
        if (world == null) return false;
        for (BlockPos f : WorldHelper.scanRegion(origin.add(-1, -2, -1), origin.add(1, -2, 2))) {
            if (!WorldHelper.isSolidBlock(f)) return false;                    // must have a floor (not a pit-with-no-floor / mid-air)
            var b = world.getBlockState(f).getBlock();
            if (b == Blocks.LAVA || b == Blocks.WATER) return false;
        }
        for (int dy = -1; dy <= 3; dy++) {                                     // open column above origin: not sealed in
            if (!world.getBlockState(origin.add(0, dy, 0)).isAir()) return false;
        }
        for (BlockPos a : WorldHelper.scanRegion(origin.add(-1, -1, -1), origin.add(1, 3, 2))) {
            var b = world.getBlockState(a).getBlock();
            if (b == Blocks.LAVA || b == Blocks.WATER) return false;          // no lava/water in the frame region
        }
        return true;
    }

    /**
     * A clean, open, flat pad for the portal: a solid floor under the whole footprint and clear air
     * for the entire frame envelope above it, no lava/water in it. Built from the ground up, every
     * obsidian places against the floor or the block below it -- no mid-air scaffolding, no pit, no
     * lava, no body enclosure. Deliberately strict: a portal sited anywhere the cast dug up is the
     * exact wedge this avoids.
     */
    private static boolean isCleanFlatBuildSite(AltoClef mod, BlockPos origin) {
        World world = mod.getWorld();
        if (world == null) return false;
        // solid floor pad beneath the whole footprint (y=-2, x in [-1,1], z in [-1,2])
        for (BlockPos f : WorldHelper.scanRegion(origin.add(-1, -2, -1), origin.add(1, -2, 2))) {
            if (!WorldHelper.isSolidBlock(f)) return false;
            var b = world.getBlockState(f).getBlock();
            if (b == Blocks.LAVA || b == Blocks.WATER) return false;
        }
        // clear air for the whole frame envelope above the floor (y=-1..3, x in [-1,1], z in [-1,2])
        for (BlockPos a : WorldHelper.scanRegion(origin.add(-1, -1, -1), origin.add(1, 3, 2))) {
            if (!world.getBlockState(a).isAir()) return false;
        }
        return true;
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();

        mod.getBehaviour().push();

        // Avoid breaking portal frame if we're obsidian.
        mod.getBehaviour().avoidBlockBreaking(block -> {
            if (origin != null) {
                // Don't break frame
                for (Vec3i framePosRelative : PORTAL_FRAME) {
                    BlockPos framePos = origin.add(framePosRelative);
                    if (block.equals(framePos)) {
                        return mod.getWorld().getBlockState(framePos).getBlock() == Blocks.OBSIDIAN;
                    }
                }
            }
            return false;
        });
        mod.getBehaviour().addProtectedItems(Items.FLINT_AND_STEEL);
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (origin != null) {
            if (mod.getWorld().getBlockState(origin.up()).getBlock() == Blocks.NETHER_PORTAL) {
                setDebugState("Done constructing nether portal.");
                mod.getBlockScanner().addBlock(Blocks.NETHER_PORTAL, origin.up());
                return null;
            }
        }
        int neededObsidian = 10;
        BlockPos placeTarget = null;
        if (origin != null) {
            for (Vec3i frameOffs : PORTAL_FRAME) {
                BlockPos framePos = origin.add(frameOffs);
                // ⛔ READ THE WORLD, NOT THE BLOCK SCANNER (G108, 2026-09-18). The scanner is
                // event-driven and lags a just-placed block, so a frame obsidian the bot placed this
                // tick reads as "still needed" -> neededObsidian stays high -> the task re-enters the
                // obsidian gather mid-build, and CollectObsidianTask then MINES that placed-but-
                // unregistered frame obsidian as "nearby obsidian to collect", churning the build
                // (obsidian consumed 8 yet 5 frame cells still 'needed', observed on the stand). The
                // world read is immediate and exact.
                if (mod.getWorld().getBlockState(framePos).getBlock() != Blocks.OBSIDIAN) {
                    placeTarget = framePos;
                    break;
                }
                neededObsidian--;
            }
        }

        // Get obsidian if we don't have.
        if (mod.getItemStorage().getItemCount(Items.OBSIDIAN) < neededObsidian) {
            setDebugState("Getting obsidian");
            return TaskCatalogue.getItemTask(Items.OBSIDIAN, neededObsidian);
        }

        // Find spot
        if (origin == null) {
            if (_areaSearchTimer.elapsed()) {
                _areaSearchTimer.reset();
                Debug.logMessage("(Searching for area to build portal nearby...)");
                origin = getBuildableAreaNearby(mod);
            }
            setDebugState("Looking for portalable area...");
            return new TimeoutWanderTask();
        }

        // Get flint and steel
        if (!mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)) {
            setDebugState("Getting flint and steel");
            return TaskCatalogue.getItemTask(Items.FLINT_AND_STEEL, 1);
        }

        // Place frame
        if (placeTarget != null) {
            World world = mod.getWorld();

            if (surroundedByAir(world,placeTarget)) {
                // ⛔ FIXED 2026-09-05: the while condition tested `placeTarget` (the fixed BFS
                // origin, never updated inside the loop) instead of the current node `pos`, so it
                // was always true given the outer `if` already established it -- the loop relied
                // entirely on the inner `return` to exit, with no bound tied to actual BFS
                // progress. Worse, nothing tracked visited positions, so the search could cycle
                // forever (e.g. `pos.up()` then later that node's `pos.down()` re-enqueues `pos`
                // itself), growing the queue without limit. Both are real hang/OOM risks on this
                // task's own tick thread. Added a visited set (also fixes re-enqueuing/re-checking
                // the same cell) and a hard node cap as a safety bound, matching this session's
                // established caution about unbounded searches.
                java.util.Set<BlockPos> visited = new java.util.HashSet<>();
                LinkedList<BlockPos> queue = new LinkedList<>();
                queue.add(placeTarget);
                visited.add(placeTarget);
                int scanned = 0;
                while (!queue.isEmpty() && scanned++ < 4096) {
                    BlockPos pos = queue.removeFirst();

                    if (surroundedByAir(world, pos)) {
                        for (BlockPos next : new BlockPos[]{pos.up(), pos.down(), pos.east(), pos.west(), pos.north(), pos.south()}) {
                            if (visited.add(next)) {
                                queue.add(next);
                            }
                        }
                    } else {
                        return new PlaceStructureBlockTask(pos);
                    }
                }

                mod.logWarning("Did not find any block to place obsidian on");
            }

            if (!world.getBlockState(placeTarget).isAir() && !world.getBlockState(placeTarget).getBlock().equals(Blocks.OBSIDIAN)) {
                return new DestroyBlockTask(placeTarget);
            }
            setDebugState("Placing frame...");
            return new PlaceBlockTask(placeTarget, Blocks.OBSIDIAN);
        }

        // Clear middle
        if (_destroyTarget != null && !WorldHelper.isAir(_destroyTarget)) {
            return new DestroyBlockTask(_destroyTarget);
        }
        for (Vec3i middleOffs : PORTAL_INTERIOR) {
            BlockPos middlePos = origin.add(middleOffs);
            if (!WorldHelper.isAir(middlePos)) {
                _destroyTarget = middlePos;
                return new DestroyBlockTask(_destroyTarget);
            }
        }
        // Flint and steel
        return new InteractWithBlockTask(new ItemTarget(Items.FLINT_AND_STEEL, 1), Direction.UP, origin.down(), true);
    }

    private boolean surroundedByAir(World world, BlockPos pos) {
        return world.getBlockState(pos.west()).isAir() && world.getBlockState(pos.south()).isAir() && world.getBlockState(pos.east()).isAir() &&
                world.getBlockState(pos.up()).isAir() && world.getBlockState(pos.down()).isAir() && world.getBlockState(pos.north()).isAir();
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ConstructNetherPortalObsidianTask;
    }

    @Override
    protected String toDebugString() {
        return "Building nether portal with obsidian";
    }
}
