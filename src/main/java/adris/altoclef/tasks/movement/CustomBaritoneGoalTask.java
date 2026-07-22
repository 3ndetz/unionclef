package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.InputControls;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.TungstenHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import net.minecraft.block.*;
import net.minecraft.util.math.BlockPos;

/**
 * Turns a baritone goal into a task.
 */
public abstract class CustomBaritoneGoalTask extends Task implements ITaskRequiresGrounded {
    private final Task wanderTask = new TimeoutWanderTask(5, true);
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final boolean wander;
    protected MovementProgressChecker checker = new MovementProgressChecker();
    protected Goal cachedGoal = null;
    // Anti-permanent-stuck (tungsten-primary): if the bot hasn't moved for a while,
    // the tungsten nav is trapped (unreachable sub-goal / stale-rooted reject loop) —
    // reset its state so it re-plans fresh, then yield to wander if it stays stuck.
    private net.minecraft.util.math.Vec3d twStuckPos = null;
    private long twStuckSinceMs = 0L;
    private int twStuckResets = 0;
    // The walker can't parkour (gap jumps / wall climbs). When it stalls we hand the
    // segment to the physics executor (which can) for a window, then re-try the walker.
    private long twPreferExecutorUntilMs = 0L;
    Block[] annoyingBlocks = new Block[]{
            Blocks.VINE,
            Blocks.NETHER_SPROUTS,
            Blocks.CAVE_VINES,
            Blocks.CAVE_VINES_PLANT,
            Blocks.TWISTING_VINES,
            Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES_PLANT,
            Blocks.LADDER,
            Blocks.BIG_DRIPLEAF,
            Blocks.BIG_DRIPLEAF_STEM,
            Blocks.SMALL_DRIPLEAF,
            Blocks.TALL_GRASS,
            Blocks.SHORT_GRASS,
            Blocks.SWEET_BERRY_BUSH
    };
    private Task unstuckTask = null;

    // This happens all the time in mineshafts and swamps/jungles

    public CustomBaritoneGoalTask(boolean wander) {
        this.wander = wander;
    }

    public CustomBaritoneGoalTask() {
        this(true);
    }

