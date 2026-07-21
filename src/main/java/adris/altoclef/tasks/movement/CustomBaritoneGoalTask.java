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
    private static int _swapDiag = 0; // throttle for swap diagnostics

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
        boolean log = (_swapDiag++ % 20 == 0);
        if (!TungstenHelper.isPrimary()) { if (log) Debug.logMessage("[swap] not primary"); return false; }
        if (cachedGoal == null) cachedGoal = newGoal(mod);
        if (cachedGoal == null || isFinished()) { if (log) Debug.logMessage("[swap] no goal / finished (cg=" + cachedGoal + ")"); return false; }
        net.minecraft.util.math.Vec3d gp = goalToVec(cachedGoal, mod);
        if (gp == null) { if (log) Debug.logMessage("[swap] goalToVec null for " + cachedGoal.getClass().getSimpleName()); return false; }
        try {
            var pf = kaptainwutax.tungsten.TungstenModDataContainer.PATHFINDER;
            var ex = kaptainwutax.tungsten.TungstenModDataContainer.EXECUTOR;
            boolean busy = (pf != null && pf.active.get()) || (ex != null && ex.isRunning());
            if (log) {
                Debug.logMessage("[swap] primary drive: goal=" + gp + " pfNull=" + (pf == null)
                        + " exNull=" + (ex == null) + " busy=" + busy);
            }
            if (pf != null && !busy) {
                if (ex != null) ex.stop = false;   // a prior ;stop leaves it stuck true
                pf.find(mod.getWorld(), gp, mod.getPlayer());
                Debug.logMessage("[swap] find() called -> " + gp);
            }
        } catch (Throwable t) {
            Debug.logMessage("[swap] tungsten primary find failed: " + t);
        }
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        checker.reset();
        setDebugState("Tungsten (primary) pathfinding...");
        return true;
    }

    /** Extract a target position from a baritone goal for tungsten (GoalBlock /
     *  GoalGetToBlock / GoalNear carry x,y,z). Null if the goal has no point. */
    private static net.minecraft.util.math.Vec3d goalToVec(Goal goal, AltoClef mod) {
        if (goal instanceof baritone.api.pathing.goals.GoalBlock gb) {
            return new net.minecraft.util.math.Vec3d(gb.x, gb.y, gb.z);
        } else if (goal instanceof baritone.api.pathing.goals.GoalGetToBlock gg) {
            return new net.minecraft.util.math.Vec3d(gg.x, gg.y, gg.z);
        }
        return null;
    }
}
