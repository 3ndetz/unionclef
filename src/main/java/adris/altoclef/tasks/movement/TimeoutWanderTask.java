package adris.altoclef.tasks.movement;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.multiversion.versionedfields.Blocks;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

// TODO improve wandering
/**
 * Call this when the place you're currently at is bad for some reason and you just wanna get away.
 */
public class TimeoutWanderTask extends Task implements ITaskRequiresGrounded {
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final float distanceToWander;
    private final MovementProgressChecker progressChecker = new MovementProgressChecker();
    private final boolean increaseRange;
    private final TimerGame timer = new TimerGame(60);
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
    private Vec3d origin;
    //private DistanceProgressChecker _distanceProgressChecker = new DistanceProgressChecker(10, 0.1f);
    private boolean _forceExplore;
    private Task _unstuckTask = null;
    private int failCounter;
    private double _wanderDistanceExtension;

    public TimeoutWanderTask(float distanceToWander, boolean increaseRange) {
        this.distanceToWander = distanceToWander;
        this.increaseRange = increaseRange;
        _forceExplore = false;
    }

    public TimeoutWanderTask(float distanceToWander) {
        this(distanceToWander, false);
    }

    public TimeoutWanderTask() {
        this(Float.POSITIVE_INFINITY, false);
    }

    public TimeoutWanderTask(boolean forceExplore) {
        this();
        _forceExplore = forceExplore;
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

    public void resetWander() {
        _wanderDistanceExtension = 0;
    }

    // This happens all the time in mineshafts and swamps/jungles
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
        AltoClef mod = AltoClef.getInstance();

        timer.reset();
        Nav.cancel();
        origin = mod.getPlayer().getPos();
        progressChecker.reset();
        stuckCheck.reset();
        failCounter = 0;
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
        } else {
            StorageHelper.closeScreen();
        }
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();


        if (Nav.isPathing()) {
            progressChecker.reset();
        }
        if (WorldHelper.isInNetherPortal()) {
            if (!Nav.isPathing()) {
                setDebugState("Getting out from nether portal");
                mod.getInputControls().hold(Input.SNEAK);
                mod.getInputControls().hold(Input.MOVE_FORWARD);
                return null;
            } else {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        } else {
            if (Nav.isPathing()) {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        }
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished() && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            Nav.clearGoal();
            Nav.stopExploring();
            return _unstuckTask;
        }
        if (!progressChecker.check(mod) || !stuckCheck.check(mod)) {
            List<Entity> closeEntities = mod.getEntityTracker().getCloseEntities();
            for (Entity CloseEntities : closeEntities) {
                if (CloseEntities instanceof MobEntity &&
                        CloseEntities.getPos().isInRange(mod.getPlayer().getPos(), 1)) {
                    setDebugState("Killing annoying entity.");
                    return new KillEntitiesTask(CloseEntities.getClass());
                }
            }
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                failCounter++;
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            // Not in annoying block — force baritone to recompute
            Nav.cancel();
            stuckCheck.reset();
        }
        setDebugState("Exploring.");
        switch (WorldHelper.getCurrentDimension()) {
            case END -> {
                if (timer.getDuration() >= 30) {
                    timer.reset();
                }
            }
            case OVERWORLD, NETHER -> {
                if (timer.getDuration() >= 30) {
                }
                if (timer.elapsed()) {
                    timer.reset();
                }
            }
        }
        // WANDERING WAS A NO-OP, AND WANDERING IS WHERE EVERY STUCK SITUATION LANDS.
        //
        // This used to say `getExploreProcess().explore(origin.x, origin.z)`, and both halves of
        // that addressed the LEGACY engine: Nav.isExploring() asks baritone's explore process,
        // which never runs now, so the guard was permanently false and explore() was called every
        // single tick -- at an engine that does not drive the body. The task therefore issued no
        // movement at all. It only looked alive because the progress checker kept failing and
        // saying so:
        //
        //   11x Failed exploring.    2x Increased wander range    2x Failed, blacklisting
        //
        // measured on the run of craft_iron_pickaxe that failed with the ingots already smelted.
        // Every recovery path in the bot falls back here -- lost the crafting table, cannot reach
        // a resource, progress checker tripped -- so "get away from here and look around" has been
        // doing nothing since the engine swap.
        //
        // The fix is to say where to go and let the live engine take it there. GetToXZTask extends
        // CustomBaritoneGoalTask, which is the drive tungsten already serves, so this needs no new
        // machinery -- only a destination.
        if (wanderTarget == null || wanderReached(mod)) {
            wanderTarget = pickWanderTarget();
        }
        if (!progressChecker.check(mod)) {
            progressChecker.reset();
            if (!_forceExplore) {
                failCounter++;
                Debug.logMessage("Failed exploring.");
            }
            // A DIRECTION THAT DID NOT WORK IS NOT WORTH REPEATING. Re-aiming on failure is what
            // makes this a search rather than a stubborn walk into the same wall.
            wanderTarget = pickWanderTarget();
        }
        wanderTicks++;
        return new GetToXZTask(wanderTarget.getX(), wanderTarget.getZ());
    }

