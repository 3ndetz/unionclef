package adris.altoclef.tasks.misc;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.eventbus.events.GameOverlayEvent;
import adris.altoclef.multiversion.blockpos.BlockPosVer;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.time.TimerGame;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SleepingChatScreen;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.apache.commons.lang3.ArrayUtils;

public class PlaceBedAndSetSpawnTask extends Task {

    private final TimerGame regionScanTimer = new TimerGame(9);
    private final Vec3i BED_CLEAR_SIZE = new Vec3i(3, 2, 3);
    private final Vec3i[] BED_BOTTOM_PLATFORM = new Vec3i[]{
            new Vec3i(0, -1, 0),
            new Vec3i(1, -1, 0),
            new Vec3i(2, -1, 0),
            new Vec3i(0, -1, -1),
            new Vec3i(1, -1, -1),
            new Vec3i(2, -1, -1),
            new Vec3i(0, -1, 1),
            new Vec3i(1, -1, 1),
            new Vec3i(2, -1, 1)
    };
    // Kinda silly but who knows if we ever want to change it.
    private final Vec3i BED_PLACE_STAND_POS = new Vec3i(0, 0, 1);
    private final Vec3i BED_PLACE_POS = new Vec3i(1, 0, 1);
    private final Vec3i[] BED_PLACE_POS_OFFSET = new Vec3i[]{
            BED_PLACE_POS,
            BED_PLACE_POS.north(),
            BED_PLACE_POS.south(),
            BED_PLACE_POS.east(),
            BED_PLACE_POS.west(),
            BED_PLACE_POS.add(-1,0,1),
            BED_PLACE_POS.add(1,0,1),
            BED_PLACE_POS.add(-1,0,-1),
            BED_PLACE_POS.add(1,0,-1),
            BED_PLACE_POS.north(2),
            BED_PLACE_POS.south(2),
            BED_PLACE_POS.east(2),
            BED_PLACE_POS.west(2),
            BED_PLACE_POS.add(-2,0,1),
            BED_PLACE_POS.add(-2,0,2),
            BED_PLACE_POS.add(2,0,1),
            BED_PLACE_POS.add(2,0,2),
            BED_PLACE_POS.add(-2,0,-1),
            BED_PLACE_POS.add(-2,0,-2),
            BED_PLACE_POS.add(2,0,-1),
            BED_PLACE_POS.add(2,0,-2)
    };
    private final Direction BED_PLACE_DIRECTION = Direction.UP;
    private final TimerGame bedInteractTimeout = new TimerGame(5);
    private final TimerGame inBedTimer = new TimerGame(1);
    private final MovementProgressChecker progressChecker = new MovementProgressChecker();
    private boolean stayInBed;
    private BlockPos currentBedRegion;
    private BlockPos currentStructure, currentBreak;
    private boolean spawnSet;
    private Subscription<ChatMessageEvent> respawnPointSetMessageCheck;
    private Subscription<GameOverlayEvent> respawnFailureMessageCheck;
    private boolean sleepAttemptMade;
    private boolean wasSleeping;
    private BlockPos bedForSpawnPoint;

    public PlaceBedAndSetSpawnTask() {

    }

    /**
     * Sets the flag to stay in bed.
     *
     * @return The current instance of PlaceBedAndSetSpawnTask.
     */
    public PlaceBedAndSetSpawnTask stayInBed() {
        // Log method call
        Debug.logInternal("Stay in bed method called");

        // Set _stayInBed flag to true
        this.stayInBed = true;
        Debug.logInternal("Setting _stayInBed to true");

        // Return current instance
        return this;
    }

    /**
     * This method is called when the mod starts.
     * It initializes various variables and sets up behaviours for the mod.
     */
    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();

        // Push the current behaviour
        mod.getBehaviour().push();

        // Reset progress checker
        progressChecker.reset();

        // Reset current bed region
        currentBedRegion = null;

        // Avoid placing blocks near bed
        mod.getBehaviour().avoidBlockPlacing(pos -> {
            if (currentBedRegion != null) {
                BlockPos start = currentBedRegion;
                BlockPos end = currentBedRegion.add(BED_CLEAR_SIZE);
                return start.getX() <= pos.getX() && pos.getX() < end.getX()
                        && start.getZ() <= pos.getZ() && pos.getZ() < end.getZ()
                        && start.getY() <= pos.getY() && pos.getY() < end.getY();
            }
            return false;
        });

