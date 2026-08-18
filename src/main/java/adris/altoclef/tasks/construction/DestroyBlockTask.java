package adris.altoclef.tasks.construction;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import kaptainwutax.tungsten.path.movements.Rotation;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.*;
import adris.altoclef.multiversion.versionedfields.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * Destroy a block at a position.
 */
public class DestroyBlockTask extends Task implements ITaskRequiresGrounded {

    /** Ticks, and each way this task gives up on its block; read over py4j in placeStats(). */
    public static volatile int dbTick, dbUnreachMove, dbUnreachWater, dbUnreachPillager,
            dbUnreachNear, dbUnreachFar, dbUnreachDistSum,
            dbNearTick, dbNearNoReach, dbNearAirborne, dbNearHungry, dbNearUnsafe, dbTargetAir, dbLeafCleared;
    /** Closest we have been to this task's block, squared; the yardstick for real progress. */
    private double _bestDistSq = Double.MAX_VALUE;
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final MovementProgressChecker _moveChecker = new MovementProgressChecker();
    private final BlockPos pos;
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
    private boolean isMining;

    public DestroyBlockTask(BlockPos pos) {
        this.pos = pos;
    }

    /**
     * Generates an array of BlockPos objects representing the sides of a given BlockPos.
     *
     * @param pos The BlockPos object to generate the sides for.
     * @return An array of BlockPos objects representing the sides of the given BlockPos.
     */
    private static BlockPos[] generateSides(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        // Log the values of x, y, and z for debugging
        Debug.logInternal("x = " + x);
        Debug.logInternal("y = " + y);
        Debug.logInternal("z = " + z);

        return new BlockPos[]{
                new BlockPos(x + 1, y, z),
                new BlockPos(x - 1, y, z),
                new BlockPos(x, y, z + 1),
                new BlockPos(x, y, z - 1),
                new BlockPos(x + 1, y, z - 1),
                new BlockPos(x + 1, y, z + 1),
                new BlockPos(x - 1, y, z - 1),
                new BlockPos(x - 1, y, z + 1)
        };
    }

