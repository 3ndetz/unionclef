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
        // No carry-over between legs: the gap between two wanders is not ground this task covered.
        lastTickPos = null;
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

        // AN ODOMETER THAT ONLY RUNS WHILE THIS TASK DOES.
        //
        // The first attempt to prove the wander fix measured how far the bot got from its start
        // over a whole course, and the A/B threw it out: the unfixed build travelled FURTHER
        // (38.0 blocks against 26.8), because the search task's own approach and the shimmy in the
        // stuck branch walk the body regardless. A gate like that answers "did the bot go
        // anywhere", which was never in question.
        //
        // This counts ground covered ONLY on ticks where this task is the one running, so no other
        // task's movement can satisfy it. That is the difference between a measurement and a
        // reassurance, and it is what makes the course able to FAIL on a build where wandering
        // issues no movement of its own.
        wanderTicks++;
        ClientPlayerEntity self = mod.getPlayer();
        if (self != null) {
            if (lastTickPos != null) {
                double dx = self.getX() - lastTickPos.x;
                double dz = self.getZ() - lastTickPos.z;
                wanderMovedCm += (int) Math.round(Math.sqrt(dx * dx + dz * dz) * 100);
            }
            lastTickPos = self.getPos();
        }

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
        // REVERTED, ON A MEASUREMENT THAT REFUTED MY OWN REASONING. KEEP THIS NOTE.
        //
        // The reasoning looked airtight: explore() and Nav.isExploring() BOTH address the legacy
        // engine, which stopped driving the body when tungsten became the default, so this branch
        // returns null having issued no movement -- and "Failed exploring." x11 appeared on the one
        // craft_iron_pickaxe run that failed. I replaced it with a real destination on the live
        // engine (GetToXZTask) and the craft suite stayed 6/6.
        //
        // Then the A/B, with an odometer that counts ground covered ONLY on ticks where this task
        // is the one running, so no other task's movement can flatter either side:
        //
        //   with the replacement:  wanderMoved=24.6   overallMoved=30.0
        //   with THIS code:        wanderMoved=42.6   overallMoved=41.8
        //
        // The bot covers MORE ground under the supposedly dead code. So wandering was never a
        // no-op -- something here does move the body (the shimmy in the stuck branch is the
        // obvious candidate) -- and the replacement measurably did LESS. The call below is still a
        // dead-engine call and still has to go for G-0, but it has to go as a real port of
        // exploration onto tungsten, not a one-line substitution that trades measured coverage for
        // a tidier dependency graph.
        if (!Nav.isExploring()) {
            mod.getClientBaritone().getExploreProcess().explore((int) origin.getX(), (int) origin.getZ());
        }
        if (!progressChecker.check(mod)) {
            progressChecker.reset();
            if (!_forceExplore) {
                failCounter++;
                Debug.logMessage("Failed exploring.");
            }
        }
        return null;
    }

    /** Ticks this task spent running. Read as wander. */
    public static volatile int wanderTicks;

    /**
     * Ground covered, in centimetres, on ticks where THIS task was the one running. Read as
     * wanderMoved. Centimetres so it stays an int in the stats line; a course divides by 100.
     */
    public static volatile int wanderMovedCm;

    private Vec3d lastTickPos;

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
