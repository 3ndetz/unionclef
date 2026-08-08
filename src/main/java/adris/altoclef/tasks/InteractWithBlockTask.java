package adris.altoclef.tasks;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import kaptainwutax.tungsten.path.movements.Rotation;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import adris.altoclef.multiversion.versionedfields.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

import java.util.Objects;
import java.util.Optional;

/**
 * Left or Right click on a block on a particular (or any) side of the block.
 */
public class InteractWithBlockTask extends Task {
    private final MovementProgressChecker moveChecker = new MovementProgressChecker();
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final ItemTarget toUse;
    private final Direction direction;
    private final BlockPos target;
    private final boolean walkInto;
    private final Vec3i interactOffset;
    private final Input interactInput;
    private final boolean shiftClick;
    private final TimerGame clickTimer = new TimerGame(5);
    private final TimeoutWanderTask wanderTask = new TimeoutWanderTask(5, true);
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
    /** Walks us into reach when the target is too far to click. See the CANT_REACH branch. */
    private static final int INTERACT_APPROACH_RANGE = 3;
    private GetWithinRangeOfBlockTask approachTask = null;
    private ClickResponse cachedClickStatus = ClickResponse.CANT_REACH;
    private int waitingForClickTicks = 0;
    private int entityBlockingTicks = 0;

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, Vec3i interactOffset, boolean shiftClick) {
        this.toUse = toUse;
        this.direction = direction;
        this.target = target;
        this.interactInput = interactInput;
        this.walkInto = walkInto;
        this.interactOffset = interactOffset;
        this.shiftClick = shiftClick;
    }

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, boolean shiftClick) {
        this(toUse, direction, target, interactInput, walkInto, Vec3i.ZERO, shiftClick);
    }

    public InteractWithBlockTask(ItemTarget toUse, Direction direction, BlockPos target, boolean walkInto) {
        this(toUse, direction, target, Input.CLICK_RIGHT, walkInto, true);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target, boolean walkInto, Vec3i interactOffset) {
        // null means any side is OK
        this(toUse, null, target, Input.CLICK_RIGHT, walkInto, interactOffset, true);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target, boolean walkInto) {
        this(toUse, target, walkInto, Vec3i.ZERO);
    }

    public InteractWithBlockTask(ItemTarget toUse, BlockPos target) {
        this(toUse, target, false);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, Vec3i interactOffset, boolean shiftClick) {
        this(new ItemTarget(toUse, 1), direction, target, interactInput, walkInto, interactOffset, shiftClick);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Input interactInput, boolean walkInto, boolean shiftClick) {
        this(new ItemTarget(toUse, 1), direction, target, interactInput, walkInto, shiftClick);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, boolean walkInto) {
        this(new ItemTarget(toUse, 1), direction, target, walkInto);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target) {
        this(new ItemTarget(toUse, 1), direction, target, Input.CLICK_RIGHT, false, false);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target, boolean walkInto, Vec3i interactOffset) {
        this(new ItemTarget(toUse, 1), target, walkInto, interactOffset);
    }

    public InteractWithBlockTask(Item toUse, Direction direction, BlockPos target, Vec3i interactOffset) {
        this(new ItemTarget(toUse, 1), direction, target, Input.CLICK_RIGHT, false, interactOffset, false);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target, Vec3i interactOffset) {
        this(new ItemTarget(toUse, 1), null, target, Input.CLICK_RIGHT, false, interactOffset, false);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target, boolean walkInto) {
        this(new ItemTarget(toUse, 1), target, walkInto);
    }

    public InteractWithBlockTask(Item toUse, BlockPos target) {
        this(new ItemTarget(toUse, 1), target);
    }

    public InteractWithBlockTask(BlockPos target, boolean shiftClick) {
        this(ItemTarget.EMPTY, null, target, Input.CLICK_RIGHT, false, shiftClick);
    }

    public InteractWithBlockTask(BlockPos target) {
        this(ItemTarget.EMPTY, null, target, Input.CLICK_RIGHT, false, false);
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
        if (annoyingBlocks != null) {
            for (Block AnnoyingBlocks : annoyingBlocks) {
                return mod.getWorld().getBlockState(pos).getBlock() == AnnoyingBlocks ||
                        mod.getWorld().getBlockState(pos).getBlock() instanceof DoorBlock ||
                        mod.getWorld().getBlockState(pos).getBlock() instanceof FenceBlock ||
                        mod.getWorld().getBlockState(pos).getBlock() instanceof FenceGateBlock ||
                        mod.getWorld().getBlockState(pos).getBlock() instanceof FlowerBlock;
            }
        }
        return false;
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
        Nav.cancel();

        moveChecker.reset();
        stuckCheck.reset();
        wanderTask.resetWander();
        clickTimer.reset();
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        if (Nav.isPathing()) {
            moveChecker.reset();
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
        if (unstuckTask != null && unstuckTask.isActive() && !unstuckTask.isFinished() && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            Nav.clearGoal();
            Nav.stopExploring();
            return unstuckTask;
        }
        if (!moveChecker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                unstuckTask = getFenceUnstuckTask();
                return unstuckTask;
            }
            stuckCheck.reset();
        }

        cachedClickStatus = ClickResponse.CANT_REACH;

        // Get our use item first
        if (!ItemTarget.nullOrEmpty(toUse) && !StorageHelper.itemTargetsMet(mod, toUse)) {
            moveChecker.reset();
            clickTimer.reset();
            return TaskCatalogue.getItemTask(toUse);
        }

        // Wander and check
        if (wanderTask.isActive() && !wanderTask.isFinished()) {
            moveChecker.reset();
            clickTimer.reset();
            return wanderTask;
        }
        if (!moveChecker.check(mod)) {
            Debug.logMessage("Failed, blacklisting and wandering.");
            mod.getBlockScanner().requestBlockUnreachable(target);
            return wanderTask;
        }

        // G-0: THE GOAL HERE EXISTED ONLY TO FEED AN ENGINE THAT DOES NOT DRIVE.
        // createGoalForInteract built a baritone Goal (GoalTwoBlocks / GoalBlockSide / GoalAnd /
        // GoalNear) and handed it to getCustomGoalProcess().setGoalAndPath -- the legacy engine,
        // which has not moved the body since tungsten became the default. Measured before touching
        // it: the bot reaches a crafting table 28 blocks away regardless (craft_at_distant_table,
        // dxToTable 0.5-0.7), so something else does the walking and this call contributed nothing.
        // Removing it takes GoalBlockSide with it, which had no other user. GoalAnd STAYS: a first
        // pass deleted it too and the build caught a second user in GetToOuterEndIslandsTask -- the
        // grep that cleared it had filtered out the very directory the user lived in.
        //
        // The remaining engine questions go through Nav, which is null-safe and is the one place
        // that names an engine. This is a REFACTOR with no promised win, gated by the craft ladder,
        // which exercises this task on every single table craft.
        cachedClickStatus = rightClick(mod);
        switch (Objects.requireNonNull(cachedClickStatus)) {
            case CANT_REACH -> {
                // THIS SAID "Getting to our goal" AND THEN WENT NOWHERE, WHICH IS THE WHOLE BUG.
                // The G-0 pass above removed the goal that fed getCustomGoalProcess, on the grounds
                // that the legacy engine does not drive and "something else does the walking". The
                // second half was wrong: nothing else does. With the goal gone, an out-of-reach
                // target left this branch setting a debug string and resetting a timer -- no goal, no
                // movement -- so the bot announced it was getting to its goal and stood still.
                // Measured on craft_at_distant_table with a trace on the decision above: near=true,
                // makeNew=INF, the container task correctly returned this task on every tick, and
                // dist stayed 28.0 for the full five minutes. The bot never took a step.
                // The removal was right about the engine and wrong about the consequence. Movement is
                // restored through the LIVE path -- AltoGoal.near via GetWithinRangeOfBlockTask, the
                // same drive the water and lava escapes use -- not through the legacy process.
                // Cached, because building a fresh task each tick would restart the walk every tick.
                // Reusing one instance is safe even after the parent stops it: Task.stop() sets
                // first=true as well as stopped=true, and tick()'s if(first) block clears stopped
                // before the `if (stopped) return` guard is reached. So the cached task revives on its
                // next use rather than going inert -- checked, because it reads like a landmine.
                setDebugState("Getting to our goal");
                clickTimer.reset();
                if (approachTask == null) {
                    approachTask = new GetWithinRangeOfBlockTask(target, INTERACT_APPROACH_RANGE);
                }
                return approachTask;
            }
            case WAIT_FOR_CLICK -> {
                setDebugState("Waiting for click");
                if (Nav.hasGoal()) {
                    Nav.clearGoal();
                }
                clickTimer.reset();

                // try to get unstuck by pressing shift
                waitingForClickTicks++;
                if (waitingForClickTicks % 25 == 0 && shiftClick) {
                    mod.getInputControls().hold(Input.SNEAK);
                    mod.log("trying to press shift");
                }

                if (waitingForClickTicks > 10*20) {
                    mod.log("trying to wander");
                    waitingForClickTicks = 0;
                    return wanderTask;
                }
            }
            case CLICK_ATTEMPTED -> {
                setDebugState("Clicking.");
                if (Nav.hasGoal()) {
                    Nav.clearGoal();
                }
                if (clickTimer.elapsed()) {
                    // We tried clicking but failed.
                    clickTimer.reset();
                    return wanderTask;
                }
            }
        }

        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();

        Nav.cancel();
        mod.getInputControls().release(Input.SNEAK);
    }

    @Override
    public boolean isFinished() {
        return false;
        //return _trying && !proc(mod).isActive();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof InteractWithBlockTask task) {
            if ((task.direction == null) != (direction == null)) return false;
            if (task.direction != null && !task.direction.equals(direction)) return false;
            if ((task.toUse == null) != (toUse == null)) return false;
            if (task.toUse != null && !task.toUse.equals(toUse)) return false;
            if (!task.target.equals(target)) return false;
            if (!task.interactInput.equals(interactInput)) return false;
            return task.walkInto == walkInto;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Interact using " + toUse + " at " + target + " dir " + direction;
    }

    public ClickResponse getClickStatus() {
        return cachedClickStatus;
    }

    private ClickResponse rightClick(AltoClef mod) {

        // Don't interact if baritone can't interact.
        if (mod.getExtraBaritoneSettings().isInteractionPaused() || mod.getFoodChain().needsToEat() ||
                mod.getPlayer().isBlocking())
            return ClickResponse.WAIT_FOR_CLICK;

        // We can't interact while a screen is open.
        if (!StorageHelper.isPlayerInventoryOpen()) {
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (!cursorStack.isEmpty()) {
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                if (moveTo.isPresent()) {
                    mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                    return ClickResponse.WAIT_FOR_CLICK;
                }
                if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                    mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                    return ClickResponse.WAIT_FOR_CLICK;
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                // Try throwing away cursor slot if it's garbage
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                    return ClickResponse.WAIT_FOR_CLICK;
                }
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return ClickResponse.WAIT_FOR_CLICK;
            } else {
                StorageHelper.closeScreen();
            }
        }

        Optional<Rotation> reachable = getCurrentReach();
        if (reachable.isPresent()) {
            // Check if an entity (hologram, armor stand, etc.) is blocking our click
            if (MinecraftClient.getInstance().targetedEntity != null) {
                // Entity in the way — need to get closer or find another angle
                entityBlockingTicks++;
                if (entityBlockingTicks > 20) {
                    // Stuck for >1 second with entity blocking — try approaching from closer
                    entityBlockingTicks = 0;
                    return ClickResponse.CANT_REACH;
                }
                return ClickResponse.WAIT_FOR_CLICK;
            }
            entityBlockingTicks = 0;

            if (LookHelper.isLookingAt(mod, target)) {
                if (toUse != null) {
                    mod.getSlotHandler().forceEquipItem(toUse, false);
                } else {
                    mod.getSlotHandler().forceDeequipRightClickableItem();
                }
                mod.getInputControls().tryPress(interactInput);
                if (mod.getInputControls().isHeldDown(interactInput)) {
                    if (shiftClick) {
                        mod.getInputControls().hold(Input.SNEAK);
                    }
                    return ClickResponse.CLICK_ATTEMPTED;
                }
            } else {
                LookHelper.lookAt(reachable.get());
            }
            return ClickResponse.WAIT_FOR_CLICK;
        }
        if (shiftClick) {
            mod.getInputControls().release(Input.SNEAK);
        }
        return ClickResponse.CANT_REACH;
    }

    public Optional<Rotation> getCurrentReach() {
        return LookHelper.getReach(target, direction);
    }

    public enum ClickResponse {
        CANT_REACH,
        WAIT_FOR_CLICK,
        CLICK_ATTEMPTED
    }
}
