package adris.altoclef.tasks.construction;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.trackers.BlockScanner;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

import java.util.Arrays;

/**
 * Places obsidian at a position using buckets and a cast.
 */
public class PlaceObsidianBucketTask extends Task {

    public static final Vec3i[] CAST_FRAME = new Vec3i[]{
            new Vec3i(0, -1, 0),
            new Vec3i(0, -1, -1),
            new Vec3i(0, -1, 1),
            new Vec3i(-1, -1, 0),
            new Vec3i(1, -1, 0),
            new Vec3i(0, 0, -1),
            new Vec3i(0, 0, 1),
            new Vec3i(-1, 0, 0),
            new Vec3i(1, 0, 0),
            new Vec3i(1, 1, 0)
    };
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final BlockPos _pos;

    private BlockPos _currentCastTarget;
    private BlockPos _currentDestroyTarget;

    public PlaceObsidianBucketTask(BlockPos pos) {
        _pos = pos;
    }

    @Override
    protected void onStart() {
        BotBehaviour botBehaviour = AltoClef.getInstance().getBehaviour();

        // Push the behaviour onto the behaviour stack
        botBehaviour.push();

        // Avoid breaking blocks within the specified conditions
        botBehaviour.avoidBlockBreaking(this::isBlockInCastFrame);

        // Avoid placing blocks within the specified conditions
        botBehaviour.avoidBlockPlacing(this::isBlockInCastWaterOrLava);

        // Reset the progress checker
        _progressChecker.reset();

        // Logging statements for debugging
        Debug.logInternal("Started onStart method");
        Debug.logInternal("Behaviour pushed");
        Debug.logInternal("Avoiding block breaking");
        Debug.logInternal("Avoiding block placing");
        Debug.logInternal("Progress checker reset");
    }

    private boolean isBlockInCastFrame(BlockPos block) {
        return Arrays.stream(PlaceObsidianBucketTask.CAST_FRAME)
                .map(_pos::add)
                .anyMatch(block::equals);
    }

    /**
     * Checks if a given block position is either the same as the current position or the position above it.
     *
     * @param blockPos The block position to check
     * @return True if the block position is the same as the current position or the position above it, false otherwise
     */
    private boolean isBlockInCastWaterOrLava(BlockPos blockPos) {
        // Calculate the position above the current position
        BlockPos waterTarget = _pos.up();

        // Logging statement for debugging
        Debug.logInternal("blockPos: " + blockPos);
        Debug.logInternal("waterTarget: " + waterTarget);

        // Check if the block position is either the same as the current position or the position above it
        return blockPos.equals(_pos) || blockPos.equals(waterTarget);
    }

    /**
     * This method is called periodically to perform a specific task.
     * It handles the logic for casting a spell using lava and water buckets.
     *
     * @return The next task to be executed
     */
    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Reset progress if pathing -- TODOS.md, the stall-detector-wipe pattern already fixed
        // in TimeoutWanderTask/DestroyBlockTask: a mere search makes Nav.isPathing() true too,
        // so resetting unconditionally could hide a genuine stall for as long as a search never
        // resolves. resetIfPathingWithGrace only resets while the body has moved recently.
        _progressChecker.resetIfPathingWithGrace(mod, Nav.isPathing());

        // Clear leftover water
        if (mod.getBlockScanner().isBlockAtPosition(_pos, Blocks.OBSIDIAN) && mod.getBlockScanner().isBlockAtPosition(_pos.up(), Blocks.WATER)) {
            return new ClearLiquidTask(_pos.up());
        }