    private static BlockPos[] generateSides(BlockPos pos) {
        return new BlockPos[]{
                pos.add(1,0,0),
                pos.add(-1,0,0),
                pos.add(0,0,1),
                pos.add(0,0,-1),
                pos.add(1,0,-1),
                pos.add(1,0,1),
                pos.add(-1,0,-1),
                pos.add(-1,0,1)
        };
    }

    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        for (Block annoyingBlock : annoyingBlocks) {
            if (block == annoyingBlock) return true;
        }
        return block instanceof DoorBlock ||
                block instanceof FenceBlock ||
                block instanceof FenceGateBlock ||
                block instanceof FlowerBlock;
    }

    private BlockPos stuckInBlock(AltoClef mod) {
        BlockPos p = mod.getPlayer().getBlockPos();
        if (isAnnoying(mod, p)) return p;
        if (isAnnoying(mod, p.up())) return p.up();
        BlockPos[] toCheck = generateSides(p);
        for (BlockPos check : toCheck) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        BlockPos[] toCheckHigh = generateSides(p.up());
        for (BlockPos check : toCheckHigh) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        return null;
    }

    private Task getFenceUnstuckTask() {
        return new SafeRandomShimmyTask();
    }

    @Override
    protected void onStart() {
        AltoClef.getInstance().getClientBaritone().getPathingBehavior().forceCancel();
        TungstenHelper.reset();
        checker.reset();
        stuckCheck.reset();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();
        InputControls controls = mod.getInputControls();
        
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            checker.reset();
        }
        if (WorldHelper.isInNetherPortal()) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                controls.hold(Input.SNEAK);
                controls.hold(Input.MOVE_FORWARD);
                return null;
            } else {
                controls.release(Input.SNEAK);
                controls.release(Input.MOVE_BACK);
                controls.release(Input.MOVE_FORWARD);
            }
        } else {
            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                controls.release(Input.SNEAK);
                controls.release(Input.MOVE_BACK);
                controls.release(Input.MOVE_FORWARD);
            }
        }
        if (unstuckTask != null && unstuckTask.isActive() && !unstuckTask.isFinished() && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return unstuckTask;
        }
        if (!checker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                unstuckTask = getFenceUnstuckTask();
                return unstuckTask;
            }
            // Not in annoying block — force baritone to recompute, so wander fallback can fire
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            stuckCheck.reset();
        }
        if (cachedGoal == null) {
            cachedGoal = newGoal(mod);
        }

        // ── Tungsten-PRIMARY (drop-in swap, TODO 13) ──
        if (driveTungstenPrimary(mod)) return null;

        // ── Tungsten lock: exclusive 30s control, Baritone stays off ──
        if (TungstenHelper.isLocked()) {
            TungstenHelper.tickLock();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            checker.reset();
            long remaining = Math.max(0, (TungstenHelper.lockUntilMs() - System.currentTimeMillis()) / 1000);
            setDebugState("Tungsten pathfinding (" + remaining + "s left)");
            return null;
        }

        // If Tungsten is actively pathfinding (outside lock), let it finish
        if (TungstenHelper.isActive()) {
            checker.reset();
            setDebugState("Tungsten fallback pathfinding...");
            return null;
        }

        if (wander) {
            if (isFinished()) {
                // Don't wander if we've reached our goal.
                checker.reset();
                TungstenHelper.stop();
            } else {
                if (wanderTask.isActive() && !wanderTask.isFinished()) {
                    setDebugState("Wandering...");
                    checker.reset();
                    return wanderTask;
                }
                if (!checker.check(mod)) {
                    // Baritone failed — try Tungsten before wandering
                    if (cachedGoal != null) {
                        var player = mod.getPlayer();
                        var goalPos = new net.minecraft.util.math.Vec3d(
                                player.getX(), player.getY(), player.getZ());
                        // Try to extract goal position from cachedGoal for Tungsten
                        if (cachedGoal instanceof baritone.api.pathing.goals.GoalBlock gb) {
                            goalPos = new net.minecraft.util.math.Vec3d(gb.x, gb.y, gb.z);
                        } else if (cachedGoal instanceof baritone.api.pathing.goals.GoalGetToBlock gg) {
                            goalPos = new net.minecraft.util.math.Vec3d(gg.x, gg.y, gg.z);
                        }
                        if (TungstenHelper.tryPathTo(goalPos)) {
                            mod.getClientBaritone().getPathingBehavior().forceCancel();
                            setDebugState("Baritone stuck, trying Tungsten...");
                            return null;
                        }
                    }
                    Debug.logMessage("Failed to make progress on goal, wandering.");
                    onWander(mod);
                    return wanderTask;
                }
            }
        }
        if (!TungstenHelper.isActive()
                && !mod.getClientBaritone().getCustomGoalProcess().isActive()
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(cachedGoal);
        }
        setDebugState("Completing goal.");
        return null;
    }

    @Override
    public boolean isFinished() {
        if (cachedGoal == null) {
            cachedGoal = newGoal(AltoClef.getInstance());
        }
        return cachedGoal != null && cachedGoal.isInGoal(AltoClef.getInstance().getPlayer().getBlockPos());
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef.getInstance().getClientBaritone().getPathingBehavior().forceCancel();
        TungstenHelper.stop();
    }

    protected abstract Goal newGoal(AltoClef mod);

    protected void onWander(AltoClef mod) {
    }

    /** Drop-in swap (TODO 13): when tungsten is PRIMARY, drive movement via
     *  tungsten directly (the same call ;goto uses — baritone movement doesn't
     *  execute on headless clients). Async: PATHFINDER.find kicks a background
     *  search, so this never blocks. Returns true if it took control (caller
     *  should return null to keep baritone off). Subclasses that override
     *  onTick (e.g. GetToBlockTask's wander) MUST call this BEFORE their own
     *  stuck/wander logic, or the wander loop starves the swap. */
    protected boolean driveTungstenPrimary(AltoClef mod) {
        if (!TungstenHelper.isPrimary()) return false;
        if (cachedGoal == null) cachedGoal = newGoal(mod);
        if (cachedGoal == null || isFinished()) return false;
        net.minecraft.util.math.Vec3d gp = goalToVec(cachedGoal, mod);
        if (gp == null) return false;

        // ── Anti-permanent-stuck safety net ──────────────────────────────
        long nowMs = System.currentTimeMillis();
        net.minecraft.util.math.Vec3d plNow = new net.minecraft.util.math.Vec3d(
                mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ());
        if (twStuckPos == null || plNow.distanceTo(twStuckPos) > 0.75) {
            twStuckPos = plNow; twStuckSinceMs = nowMs; twStuckResets = 0;
        } else if (kaptainwutax.tungsten.task.BlockPathWalker.isRunning() && nowMs - twStuckSinceMs > 2500) {
            // The WALKER stalled — most likely a parkour move it can't do (gap jump /
            // wall climb). Hand this segment to the physics executor (which parkours)
            // for a window, then re-try the walker.
            kaptainwutax.tungsten.task.BlockPathWalker.stop();
            twPreferExecutorUntilMs = nowMs + 8000;
            twStuckSinceMs = nowMs;
        } else if (nowMs - twStuckSinceMs > 5000) {
            // Even the executor is stuck — trapped (stale-rooted reject loop /
            // unreachable sub-goal). Reset the nav to re-plan from the ACTUAL position;
            // after a few fruitless resets, yield to the wander so we walk out of a
            // local trap instead of freezing forever.
            var pfR = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER;
            var exR = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            if (pfR != null) { pfR.stop.set(true); pfR.overrideStartPos = null; }
            if (exR != null) exR.stop = true;
            kaptainwutax.tungsten.task.BlockPathWalker.stop();
            twStuckSinceMs = nowMs;
            if (++twStuckResets >= 3) { twStuckResets = 0; twStuckPos = null; return false; }
        }

        try {
            var pf = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER;
            var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            boolean walking = kaptainwutax.tungsten.task.BlockPathWalker.isRunning();

            // DRIFT-IMMUNE terrain nav gets PRIORITY (user's directive: @gamer must be
            // extremely stable, never stuck). The physics executor replays a simulated
            // trajectory that DRIFTS on steps/slopes; at drift>threshold it hard-stops
            // AND the search rejects its own path ("root far from player") — so the
            // pathfinder is perpetually busy, never yielding, and the bot stalls. The
            // BlockPathWalker instead sprints from the bot's REAL position toward each
            // block-path waypoint (CombatPathfinder's grid BFS already does step-up/down),
            // so drift can't accumulate. When a walkable block path exists we FORCE the
            // drift-prone pathfinder/executor off and let the walker own movement; the
            // path is re-planned per ~25-block segment (rolling horizon). Water/parkour,
            // where the block BFS returns nothing, fall through to the physics executor.
            double dgx = mod.getPlayer().getX() - gp.x, dgy = mod.getPlayer().getY() - gp.y,
                    dgz = mod.getPlayer().getZ() - gp.z;
            double distToGoal = Math.sqrt(dgx * dgx + dgy * dgy + dgz * dgz);
            // Walker owns the LONG haul (drift-immune); the physics executor does the
            // final ~4-block precise approach (short range = negligible drift), which
            // closes the last steps a short "within 1.5 of goal" BFS path stalls on.
            // Close to the goal — stop the walker; the executor does the final <=4-block
            // precise approach (short range = negligible drift).
            if (walking && distToGoal <= 4.0) { kaptainwutax.tungsten.task.BlockPathWalker.stop(); walking = false; }
            if (walking) {
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                checker.reset();
                setDebugState("Tungsten (primary) walking terrain...");
                return true;
            }
            if (distToGoal > 4.0 && !mod.getPlayer().isTouchingWater()
                    && nowMs >= twPreferExecutorUntilMs) {
                // (1) cheap grid BFS — instant, good for near/clean terrain.
                net.minecraft.util.math.BlockPos startB = mod.getPlayer().getBlockPos();
                net.minecraft.util.math.BlockPos goalB = net.minecraft.util.math.BlockPos.ofFloored(gp);
                java.util.List<net.minecraft.util.math.BlockPos> bfs =
                        kaptainwutax.tungsten.combat.CombatPathfinder.findPath(startB, goalB, mod.getWorld());
                // A degenerate 2-waypoint stub to a FAR goal means the cheap grid BFS
                // couldn't route the terrain (e.g. gapped diagonal steps): its endpoint
                // barely progresses toward the goal. Walking such a stub makes the walker
                // stop/restart every step, which kills the sprint momentum a running jump
                // needs. Fall through to the robust elevation-aware block path (2)/(3) —
                // one continuous path the walker rides without stopping.
                boolean degenerateStub = bfs.size() == 2 && distToGoal > 6.0
                        && Math.sqrt(bfs.get(1).getSquaredDistance(goalB)) > distToGoal - 3.0;
                if (bfs.size() >= 2 && !degenerateStub) {
                    if (pf != null) pf.stop.set(true);
                    if (ex != null) ex.stop = true;
                    kaptainwutax.tungsten.task.BlockPathWalker.startBFS(bfs);
                    mod.getClientBaritone().getPathingBehavior().forceCancel();
                    checker.reset();
                    setDebugState("Tungsten (primary) walking terrain...");
                    return true;
                }
                // (2) cheap BFS can't route this (natural terrain, >25 blocks) — follow the
                // ROBUST elevation-aware block path the async search computes, drift-immune,
                // instead of the drift-prone physics executor (user's directive).
                java.util.Optional<java.util.List<kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode>> bp =
                        kaptainwutax.tungsten.path.PathFinder.getComputedBlockPath();
                if (bp.isPresent() && bp.get().size() >= 2) {
                    java.util.List<net.minecraft.util.math.BlockPos> wps = new java.util.ArrayList<>();
                    for (kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode n : bp.get()) wps.add(n.getBlockPos());
                    if (ex != null) ex.stop = true;   // don't let the executor drift-replay
                    kaptainwutax.tungsten.task.BlockPathWalker.startBFS(wps);
                    mod.getClientBaritone().getPathingBehavior().forceCancel();
                    checker.reset();
                    setDebugState("Tungsten (primary) walking (robust path)...");
                    return true;
                }
                // (3) no block path yet — kick the async search to compute one.
                boolean busy = (pf != null && pf.active.get()) || (ex != null && ex.isRunning());
                if (!busy && pf != null) { if (ex != null) ex.stop = false; pf.find(mod.getWorld(), gp, mod.getPlayer()); }
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                checker.reset();
                setDebugState("Tungsten (primary) planning...");
                return true;
            }
            // Final approach (<=4 blocks) or water → physics executor.
            boolean busy = (pf != null && pf.active.get()) || (ex != null && ex.isRunning());
            if (!busy && pf != null) {
                if (ex != null) ex.stop = false;   // a prior ;stop leaves it stuck true
                pf.find(mod.getWorld(), gp, mod.getPlayer());
            }
        } catch (Throwable t) {
            Debug.logInternal("[swap] tungsten primary drive failed: " + t);
        }
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        checker.reset();
        setDebugState("Tungsten (primary) pathfinding...");
        return true;
    }

    /** Extract a target position from a baritone goal for tungsten (GoalBlock /
     *  GoalGetToBlock / GoalNear carry x,y,z). Null if the goal has no point. */
    private static net.minecraft.util.math.Vec3d goalToVec(Goal goal, AltoClef mod) {
        net.minecraft.util.math.Vec3d raw = null;
        if (goal instanceof baritone.api.pathing.goals.GoalBlock gb) {
            raw = new net.minecraft.util.math.Vec3d(gb.x, gb.y, gb.z);
        } else if (goal instanceof baritone.api.pathing.goals.GoalGetToBlock gg) {
            raw = new net.minecraft.util.math.Vec3d(gg.x, gg.y, gg.z);
        }
        return raw == null ? null : snapGoalToStandable(raw, mod);
    }

    /** A goal cell that isn't standable (inside a solid block, or floating in air
     *  above the ground — e.g. a click on a grass block reports the cell ABOVE the
     *  surface) can never be reached exactly, so the tungsten search stalls at it.
     *  Snap it to the nearest standable cell (surface on top of a block / ground
     *  below the air) so the bot actually approaches. Valid standable goals are
     *  returned unchanged — normal navigation is untouched. */
    private static net.minecraft.util.math.Vec3d snapGoalToStandable(net.minecraft.util.math.Vec3d gp, AltoClef mod) {
        try {
            net.minecraft.world.World w = mod.getWorld();
            int gx = (int) Math.floor(gp.x), gy = (int) Math.floor(gp.y), gz = (int) Math.floor(gp.z);
            if (standable(w, gx, gy, gz)) return gp;                 // already fine
            if (isSolidAt(w, gx, gy, gz)) {                          // goal inside a block → stand on top
                for (int y = gy + 1; y <= gy + 5; y++)
                    if (standable(w, gx, y, gz)) return new net.minecraft.util.math.Vec3d(gx + 0.5, y, gz + 0.5);
            }
            for (int y = gy; y >= gy - 6; y--)                       // floating goal → drop to the ground
                if (standable(w, gx, y, gz)) return new net.minecraft.util.math.Vec3d(gx + 0.5, y, gz + 0.5);
        } catch (Throwable ignored) { }
        return gp;
    }

    private static boolean standable(net.minecraft.world.World w, int x, int y, int z) {
        return isSolidAt(w, x, y - 1, z) && !isSolidAt(w, x, y, z) && !isSolidAt(w, x, y + 1, z);
    }

    private static boolean isSolidAt(net.minecraft.world.World w, int x, int y, int z) {
        net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(x, y, z);
        return !w.getBlockState(p).getCollisionShape(w, p).isEmpty();
    }
}