        // Avoid breaking blocks near bed
        mod.getBehaviour().avoidBlockBreaking(pos -> {
            if (currentBedRegion != null) {
                for (Vec3i baseOffs : BED_BOTTOM_PLATFORM) {
                    BlockPos base = currentBedRegion.add(baseOffs);
                    if (base.equals(pos)) return true;
                }
            }
            // Don't ever break beds. If one exists, we will sleep in it.
            if (mod.getWorld() != null) {
                return mod.getWorld().getBlockState(pos).getBlock() instanceof BedBlock;
            }
            return false;
        });

        // Reset variables for sleep handling
        spawnSet = false;
        sleepAttemptMade = false;
        wasSleeping = false;

        // Subscribe to respawn point set message event
        respawnPointSetMessageCheck = EventBus.subscribe(ChatMessageEvent.class, evt -> {
            String msg = evt.toString();
            if (msg.contains("Respawn point set")) {
                spawnSet = true;
                inBedTimer.reset();
            }
        });

        // Subscribe to respawn failure message event
        respawnFailureMessageCheck = EventBus.subscribe(GameOverlayEvent.class, evt -> {
            final String[] NEUTRAL_MESSAGES = new String[]{
                    "You can sleep only at night",
                    "You can only sleep at night",
                    "You may not rest now; there are monsters nearby"
            };
            for (String checkMessage : NEUTRAL_MESSAGES) {
                if (evt.message.contains(checkMessage)) {
                    if (!sleepAttemptMade) {
                        bedInteractTimeout.reset();
                    }
                    sleepAttemptMade = true;
                }
            }
        });