    /**
     * Checks if a block is annoying.
     *
     * @param mod The AltoClef mod instance.
     * @param pos The position of the block.
     * @return true if the block is annoying, false otherwise.
     */
    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        for (Block annoyingBlock : annoyingBlocks) {
            boolean isAnnoying = mod.getWorld().getBlockState(pos).getBlock() == annoyingBlock
                    || mod.getWorld().getBlockState(pos).getBlock() instanceof DoorBlock
                    || mod.getWorld().getBlockState(pos).getBlock() instanceof FenceBlock
                    || mod.getWorld().getBlockState(pos).getBlock() instanceof FenceGateBlock
                    || mod.getWorld().getBlockState(pos).getBlock() instanceof FlowerBlock;
            if (isAnnoying) {
                Debug.logInternal("Block at position " + pos + " is annoying.");
                return true;
            }
        }
        Debug.logInternal("Block at position " + pos + " is not annoying.");
        return false;
    }

    /**
     * Returns the position of the block where the player is stuck.
     * If there are no annoying block positions, returns null.
     *
     * @param mod The instance of the AltoClef mod.
     * @return The BlockPos of the stuck block, or null if none found.
     */
    private BlockPos stuckInBlock(AltoClef mod) {
        BlockPos playerPos = mod.getPlayer().getBlockPos();
        BlockPos[] toCheck = generateSides(playerPos);
        BlockPos[] toCheckHigh = generateSides(playerPos.up());

        // Check if player position is annoying
        if (isAnnoying(mod, playerPos)) {
            Debug.logInternal("Player position is annoying: " + playerPos);
            return playerPos;
        }

        // Check if player position (up) is annoying
        if (isAnnoying(mod, playerPos.up())) {
            Debug.logInternal("Player position (up) is annoying: " + playerPos.up());
            return playerPos.up();
        }

        // Check each side block position
        for (BlockPos check : toCheck) {
            if (isAnnoying(mod, check)) {
                Debug.logInternal("Block position is annoying: " + check);
                return check;
            }
        }

        // Check each high block position
        for (BlockPos check : toCheckHigh) {
            if (isAnnoying(mod, check)) {
                Debug.logInternal("Block position (up) is annoying: " + check);
                return check;
            }
        }

        Debug.logInternal("No annoying block positions found.");
        return null;
    }

    /**
     * Retrieves a task to get the fence unstuck.
     *
     * @return The task to get the fence unstuck.
     */
    private Task getFenceUnstuckTask() {
        // Log the start of the function
        Debug.logInternal("Entering getFenceUnstuckTask");

        // Create a safe random shimmy task
        Task task = createSafeRandomShimmyTask();

        // Log the end of the function
        Debug.logInternal("Exiting getFenceUnstuckTask");

        // Return the task
        return task;
    }

    /**
     * Creates a new instance of SafeRandomShimmyTask.
     *
     * @return The created SafeRandomShimmyTask.
     */
    private Task createSafeRandomShimmyTask() {
        Task task = new SafeRandomShimmyTask();
        Debug.logInternal("Created SafeRandomShimmyTask: " + task);
        return task;
    }

    /**
     * This method is called when the mod starts.
     * It cancels any ongoing pathing behavior, resets move checker and stuck check.
     * If the cursor stack is not empty, it tries to move it to a suitable slot in the player inventory.
     * If the item can be thrown away, it drops it in an undefined slot or the garbage slot.
     * If the cursor stack is empty, it closes the screen.
     */
    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();

        // Cancel any ongoing pathing behavior.
        Nav.cancel();

        // Reset move checker and stuck check.
        _moveChecker.reset();
        stuckCheck.reset();

        // Get the item stack in the cursor slot.
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        Debug.logInternal("Cursor stack: " + cursorStack);

        // If the cursor stack is not empty, try to move it to a suitable slot in the player inventory.
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            Debug.logInternal("Move to slot: " + moveTo);

            // If there is a slot where the item can fit, click on that slot to move the item.
            moveTo.ifPresent(slot -> {
                mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                Debug.logInternal("Clicked slot: " + slot);
            });

            // If the item can be thrown away, click on an undefined slot to drop the item.
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                Debug.logInternal("Clicked undefined slot");
            }

            // Get the garbage slot and click on it to move the item.
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            Debug.logInternal("Garbage slot: " + garbage);

            garbage.ifPresent(slot -> {
                mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                Debug.logInternal("Clicked slot: " + slot);
            });

            // Click on an undefined slot to drop the item.
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            Debug.logInternal("Clicked undefined slot");
        } else {
            // If the cursor stack is empty, close the screen.
            StorageHelper.closeScreen();
            Debug.logInternal("Closed screen");
        }
    }

    /**
     * This method is called periodically to perform various tasks.
     *
     * @return The next task to be executed.
     */
    @Override
    protected Task onTick() {
        dbTick++;
        AltoClef mod = AltoClef.getInstance();
        // IS THE BLOCK STILL THERE? The reach ray was measured hitting NOTHING (MISS) 5142 times
        // in one run, which is what aiming at an empty cell looks like. If the log has already
        // been felled, every downstream symptom follows: no reach, no swing, no movement, the
        // progress checker expires and a block that no longer exists gets blacklisted.
        if (mod.getWorld() != null && mod.getWorld().getBlockState(pos).isAir()) dbTargetAir++;

        // Check if there is white wool at the specified position
        if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.WHITE_WOOL) {
            // Iterate over all entities in the world
            Iterable<Entity> entities = mod.getWorld().getEntities();
            for (Entity entity : entities) {
                // Check if the entity is a PillagerEntity and is within a distance of 144 blocks from the position
                if (entity instanceof PillagerEntity && pos.isWithinDistance(entity.getPos(), 144)) {
                    Debug.logMessage("Blacklisting pillager wool.");
                    dbUnreachPillager++;
                    // Request the block at the position to be marked as unreachable
                    mod.getBlockScanner().requestBlockUnreachable(pos, 0);
                }
            }
        }

        // Reset the move checker if Baritone is currently pathing
        if (Nav.isPathing()) {
            _moveChecker.reset();
        }

        // Check if the player is in a Nether portal
        if (WorldHelper.isInNetherPortal()) {
            if (!Nav.isPathing()) {
                setDebugState("Getting out from nether portal");
                // Hold the sneak and move forward inputs to exit the Nether portal
                mod.getInputControls().hold(Input.SNEAK);
                mod.getInputControls().hold(Input.MOVE_FORWARD);
                return null;
            } else {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        } else if (Nav.isPathing()) {
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }

        // Check if there is an active unstuck task and the player is stuck in a block
        if (unstuckTask != null && unstuckTask.isActive() && !unstuckTask.isFinished() && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Release control of Baritone's custom goal process and explore process
            Nav.clearGoal();
            Nav.stopExploring();
            return unstuckTask;
        }

        // Check if the move checker or the stuck check failed
        if (!_moveChecker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                unstuckTask = getFenceUnstuckTask();
                return unstuckTask;
            }
            stuckCheck.reset();
        }

        // Check if the move checker failed
        // A BLOCK YOU ARE STILL WALKING TOWARDS IS NOT UNREACHABLE.
        // Measured: 21 blacklistings in eight minutes, every target a real dark oak log within
        // fifteen blocks, and the counters say all 21 came from here (dbUnreachWater and
        // dbUnreachPillager were 0). The parent then picks the next log, so the bot toured
        // eighteen perfectly good trees and felled none. The generic move checker times out on
        // things that are not failure — a detour, a climb, a fight — and its verdict was being
        // spent on a permanent judgement about the block.
        // Progress is progress TOWARDS THIS BLOCK, so that is what is measured, and the checker
        // only gets to condemn a block the bot has genuinely stopped closing on.
        double distSqNow = mod.getPlayer().getPos().squaredDistanceTo(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSqNow < _bestDistSq - 0.5) {
            _bestDistSq = distSqNow;
            _moveChecker.reset();
        }
        if (!_moveChecker.check(mod)) {
            dbUnreachMove++;
            // FAR OR NEAR? Those need opposite fixes. Far means the bot never got there at all;
            // near means it arrived and something stops it finishing. One number tells them apart.
            int d = (int) Math.round(Math.sqrt(distSqNow));
            dbUnreachDistSum += d;
            if (d <= 4) dbUnreachNear++; else dbUnreachFar++;
            _moveChecker.reset();
            // Request the block at the position to be marked as unreachable
            mod.getBlockScanner().requestBlockUnreachable(pos);
        }

        // Check if the block above the position is not solid, the player is above the position,
        // and the player is within a distance of 0.89 blocks from the position
        if (!WorldHelper.isSolidBlock(pos.up()) && mod.getPlayer().getPos().y > pos.getY() && pos.isWithinDistance(mod.getPlayer().isOnGround() ? mod.getPlayer().getPos() : mod.getPlayer().getPos().add(0, -1, 0), 0.89)) {
            if (WorldHelper.dangerousToBreakIfRightAbove(pos)) {
                setDebugState("It's dangerous to break as we're right above it, moving away and trying again.");
                return new RunAwayFromPositionTask(3, pos.getY(), pos);
            }
        }

        Optional<Rotation> reach = LookHelper.getReach(pos);
        // WHY DOES A BOT STANDING NEXT TO A TREE KEEP WALKING? Measured: the give-ups are
        // overwhelmingly NEAR -- eight of eight and three of three at a mean 2.5 blocks. Something
        // in this condition sends an arrived bot back to "Getting to block...", and there are six
        // clauses that could. Count them apart instead of picking one.
        if (distSqNow <= 16.0) {
            dbNearTick++;
            if (!reach.isPresent()) dbNearNoReach++;
            else if (!(mod.getPlayer().isTouchingWater() || mod.getPlayer().isOnGround())) dbNearAirborne++;
            else if (mod.getFoodChain().needsToEat()) dbNearHungry++;
            else if (!Nav.isSafeToCancel()) dbNearUnsafe++;
        }
        // FELL WHAT IS IN THE WAY, DO NOT WALK AWAY FROM IT.
        // Measured: while the bot stands within four blocks of its target log, the reach ray is
        // stopped by LEAVES on 91-100% of the ticks it fails -- the same tree's own canopy. The
        // old answer was to keep walking, which the progress checker then read as being stuck,
        // so a perfectly good log was blacklisted and the bot toured trees without felling one.
        // Leaves are due to come down anyway, so the obstruction is simply the first block of
        // this job rather than a reason to abandon it.
        //
        // AND IT IS NOT ONLY LEAVES. A stall capture at a dark oak: rayOther=502 to rayLeaves=10,
        // blockedBy=minecraft:dark_oak_log -- the near side of the trunk hiding the far side, with
        // dbTick=1525 and nothing broken. So clear whatever is genuinely in the way, subject to
        // the three things that make clearing wrong rather than slow.
        if (!reach.isPresent() && distSqNow <= 16.0) {
            net.minecraft.util.math.BlockPos blocking =
                    kaptainwutax.tungsten.path.movements.RotationHelper.blockedPos;
            // ⛔ WHY THE CLEAR NEVER FIRES -- SPLIT IT, DO NOT GUESS. A stall capture reads
            // rayOther=3091 with blockedBy=minecraft:grass_block and leafCleared=0: the ray to the
            // target is stopped three thousand times and nothing is ever cleared. Three different
            // refusals can produce that and they want different fixes, so each is counted:
            //   self-floor  the obstruction IS the block under our own feet, which canClear
            //               rightly refuses -- digging it drops us. The answer there is to MOVE so
            //               the line opens, not to dig.
            //   unclearable canClear said no for another reason (bedrock, fluid, air).
            //   noReach     it is clearable but we cannot even look at IT.
            if (blocking != null && !blocking.equals(pos)) {
                if (blocking.equals(mod.getPlayer().getBlockPos().down())) {
                    dbBlockedSelfFloor++;
                } else if (!canClear(mod, blocking)) {
                    dbBlockedUnclearable++;
                }
            }
            if (blocking != null && !blocking.equals(pos) && canClear(mod, blocking)) {
                Optional<Rotation> clearReach = LookHelper.getReach(blocking);
                if (!clearReach.isPresent()) {
                    dbBlockedNoReach++;
                }
                if (clearReach.isPresent()) {
                    dbLeafCleared++;
                    _moveChecker.reset();   // clearing a path IS progress
                    LookHelper.lookAt(clearReach.get());
                    mod.getInputControls().hold(Input.CLICK_LEFT);
                    return null;
                }
            }
        }
        if (reach.isPresent() && (mod.getPlayer().isTouchingWater() || mod.getPlayer().isOnGround()) && !mod.getFoodChain().needsToEat() && !WorldHelper.isInNetherPortal() && Nav.isSafeToCancel()) {
            setDebugState("Block in range, mining...");
            stuckCheck.reset();
            isMining = true;
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
            Nav.clearGoal();
            if (!LookHelper.isLookingAt(mod, reach.get())) {
                LookHelper.lookAt(reach.get());
            }
            // Tool equip is handled in `PlayerInteractionFixChain`. Oof.
            mod.getInputControls().hold(Input.CLICK_LEFT);
        } else {
            setDebugState("Getting to block...");
            if (isMining && mod.getPlayer().isTouchingWater()) {
                setDebugState("We are in water... holding break button");
                isMining = false;
                dbUnreachWater++;
                mod.getBlockScanner().requestBlockUnreachable(pos);
                mod.getInputControls().hold(Input.CLICK_LEFT);
            } else {
                isMining = false;
            }
            boolean isCloseToMoveBack = pos.isWithinDistance(mod.getPlayer().getPos(), 2);
            if (isCloseToMoveBack) {
                if (!Nav.isPathing() && !mod.getPlayer().isTouchingWater() &&
                        !mod.getFoodChain().needsToEat()) {
                    mod.getInputControls().hold(Input.MOVE_BACK);
                    mod.getInputControls().hold(Input.SNEAK);
                } else {
                    mod.getInputControls().release(Input.MOVE_BACK);
                    mod.getInputControls().release(Input.SNEAK);
                }
            }
            // WALK THERE WITH THE MOD'S OWN DRIVER, NOT WITH SHREDDER.
            // This called getCustomGoalProcess().setGoalAndPath() directly, which is the old
            // pathfinder — tungsten never saw the request. And this task is the LEAF of the whole
            // playthrough: "beat the game" descends through pickaxe -> planks -> Mine And Collect
            // -> Destroy block at (-177,67,331), and that last step is how the bot walks to every
            // log, every ore, every block it ever breaks. Measured on the playthrough course: the
            // task chain sat on exactly this leaf while EVERY tungsten counter read zero
            // (mqStarted=0, called=0, staleRoot=0) and the bot did not move for ten minutes.
            // GetToBlockTask extends CustomBaritoneGoalTask, so returning it here puts the walk on
            // the tungsten-primary driver like the rest of navigation.
            return new GetToBlockTask(pos, false);
        }
        return null;
    }

    /**
     * This method is called when the task is interrupted or stopped.
     * It cancels Baritone pathing and releases certain input controls.
     *
     * @param interruptTask The task that interrupted the current task.
     */
    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();

        // Cancel Baritone pathing
        Nav.cancel();

        // If not in game, return
        if (!AltoClef.inGame()) {
            return;
        }

        // Release input controls
        mod.getInputControls().release(Input.CLICK_LEFT);
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.MOVE_BACK);
        mod.getInputControls().release(Input.MOVE_FORWARD);

        // Logging statements for debugging
        Debug.logInternal("onStop method called");
        Debug.logInternal("Baritone pathing cancelled");
        if (!AltoClef.inGame()) {
            Debug.logInternal("Not in game");
        }
        Debug.logInternal("Left click input force state set to false");
        Debug.logInternal("Released sneak input control");
        Debug.logInternal("Released move back input control");
        Debug.logInternal("Released move forward input control");
    }

    /**
     * Checks if the block at the given position is air.
     *
     * @return true if the block is air, false otherwise
     */
    @Override
    public boolean isFinished() {
        BlockState blockState = AltoClef.getInstance().getWorld().getBlockState(pos);
        boolean isAir = blockState.isAir();
        Debug.logInternal("Block at position " + pos + " is air: " + isAir);
        return isAir;
    }

    /**
     * Checks if this task is equal to another task.
     *
     * @param other The other task to compare against.
     * @return True if the tasks are equal, false otherwise.
     */
    @Override
    protected boolean isEqual(Task other) {
        boolean isSame = false;

        // Check if the other task is an instance of DestroyBlockTask
        if (other instanceof DestroyBlockTask destroyBlockTask) {

            // Check if the positions of the tasks are equal
            if (destroyBlockTask.pos.equals(pos)) {
                isSame = true;
            }
        }

        // Log the result of the equality check
        Debug.logInternal("isEqual result: " + isSame);

        // Return the result of the equality check
        return isSame;
    }

    /**
     * Generates a debug string representing the block destruction position.
     *
     * @return The debug string.
     */
    @Override
    protected String toDebugString() {
        return "Destroy block at " + pos.toShortString();
    }

    /**
     * Is this obstruction one we may break to open a line to the target?
     *
     * <p>Three refusals, and no more than three -- an over-cautious rule here puts the bot back to
     * standing in front of a trunk it will not touch:
     * <ul>
     *   <li>UNBREAKABLE (hardness below zero: bedrock, barriers) -- swinging at it is a loop;</li>
     *   <li>FLUID -- you do not mine water, and the ray stopping at one is not an obstruction
     *       this task can remove;</li>
     *   <li>THE BLOCK UNDER OUR OWN FEET -- clearing that is how a bot digs itself into a hole
     *       while trying to see a tree.</li>
     * </ul>
     */
    /** Why the line-of-sight clear did not fire. Read as dbBlocked=selfFloor/unclearable/noReach. */
    public static volatile int dbBlockedSelfFloor, dbBlockedUnclearable, dbBlockedNoReach;

    private boolean canClear(AltoClef mod, net.minecraft.util.math.BlockPos blocking) {
        net.minecraft.block.BlockState st = mod.getWorld().getBlockState(blocking);
        if (st.isAir() || !st.getFluidState().isEmpty()) {
            return false;
        }
        if (st.getHardness(mod.getWorld(), blocking) < 0) {
            return false;
        }
        return !blocking.equals(mod.getPlayer().getBlockPos().down());
    }

}