        // Make sure we have a water bucket
        if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
            _progressChecker.reset();
            return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
        }

        // Make sure we have a lava bucket
        if (!mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
            // The only excuse is that we have lava at our position.
            if (!mod.getBlockScanner().isBlockAtPosition(_pos, Blocks.LAVA)) {
                _progressChecker.reset();
                return TaskCatalogue.getItemTask(Items.LAVA_BUCKET, 1);
            }
        }

        // Check progress
        if (!_progressChecker.check(mod)) {
            Nav.cancel();
            mod.getBlockScanner().requestBlockUnreachable(_pos);
            _progressChecker.reset();
            return new TimeoutWanderTask(5);
        }

        // Build cast frame if not already built
        if (_currentCastTarget != null) {
            if (WorldHelper.isSolidBlock(_currentCastTarget)) {
                _currentCastTarget = null;
            } else {
                return new PlaceBlockTask(_currentCastTarget,
                        Arrays.stream(ItemHelper.itemsToBlocks(mod.getModSettings().getThrowawayItems(mod))).filter((b)-> !Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.LEAVES)).toList().contains(b)).toArray(Block[]::new)
                );
            }
        }

        // Destroy block if needed
        if (_currentDestroyTarget != null) {
            if (!WorldHelper.isSolidBlock(_currentDestroyTarget)) {
                _currentDestroyTarget = null;
            } else {
                return new DestroyBlockTask(_currentDestroyTarget);
            }
        }

        // Build the cast frame if not already built
        if (_currentCastTarget != null && WorldHelper.isSolidBlock(_currentCastTarget)) {
            // Current cast frame already built.
            _currentCastTarget = null;
        }
        for (Vec3i castPosRelative : CAST_FRAME) {
            BlockPos castPos = _pos.add(castPosRelative);
            if (!WorldHelper.isSolidBlock(castPos)) {
                _currentCastTarget = kaptainwutax.tungsten.TungstenConfig.get().castSupports ? supportFirst(mod, castPos) : castPos;
                if (!_currentCastTarget.equals(castPos)) supportsPlaced++;
                Debug.logInternal("Building cast frame...");
                return null;
            }
        }

        // Place lava
        if (mod.getWorld().getBlockState(_pos).getBlock() != Blocks.LAVA) {
            // Don't place lava at our position!
            // Would lead to an embarrassing death.
            BlockPos targetPos = _pos.add(-1,1,0);
            if (!mod.getPlayer().getBlockPos().equals(targetPos) && mod.getItemStorage().hasItem(Items.LAVA_BUCKET)) {
                Debug.logInternal("Positioning player before placing lava...");
                return new GetToBlockTask(targetPos, false);
            }
            if (WorldHelper.isSolidBlock(_pos)) {
                Debug.logInternal("Clearing space around lava...");
                _currentDestroyTarget = _pos;
                return null;
            }
            // Clear the upper two as well, to make placing more reliable.
            if (WorldHelper.isSolidBlock(_pos.up())) {
                Debug.logInternal("Clearing space around lava...");
                _currentDestroyTarget = _pos.up();
                return null;
            }
            if (WorldHelper.isSolidBlock(_pos.up(2))) {
                Debug.logInternal("Clearing space around lava...");
                _currentDestroyTarget = _pos.up(2);
                return null;
            }
            Debug.logInternal("Placing lava for cast...");
            return new InteractWithBlockTask(new ItemTarget(Items.LAVA_BUCKET, 1), Direction.WEST, _pos.add(1,0,0), false);
        }
        // Lava placed, Now, place water.
        BlockPos waterCheck = _pos.up();
        if (mod.getWorld().getBlockState(waterCheck).getBlock() != Blocks.WATER) {
            Debug.logInternal("Placing water for cast...");
            // Get to position to avoid weird stuck scenario
            BlockPos targetPos = _pos.add(-1,1,0);
            if (!mod.getPlayer().getBlockPos().equals(targetPos) && mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                Debug.logInternal("Positioning player before placing water...");
                return new GetToBlockTask(targetPos, false);
            }
            if (WorldHelper.isSolidBlock(waterCheck)) {
                _currentDestroyTarget = waterCheck;
                return null;
            }
            if (WorldHelper.isSolidBlock(waterCheck.up())) {
                _currentDestroyTarget = waterCheck.up();
                return null;
            }
            return new InteractWithBlockTask(new ItemTarget(Items.WATER_BUCKET, 1), Direction.WEST, _pos.add(1,1,0), true);
        }
        return null;
    }

    /** Support blocks placed under cast cells that had nothing to be placed against. */
    public static volatile int supportsPlaced;

    /**
     * The cell to place before {@code cell}: the cell itself if it has a solid neighbour to place
     * against, otherwise the nearest empty air cell (down first) that has one -- a support of
     * throwaway blocks that grows back to the cell like a bridge.
     *
     * <p>What baritone does, read from the source: BuilderProcess.possibleToPlace
     * (baritone/src/main/java/baritone/process/BuilderProcess.java:498) places a block only where a
     * solid neighbour's face can be clicked, and assemble (same file, :941) takes pending cells
     * bottom-up (none pending one or two below). A cell with no neighbour is simply never placed --
     * BuilderProcess has no scaffolding; what lifts the body to a placement is the movements'
     * throwaway blocks (MovementPillar.java:226, selectThrowawayForLocation), which baritone never
     * removes. So this is the same rule with the gap baritone leaves: a chain of throwaway supports
     * to the cell, placed from the supported end, and never removed by this task.
     *
     * <p>⛔ WHY (G108, reproduced on portal_lava_lake 2026-09-29). A frame cell above the ground has
     * mould cells in the air -- the ring under it and the walls beside it one level down -- and
     * PlaceBlockTask cannot place into a cell with no face to click: "Place structure" -> "Wander
     * for 5 blocks" for the rest of the course, and the last hour of full56 and full57. Supports
     * are never removed by this task. The ones inside the portal's opening are cleared by
     * ConstructNetherPortalBucketTask's "Clearing inside of portal" step, which runs only after
     * every frame cell is obsidian -- after the last placement -- so nothing is placed again once
     * clearing starts. The ones outside the opening stay: they block nothing.
     */
    private static BlockPos supportFirst(AltoClef mod, BlockPos cell) {
        // Breadth-first over EMPTY AIR only, down before sideways before up, to the nearest cell that
        // has a solid neighbour: that one is placed first, and the chain grows back to the cell the
        // way a bridge does. Never through a fluid: the first version walked the column down
        // through the lava lake next to the portal and started filling the lake with cobblestone.
        var world = mod.getWorld();
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
        java.util.Map<BlockPos, Integer> depth = new java.util.HashMap<>();
        q.add(cell);
        depth.put(cell, 0);
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        while (!q.isEmpty()) {
            BlockPos c = q.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = c.offset(d);
                if (WorldHelper.isSolidBlock(n) && world.getFluidState(n).isEmpty()) return c;
            }
            int dd = depth.get(c);
            if (dd >= SUPPORT_SEARCH_DEPTH) continue;
            for (Direction d : order) {
                BlockPos n = c.offset(d);
                if (depth.containsKey(n) || !world.getBlockState(n).isAir()) continue;
                depth.put(n, dd + 1);
                q.add(n);
            }
        }
        return cell;   // nothing within reach: leave it to the placer as before
    }

    private static final int SUPPORT_SEARCH_DEPTH = 5;

    /**
     * This method is called when the task is interrupted.
     *
     * @param interruptTask The task that caused the interruption.
     */
    @Override
    protected void onStop(Task interruptTask) {
        // Check if the mod's behaviour is not null
        if (AltoClef.getInstance().getBehaviour() != null) {
            // Pop the behaviour from the stack
            AltoClef.getInstance().getBehaviour().pop();
            // Log a message indicating that the behaviour was popped
            Debug.logInternal("Behaviour popped.");
        }
    }

    /**
     * Check if the current task is finished.
     * The task is considered finished if the block at the specified position is obsidian
     * and there is no water block above it.
     *
     * @return True if the task is finished, False otherwise.
     */
    @Override
    public boolean isFinished() {
        // Get the BlockTracker instance from the mod
        BlockScanner blockTracker = AltoClef.getInstance().getBlockScanner();

        // Get the position of the block to check
        BlockPos pos = _pos;

        // Check if the block at the specified position is obsidian
        boolean isObsidian = blockTracker.isBlockAtPosition(pos, Blocks.OBSIDIAN);
        Debug.logInternal("isObsidian: " + isObsidian);

        // Check if there is no water block above the specified position
        boolean isNotWaterAbove = !blockTracker.isBlockAtPosition(pos.up(), Blocks.WATER);
        Debug.logInternal("isNotWaterAbove: " + isNotWaterAbove);

        // The task is considered finished if the block is obsidian and there is no water above
        boolean isFinished = isObsidian && isNotWaterAbove;
        Debug.logInternal("isFinished: " + isFinished);

        return isFinished;
    }

    /**
     * Checks if the given task is equal to this PlaceObsidianBucketTask.
     * Two PlaceObsidianBucketTasks are considered equal if their positions are equal.
     * Overrides the isEqual() method from the parent class.
     *
     * @param other the task to compare with
     * @return true if the tasks are equal, false otherwise
     */
    @Override
    protected boolean isEqual(Task other) {
        // Check if the other task is an instance of PlaceObsidianBucketTask
        if (other instanceof PlaceObsidianBucketTask task) {
            // Check if the positions are equal
            boolean isEqual = task.getPos().equals(getPos());
            // Log the result of the comparison
            Debug.logInternal("isEqual: " + isEqual);
            // Return the result
            return isEqual;
        }
        // Log that the tasks are not equal
        Debug.logInternal("isEqual: false");
        // Return false
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Placing obsidian at " + _pos + " with a cast";
    }

    /**
     * Retrieves the position of the object.
     *
     * @return The position of the object.
     */
    public BlockPos getPos() {
        // Added logging statement for debugging
        Debug.logInternal("Entering getPos()");

        return _pos;
    }
}