        // Logging statements for debugging
        Debug.logInternal("Started onStart() method");
        Debug.logInternal("Current bed region: " + currentBedRegion);
        Debug.logInternal("Spawn set: " + spawnSet);
    }

    public void resetSleep() {
        spawnSet = false;
        sleepAttemptMade = false;
        wasSleeping = false;
    }

    @Override
    protected Task onTick() {
        // Summary:
        // If we find a bed nearby, sleep in it.
        // Otherwise, place bed:
        //      Collect bed if we don't have one.
        //      Find a 3x2x1 region and clear it
        //      Stand on the edge of the long (3) side
        //      Place on the middle block, reliably placing the bed.
        AltoClef mod = AltoClef.getInstance();

        if (!progressChecker.check(mod) && currentBedRegion != null) {
            progressChecker.reset();
            Debug.logMessage("Searching new bed region.");
            currentBedRegion = null;
        }
        if (WorldHelper.isInNetherPortal()) {
            setDebugState("We are in nether portal. Wandering");
            currentBedRegion = null;
            return new TimeoutWanderTask();
        }
        // We cannot do this anywhere but the overworld.
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            setDebugState("Going to the overworld first.");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }
        Screen screen = MinecraftClient.getInstance().currentScreen;
        if (screen instanceof SleepingChatScreen) {
            progressChecker.reset();
            setDebugState("Sleeping...");
            wasSleeping = true;
            //Debug.logMessage("Closing sleeping thing");
            spawnSet = true;
            return null;
        }

        if (sleepAttemptMade) {
            if (bedInteractTimeout.elapsed()) {
                Debug.logMessage("Failed to get \"Respawn point set\" message or sleeping, assuming that this bed already contains our spawn.");
                spawnSet = true;
                return null;
            }
        }
        if (mod.getBlockScanner().anyFound(blockPos -> (WorldHelper.canReach(blockPos) &&
                blockPos.isWithinDistance(mod.getPlayer().getPos(), 40) &&
                mod.getItemStorage().hasItem(ItemHelper.BED)) || (WorldHelper.canReach(blockPos) &&
                !mod.getItemStorage().hasItem(ItemHelper.BED)), ItemHelper.itemsToBlocks(ItemHelper.BED))) {
            // Sleep in the nearest bed
            setDebugState("Going to bed to sleep...");
            return new DoToClosestBlockTask(toSleepIn -> {
                boolean closeEnough = toSleepIn.isWithinDistance(mod.getPlayer().getPos(), 3);
                if (closeEnough) {
                    // why 0.2? I'm tired.
                    Vec3d centerBed = new Vec3d(toSleepIn.getX() + 0.5, toSleepIn.getY() + 0.2, toSleepIn.getZ() + 0.5);
                    BlockHitResult hit = LookHelper.raycast(mod.getPlayer(), centerBed, 6);
                    // TODO: Kinda ugly, but I'm tired and fixing for the 2nd attempt speedrun so I will fix this block later
                    closeEnough = false;
                    if (hit.getType() != HitResult.Type.MISS) {
                        // At this poinAt, if we miss, we probably are close enough.
                        BlockPos p = hit.getBlockPos();
                        if (ArrayUtils.contains(ItemHelper.itemsToBlocks(ItemHelper.BED), mod.getWorld().getBlockState(p).getBlock())) {
                            // We have a bed!
                            closeEnough = true;
                        }
                    }
                }
                bedForSpawnPoint = WorldHelper.getBedHead(toSleepIn);
                if (bedForSpawnPoint == null) {
                    bedForSpawnPoint = toSleepIn;
                }
                if (!closeEnough) {
                    try {
                        Direction face = mod.getWorld().getBlockState(toSleepIn).get(BedBlock.FACING);
                        Direction side = face.rotateYClockwise();
                        /*
                        BlockPos targetMove = toSleepIn.offset(side).offset(side); // Twice, juust to make sure...
                         */
                        return new GetToBlockTask(bedForSpawnPoint.add(side.getVector()));
                    } catch (IllegalArgumentException e) {
                        // If bed is not loaded, this will happen. In that case just get to the bed first.
                    }
                } else {
                    inBedTimer.reset();
                }
                if (closeEnough) {
                    inBedTimer.reset();
                }
                // Keep track of where our spawn point is
                progressChecker.reset();
                return new InteractWithBlockTask(bedForSpawnPoint);
            }, ItemHelper.itemsToBlocks(ItemHelper.BED));
        }

        if (mod.getPlayer().isTouchingWater() && mod.getItemStorage().hasItem(ItemHelper.BED)) {
            setDebugState("We are in water. Wandering");
            currentBedRegion = null;
            return new TimeoutWanderTask();
        }

        if (currentBedRegion != null) {
            for (Vec3i BedPlacePos : BED_PLACE_POS_OFFSET) {
                Block getBlock = mod.getWorld().getBlockState(currentBedRegion.add(BedPlacePos)).getBlock();
                if (getBlock instanceof BedBlock) {
                    mod.getBlockScanner().addBlock(getBlock, currentBedRegion.add(BedPlacePos));
                    break;
                }
            }
        }
        // Get a bed if we don't have one.
        if (!mod.getItemStorage().hasItem(ItemHelper.BED)) {
            setDebugState("Getting a bed first");
            return TaskCatalogue.getItemTask("bed", 1);
        }

        if (currentBedRegion == null) {
            if (regionScanTimer.elapsed()) {
                Debug.logMessage("Rescanning for nearby bed place position...");
                regionScanTimer.reset();
                currentBedRegion = this.locateBedRegion(mod, mod.getPlayer().getBlockPos());
            }
        }
        if (currentBedRegion == null) {
            setDebugState("Searching for spot to place bed, wandering...");
            return new TimeoutWanderTask();
        }

        // Clear and make bed foundation

        // Only the strip the bed needs: where we stand, the bed's foot and head, a cell of
        // headroom over each, and the floor under all three (see stripWork). This used to clear a
        // 3x2x3 box and lay a 3x3 platform, a dozen block operations on a slope or in a forest --
        // full51/full52 spent whole nights on them ("Failed to place, wandering timeout", "Place
        // structure" stuck for a minute) with the bed in the pack.
        for (int dx = 0; dx < 3; ++dx) {
            BlockPos floor = currentBedRegion.add(dx, -1, 1);
            if (!WorldHelper.isSolidBlock(floor)) {
                currentStructure = floor;
                break;
            }
        }

        outer:
        for (int dx = 0; dx < 3; ++dx) {
            for (int dy = 0; dy < 2; ++dy) {
                BlockPos toClear = currentBedRegion.add(dx, dy, 1);
                if (WorldHelper.isSolidBlock(toClear)) {
                    currentBreak = toClear;
                    break outer;
                }
            }
        }

        if (currentStructure != null) {
            if (WorldHelper.isSolidBlock(currentStructure)) {
                currentStructure = null;
            } else {
                setDebugState("Placing structure for bed");
                return new PlaceStructureBlockTask(currentStructure);
            }
        }
        if (currentBreak != null) {
            if (!WorldHelper.isSolidBlock(currentBreak)) {
                currentBreak = null;
            } else {
                setDebugState("Clearing region for bed");
                return new DestroyBlockTask(currentBreak);
            }
        }

        BlockPos toStand = currentBedRegion.add(BED_PLACE_STAND_POS);
        // Our bed region is READY TO BE PLACED
        if (!mod.getPlayer().getBlockPos().equals(toStand)) {
            return new GetToBlockTask(toStand);
        }

        BlockPos toPlace = currentBedRegion.add(BED_PLACE_POS);
        if (mod.getWorld().getBlockState(toPlace.offset(BED_PLACE_DIRECTION)).getBlock() instanceof BedBlock) {
            setDebugState("Waiting to rescan + find bed that we just placed. Should be almost instant.");
            progressChecker.reset();
            return null;
        }
        setDebugState("Placing bed...");

        setDebugState("Filling in Portal");
        if (!progressChecker.check(mod)) {
            Nav.cancelEverything();
            Nav.cancel();
            Nav.stopExploring();
            Nav.clearGoal();
            progressChecker.reset();
        }

        // Scoot backwards if we're trying to place and fail
        if (thisOrChildSatisfies(task -> {
            if (task instanceof InteractWithBlockTask intr)
                return intr.getClickStatus() == InteractWithBlockTask.ClickResponse.CLICK_ATTEMPTED;
            return false;
        })) {
            mod.getInputControls().tryPress(Input.MOVE_BACK);
        }
        return new InteractWithBlockTask(new ItemTarget("bed", 1), BED_PLACE_DIRECTION, toPlace.offset(BED_PLACE_DIRECTION.getOpposite()), false);
    }

    /**
     * Override method called when the task is interrupted.
     *
     * @param interruptTask The task that interrupted this task.
     */
    @Override
    protected void onStop(Task interruptTask) {
        // Pop the behaviour stack
        AltoClef.getInstance().getBehaviour().pop();

        // Unsubscribe from respawn point set message
        EventBus.unsubscribe(respawnPointSetMessageCheck);

        // Unsubscribe from respawn failure message
        EventBus.unsubscribe(respawnFailureMessageCheck);

        // Logging statements for debugging
        Debug.logInternal("Tracking stopped for beds");
        Debug.logInternal("Behaviour popped");
        Debug.logInternal("Unsubscribed from respawn point set message");
        Debug.logInternal("Unsubscribed from respawn failure message");
    }

    /**
     * Checks if the given task is equal to this task.
     *
     * @param other The task to compare with.
     * @return True if the tasks are equal, false otherwise.
     */
    @Override
    protected boolean isEqual(Task other) {
        // Check if the other task is an instance of PlaceBedAndSetSpawnTask
        boolean isSameTask = (other instanceof PlaceBedAndSetSpawnTask);

        if (!isSameTask) {
            // Log a debug message if the tasks are not of the same type
            Debug.logInternal("Tasks are not of the same type");
        }

        return isSameTask;
    }

    /**
     * Returns a string representation of the action performed by this method.
     * The action is described as "Placing a bed nearby + resetting spawn point".
     *
     * @return a string representation of the action
     */
    @Override
    protected String toDebugString() {
        return "Placing a bed nearby + resetting spawn point";
    }

    /**
     * Checks if the spawnpoint/sleep condition is finished.
     *
     * @return Whether the condition is finished.
     */
    @Override
    public boolean isFinished() {
        // Check if we are in the overworld
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            Debug.logInternal("Can't place spawnpoint/sleep in a bed unless we're in the overworld!");
            return true;
        }

        // NO PLAYER MEANS NOT FINISHED, NOT A CRASH.
        // isFinished() is reached from BeatMinecraftTask's CONSTRUCTOR, through getTargetBeds and
        // isTaskRunning, so it runs before the world has handed over a player -- which is exactly
        // what happens when @gamer is issued the moment the bot connects. Caught in the client
        // log: NullPointerException on getPlayer() at this line, thrown out of
        // BeatMinecraftTask.<init> via GamerCommand, so the task never existed and the bot sat
        // idle for the whole run. Same shape as the world-null guard already in that constructor.
        if (AltoClef.getInstance() == null || AltoClef.getInstance().getPlayer() == null) {
            return false;
        }

        // Check if player is sleeping
        boolean isSleeping = AltoClef.getInstance().getPlayer().isSleeping();

        // Check if timer has elapsed
        boolean timerElapsed = inBedTimer.elapsed();

        // Check if spawnpoint is set, player is not sleeping, and timer has elapsed
        boolean isFinished = spawnSet && !isSleeping && timerElapsed;

        // Log the values for debugging
        Debug.logInternal("isSleeping: " + isSleeping);
        Debug.logInternal("timerElapsed: " + timerElapsed);
        Debug.logInternal("isFinished: " + isFinished);

        return isFinished;
    }

    /**
     * Returns the position of the bed where the player last slept.
     *
     * @return The BlockPos of the bed.
     */
    public BlockPos getBedSleptPos() {
        // Log a debug message indicating that the bed slept position is being fetched
        Debug.logInternal("Fetching bed slept position");

        // Return the stored bed position
        return bedForSpawnPoint;
    }

    /**
     * Checks if the spawn is set.
     *
     * @return true if the spawn is set, false otherwise.
     */
    public boolean isSpawnSet() {
        // Log internal message for debugging
        Debug.logInternal("Checking if spawn is set");

        // Return the value of the _spawnSet variable
        return spawnSet;
    }

    /**
     * Locates the closest good position within a specified range from the given origin.
     *
     * @param mod    The mod instance.
     * @param origin The origin position.
     * @return The closest good position.
     */
    private BlockPos locateBedRegion(AltoClef mod, BlockPos origin) {
        final int SCAN_RANGE = 10;

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int x = origin.getX() - SCAN_RANGE; x < origin.getX() + SCAN_RANGE; ++x) {
            for (int z = origin.getZ() - SCAN_RANGE; z < origin.getZ() + SCAN_RANGE; ++z) {
                for (int y = origin.getY() - SCAN_RANGE; y < origin.getY() + SCAN_RANGE; ++y) {
                    BlockPos attemptPos = new BlockPos(x, y, z);
                    int work = stripWork(mod, attemptPos);
                    if (work < 0) continue;
                    double score = Math.sqrt(BlockPosVer.getSquaredDistance(attemptPos, mod.getPlayer().getPos()))
                            + WORK_BLOCKS * work;
                    if (score < bestScore) {
                        bestScore = score;
                        best = attemptPos;
                    }
                }
            }
        }
        return best;
    }

    /** One block of digging or filling costs as much as this many blocks of walking. */
    private static final double WORK_BLOCKS = 8;

    /**
     * Block operations the bed strip at {@code region} needs, or -1 if it cannot be made.
     *
     * <p>The strip is x = 0..2 at z = 1, relative to the region: we stand in x=0 and click the floor
     * of x=1 facing +x, so the bed's foot lands in x=1 and its head in x=2. Each of the three cells
     * and the cell above it must be clear (a solid one is one dig), the floor under each must be
     * solid (a missing one is one placement). Fluids, unbreakable blocks and hazards rule a strip out.
     */
    private int stripWork(AltoClef mod, BlockPos region) {
        int work = 0;
        for (int dx = 0; dx < 3; ++dx) {
            BlockPos floor = region.add(dx, -1, 1);
            if (!mod.getWorld().getFluidState(floor).isEmpty()) return -1;
            if (WorldHelper.isSolidBlock(floor)) {
                // a floor as it is
            } else if (WorldHelper.isAir(floor) || mod.getWorld().getBlockState(floor).isReplaceable()) {
                work++;
            } else {
                return -1;
            }
            for (int dy = 0; dy < 2; ++dy) {
                BlockPos c = region.add(dx, dy, 1);
                if (!mod.getWorld().getFluidState(c).isEmpty()) return -1;
                if (WorldHelper.isSolidBlock(c)) {
                    if (!WorldHelper.canBreak(c)) return -1;
                    work++;
                } else if (!WorldHelper.isAir(c) && !mod.getWorld().getBlockState(c).isReplaceable()) {
                    return -1;
                }
            }
        }
        return work;
    }
}