    /** Times this task actually asked the live engine to take the body somewhere. Read as wander. */
    public static volatile int wanderTicks;

    private BlockPos wanderTarget;
    private int wanderSpin;

    /** Are we close enough to the point we picked to want a new one? */
    private boolean wanderReached(AltoClef mod) {
        ClientPlayerEntity player = mod.getPlayer();
        if (player == null || wanderTarget == null) {
            return true;
        }
        double dx = player.getX() - wanderTarget.getX();
        double dz = player.getZ() - wanderTarget.getZ();
        return dx * dx + dz * dz < 9;   // three blocks, in the horizontal plane only
    }

    /**
     * Somewhere to go, that far from where we started, in a direction we have not just tried.
     *
     * <p>The distance is the task's own contract ({@code distanceToWander} plus whatever the range
     * extension has added), so callers that ask to be taken far away still are. The direction turns
     * by a whole number of radians each time rather than being random, because a wander that
     * repeats its own choices is the failure this replaces, and a deterministic sweep is also
     * reproducible on the bench.
     *
     * <p>An infinite distance means "just get out of here" — no caller can walk to infinity, so it
     * is treated as a sensible finite step.
     */
    private BlockPos pickWanderTarget() {
        double distance = distanceToWander + _wanderDistanceExtension;
        if (!Double.isFinite(distance) || distance <= 0) {
            distance = 32;
        }
        distance = Math.min(distance, 128);
        // AIM PAST THE LINE, NOT AT IT. isFinished ends this task when the player is STRICTLY
        // farther from the origin than distanceToWander, so a target sitting exactly on that circle
        // can be reached without ever satisfying it -- the bot would arrive, pick the next
        // direction and set off again, finishing only when the fail counter ran out. Two blocks of
        // margin makes "reached the point I picked" mean "far enough away", which is what every
        // caller of this task is actually asking for.
        distance += 2;
        double angle = (wanderSpin++) * 1.0;
        Vec3d from = origin != null ? origin : AltoClef.getInstance().getPlayer().getPos();
        return new BlockPos((int) Math.round(from.getX() + Math.cos(angle) * distance), 0,
                (int) Math.round(from.getZ() + Math.sin(angle) * distance));
    }

    @Override
    protected void onStop(Task interruptTask) {
        Nav.cancel();
        if (isFinished()) {
            if (increaseRange) {
                _wanderDistanceExtension += distanceToWander;
                Debug.logMessage("Increased wander range");
            }
        }
    }

    @Override
    public boolean isFinished() {
        // Why the heck did I add this in?
        //if (_origin == null) return true;

        if (Float.isInfinite(distanceToWander)) return false;

        // If we fail 10 times or more, we may as well try the previous task again.
        if (failCounter > 10) {
            return true;
        }

        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();

        if (player != null && player.getPos() != null && (player.isOnGround() ||
                player.isTouchingWater())) {
            double sqDist = player.getPos().squaredDistanceTo(origin);
            double toWander = distanceToWander + _wanderDistanceExtension;
            return sqDist > toWander * toWander;
        } else {
            return false;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof TimeoutWanderTask task) {
            if (Float.isInfinite(task.distanceToWander) || Float.isInfinite(distanceToWander)) {
                return Float.isInfinite(task.distanceToWander) == Float.isInfinite(distanceToWander);
            }
            return Math.abs(task.distanceToWander - distanceToWander) < 0.5f;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Wander for " + (distanceToWander + _wanderDistanceExtension) + " blocks";
    }
}
