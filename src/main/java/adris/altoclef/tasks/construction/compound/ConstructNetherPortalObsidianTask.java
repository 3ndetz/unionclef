package adris.altoclef.tasks.construction.compound;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
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

    // ⛔ A FULL RECTANGLE WITH CORNERS, BUILT BOTTOM-UP -- NO MID-AIR PLACEMENT (G108, 2026-09-18).
    // The old frame was the 10-block MINIMAL ring (no corners): the column bases sat one above the
    // absent corners and the top row sat over the interior, so both were placed MID-AIR and needed a
    // scaffold (PlaceStructureBlockTask), which reactively wanders and stalls -- the "mid-air upper
    // frame" fragility that never lit a portal on ~half the runs (bench: 42 s wandering on one top
    // cell, 0 portal). A cornered 14-block rectangle removes the mid-air entirely: every cell rests
    // on the block directly BELOW it (or, for the top row, beside an already-placed neighbour), so
    // PlaceBlockTask places each one against a real face and the scaffold path is never taken. The
    // four extra obsidian are free -- the flood makes 20+ from one lake -- and a portal with corners
    // lights and behaves exactly like one without. ORDER MATTERS: bottom row (on the floor pad), then
    // both columns bottom-up, then the top row left-to-right; each entry has support by the time it
    // is placed.
    private static final Vec3i[] PORTAL_FRAME = new Vec3i[]{
            // Bottom row on the floor pad (y=-1): z = -1,0,1,2
            new Vec3i(0, -1, -1),
            new Vec3i(0, -1, 0),
            new Vec3i(0, -1, 1),
            new Vec3i(0, -1, 2),
            // Left column up (z=-1): each on the block below
            new Vec3i(0, 0, -1),
            new Vec3i(0, 1, -1),
            new Vec3i(0, 2, -1),
            // Right column up (z=2): each on the block below
            new Vec3i(0, 0, 2),
            new Vec3i(0, 1, 2),
            new Vec3i(0, 2, 2),
            // Top row (y=3) left-to-right: corners rest on the columns, middles against their neighbour
            new Vec3i(0, 3, -1),
            new Vec3i(0, 3, 0),
            new Vec3i(0, 3, 1),
            new Vec3i(0, 3, 2)
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

    // ⛔ A TEMPORARY STANDING WALL IN FRONT OF THE FRAME, SO THE TOP ROW IS PLACED FROM A SIDE STAND
    // (G108, 2026-09-19). The top row (y=origin+3) sits over the open interior: it has no reachable
    // ground stand beside it, so the placer fell to the pillar branch, which asks FastNavigator to
    // stand IN the cell -- and it cannot reliably path onto a cell perched on a 1-wide column top over
    // the gap, so it deferred in a tight loop and the build HARD-STALLED at 13/14 on ~half the runs.
    // Building a solid cobblestone wall one row in front (x=+1) up to the column top (y=+2) gives the
    // bot a stable strip to stand on -- feet at (1, y+3, z), on top of the wall -- from which
    // placementStand returns that side stand and the top row places obsidian via the normal (reliable)
    // side-stand path, never the pillar. Built bottom-up like the frame columns, then mined back out
    // before lighting. Cobblestone, so the obsidian gather never touches it.
    private static final Vec3i[] FRONT_SCAFFOLD;
    static {
        java.util.List<Vec3i> fs = new java.util.ArrayList<>();
        for (int dy = -1; dy <= 2; dy++) {
            for (int dz = -1; dz <= 2; dz++) {
                fs.add(new Vec3i(1, dy, dz));
            }
        }
        FRONT_SCAFFOLD = fs.toArray(new Vec3i[0]);
    }

    private final TimerGame _areaSearchTimer = new TimerGame(5);

    private BlockPos origin;

    /**
     * Progress of the walk back to {@link #origin}, and how long it has made none. A reservation the
     * body cannot get back to is not a reservation -- see the re-siting branch in {@link #onTick()}.
     * Squared distance, so "closer by a block" is a change of more than 1.0 near the pad.
     */
    private double _returnBestDistSq = Double.MAX_VALUE;
    private int _returnNoProgressTicks;
    /** ~10 s at 20 tps: long enough for a detour round a wall, far short of the 9-minute freeze. */
    private static final int RETURN_NO_PROGRESS_LIMIT = 200;

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
     *
     * <p>⛔ AND NO OBSIDIAN IN THE FLOOR OR FRAME REGION (G108, 2026-09-18). The obsidian method now
     * FLOODS a lava lake to make obsidian, which leaves a flat obsidian SHEET where the bot is
     * standing. That sheet reads as "solid floor + open air above", so the frame was sited ON it --
     * and then the gather and the build fought over the same blocks: the re-gather mined the sheet
     * (including cells the frame needed) while the builder placed into it, an endless place/mine churn
     * that never completed (bench: obsidian cycling 0<->2 for 90 s, no portal). Reject obsidian in the
     * footprint so the frame is built on FRESH ground beside the pool -- then it needs exactly its ten
     * placements and never re-gathers.
     */
    private static boolean isDecentBuildSite(AltoClef mod, BlockPos origin) {
        World world = mod.getWorld();
        if (world == null) return false;
        for (BlockPos f : WorldHelper.scanRegion(origin.add(-1, -2, -1), origin.add(1, -2, 2))) {
            if (!WorldHelper.isSolidBlock(f)) return false;                    // must have a floor (not a pit-with-no-floor / mid-air)
            var b = world.getBlockState(f).getBlock();
            if (b == Blocks.LAVA || b == Blocks.WATER || b == Blocks.OBSIDIAN) return false;
        }
        for (int dy = -1; dy <= 3; dy++) {                                     // open column above origin: not sealed in
            if (!world.getBlockState(origin.add(0, dy, 0)).isAir()) return false;
        }
        for (BlockPos a : WorldHelper.scanRegion(origin.add(-1, -1, -1), origin.add(1, 3, 2))) {
            var b = world.getBlockState(a).getBlock();
            if (b == Blocks.LAVA || b == Blocks.WATER || b == Blocks.OBSIDIAN) return false;  // no lava/water/obsidian in the frame region
        }
        return true;
    }

    /**
     * A clean, open, flat pad for the portal: a solid floor under the whole footprint and clear air
     * for the entire frame envelope above it, no lava/water in it. Built from the ground up, every
     * obsidian places against the floor or the block below it -- no mid-air scaffolding, no pit, no
     * lava, no body enclosure. Deliberately strict: a portal sited anywhere the cast dug up is the
     * exact wedge this avoids.
     *
     * <p>⛔ Also rejects an OBSIDIAN floor (G108, 2026-09-18): the flood-lava gather leaves an obsidian
     * sheet, and a frame sited on it churns gather-against-build (see isDecentBuildSite). Building on
     * fresh ground keeps the frame's ten placements the only obsidian the task ever spends here.
     */
    private static boolean isCleanFlatBuildSite(AltoClef mod, BlockPos origin) {
        World world = mod.getWorld();
        if (world == null) return false;
        // solid floor pad beneath the whole footprint (y=-2, x in [-1,1], z in [-1,2])
        for (BlockPos f : WorldHelper.scanRegion(origin.add(-1, -2, -1), origin.add(1, -2, 2))) {
            if (!WorldHelper.isSolidBlock(f)) return false;
            var b = world.getBlockState(f).getBlock();
            if (b == Blocks.LAVA || b == Blocks.WATER || b == Blocks.OBSIDIAN) return false;
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
        // ⛔ SITE THE FRAME BEFORE GATHERING, ON PRISTINE GROUND (G108, 2026-09-18). The obsidian is
        // now made by FLOODING a lava lake, which tears up the terrain where the bot gathers (a lava
        // pool turned to obsidian, then mined into a pocked sheet with holes). This task used to gather
        // ALL the obsidian FIRST and only then look for a build site -- so it always sited the frame
        // standing in the mess it had just made: bottom cells over mined-out holes needed mid-air
        // scaffolding, remaining pool obsidian sat in the frame region, and the gather/build fought over
        // the same blocks in an endless place/mine churn that never lit a portal (bench: obsidian
        // cycling 0<->10 for 240 s, no portal). Choosing the origin FIRST -- while the bot still stands
        // on undisturbed ground -- fixes it at the root: the flood then happens at the lake (away from
        // the origin), and the bot returns to a clean, reserved pad to build. A human picks the spot,
        // goes for obsidian, and comes back; so does the bot now.
        if (origin == null) {
            if (_areaSearchTimer.elapsed()) {
                _areaSearchTimer.reset();
                Debug.logMessage("(Searching for area to build portal nearby...)");
                origin = getBuildableAreaNearby(mod);
            }
            setDebugState("Looking for portalable area...");
            return new TimeoutWanderTask();
        }

        int neededObsidian = PORTAL_FRAME.length;
        BlockPos placeTarget = null;
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

        // Get obsidian if we don't have (the frame site is already reserved, so this gathers away
        // from it and returns). Gather a small RESERVE on top of the frame's need: a top-row cell
        // occasionally loses a block during placement (a mis-place that the loop then clears, or a
        // drop while repositioning at head height), and re-gathering a single block means a full
        // round-trip back to the lake mid-build -- which on a tight window is the difference between
        // finishing and timing out at 13/14 (measured). The spare is free: one lake flood makes 20+.
        int obsidianReserve = 3;
        if (mod.getItemStorage().getItemCount(Items.OBSIDIAN) < neededObsidian) {
            setDebugState("Getting obsidian");
            return TaskCatalogue.getItemTask(Items.OBSIDIAN, neededObsidian + obsidianReserve);
        }

        // Get flint and steel
        if (!mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)) {
            setDebugState("Getting flint and steel");
            return TaskCatalogue.getItemTask(Items.FLINT_AND_STEEL, 1);
        }

        // ⛔ KEEP THE BUILDER AT THE BUILD (G108, 2026-09-19). On messy natural terrain a placement
        // that cannot reach its stand falls to TimeoutWanderTask, and that wander DRIFTS the body far
        // from the frame -- measured 26 blocks away on the gamer server, holding the block, never
        // returning, so the build is abandoned. (The flat bench never drifts: its pad is clean, every
        // stand solid, no placement stalls -- proven, a hand-laid clean pad builds AND lights the
        // portal on the same server.) So whenever we are in the build phase and the body has strayed
        // well past the frame's stand range, walk it back to the origin first; the drain then
        // re-approaches the cell locally. This also pulls the body back from the lava after a gather.
        // No-op on the bench, where the body stays within a couple of blocks of the frame.
        if (placeTarget != null) {
            BlockPos body = mod.getPlayer().getBlockPos();
            long ddx = body.getX() - origin.getX(), ddz = body.getZ() - origin.getZ();
            long ddy = body.getY() - origin.getY();
            // ⛔ ...AND STRAYED VERTICALLY TOO (G108, 2026-09-21). This guard measured X/Z only, so a
            // body standing right UNDER the frame's column was "at the build" to it. Measured on a
            // recorded nether-reach run: the frame origin was chosen on the surface at (732,69,832),
            // the bot then mined its obsidian deep and ended at (731.7,13,833.7) -- 1.4 blocks away
            // horizontally, 56 blocks BELOW -- so this check never fired, PlaceBlockTask fell to
            // TimeoutWanderTask ("Wander for 5 blocks / Failed exploring") against a wall at y=13 for
            // the last four minutes of the run, and the portal that had all 14 obsidian in hand was
            // never placed. A frame is five blocks tall and its stands are within a couple of blocks
            // of the origin, so more than six blocks of vertical separation is never "at the build":
            // walk back up (or down) to the origin first, exactly as for a horizontal drift.
            // ⛔ MEASURE THE STRAY AGAINST THE FRAME, NOT THE ORIGIN POINT (G108, 2026-09-21). The
            // vertical test was |dy| > 6, symmetric about the origin -- but the frame is NOT
            // symmetric about it: it occupies origin.y-1 (the bottom row on the floor pad) up to
            // origin.y+3 (the top row), and every stand the build needs is within that band. So the
            // old bound called a body FOUR BLOCKS BELOW THE FRAME'S FLOOR "at the build". Measured on
            // a 35-minute nether-reach run (2026-09-21) with the re-siting fix live: frame at
            // origin (749,14,823), ten of fourteen obsidian placed into a stone wall, body at y=9 --
            // dy=-5, inside the old bound, so this guard stayed silent -- sealed under the rock in the
            // cave below, unable to reach the scaffold cell at (750,16,822). It shimmied there for
            // NINETEEN MINUTES, UnstuckChain firing every ten seconds, and the run ended 10/14.
            // Bound the band to the frame plus two blocks of slack each way instead.
            boolean strayedDown = ddy < -3;                      // below the floor pad (origin.y-2)
            boolean strayedUp = ddy > 5;                         // above the top row (origin.y+3)
            if (ddx * ddx + ddz * ddz > 100 || strayedDown || strayedUp) {   // > 10 blocks horizontally, or off the frame's band
                // ⛔ ...AND A PAD THE BODY CANNOT GET BACK TO IS NOT A PAD (G108, 2026-09-21). The two
                // guards above walk the body back to a RESERVED origin, and they assumed the walk is
                // always possible. It is not. The site is chosen while the bot stands wherever the
                // portal task began -- usually the surface -- and the obsidian is then made by
                // flooding a lava lake, which on natural terrain is tens of blocks DOWN a cave. Then
                // this guard fires by construction on every tick, for a walk the drive cannot make.
                // Measured on a 35-minute nether-reach run (2026-09-21): origin (735,68,829), body
                // (799,4,846) -- 64 blocks down, 64 across, in the water of the cave it had just
                // flooded -- "Returning to the portal build" -> GetToBlockTask for the LAST NINE
                // MINUTES of the run, position frozen to the decimetre, 17 obsidian in the pack and
                // no portal. The run before it lost its last four minutes to the horizontal twin of
                // the same thing.
                //
                // So: keep siting FIRST (that is right, and the reasons are above), but treat the
                // reservation as provisional. When the return stops getting closer, the pad is
                // unreachable FROM HERE: drop it and re-site from where the body actually is, which
                // is beside the pool it just made. Siting there is safe now in a way it was not when
                // "site first" was introduced -- isDecentBuildSite / isCleanFlatBuildSite both reject
                // obsidian, lava and water in the footprint, so the search cannot land in the mess
                // the flood left; it lands on clean ground next to it. A human who carried obsidian
                // up out of a cave and found the surface pad unreachable would build by the cave
                // mouth, not spend nine minutes on the climb.
                double distSq = body.getSquaredDistance(origin);
                if (distSq < _returnBestDistSq - 1.0) {
                    _returnBestDistSq = distSq;
                    _returnNoProgressTicks = 0;
                } else if (++_returnNoProgressTicks > RETURN_NO_PROGRESS_LIMIT) {
                    Debug.logMessage(String.format(
                            "Portal pad %s is unreachable from %s (no progress for %d ticks) — re-siting here",
                            origin.toShortString(), body.toShortString(), RETURN_NO_PROGRESS_LIMIT));
                    origin = null;
                    // The interior-clearing target is origin-relative. Left behind, it would send the
                    // bot back to the ABANDONED site to break a block the moment the new frame closed.
                    _destroyTarget = null;
                    _returnBestDistSq = Double.MAX_VALUE;
                    _returnNoProgressTicks = 0;
                    _areaSearchTimer.forceElapse();   // search again on the very next tick
                    return null;
                }
                setDebugState("Returning to the portal build");
                return new GetToBlockTask(origin, false);
            }
            // At the build: the next stray starts its progress measurement from scratch.
            _returnBestDistSq = Double.MAX_VALUE;
            _returnNoProgressTicks = 0;
        }

        // Before placing a TOP-ROW cell (y = origin+3, over the open interior), raise the temporary
        // front standing wall so the cell gets a reachable SIDE stand instead of the flaky
        // pillar-into-cell climb. Built bottom-up; each block rests on the one below (or the floor pad
        // the site check guarantees at x=+1), so it places from a side stand exactly like the frame
        // columns do. It is mined back out below, before the interior is cleared and the portal is lit.
        //
        // ⛔ NOT y>=origin+2 (reverted 2026-09-19). Extending the scaffold to the COLUMN TOPS was
        // measured a NET REGRESSION on the full flood (0/2 vs 0.95.17's 3/4): it adds the scaffold's
        // OWN high edge cells (dy=2, e.g. (1,2,2)) as new placements, and those have the same
        // high-over-open-space stall as the frame cells they were meant to help -- the bot balances
        // on the 1-wide wall top, shimmies, and the escape wander cannot leave the ledge, so the
        // scaffold never completes and the frame's stand is missing anyway. The column tops keep the
        // pillar path (guarded by PillarTask's target-column pin, G108 3a, so it can no longer cast
        // off-plane); the deeper high-cell stand reliability is the remaining ceiling, tracked as the
        // next pass rather than papered over with more high scaffold cells.
        if (placeTarget != null && placeTarget.getY() == origin.getY() + 3 && !frontScaffoldComplete(mod)) {
            BlockPos scaffoldCell = nextFrontScaffoldCell(mod);
            if (scaffoldCell != null) {
                setDebugState("Raising front scaffold to reach the top row");
                return new PlaceStructureBlockTask(scaffoldCell);
            }
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

        // The frame is complete -> mine the temporary front scaffold back out (top-down, so removing a
        // support never strands the bot above the rest) before clearing the interior and lighting.
        BlockPos scaffoldBlock = highestFrontScaffoldBlock(mod);
        if (scaffoldBlock != null) {
            setDebugState("Removing front scaffold");
            return new DestroyBlockTask(scaffoldBlock);
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

    /** Every cell of the temporary front standing wall is solid. */
    private boolean frontScaffoldComplete(AltoClef mod) {
        for (Vec3i o : FRONT_SCAFFOLD) {
            if (!WorldHelper.isSolidBlock(origin.add(o))) return false;
        }
        return true;
    }

    /** The next front-scaffold cell to place: the lowest air cell that already rests on a solid block,
     *  so the wall goes up bottom-up and every block places from a side stand. Null when complete. */
    private BlockPos nextFrontScaffoldCell(AltoClef mod) {
        for (int dy = -1; dy <= 2; dy++) {
            for (int dz = -1; dz <= 2; dz++) {
                BlockPos p = origin.add(1, dy, dz);
                if (WorldHelper.isSolidBlock(p)) continue;          // already built
                if (!WorldHelper.isSolidBlock(p.down())) continue;  // no support yet; build lower first
                return p;
            }
        }
        return null;
    }

    /** The highest non-air front-scaffold cell, to mine the wall back out top-down. Null when gone. */
    private BlockPos highestFrontScaffoldBlock(AltoClef mod) {
        for (int dy = 2; dy >= -1; dy--) {
            for (int dz = -1; dz <= 2; dz++) {
                BlockPos p = origin.add(1, dy, dz);
                if (!WorldHelper.isAir(p)) return p;
            }
        }
        return null;
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
